package com.stockpro.service;

import com.stockpro.domain.BatchLot;
import com.stockpro.domain.BatchRecallTrace;
import com.stockpro.domain.BundleComponent;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProductAttachment;
import com.stockpro.domain.ProductVariant;
import com.stockpro.domain.SerialNumberRecord;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.dsa.DagCycleDetector;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SchemaMigrator;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Version 2 schema migrations, parent-child matrix variants with cascading updates,
 * BigDecimal UoM conversion, DAG cycle detection and bundle deduction, serial tracking,
 * batch recall tracing, PDF spec sheets, and JSON custom metadata fields.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2SchemaVariantsBomTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private ProductRepository productRepo;
    private SupplierRepository supplierRepo;
    private CatalogVariantBomService catalogService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("v2-branch01.db"));
        productRepo = new ProductRepository(dbManager);
        supplierRepo = new SupplierRepository(dbManager);
        StockMovementRepository moveRepo = new StockMovementRepository(dbManager);
        AuditService auditService = new AuditService(new AuditLogRepository(dbManager));
        catalogService = new CatalogVariantBomService(
                dbManager,
                productRepo,
                moveRepo,
                auditService,
                InventoryEventBus.getInstance(),
                tempDir.resolve("spec-sheets")
        );
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Upgrades existing Version 1 SQLite databases idempotently without losing V1 products")
    void migratesV1DatabaseSafelyAndCreatesV2Tables() throws Exception {
        Path v1DbPath = tempDir.resolve("legacy-v1.db");
        try (Connection raw = java.sql.DriverManager.getConnection("jdbc:sqlite:" + v1DbPath);
             Statement st = raw.createStatement()) {
            st.execute("""
                    CREATE TABLE products (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, sku TEXT NOT NULL UNIQUE, barcode TEXT,
                        name TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'General', tags TEXT,
                        quantity INTEGER NOT NULL DEFAULT 0, damaged_qty INTEGER NOT NULL DEFAULT 0,
                        low_stock_threshold INTEGER NOT NULL DEFAULT 10, buy_price REAL NOT NULL DEFAULT 0.0,
                        sell_price REAL NOT NULL DEFAULT 0.0, shelf_location TEXT, expiry_date TEXT,
                        status TEXT NOT NULL DEFAULT 'In Stock', manual_status_override INTEGER NOT NULL DEFAULT 0,
                        photo_path TEXT, substitute_sku TEXT, archived INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                    );
                    """);
            st.execute("""
                    INSERT INTO products (sku, barcode, name, category, quantity, created_at, updated_at)
                    VALUES ('PRD-9001', 'PRD-9001', 'Legacy V1 Item', 'Groceries', 42, '2026-01-01T00:00:00', '2026-01-01T00:00:00');
                    """);
        }

        try (DatabaseManager upgraded = DatabaseManager.createForFile(v1DbPath)) {
            ProductRepository upgradedRepo = new ProductRepository(upgraded);
            Product preserved = upgradedRepo.findBySku("PRD-9001").orElseThrow();
            assertEquals("Legacy V1 Item", preserved.getName());
            assertEquals(42, preserved.getQuantity());
            try (Connection conn = upgraded.getConnection()) {
                assertTrue(SchemaMigrator.isVersionApplied(conn, 2));
            }
        }
    }

    @Test
    @DisplayName("Creates matrix variants and cascades parent category, tag, and pricing changes")
    void matrixVariantsAndParentCascade() {
        Product parent = new Product("Cotton Hoodie", 20, StockStatus.IN_STOCK);
        parent.setSku("PRD-2001");
        parent.setCategory("Apparel");
        parent.setTags("Winter");
        parent.setBuyPrice(15.0);
        parent.setSellPrice(30.0);
        Product savedParent = productRepo.insert(parent);

        InventoryOperationResult<ProductVariant> v1 = catalogService.createMatrixVariant(
                savedParent.getId(), "Midnight / L", "Midnight", "L", null, 5.0, 12);
        assertTrue(v1.isSuccess());
        Product child1 = productRepo.findById(v1.getData().childProductId()).orElseThrow();
        assertEquals(35.0, child1.getSellPrice(), 0.01);

        InventoryOperationResult<Integer> cascade = catalogService.cascadeParentEdits(
                savedParent.getId(), "Streetwear", "Winter,Premium", 40.0);
        assertTrue(cascade.isSuccess());
        assertEquals(1, cascade.getData());

        Product refreshedChild = productRepo.findById(child1.getId()).orElseThrow();
        assertEquals("Streetwear", refreshedChild.getCategory());
        assertEquals("Winter,Premium", refreshedChild.getTags());
        assertEquals(45.0, refreshedChild.getSellPrice(), 0.01);
    }

    @Test
    @DisplayName("Converts multi-tier pack units via BigDecimal without floating-point drift")
    void bigDecimalUomPackToPieceConverter() {
        Product item = new Product("Sparkling Water", 100, StockStatus.IN_STOCK);
        item.setSku("PRD-2002");
        Product saved = productRepo.insert(item);

        assertTrue(catalogService.registerUomRule(saved.getId(), "Pack", new BigDecimal("6"), "STORAGE").isSuccess());
        assertTrue(catalogService.registerUomRule(saved.getId(), "Box", new BigDecimal("24"), "PURCHASE").isSuccess());

        assertEquals(new BigDecimal("24"),
                catalogService.convertUnits(saved.getId(), BigDecimal.ONE, "Box", "Piece"));
        assertEquals(new BigDecimal("4"),
                catalogService.convertUnits(saved.getId(), BigDecimal.ONE, "Box", "Pack"));
        assertEquals(new BigDecimal("2.5"),
                catalogService.convertUnits(saved.getId(), new BigDecimal("15"), "Piece", "Pack"));
    }

    @Test
    @DisplayName("Enforces DAG acyclicity for bundles and deducts BOM components on sale")
    void bundleDagCycleDetectionAndComponentDeduction() {
        Product bundle = productRepo.insert(buildProduct("PRD-3001", "Office Starter Kit", 10));
        Product mouse = productRepo.insert(buildProduct("PRD-3002", "Optical Mouse", 30));
        Product pad = productRepo.insert(buildProduct("PRD-3003", "Desk Mat", 15));

        assertTrue(catalogService.addBundleComponent(bundle.getId(), mouse.getId(), 2, "Piece").isSuccess());
        assertTrue(catalogService.addBundleComponent(bundle.getId(), pad.getId(), 1, "Piece").isSuccess());

        InventoryOperationResult<BundleComponent> selfLoop = catalogService.addBundleComponent(
                bundle.getId(), bundle.getId(), 1, "Piece");
        assertFalse(selfLoop.isSuccess());

        InventoryOperationResult<BundleComponent> cycle = catalogService.addBundleComponent(
                mouse.getId(), bundle.getId(), 1, "Piece");
        assertFalse(cycle.isSuccess());
        assertTrue(cycle.getMessage().toLowerCase().contains("cycle"));
        assertTrue(DagCycleDetector.wouldCreateCycle(Map.of(bundle.getId(), List.of(mouse.getId())), mouse.getId(), bundle.getId()));

        InventoryOperationResult<Integer> sold = catalogService.deductBundleComponentsOnSale(bundle.getId(), 3, "admin");
        assertTrue(sold.isSuccess());
        assertEquals(2, sold.getData());
        assertEquals(24, productRepo.findById(mouse.getId()).orElseThrow().getQuantity());
        assertEquals(12, productRepo.findById(pad.getId()).orElseThrow().getQuantity());
    }

    @Test
    @DisplayName("Tracks serials, traces recalled batches, stores PDF spec sheets, and saves custom JSON attributes")
    void serialBatchRecallAttachmentsAndCustomFields() throws Exception {
        Supplier sup = new Supplier();
        sup.setName("Apex Tech Supply");
        sup = supplierRepo.insert(sup);

        Product laptop = productRepo.insert(buildProduct("PRD-4001", "ProBook 14", 8));
        InventoryOperationResult<BatchLot> lotRes = catalogService.registerBatchLot(
                "LOT-2026-09", laptop.getId(), sup.getId(), 8, LocalDate.now().plusMonths(12));
        assertTrue(lotRes.isSuccess());

        InventoryOperationResult<SerialNumberRecord> sRes = catalogService.registerSerialNumber(
                laptop.getId(), "SN-PB14-0001", "LOT-2026-09", 1L, "Factory sealed");
        assertTrue(sRes.isSuccess());
        assertTrue(catalogService.updateSerialStatus("SN-PB14-0001", "SOLD", "Dispatched POS #10").isSuccess());
        assertEquals("SOLD", catalogService.findBySerialCode("SN-PB14-0001").orElseThrow().status());

        InventoryOperationResult<BatchRecallTrace> traceRes = catalogService.flagBatchRecall("LOT-2026-09", "Battery thermal notice");
        assertTrue(traceRes.isSuccess());
        BatchRecallTrace trace = traceRes.getData();
        assertTrue(trace.lot().recalled());
        assertEquals("Apex Tech Supply", trace.supplierName());
        assertEquals(1, trace.linkedSerials().size());
        assertEquals("SN-PB14-0001", trace.linkedSerials().get(0).serialCode());

        Path pdfSrc = tempDir.resolve("probook-manual.pdf");
        Files.writeString(pdfSrc, "%PDF-1.4\n%Spec sheet sample");
        InventoryOperationResult<ProductAttachment> attRes = catalogService.attachSpecSheet(laptop.getId(), pdfSrc, "SPEC_SHEET");
        assertTrue(attRes.isSuccess());
        assertTrue(Files.exists(Path.of(attRes.getData().filePath())));
        assertEquals(1, catalogService.listAttachments(laptop.getId()).size());

        assertTrue(catalogService.saveCustomFields(laptop.getId(), Map.of("Voltage", "20V", "Brand", "ProBook")).isSuccess());
        Map<String, String> loaded = catalogService.getCustomFields(laptop.getId());
        assertEquals("20V", loaded.get("Voltage"));
        assertEquals("ProBook", loaded.get("Brand"));
    }

    private static Product buildProduct(String sku, String name, int qty) {
        Product p = new Product(name, qty, StockStatus.IN_STOCK);
        p.setSku(sku);
        p.setBarcode(sku);
        p.setCategory("Electronics");
        p.setShelfLocation("A1-01");
        p.setBuyPrice(10.0);
        p.setSellPrice(20.0);
        p.refreshStatusIfAutomatic();
        return p;
    }
}
