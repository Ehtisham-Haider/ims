package com.stockpro.repository;

import com.stockpro.util.AppLogger;
import com.stockpro.util.PasswordUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * Manages the local SQLite database lifecycle, HikariCP connection pool, schema creation, and default seed data.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class DatabaseManager implements AutoCloseable {
    private static final Logger LOG = AppLogger.get(DatabaseManager.class);
    private static final String DEFAULT_DB_DIR = "data";
    private static final String DEFAULT_DB_FILE = "stockpro.db";

    private static volatile DatabaseManager instance;

    private final String jdbcUrl;
    private final Path databaseFilePath;
    private final HikariDataSource dataSource;

    /**
     * Creates a database manager bound to a specific SQLite JDBC URL and initializes all tables.
     *
     * @param jdbcUrl SQLite JDBC connection string
     * @param databaseFilePath optional local file path for disk-backed databases
     * @author Ehtisham Haider, Syed Ahmed
     */
    private DatabaseManager(String jdbcUrl, Path databaseFilePath) {
        this.jdbcUrl = jdbcUrl;
        this.databaseFilePath = databaseFilePath;
        ensureParentDirectory(databaseFilePath);
        this.dataSource = createPool(jdbcUrl);
        initializeSchema();
        seedDefaults();
    }

    /**
     * Returns the application-wide singleton {@link DatabaseManager}, initializing the local SQLite database on first use.
     *
     * @return active singleton {@link DatabaseManager}
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static DatabaseManager getInstance() {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    Path dbPath = Paths.get(DEFAULT_DB_DIR, DEFAULT_DB_FILE).toAbsolutePath();
                    instance = new DatabaseManager("jdbc:sqlite:" + dbPath, dbPath);
                }
            }
        }
        return instance;
    }

    /**
     * Creates an isolated {@link DatabaseManager} instance for unit and integration tests using a temporary SQLite file.
     *
     * @param testDbFile path to the temporary SQLite database file
     * @return standalone {@link DatabaseManager} configured for testing
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static DatabaseManager createForFile(Path testDbFile) {
        Path absPath = testDbFile.toAbsolutePath();
        return new DatabaseManager("jdbc:sqlite:" + absPath, absPath);
    }

    /**
     * Ensures parent directories exist before opening a file-backed SQLite database.
     *
     * @param filePath target SQLite file path
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void ensureParentDirectory(Path filePath) {
        if (filePath != null && filePath.getParent() != null) {
            try {
                Files.createDirectories(filePath.getParent());
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to create database directory: " + filePath.getParent(), ex);
            }
        }
    }

    /**
     * Builds and configures the HikariCP connection pool for local SQLite access.
     *
     * @param url JDBC URL
     * @return configured {@link HikariDataSource}
     * @author Ehtisham Haider, Syed Ahmed
     */
    private HikariDataSource createPool(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setPoolName("StockProSQLitePool");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
        return new HikariDataSource(config);
    }

    /**
     * Obtains a pooled JDBC connection from HikariCP.
     *
     * @return open {@link Connection}
     * @throws SQLException if a connection cannot be acquired
     * @author Ehtisham Haider, Syed Ahmed
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Returns the configured SQLite JDBC URL.
     *
     * @return JDBC connection URL
     * @author Ehtisham Haider, Syed Ahmed
     */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Returns the path to the backing SQLite database file, if disk-backed.
     *
     * @return database file {@link Path}, or {@code null}
     * @author Ehtisham Haider, Syed Ahmed
     */
    public Path getDatabaseFilePath() {
        return databaseFilePath;
    }

    /**
     * Creates all core Version 1 tables and performance indexes if they do not already exist.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public void initializeSchema() {
        String[] statements = {
                "PRAGMA journal_mode = WAL;",
                """
                CREATE TABLE IF NOT EXISTS products (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    sku TEXT NOT NULL UNIQUE,
                    barcode TEXT,
                    name TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'General',
                    tags TEXT,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    damaged_qty INTEGER NOT NULL DEFAULT 0,
                    low_stock_threshold INTEGER NOT NULL DEFAULT 10,
                    buy_price REAL NOT NULL DEFAULT 0.0,
                    sell_price REAL NOT NULL DEFAULT 0.0,
                    shelf_location TEXT,
                    expiry_date TEXT,
                    status TEXT NOT NULL DEFAULT 'In Stock',
                    manual_status_override INTEGER NOT NULL DEFAULT 0,
                    photo_path TEXT,
                    substitute_sku TEXT,
                    archived INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS stock_movements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    product_sku TEXT NOT NULL,
                    product_name TEXT NOT NULL,
                    movement_type TEXT NOT NULL,
                    quantity_delta INTEGER NOT NULL,
                    previous_qty INTEGER NOT NULL,
                    new_qty INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    notes TEXT,
                    performed_by TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS suppliers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    phone TEXT,
                    email TEXT,
                    delivery_days INTEGER NOT NULL DEFAULT 3,
                    reliability_score REAL NOT NULL DEFAULT 95.0,
                    linked_categories TEXT,
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS purchase_orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    po_number TEXT NOT NULL UNIQUE,
                    supplier_id INTEGER NOT NULL,
                    supplier_name TEXT NOT NULL,
                    items_json TEXT NOT NULL,
                    total_cost REAL NOT NULL DEFAULT 0.0,
                    status TEXT NOT NULL DEFAULT 'Draft',
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE RESTRICT
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    full_name TEXT NOT NULL,
                    role TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    entity_ref TEXT NOT NULL,
                    details TEXT,
                    prev_hash TEXT NOT NULL,
                    curr_hash TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_proposals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    agent_name TEXT NOT NULL,
                    proposal_type TEXT NOT NULL,
                    title TEXT NOT NULL,
                    reasoning TEXT NOT NULL,
                    financial_impact REAL NOT NULL DEFAULT 0.0,
                    payload_json TEXT,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS app_settings (
                    setting_key TEXT PRIMARY KEY,
                    setting_value TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_products_sku ON products(sku);",
                "CREATE INDEX IF NOT EXISTS idx_products_name ON products(name);",
                "CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);",
                "CREATE INDEX IF NOT EXISTS idx_products_status ON products(status);",
                "CREATE INDEX IF NOT EXISTS idx_movements_product ON stock_movements(product_id);",
                "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_logs(timestamp);"
        };

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
            LOG.info("SQLite schema initialized cleanly at {}", jdbcUrl);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialize SQLite schema", ex);
        }
    }

    /**
     * Populates default application settings and built-in staff accounts if missing.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void seedDefaults() {
        String now = LocalDateTime.now().toString();
        String[][] defaultSettings = {
                {"store.name", "StockPro Store"},
                {"store.currency", "USD ($)"},
                {"store.tax_rate", "15"},
                {"ui.theme", "light"},
                {"offline.mode", "true"},
                {"dead_stock.days", "60"},
                {"groq.api_key", ""},
                {"groq.model", "llama-3.3-70b-versatile"}
        };

        String upsertSetting = """
                INSERT OR IGNORE INTO app_settings (setting_key, setting_value, updated_at)
                VALUES (?, ?, ?)
                """;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(upsertSetting)) {
            for (String[] pair : defaultSettings) {
                ps.setString(1, pair[0]);
                ps.setString(2, pair[1]);
                ps.setString(3, now);
                ps.addBatch();
            }
            ps.executeBatch();

            seedDefaultUsersIfEmpty(conn, now);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to seed initial SQLite defaults", ex);
        }
    }

    /**
     * Inserts default Owner, Manager, and Cashier accounts only when the users table has zero rows.
     *
     * @param conn active JDBC connection
     * @param timestamp current timestamp string
     * @throws SQLException if querying or inserting users fails
     * @author Ehtisham Haider, Syed Ahmed
     */
    private void seedDefaultUsersIfEmpty(Connection conn, String timestamp) throws SQLException {
        try (Statement checkStmt = conn.createStatement();
             ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        String insertUser = """
                INSERT INTO users (username, password_hash, full_name, role, active, created_at)
                VALUES (?, ?, ?, ?, 1, ?)
                """;
        Object[][] defaults = {
                {"admin", PasswordUtil.hash("admin123"), "Syed Ahmed", "OWNER"},
                {"manager", PasswordUtil.hash("manager123"), "Ehtisham Haider", "MANAGER"},
                {"cashier", PasswordUtil.hash("cashier123"), "Sara Khan", "CASHIER"}
        };
        try (PreparedStatement ps = conn.prepareStatement(insertUser)) {
            for (Object[] row : defaults) {
                ps.setString(1, (String) row[0]);
                ps.setString(2, (String) row[1]);
                ps.setString(3, (String) row[2]);
                ps.setString(4, (String) row[3]);
                ps.setString(5, timestamp);
                ps.addBatch();
            }
            ps.executeBatch();
            LOG.info("Seeded default Owner, Manager, and Cashier accounts.");
        }
    }

    /**
     * Closes the HikariCP connection pool cleanly.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
