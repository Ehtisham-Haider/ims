package com.stockpro.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

/**
 * Backward-compatible additive SQLite schema migration runner upgrading Version 1 databases
 * to Version 2 without altering or deleting existing Version 1 rows.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class SchemaMigrator {

    /** Latest Version 2 database schema revision number. */
    public static final int LATEST_VERSION = 2;

    /**
     * Prevents direct utility instantiation.
     */
    private SchemaMigrator() {
    }

    /**
     * Applies pending schema migrations up to {@link #LATEST_VERSION} idempotently.
     *
     * @param conn open SQLite JDBC connection inside schema initialization
     * @throws SQLException if any DDL or seed statement fails
     */
    public static void migrate(Connection conn) throws SQLException {
        if (conn == null) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        description TEXT NOT NULL,
                        applied_at TEXT NOT NULL
                    );
                    """);
        }

        String now = LocalDateTime.now().toString();
        recordMigrationIfAbsent(conn, 1, "StockPro Version 1 baseline schema", now);

        if (!isVersionApplied(conn, 2)) {
            applyV2Schema(conn, now);
            recordMigrationIfAbsent(conn, 2, "StockPro Version 2 enterprise catalog, multi-branch, WMS, ML, and security tables", now);
        } else {
            ensureV2TablesExist(conn, now);
        }
    }

    /**
     * Checks whether a given migration version has already been recorded.
     *
     * @param conn active JDBC connection
     * @param version target schema version
     * @return {@code true} if the version row exists
     * @throws SQLException if querying {@code schema_migrations} fails
     */
    public static boolean isVersionApplied(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void recordMigrationIfAbsent(Connection conn, int version, String description, String now)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, description);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static void applyV2Schema(Connection conn, String now) throws SQLException {
        ensureV2TablesExist(conn, now);
        seedV2Defaults(conn, now);
    }

    private static void ensureV2TablesExist(Connection conn, String now) throws SQLException {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS product_variants (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    parent_product_id INTEGER NOT NULL,
                    child_product_id INTEGER,
                    variant_sku TEXT NOT NULL UNIQUE,
                    variant_name TEXT NOT NULL,
                    color TEXT,
                    size TEXT,
                    flavor TEXT,
                    sell_price_delta REAL NOT NULL DEFAULT 0.0,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (parent_product_id) REFERENCES products(id) ON DELETE CASCADE,
                    FOREIGN KEY (child_product_id) REFERENCES products(id) ON DELETE SET NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS uom_conversions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    unit_name TEXT NOT NULL,
                    factor_to_base TEXT NOT NULL,
                    unit_role TEXT NOT NULL DEFAULT 'SELLING',
                    updated_at TEXT NOT NULL,
                    UNIQUE (product_id, unit_name),
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS product_bundles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    bundle_product_id INTEGER NOT NULL,
                    component_product_id INTEGER NOT NULL,
                    component_qty INTEGER NOT NULL DEFAULT 1,
                    unit_name TEXT NOT NULL DEFAULT 'Piece',
                    created_at TEXT NOT NULL,
                    UNIQUE (bundle_product_id, component_product_id),
                    FOREIGN KEY (bundle_product_id) REFERENCES products(id) ON DELETE CASCADE,
                    FOREIGN KEY (component_product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS serial_numbers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    serial_code TEXT NOT NULL UNIQUE,
                    batch_code TEXT,
                    status TEXT NOT NULL DEFAULT 'IN_STOCK',
                    branch_id INTEGER NOT NULL DEFAULT 1,
                    notes TEXT,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS batch_lots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_code TEXT NOT NULL UNIQUE,
                    product_id INTEGER NOT NULL,
                    supplier_id INTEGER,
                    quantity_received INTEGER NOT NULL DEFAULT 0,
                    expiry_date TEXT,
                    recalled INTEGER NOT NULL DEFAULT 0,
                    recall_reason TEXT,
                    received_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS product_attachments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    doc_type TEXT NOT NULL DEFAULT 'SPEC_SHEET',
                    file_size_bytes INTEGER NOT NULL DEFAULT 0,
                    added_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS product_custom_fields (
                    product_id INTEGER PRIMARY KEY,
                    attributes_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS branches (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    branch_code TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    address TEXT,
                    manager_name TEXT,
                    active INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS branch_stock (
                    branch_id INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    on_hand_qty INTEGER NOT NULL DEFAULT 0,
                    reserved_qty INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (branch_id, product_id),
                    FOREIGN KEY (branch_id) REFERENCES branches(id) ON DELETE CASCADE,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS stock_transfers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    transfer_code TEXT NOT NULL UNIQUE,
                    source_branch_id INTEGER NOT NULL,
                    target_branch_id INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    quantity INTEGER NOT NULL,
                    unit_cost REAL NOT NULL DEFAULT 0.0,
                    status TEXT NOT NULL DEFAULT 'REQUESTED',
                    notes TEXT,
                    created_by TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (source_branch_id) REFERENCES branches(id),
                    FOREIGN KEY (target_branch_id) REFERENCES branches(id),
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS goods_receipts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    po_id INTEGER NOT NULL,
                    blind_counts_json TEXT NOT NULL,
                    invoice_number TEXT,
                    invoice_total REAL NOT NULL DEFAULT 0.0,
                    discrepancy_notes TEXT,
                    match_status TEXT NOT NULL DEFAULT 'PENDING',
                    received_by TEXT NOT NULL,
                    received_at TEXT NOT NULL,
                    FOREIGN KEY (po_id) REFERENCES purchase_orders(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS returns_rma (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rma_code TEXT NOT NULL UNIQUE,
                    product_id INTEGER NOT NULL,
                    branch_id INTEGER NOT NULL DEFAULT 1,
                    quantity INTEGER NOT NULL,
                    disposition TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    refund_amount REAL NOT NULL DEFAULT 0.0,
                    processed_by TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS backorders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER NOT NULL,
                    customer_name TEXT NOT NULL,
                    customer_contact TEXT,
                    requested_qty INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'WAITING',
                    notified INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    fulfilled_at TEXT,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS shelf_bins (
                    bin_code TEXT PRIMARY KEY,
                    aisle TEXT NOT NULL,
                    row_index INTEGER NOT NULL,
                    col_index INTEGER NOT NULL,
                    max_weight_kg REAL NOT NULL DEFAULT 120.0,
                    current_weight_kg REAL NOT NULL DEFAULT 0.0,
                    max_volume_dm3 REAL NOT NULL DEFAULT 250.0,
                    current_volume_dm3 REAL NOT NULL DEFAULT 0.0
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS fx_rates_cache (
                    currency_code TEXT PRIMARY KEY,
                    rate_from_usd REAL NOT NULL,
                    source TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS vendor_price_tiers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    supplier_id INTEGER NOT NULL,
                    product_id INTEGER NOT NULL,
                    min_qty INTEGER NOT NULL,
                    moq INTEGER NOT NULL DEFAULT 1,
                    unit_price REAL NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS landed_cost_allocations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    po_id INTEGER NOT NULL,
                    freight_cost REAL NOT NULL DEFAULT 0.0,
                    customs_duty REAL NOT NULL DEFAULT 0.0,
                    handling_fee REAL NOT NULL DEFAULT 0.0,
                    strategy TEXT NOT NULL,
                    breakdown_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS image_phashes (
                    product_id INTEGER PRIMARY KEY,
                    phash_hex TEXT NOT NULL,
                    image_path TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS agent_feedback_weights (
                    weight_key TEXT PRIMARY KEY,
                    weight_value REAL NOT NULL DEFAULT 1.0,
                    approve_count INTEGER NOT NULL DEFAULT 0,
                    reject_count INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS waste_eco_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    product_id INTEGER,
                    event_type TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    weight_kg REAL NOT NULL DEFAULT 0.0,
                    financial_value REAL NOT NULL DEFAULT 0.0,
                    notes TEXT,
                    created_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS user_column_prefs (
                    username TEXT PRIMARY KEY,
                    columns_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS alert_dispatch_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    recipient TEXT,
                    message TEXT NOT NULL,
                    delivered INTEGER NOT NULL DEFAULT 0,
                    status_note TEXT,
                    created_at TEXT NOT NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS idx_variants_parent ON product_variants(parent_product_id);",
                "CREATE INDEX IF NOT EXISTS idx_bundles_parent ON product_bundles(bundle_product_id);",
                "CREATE INDEX IF NOT EXISTS idx_serials_product ON serial_numbers(product_id);",
                "CREATE INDEX IF NOT EXISTS idx_batches_product ON batch_lots(product_id);",
                "CREATE INDEX IF NOT EXISTS idx_transfers_status ON stock_transfers(status);",
                "CREATE INDEX IF NOT EXISTS idx_backorders_product ON backorders(product_id);"
        };

        try (Statement stmt = conn.createStatement()) {
            for (String sql : ddl) {
                stmt.execute(sql);
            }
        }
        seedV2Settings(conn, now);
    }

    private static void seedV2Settings(Connection conn, String now) throws SQLException {
        String[][] v2Settings = {
                {"app.language", "en"},
                {"api.local.enabled", "false"},
                {"api.local.port", "8765"},
                {"alerts.telegram.token", ""},
                {"alerts.telegram.chat_id", ""},
                {"alerts.gmail.address", ""},
                {"alerts.gmail.app_password", ""},
                {"alerts.gmail.recipient", ""},
                {"ai.budget.monthly_cap", "5000.0"}
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO app_settings (setting_key, setting_value, updated_at) VALUES (?, ?, ?)")) {
            for (String[] pair : v2Settings) {
                ps.setString(1, pair[0]);
                ps.setString(2, pair[1]);
                ps.setString(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedV2Defaults(Connection conn, String now) throws SQLException {
        try (Statement check = conn.createStatement();
             ResultSet rs = check.executeQuery("SELECT COUNT(*) FROM branches")) {
            if (!rs.next() || rs.getInt(1) == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO branches (branch_code, name, address, manager_name, active, created_at) VALUES (?, ?, ?, ?, 1, ?)")) {
                    Object[][] branches = {
                            {"MAIN", "Main Flagship Store", "Gulberg Boulevard #14", "Syed Ehtisham Haider Bukhari"},
                            {"NORTH", "North Distribution Hub", "Industrial Zone Sector B", "Faheem Ahmed Khan"},
                            {"AIRPORT", "Airport Express Branch", "Terminal Concourse 2", "Sara Khan"}
                    };
                    for (Object[] b : branches) {
                        ps.setString(1, (String) b[0]);
                        ps.setString(2, (String) b[1]);
                        ps.setString(3, (String) b[2]);
                        ps.setString(4, (String) b[3]);
                        ps.setString(5, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
        }

        try (Statement checkBins = conn.createStatement();
             ResultSet rsBins = checkBins.executeQuery("SELECT COUNT(*) FROM shelf_bins")) {
            if (!rsBins.next() || rsBins.getInt(1) == 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO shelf_bins (bin_code, aisle, row_index, col_index, max_weight_kg, current_weight_kg, max_volume_dm3, current_volume_dm3) VALUES (?, ?, ?, ?, 120.0, 0.0, 250.0, 0.0)")) {
                    for (int a = 0; a < 5; a++) {
                        String aisle = String.valueOf((char) ('A' + a));
                        for (int r = 1; r <= 4; r++) {
                            for (int c = 1; c <= 12; c++) {
                                String binCode = String.format("%s%d-%02d", aisle, r, c);
                                ps.setString(1, binCode);
                                ps.setString(2, aisle);
                                ps.setInt(3, r);
                                ps.setInt(4, c);
                                ps.addBatch();
                            }
                        }
                    }
                    ps.executeBatch();
                }
            }
        }
    }
}
