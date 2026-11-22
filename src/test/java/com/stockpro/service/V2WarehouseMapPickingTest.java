package com.stockpro.service;

import com.stockpro.domain.PickRoutePlan;
import com.stockpro.domain.Product;
import com.stockpro.domain.ShelfBinRecord;
import com.stockpro.dsa.WarehousePickRouter;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 03:
 * 2D warehouse floorplan grid, {@code >=85%} saturation monitoring, one-click bin moves,
 * and Dijkstra shortest-path picking optimization.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2WarehouseMapPickingTest {

    @TempDir
    Path tempDir;

    @Test
    void shelfBinsDetectSaturationAbove85PercentAndNormalizeLegacyLocations() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch03-bins.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            WarehouseMapPickingService mapService = new WarehouseMapPickingService(
                    db, productRepo, auditService, InventoryEventBus.getInstance());

            assertEquals("A1-03", mapService.normalizeBinCode("A-03"));
            assertEquals("E4-12", mapService.normalizeBinCode("E4-12"));

            List<ShelfBinRecord> allBins = mapService.listAllBins();
            assertEquals(240, allBins.size(), "Five aisles x 4 rows x 12 cols = 240 shelf bins");

            // Create a heavy 5kg rice SKU with 22 units -> 110 kg / 120 kg = 91.67% (>=85% alert)
            Product heavyRice = new Product();
            heavyRice.setSku("TEST-RICE-5KG");
            heavyRice.setName("Super Basmati Rice 5kg");
            heavyRice.setCategory("Groceries");
            heavyRice.setQuantity(22);
            heavyRice.setLowStockThreshold(5);
            heavyRice.setBuyPrice(4.0);
            heavyRice.setSellPrice(7.0);
            heavyRice.setShelfLocation("B2-05");
            productRepo.insert(heavyRice);

            ShelfBinRecord b205 = mapService.findBin("B2-05").orElseThrow();
            assertTrue(b205.isNearlyFull(), "22 x 5kg = 110kg on 120kg bin should exceed 85% threshold");
            assertEquals("FULL", b205.capacityTier());
            assertTrue(mapService.listSaturatedBins(85.0).stream().anyMatch(b -> b.binCode().equals("B2-05")));
        } finally {
            db.close();
        }
    }

    @Test
    void moveItemToBinUpdatesLoadsLogsAuditAndRejectsInvalidTarget() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch03-move.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            WarehouseMapPickingService mapService = new WarehouseMapPickingService(
                    db, productRepo, auditService, InventoryEventBus.getInstance());

            Product item = new Product();
            item.setSku("TEST-OLIVE");
            item.setName("Extra Virgin Olive Oil");
            item.setCategory("Groceries");
            item.setQuantity(10);
            item.setLowStockThreshold(3);
            item.setBuyPrice(5.0);
            item.setSellPrice(9.0);
            item.setShelfLocation("A1-01");
            Product saved = productRepo.insert(item);

            InventoryOperationResult<Product> invalid = mapService.moveItemToBin(saved.getId(), "Z9-99", "tester");
            assertFalse(invalid.isSuccess(), "Nonexistent bin must be rejected cleanly");

            InventoryOperationResult<Product> moved = mapService.moveItemToBin(saved.getId(), "C3-08", "tester");
            assertTrue(moved.isSuccess(), "Valid relocation into C3-08 should succeed");
            assertEquals("C3-08", productRepo.findById(saved.getId()).orElseThrow().getShelfLocation());
            assertEquals(1, mapService.findBin("C3-08").orElseThrow().skuCount());
            assertEquals(0, mapService.findBin("A1-01").orElseThrow().skuCount());
            assertFalse(auditService.getRecentEntries(5).isEmpty());
            assertTrue(auditService.verifyIntegrity().toString().contains("=true"),
                    "Audit hash chain must remain valid after bin move");
        } finally {
            db.close();
        }
    }

    @Test
    void dijkstraPickRouterProducesShorterOrEqualMultiAisleTourAndIncludesEveryItem() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch03-route.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            WarehouseMapPickingService mapService = new WarehouseMapPickingService(
                    db, productRepo, auditService, InventoryEventBus.getInstance());

            String[] coords = {"E1-01", "A1-02", "D4-10", "A1-04", "E1-03"};
            Map<Long, Integer> req = new LinkedHashMap<>();
            for (int i = 0; i < coords.length; i++) {
                Product p = new Product();
                p.setSku("PICK-" + i);
                p.setName("Item " + coords[i]);
                p.setCategory("Snacks");
                p.setQuantity(15);
                p.setLowStockThreshold(3);
                p.setBuyPrice(1.5);
                p.setSellPrice(3.0);
                p.setShelfLocation(coords[i]);
                Product inserted = productRepo.insert(p);
                req.put(inserted.getId(), 2);
            }

            PickRoutePlan plan = mapService.optimizePickList(req);
            assertEquals(coords.length, plan.steps().size());
            assertTrue(plan.totalDistanceMeters() > 0.0);
            assertTrue(plan.totalDistanceMeters() <= plan.baselineUnoptimizedMeters(),
                    "Optimized Dijkstra + 2-opt tour must beat or match arbitrary input order");

            WarehousePickRouter router = new WarehousePickRouter();
            assertTrue(router.shortestDistanceMeters("A4-12", "B4-12") < 8.0,
                    "Rear cross-aisle must permit short aisle hops at row 4 without returning to front");
        } finally {
            db.close();
        }
    }
}
