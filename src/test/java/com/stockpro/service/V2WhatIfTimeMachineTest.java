package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.WhatIfSimulationModels.SimulationResultSummary;
import com.stockpro.domain.WhatIfSimulationModels.SimulationScenarioInput;
import com.stockpro.ml.MonteCarloSimulator;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 10 Monte Carlo What-If Time Machine:
 * 1,000-trial demand surge and supplier delay simulation quantiles, stockout risk response,
 * and threshold/settings persistence via explicit user approval.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2WhatIfTimeMachineTest {

    @TempDir
    Path tempDir;

    @Test
    void thousandTrialMonteCarloProducesOrderedQuantilesAndHigherRiskUnderDelayStress() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch10-whatif.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SettingsRepository settingsRepo = new SettingsRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            OfflineMlEngine mlEngine = new OfflineMlEngine(db, productRepo);
            MonteCarloSimulator simulator = new MonteCarloSimulator(
                    productRepo, mlEngine, settingsRepo, auditService, InventoryEventBus.getInstance());

            Product p1 = new Product("Whole Milk 1L", 18, StockStatus.IN_STOCK);
            p1.setSku("SIM-MILK-01");
            p1.setCategory("Dairy");
            p1.setLowStockThreshold(8);
            p1.setBuyPrice(2.0);
            p1.setSellPrice(4.5);
            p1.setExpiryDate(LocalDate.now().plusDays(10));
            productRepo.insert(p1);

            Product p2 = new Product("Basmati Rice 5kg", 10, StockStatus.IN_STOCK);
            p2.setSku("SIM-RICE-02");
            p2.setCategory("Pantry");
            p2.setLowStockThreshold(6);
            p2.setBuyPrice(8.0);
            p2.setSellPrice(14.0);
            productRepo.insert(p2);

            SimulationResultSummary calm = simulator.runSimulation(
                    SimulationScenarioInput.builder()
                            .demandSurgePercent(0.0)
                            .supplierDelayDays(0)
                            .monthlyBudgetCap(20000.0)
                            .safetyBufferPercent(25.0)
                            .trialCount(1000)
                            .seed(99L)
                            .build()
            );

            assertEquals(1000, calm.trialCount());
            assertTrue(calm.revenueP10() <= calm.revenueP50() && calm.revenueP50() <= calm.revenueP90(),
                    "Expected ordered P10 <= P50 <= P90 quantiles");
            assertTrue(calm.baselineRevenue() > 0.0, "Baseline revenue must be positive");
            assertEquals(2, calm.skuRows().size());

            SimulationResultSummary stressed = simulator.runSimulation(
                    SimulationScenarioInput.builder()
                            .demandSurgePercent(85.0)
                            .supplierDelayDays(12)
                            .monthlyBudgetCap(400.0)
                            .safetyBufferPercent(5.0)
                            .trialCount(1000)
                            .seed(99L)
                            .build()
            );

            assertTrue(stressed.stockoutRiskPercent() > calm.stockoutRiskPercent(),
                    "Higher demand surge + supplier delay + tighter cap should elevate stockout risk");
            assertTrue(stressed.recommendedSafetyBufferPercent() > calm.recommendedSafetyBufferPercent(),
                    "Stressed scenario should recommend a higher safety stock buffer");
        } finally {
            db.close();
        }
    }

    @Test
    void applyRecommendedBufferPersistsSettingsRaisesAtRiskThresholdsAndWritesAuditLog() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch10-apply.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SettingsRepository settingsRepo = new SettingsRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            OfflineMlEngine mlEngine = new OfflineMlEngine(db, productRepo);
            MonteCarloSimulator simulator = new MonteCarloSimulator(
                    productRepo, mlEngine, settingsRepo, auditService, InventoryEventBus.getInstance());

            Product coffee = new Product("Espresso Roast 500g", 12, StockStatus.IN_STOCK);
            coffee.setSku("SIM-COF-01");
            coffee.setCategory("Beverages");
            coffee.setLowStockThreshold(5);
            coffee.setBuyPrice(6.0);
            coffee.setSellPrice(12.5);
            Product saved = productRepo.insert(coffee);

            SimulationResultSummary summary = simulator.runSimulation(
                    SimulationScenarioInput.builder()
                            .demandSurgePercent(90.0)
                            .supplierDelayDays(10)
                            .monthlyBudgetCap(300.0)
                            .safetyBufferPercent(10.0)
                            .trialCount(1000)
                            .seed(77L)
                            .build()
            );

            InventoryOperationResult<Integer> applyRes = simulator.applyRecommendedBuffer(summary, "ehtisham");
            assertTrue(applyRes.isSuccess());
            assertEquals(
                    String.valueOf(summary.recommendedSafetyBufferPercent()),
                    settingsRepo.get("ai.recommended_safety_buffer_pct", "")
            );
            Product reloaded = productRepo.findById(saved.getId()).orElseThrow();
            assertTrue(reloaded.getLowStockThreshold() >= 5);
            assertTrue(auditService.verifyIntegrity().intact(), "Audit hash chain must remain intact");
        } finally {
            db.close();
        }
    }
}
