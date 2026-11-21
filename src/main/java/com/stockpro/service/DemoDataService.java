package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.StockStatus;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * Generates 1,000 realistic store products, suppliers, and sales history in a single fast SQLite transaction
 * and provides a one-click store wipe for testing empty-inventory workflows.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class DemoDataService {
    private static final String[] CATEGORIES = {
            "Groceries", "Dairy", "Bakery", "Snacks", "Drinks", "Electronics", "Accessories", "Household"
    };
    private static final String[] ADJECTIVES = {
            "Fresh", "Organic", "Premium", "Wireless", "Compact", "Classic", "Ultra", "Family", "Daily", "Pro"
    };
    private static final String[] NOUNS = {
            "Rice 5kg", "Milk 1L", "Wheat Bread", "Potato Chips", "Sparkling Cola", "Mouse",
            "Keyboard", "USB-C Cable", "Phone Stand", "Olive Oil", "Coffee Beans", "Green Tea",
            "Greek Yogurt", "Cheddar Cheese", "Laundry Gel", "Hand Soap", "Notebook", "Bluetooth Speaker",
            "Power Bank", "LED Bulb"
    };

    private final DatabaseManager dbManager;
    private final CatalogSearchEngine searchEngine;
    private final InventoryEventBus eventBus;

    /**
     * Constructs a demo data generator service.
     *
     * @param dbManager database manager
     * @param searchEngine catalog search engine to refresh after bulk changes
     * @param eventBus shared UI refresh event bus
     */
    public DemoDataService(
            DatabaseManager dbManager,
            CatalogSearchEngine searchEngine,
            InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.searchEngine = searchEngine;
        this.eventBus = eventBus;
    }

    /**
     * Generates {@code targetProductCount} realistic products (default 1,000), 6 suppliers,
     * and 250 sales/restock movement records in a single batch transaction.
     *
     * @param targetProductCount number of demo products to insert
     * @return count of inserted products
     */
    public int generateRealisticDemoStore(int targetProductCount) {
        int count = Math.max(10, targetProductCount);
        Random random = new Random(42L);
        LocalDateTime now = LocalDateTime.now();

        String insertSup = """
                INSERT INTO suppliers (name, phone, email, delivery_days, reliability_score, linked_categories, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        String insertProd = """
                INSERT INTO products (
                    sku, barcode, name, category, tags, quantity, damaged_qty,
                    low_stock_threshold, buy_price, sell_price, shelf_location,
                    expiry_date, status, manual_status_override, photo_path,
                    substitute_sku, archived, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, 0, ?, ?)
                """;
        String insertMove = """
                INSERT INTO stock_movements (
                    product_id, product_sku, product_name, movement_type,
                    quantity_delta, previous_qty, new_qty, reason, notes, performed_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement clear = conn.createStatement()) {
                clear.execute("DELETE FROM stock_movements");
                clear.execute("DELETE FROM purchase_orders");
                clear.execute("DELETE FROM agent_proposals");
                clear.execute("DELETE FROM products");
                clear.execute("DELETE FROM suppliers");
            }

            try (PreparedStatement psSup = conn.prepareStatement(insertSup)) {
                Object[][] sups = {
                        {"TechGlobal Ltd", "+92 300 1234567", "orders@techglobal.example", 2, 98.0, "Electronics,Accessories"},
                        {"FreshFarm Dairy Co", "+92 301 7654321", "supply@freshfarm.example", 1, 96.5, "Dairy,Bakery"},
                        {"Pak Wholesale Traders", "+92 302 1112233", "sales@paktraders.example", 3, 94.0, "Groceries,Snacks,Drinks"},
                        {"Metro Home Care", "+92 303 4445566", "dispatch@metrohome.example", 4, 92.0, "Household,Groceries"},
                        {"Apex Peripherals", "+92 304 9988776", "hello@apexperipherals.example", 2, 97.5, "Electronics,Accessories"},
                        {"GreenValley Organics", "+92 305 2233445", "orders@greenvalley.example", 2, 95.0, "Groceries,Drinks"}
                };
                for (Object[] row : sups) {
                    psSup.setString(1, (String) row[0]);
                    psSup.setString(2, (String) row[1]);
                    psSup.setString(3, (String) row[2]);
                    psSup.setInt(4, (Integer) row[3]);
                    psSup.setDouble(5, (Double) row[4]);
                    psSup.setString(6, (String) row[5]);
                    psSup.setString(7, now.toString());
                    psSup.addBatch();
                }
                psSup.executeBatch();
            }

            try (PreparedStatement psProd = conn.prepareStatement(insertProd)) {
                for (int i = 1; i <= count; i++) {
                    String sku = String.format("PRD-%04d", 1000 + i);
                    String cat = CATEGORIES[i % CATEGORIES.length];
                    String adj = ADJECTIVES[i % ADJECTIVES.length];
                    String noun = NOUNS[(i / ADJECTIVES.length) % NOUNS.length];
                    String name = adj + " " + noun + " #" + i;

                    int qty;
                    if (i % 17 == 0) {
                        qty = 0;
                    } else if (i % 7 == 0) {
                        qty = 2 + random.nextInt(8);
                    } else {
                        qty = 18 + random.nextInt(140);
                    }
                    int threshold = 10;
                    double buy = Math.round((1.0 + random.nextDouble() * 35.0) * 100.0) / 100.0;
                    double sell = Math.round(buy * (1.28 + random.nextDouble() * 0.35) * 100.0) / 100.0;
                    String shelf = String.format("%c%d-%02d", 'A' + (i % 5), 1 + (i % 4), 1 + (i % 12));
                    LocalDate expiry = ("Dairy".equals(cat) || "Bakery".equals(cat) || "Drinks".equals(cat))
                            ? LocalDate.now().plusDays(2 + random.nextInt(35))
                            : null;
                    StockStatus st = qty <= 0 ? StockStatus.OUT_OF_STOCK : (qty <= threshold ? StockStatus.LOW_STOCK : StockStatus.IN_STOCK);

                    psProd.setString(1, sku);
                    psProd.setString(2, sku);
                    psProd.setString(3, name);
                    psProd.setString(4, cat);
                    psProd.setString(5, cat + ", Demo");
                    psProd.setInt(6, qty);
                    psProd.setInt(7, threshold);
                    psProd.setDouble(8, buy);
                    psProd.setDouble(9, sell);
                    psProd.setString(10, shelf);
                    psProd.setString(11, expiry != null ? expiry.toString() : null);
                    psProd.setString(12, st.getDisplayName());
                    psProd.setString(13, now.toString());
                    psProd.setString(14, now.toString());
                    psProd.addBatch();
                }
                psProd.executeBatch();
            }

            // Seed 250 historical sales & restock movements across recent 7 days
            try (Statement qStmt = conn.createStatement();
                 var rs = qStmt.executeQuery("SELECT id, sku, name, quantity FROM products ORDER BY id ASC LIMIT 60");
                 PreparedStatement psMove = conn.prepareStatement(insertMove)) {
                int mCount = 0;
                while (rs.next() && mCount < 250) {
                    long pid = rs.getLong(1);
                    String sku = rs.getString(2);
                    String name = rs.getString(3);
                    int qty = rs.getInt(4);
                    for (int k = 0; k < 5 && mCount < 250; k++) {
                        boolean isSale = k % 4 != 0;
                        MovementType type = isSale ? MovementType.SALE : MovementType.RESTOCK;
                        int delta = isSale ? -(1 + random.nextInt(6)) : 20;
                        LocalDateTime moveTs = now.minusDays(random.nextInt(7)).minusHours(random.nextInt(10));
                        psMove.setLong(1, pid);
                        psMove.setString(2, sku);
                        psMove.setString(3, name);
                        psMove.setString(4, type.name());
                        psMove.setInt(5, delta);
                        psMove.setInt(6, Math.max(0, qty - delta));
                        psMove.setInt(7, qty);
                        psMove.setString(8, isSale ? "POS Customer Sale" : "Supplier Replenishment");
                        psMove.setString(9, "Demo Store History");
                        psMove.setString(10, isSale ? "Sara Khan" : "Syed Ahmed");
                        psMove.setString(11, moveTs.toString());
                        psMove.addBatch();
                        mCount++;
                    }
                }
                psMove.executeBatch();
            }

            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to generate demo dataset", ex);
        }

        if (searchEngine != null) {
            searchEngine.rebuildIndices();
        }
        eventBus.publish("DEMO_DATA_LOADED");
        return count;
    }

    /**
     * Clears products, stock movements, purchase orders, and pending proposals to verify empty-store behavior.
     */
    public void clearStoreForEmptyStateTest() {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM stock_movements");
            stmt.execute("DELETE FROM purchase_orders");
            stmt.execute("DELETE FROM agent_proposals");
            stmt.execute("DELETE FROM products");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to clear store catalog", ex);
        }
        if (searchEngine != null) {
            searchEngine.rebuildIndices();
        }
        eventBus.publish("STORE_CLEARED");
    }
}
