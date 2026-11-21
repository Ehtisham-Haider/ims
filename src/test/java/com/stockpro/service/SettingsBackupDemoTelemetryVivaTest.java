package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies SQLite backup and restore, 1,000-product batch demo generation and empty reset,
 * telemetry latency/memory snapshots, and all 8 built-in 30B.docx lab QA checks.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class SettingsBackupDemoTelemetryVivaTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private ProductRepository productRepo;
    private CatalogSearchEngine searchEngine;
    private DemoDataService demoService;
    private BackupService backupService;
    private TelemetryMonitorService telemetryService;
    private BuiltInLabTestRunner labTestRunner;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("settings-qa-test.db"));
        productRepo = new ProductRepository(dbManager);
        searchEngine = new CatalogSearchEngine(productRepo, null);
        demoService = new DemoDataService(dbManager, searchEngine, InventoryEventBus.getInstance());
        backupService = new BackupService(dbManager, InventoryEventBus.getInstance());
        telemetryService = new TelemetryMonitorService(dbManager, searchEngine);
        labTestRunner = new BuiltInLabTestRunner();
    }

    @AfterEach
    void tearDown() {
        searchEngine.close();
        dbManager.close();
    }

    @Test
    @DisplayName("Seeds 1,000 realistic items quickly, captures telemetry, backs up SQLite, wipes, and restores")
    void demoDataTelemetryBackupAndRestore() {
        int seeded = demoService.generateRealisticDemoStore(1000);
        assertEquals(1000, seeded);
        assertEquals(1000, productRepo.countActive());

        TelemetryMonitorService.TelemetrySnapshot snap = telemetryService.captureSnapshot();
        assertEquals(1000, snap.productCount());
        assertTrue(snap.movementCount() > 0);
        assertTrue(snap.maxHeapMb() > 0);
        assertTrue(snap.trieLatencyMs() >= 0.0);

        Path backupPath = tempDir.resolve("snapshot.db");
        backupService.createBackup(backupPath);
        assertTrue(Files.exists(backupPath));
        assertTrue(backupService.validateBackupFile(backupPath));

        demoService.clearStoreForEmptyStateTest();
        assertEquals(0, productRepo.countActive());

        InventoryOperationResult<Integer> restored = backupService.restoreFromBackup(backupPath);
        assertTrue(restored.isSuccess());
        assertEquals(1000, restored.getData());
        assertEquals(1000, productRepo.countActive());
    }

    @Test
    @DisplayName("BuiltInLabTestRunner passes all 8 mandatory 30B.docx checks live")
    void builtInLabTestRunnerPassesAllEightCases() {
        List<BuiltInLabTestRunner.LabCaseResult> results = labTestRunner.runAllMandatoryLabChecks();
        assertEquals(8, results.size());
        for (BuiltInLabTestRunner.LabCaseResult r : results) {
            assertTrue(r.passed(), "Lab check #" + r.caseNumber() + " (" + r.testCaseName() + ") failed: " + r.actualResult());
        }
    }
}
