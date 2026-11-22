package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.dsa.LruProductCache;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.MegaScaleBenchmarkService.MegaScaleBenchmarkResult;
import com.stockpro.service.MegaScaleBenchmarkService.PagedCatalogResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic integration tests for Branch 12 Mega-Scale Performance &amp; AES-256-GCM Security:
 * O(1) LRU cache eviction &amp; hit-rate telemetry, indexed SQLite cursor pagination,
 * 100,000-SKU sub-50ms search benchmark, and PBKDF2 + AES-256-GCM encrypted backup/restore.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2MegaScaleSecurityQaTest {

    @TempDir
    Path tempDir;

    @Test
    void lruCacheEvictsOldestEntriesTracksHitRateAndPaginationSlicesCatalogCleanly() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch12-scale.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            for (int i = 1; i <= 15; i++) {
                Product p = new Product("Paginated Item " + i, i * 2, StockStatus.IN_STOCK);
                p.setSku("PG-" + String.format("%03d", i));
                p.setSellPrice(5.0 + i);
                productRepo.insert(p);
            }

            LruProductCache<String, Product> cache = new LruProductCache<>(3);
            List<Product> all = productRepo.findAll(false);
            cache.put(all.get(0).getSku(), all.get(0));
            cache.put(all.get(1).getSku(), all.get(1));
            cache.put(all.get(2).getSku(), all.get(2));
            assertTrue(cache.get(all.get(0).getSku()).isPresent()); // promotes PG-001 to MRU
            cache.put(all.get(3).getSku(), all.get(3)); // evicts PG-002 (LRU)

            assertEquals(3, cache.size());
            assertEquals(1L, cache.getEvictions());
            assertFalse(cache.get(all.get(1).getSku()).isPresent(), "LRU entry PG-002 should have evicted");
            assertTrue(cache.getHitRatePercent() > 0.0);

            MegaScaleBenchmarkService bench = new MegaScaleBenchmarkService(db, new LruProductCache<>(200));
            PagedCatalogResult page0 = bench.fetchPage(0, 6, false);
            PagedCatalogResult page2 = bench.fetchPage(2, 6, false);
            assertEquals(15, page0.totalItems());
            assertEquals(3, page0.totalPages());
            assertEquals(6, page0.items().size());
            assertEquals(3, page2.items().size());

            MegaScaleBenchmarkResult res100k = bench.benchmarkScaleSearch(100_000);
            assertEquals(100_000, res100k.totalIndexedSkus());
            assertTrue(res100k.coldSearchMs() < 50.0,
                    "100k-SKU cold lookup expected under 50ms, was " + res100k.coldSearchMs());
            assertTrue(res100k.sub50msPassed());
        } finally {
            db.close();
        }
    }

    @Test
    void encryptedBackupUsesAes256GcmRoundTripAndRejectsIncorrectPassphrase() throws Exception {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch12-enc.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            BackupService backupService = new BackupService(db, InventoryEventBus.getInstance());
            EncryptedBackupService encService = new EncryptedBackupService(backupService, auditService);

            Product saffron = new Product("Kashmiri Saffron 2g", 25, StockStatus.IN_STOCK);
            saffron.setSku("ENC-SAF-01");
            saffron.setSellPrice(19.50);
            productRepo.insert(saffron);

            Path encFile = tempDir.resolve("store-vault.enc");
            char[] goodPass = "StrongPass#2026".toCharArray();
            char[] wrongPass = "WrongPass#9999".toCharArray();

            InventoryOperationResult<Path> encRes = encService.createEncryptedBackup(encFile, goodPass, "ehtisham");
            assertTrue(encRes.isSuccess());
            assertTrue(Files.exists(encFile));

            // Clear table, verify wrong passphrase is rejected before modifying DB
            productRepo.deleteById(productRepo.findBySku("ENC-SAF-01").orElseThrow().getId());
            assertEquals(0, productRepo.countActive());

            InventoryOperationResult<Integer> badRestore = encService.restoreEncryptedBackup(encFile, wrongPass, "ehtisham");
            assertFalse(badRestore.isSuccess(), "Wrong passphrase must fail GCM authentication");
            assertEquals(0, productRepo.countActive());

            // Restore with valid passphrase
            InventoryOperationResult<Integer> okRestore = encService.restoreEncryptedBackup(encFile, goodPass, "ehtisham");
            assertTrue(okRestore.isSuccess());
            assertEquals(1, okRestore.getData());
            assertTrue(productRepo.findBySku("ENC-SAF-01").isPresent());
        } finally {
            db.close();
        }
    }
}
