package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.StockStatus;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.DatabaseManager;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Generates 1,000 realistic store products with category/product images, suppliers, and sales history
 * in a single fast SQLite transaction and provides a one-click store wipe for testing empty-inventory workflows.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class DemoDataService {
    private record DemoTemplate(
            String baseName,
            String category,
            String[] variants,
            double minBuy,
            double maxBuy,
            boolean perishable) {
    }

    private static final DemoTemplate[] TEMPLATES = {
            new DemoTemplate("Basmati Rice 5kg", "Groceries", new String[]{"Royal", "Harvest", "Golden", "Himalayan", "Super"}, 4.50, 8.50, false),
            new DemoTemplate("Whole Milk 1L", "Dairy", new String[]{"FreshFarm", "Organic", "Creamy", "Valley", "Pure"}, 0.90, 1.60, true),
            new DemoTemplate("Artisan Bread Loaf", "Bakery", new String[]{"Multigrain", "Sourdough", "Whole Wheat", "Honey Oat", "Country"}, 0.80, 1.80, true),
            new DemoTemplate("Bluetooth Speaker", "Electronics", new String[]{"Pulse", "Acoustic", "Studio", "Pocket", "BassPro"}, 18.00, 36.00, false),
            new DemoTemplate("Sea Salt Potato Chips", "Snacks", new String[]{"Kettle", "Crunchy", "BBQ", "Classic", "Spicy"}, 0.90, 1.90, false),
            new DemoTemplate("Sparkling Cola 330ml", "Drinks", new String[]{"Zero Sugar", "Chilled", "Craft", "Classic", "Cherry"}, 0.55, 1.10, true),
            new DemoTemplate("USB-C Fast Cable", "Accessories", new String[]{"Braided 2m", "60W PD", "Armor", "Travel", "Slim"}, 3.00, 7.50, false),
            new DemoTemplate("Laundry Detergent Gel", "Household", new String[]{"Lavender", "Fresh Linen", "Active", "Sensitive", "Eco"}, 4.50, 9.00, false),
            new DemoTemplate("Arabica Coffee Beans", "Drinks", new String[]{"Dark Roast", "Espresso", "Colombian", "Medium Roast", "Single Origin"}, 6.50, 13.50, false),
            new DemoTemplate("Wireless Optical Mouse", "Electronics", new String[]{"Ergo", "Silent", "Travel", "Precision", "Pro"}, 9.00, 22.00, false),
            new DemoTemplate("Extra Virgin Olive Oil", "Groceries", new String[]{"Cold Pressed", "Mediterranean", "Reserve", "Organic", "Pure"}, 5.50, 12.00, false),
            new DemoTemplate("Greek Yogurt Cup", "Dairy", new String[]{"Honey", "Vanilla", "Plain", "Berry", "Low-Fat"}, 1.10, 2.40, true),
            new DemoTemplate("Butter Croissant Pack", "Bakery", new String[]{"French", "Almond", "Chocolate", "Warm", "Classic"}, 1.80, 3.80, true),
            new DemoTemplate("Mechanical Keyboard", "Electronics", new String[]{"Compact", "RGB", "Tactile", "Quiet", "Creator"}, 24.00, 52.00, false),
            new DemoTemplate("Roasted Almond Mix", "Snacks", new String[]{"Smoked", "Honey Glazed", "Raw", "Lightly Salted", "Trail"}, 2.80, 6.20, false),
            new DemoTemplate("Smartphone Desk Stand", "Accessories", new String[]{"Aluminum", "Foldable", "MagSafe", "Adjustable", "DeskPro"}, 4.50, 11.50, false),
            new DemoTemplate("Botanical Hand Soap", "Household", new String[]{"Citrus", "Aloe Vera", "Oatmeal", "Mint", "Rosewater"}, 1.80, 4.20, false),
            new DemoTemplate("Aged Cheddar Cheese", "Dairy", new String[]{"Sharp", "Mild", "Farmhouse", "Sliced", "Reserve"}, 2.40, 5.20, true),
            new DemoTemplate("Matcha Green Tea", "Drinks", new String[]{"Jasmine", "Organic", "Sencha", "Mint", "Imperial"}, 3.20, 7.00, false),
            new DemoTemplate("Portable Power Bank", "Electronics", new String[]{"10000mAh", "20000mAh", "Slim PD", "Solar", "Travel"}, 12.00, 28.00, false),
            new DemoTemplate("Spiral Hardcover Notebook", "Accessories", new String[]{"Dotted A5", "Ruled", "Executive", "Sketch", "Minimal"}, 2.00, 5.00, false),
            new DemoTemplate("Smart LED Bulb 12W", "Household", new String[]{"Warm White", "Daylight", "Dimmable", "Color RGB", "Eco"}, 2.50, 6.50, false),
            new DemoTemplate("Organic Farm Eggs (12)", "Dairy", new String[]{"Free Range", "Brown", "Pasture", "Omega-3", "Jumbo"}, 1.90, 3.60, true),
            new DemoTemplate("Dark Chocolate Bar", "Snacks", new String[]{"70% Cacao", "Hazelnut", "Sea Salt", "Caramel", "Artisan"}, 1.40, 3.20, false)
    };

    private final DatabaseManager dbManager;
    private final CatalogSearchEngine searchEngine;
    private final InventoryEventBus eventBus;
    private final ProductImageService productImageService;

    /**
     * Constructs a demo data generator service using a database-local image cache.
     *
     * @param dbManager database manager
     * @param searchEngine catalog search engine to refresh after bulk changes
     * @param eventBus shared UI refresh event bus
     */
    public DemoDataService(
            DatabaseManager dbManager,
            CatalogSearchEngine searchEngine,
            InventoryEventBus eventBus) {
        this(dbManager, searchEngine, eventBus, null);
    }

    /**
     * Constructs a demo data generator service wired to a shared {@link ProductImageService}.
     *
     * @param dbManager database manager
     * @param searchEngine catalog search engine to refresh after bulk changes
     * @param eventBus shared UI refresh event bus
     * @param productImageService image resolver assigning demo product visuals
     */
    public DemoDataService(
            DatabaseManager dbManager,
            CatalogSearchEngine searchEngine,
            InventoryEventBus eventBus,
            ProductImageService productImageService) {
        this.dbManager = dbManager;
        this.searchEngine = searchEngine;
        this.eventBus = eventBus;
        this.productImageService = productImageService;
    }

    /**
     * Returns the number of distinct curated retail templates cycled during demo store generation.
     *
     * @return count of curated product templates
     */
    public static int getTemplateCount() {
        return TEMPLATES.length;
    }

    /**
     * Generates {@code targetProductCount} realistic products (default 1,000) with product images,
     * 6 suppliers, and 250 sales/restock movement records in a single batch transaction.
     *
     * @param targetProductCount number of demo products to insert
     * @return count of inserted products
     */
    public int generateRealisticDemoStore(int targetProductCount) {
        int count = Math.max(10, targetProductCount);
        Random random = new Random(42L);
        LocalDateTime now = LocalDateTime.now();
        ProductImageService imageResolver = resolveImageService();
        Map<String, String> cachedSlugPaths = new HashMap<>();

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
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, 0, ?, NULL, 0, ?, ?)
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
                    int zeroIdx = i - 1;
                    DemoTemplate tpl = TEMPLATES[zeroIdx % TEMPLATES.length];
                    int cycle = zeroIdx / TEMPLATES.length;
                    String variant = tpl.variants()[cycle % tpl.variants().length];
                    int batchNum = (cycle / tpl.variants().length) + 1;
                    String name = batchNum == 1
                            ? variant + " " + tpl.baseName()
                            : variant + " " + tpl.baseName() + " #" + i;
                    String cat = tpl.category();

                    String imgKey = cat + "|"
                            + ProductImageService.toSlug(ProductImageService.buildSearchQuery(name, cat))
                            + "|" + ProductImageService.variantSlot(name);
                    String photoPath = cachedSlugPaths.computeIfAbsent(
                            imgKey, k -> imageResolver.resolveImagePath(name, cat, false));

                    int qty;
                    if (i % 17 == 0) {
                        qty = 0;
                    } else if (i % 7 == 0) {
                        qty = 2 + random.nextInt(8);
                    } else {
                        qty = 18 + random.nextInt(140);
                    }
                    int threshold = 10;
                    double buy = Math.round(
                            (tpl.minBuy() + random.nextDouble() * (tpl.maxBuy() - tpl.minBuy())) * 100.0) / 100.0;
                    double sell = Math.round(buy * (1.28 + random.nextDouble() * 0.35) * 100.0) / 100.0;
                    String shelf = String.format("%c%d-%02d", 'A' + (i % 5), 1 + (i % 4), 1 + (i % 12));
                    LocalDate expiry = tpl.perishable()
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
                    psProd.setString(13, photoPath);
                    psProd.setString(14, now.toString());
                    psProd.setString(15, now.toString());
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
                        psMove.setString(10, isSale ? "Faheen Ahmad Khan" : "Syed Ehtisham Haider Bukhari");
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

    private ProductImageService resolveImageService() {
        if (productImageService != null) {
            return productImageService;
        }
        Path dbFile = dbManager != null ? dbManager.getDatabaseFilePath() : null;
        Path cacheDir = (dbFile != null && dbFile.getParent() != null)
                ? dbFile.getParent().resolve("product-images")
                : Path.of("data", "product-images");
        return new ProductImageService(null, null, cacheDir);
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
