package com.stockpro.service;

import com.stockpro.repository.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Provides one-click atomic SQLite database snapshot creation ({@code VACUUM INTO})
 * and validated snapshot restoration without leaving stale pool handles.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class BackupService {
    private final DatabaseManager dbManager;
    private final InventoryEventBus eventBus;

    /**
     * Creates a backup &amp; restore service bound to {@link DatabaseManager}.
     *
     * @param dbManager database manager
     * @param eventBus shared UI refresh event bus
     */
    public BackupService(DatabaseManager dbManager, InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.eventBus = eventBus;
    }

    /**
     * Creates a consistent, standalone SQLite database backup file via {@code VACUUM INTO}.
     *
     * @param backupDestination destination {@code .db} file path
     * @return verified backup {@link Path}
     */
    public Path createBackup(Path backupDestination) {
        try {
            if (backupDestination.getParent() != null) {
                Files.createDirectories(backupDestination.getParent());
            }
            Files.deleteIfExists(backupDestination);
            String escapedPath = backupDestination.toAbsolutePath().toString().replace("'", "''");
            try (Connection conn = dbManager.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + escapedPath + "'");
            }
            if (!validateBackupFile(backupDestination)) {
                throw new IllegalStateException("Generated backup snapshot failed SQLite verification.");
            }
            return backupDestination;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create SQLite backup: " + ex.getMessage(), ex);
        }
    }

    /**
     * Validates an external SQLite backup file for core StockPro tables before restoring.
     *
     * @param backupFile candidate {@code .db} snapshot path
     * @return {@code true} if the file is a valid StockPro SQLite database
     */
    public boolean validateBackupFile(Path backupFile) {
        if (backupFile == null || !Files.exists(backupFile) || !Files.isRegularFile(backupFile)) {
            return false;
        }
        String url = "jdbc:sqlite:" + backupFile.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products")) {
            return rs.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * Restores all catalog, supplier, order, movement, audit, proposal, and settings rows atomically
     * from a validated SQLite snapshot into the active database.
     *
     * @param backupFile verified snapshot {@code .db} path
     * @return summary message reporting restored product count
     */
    public InventoryOperationResult<Integer> restoreFromBackup(Path backupFile) {
        if (!validateBackupFile(backupFile)) {
            return InventoryOperationResult.fail("Invalid or corrupted SQLite backup file.");
        }
        String escaped = backupFile.toAbsolutePath().toString().replace("'", "''");
        try (Connection conn = dbManager.getConnection();
             Statement outerStmt = conn.createStatement()) {
            outerStmt.execute("PRAGMA foreign_keys = OFF;");
            outerStmt.execute("ATTACH DATABASE '" + escaped + "' AS bak;");

            try {
                conn.setAutoCommit(false);
                String[] tables = {
                        "stock_movements",
                        "purchase_orders",
                        "products",
                        "suppliers",
                        "audit_logs",
                        "agent_proposals",
                        "app_settings"
                };
                for (String t : tables) {
                    outerStmt.execute("DELETE FROM main." + t + ";");
                    outerStmt.execute("INSERT INTO main." + t + " SELECT * FROM bak." + t + ";");
                }
                conn.commit();
            } catch (SQLException inner) {
                conn.rollback();
                throw inner;
            } finally {
                conn.setAutoCommit(true);
                try {
                    outerStmt.execute("DETACH DATABASE bak;");
                } catch (SQLException ignored) {
                }
                outerStmt.execute("PRAGMA foreign_keys = ON;");
            }

            int restoredProducts = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM products");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    restoredProducts = rs.getInt(1);
                }
            }
            eventBus.publish("DATABASE_RESTORED");
            return InventoryOperationResult.ok(restoredProducts,
                    "Restored backup cleanly (" + restoredProducts + " product(s)).");
        } catch (SQLException ex) {
            return InventoryOperationResult.fail("Restore failed: " + ex.getMessage());
        }
    }
}
