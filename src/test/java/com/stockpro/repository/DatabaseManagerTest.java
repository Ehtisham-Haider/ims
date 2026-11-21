package com.stockpro.repository;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies local SQLite schema auto-initialization, HikariCP pooling, and offline default seeding.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class DatabaseManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Auto-initializes all 8 core SQLite tables and seeds defaults offline")
    void initializesAllCoreTablesAndSeedsDefaults() throws Exception {
        Path dbFile = tempDir.resolve("foundation-test.db");
        try (DatabaseManager db = DatabaseManager.createForFile(dbFile);
             Connection conn = db.getConnection()) {

            DatabaseMetaData meta = conn.getMetaData();
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }

            assertTrue(tables.contains("products"));
            assertTrue(tables.contains("stock_movements"));
            assertTrue(tables.contains("suppliers"));
            assertTrue(tables.contains("purchase_orders"));
            assertTrue(tables.contains("users"));
            assertTrue(tables.contains("audit_logs"));
            assertTrue(tables.contains("agent_proposals"));
            assertTrue(tables.contains("app_settings"));

            try (Statement stmt = conn.createStatement();
                 ResultSet userCount = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(userCount.next());
                assertEquals(3, userCount.getInt(1), "Default Owner, Manager, and Cashier should be seeded");
            }
        }
    }

    @Test
    @DisplayName("Domain status calculation computes In Stock, Low Stock, and Out of Stock accurately")
    void productAutomaticStatusComputation() {
        Product product = new Product("Wireless Mouse", 25, null);
        product.setLowStockThreshold(10);
        assertEquals(StockStatus.IN_STOCK, product.computeAutomaticStatus());

        product.setQuantity(10);
        assertEquals(StockStatus.LOW_STOCK, product.computeAutomaticStatus());

        product.setQuantity(0);
        assertEquals(StockStatus.OUT_OF_STOCK, product.computeAutomaticStatus());
    }
}
