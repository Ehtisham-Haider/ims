package com.stockpro.service;

import com.stockpro.ai.AgenticOrchestratorService;
import com.stockpro.ai.MultiAgentStoreBrainService;
import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.StoreBrainModels.AgentFeedbackWeight;
import com.stockpro.domain.StoreBrainModels.SalesDetectiveReport;
import com.stockpro.domain.StoreBrainModels.StoreHealthScoreReport;
import com.stockpro.domain.StoreBrainModels.WasteEcoImpactSummary;
import com.stockpro.domain.StoreBrainModels.WasteZeroBundle;
import com.stockpro.domain.Supplier;
import com.stockpro.ml.AdvancedOfflineMlService;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AgentProposalRepository;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 09:
 * 7-Agent Multi-Agent Team consensus, SQLite self-learning Approve/Reject weights,
 * Sales Detective root causes, Waste-Zero BOGO bundles, Eco savings tracker, and 0–100 Store Health Score.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2MultiAgentStoreBrainTest {

    @TempDir
    Path tempDir;

    @Test
    void sevenAgentConsensusQueuesProposalsWithoutSilentMutationsAndLearnsFeedbackWeights() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch09-agents.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AgentProposalRepository propRepo = new AgentProposalRepository(db);
            SettingsRepository settingsRepo = new SettingsRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            SupplierOrderService supService = new SupplierOrderService(
                    supRepo, poRepo, stockOps, auditService, InventoryEventBus.getInstance());
            OfflineMlEngine baseMl = new OfflineMlEngine(db, productRepo);
            AdvancedOfflineMlService advMl = new AdvancedOfflineMlService(db, productRepo, supRepo, poRepo, baseMl);
            WarehouseMapPickingService wms = new WarehouseMapPickingService(
                    db, productRepo, auditService, InventoryEventBus.getInstance());
            AgenticOrchestratorService baseOrch = new AgenticOrchestratorService(
                    propRepo, productRepo, supRepo, supService, baseMl, auditService, InventoryEventBus.getInstance());

            Supplier slowSup = new Supplier();
            slowSup.setName("Delayed Port Freight");
            slowSup.setDeliveryDays(6);
            slowSup.setReliabilityScore(72.0);
            supRepo.insert(slowSup);

            Product heavyRice = new Product("Basmati Rice 5kg", 22, StockStatus.LOW_STOCK);
            heavyRice.setSku("B9-RICE");
            heavyRice.setCategory("Groceries");
            heavyRice.setLowStockThreshold(25);
            heavyRice.setBuyPrice(5.0);
            heavyRice.setSellPrice(8.5);
            heavyRice.setShelfLocation("A1-01"); // 110kg >= 85% bin saturation!
            productRepo.insert(heavyRice);

            MultiAgentStoreBrainService brain = new MultiAgentStoreBrainService(
                    db, propRepo, productRepo, supRepo, poRepo, settingsRepo,
                    baseOrch, advMl, wms, auditService, InventoryEventBus.getInstance());

            List<AgentProposal> team = brain.runSevenAgentTeamConsensus();
            assertTrue(team.size() >= 3);
            assertTrue(team.stream().anyMatch(p -> p.getAgentName().contains("Budget Guardian")));
            assertTrue(team.stream().anyMatch(p -> p.getAgentName().contains("Delay-Rescue")));
            assertTrue(team.stream().anyMatch(p -> p.getAgentName().contains("Warehouse Organizer")));
            assertTrue(poRepo.findAll().isEmpty(), "Agents must NEVER create POs silently without human approval");

            AgentFeedbackWeight up = brain.recordDecisionFeedback("AI Auto-Buyer Agent", true);
            assertEquals(1.12, up.weightValue(), 0.001);
            AgentFeedbackWeight down = brain.recordDecisionFeedback("Delay-Rescue Agent", false);
            assertEquals(0.85, down.weightValue(), 0.001);
            assertTrue(brain.listFeedbackWeights().size() >= 2);
        } finally {
            db.close();
        }
    }

    @Test
    void salesDetectiveWasteZeroBundlesEcoTrackerAndStoreHealthScoreProduceAccurateMetrics() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch09-eco.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AgentProposalRepository propRepo = new AgentProposalRepository(db);
            SettingsRepository settingsRepo = new SettingsRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            SupplierOrderService supService = new SupplierOrderService(
                    supRepo, poRepo, stockOps, auditService, InventoryEventBus.getInstance());
            OfflineMlEngine baseMl = new OfflineMlEngine(db, productRepo);
            AdvancedOfflineMlService advMl = new AdvancedOfflineMlService(db, productRepo, supRepo, poRepo, baseMl);
            WarehouseMapPickingService wms = new WarehouseMapPickingService(
                    db, productRepo, auditService, InventoryEventBus.getInstance());
            AgenticOrchestratorService baseOrch = new AgenticOrchestratorService(
                    propRepo, productRepo, supRepo, supService, baseMl, auditService, InventoryEventBus.getInstance());

            Product yogurt = new Product("Greek Yogurt", 15, StockStatus.IN_STOCK);
            yogurt.setSku("ECO-YOG");
            yogurt.setBuyPrice(1.0);
            yogurt.setSellPrice(3.0);
            yogurt.setExpiryDate(LocalDate.now().plusDays(4));
            yogurt = productRepo.insert(yogurt);

            Product granola = new Product("Honey Granola", 40, StockStatus.IN_STOCK);
            granola.setSku("ECO-GRAN");
            granola.setBuyPrice(2.0);
            granola.setSellPrice(5.0);
            granola = productRepo.insert(granola);

            stockOps.recordOperation(granola.getId(), MovementType.SALE, "4", "POS", "Sale", "cashier");

            MultiAgentStoreBrainService brain = new MultiAgentStoreBrainService(
                    db, propRepo, productRepo, supRepo, poRepo, settingsRepo,
                    baseOrch, advMl, wms, auditService, InventoryEventBus.getInstance());

            SalesDetectiveReport det = brain.investigateSalesDrivers(14);
            assertEquals(4, det.totalSalesUnits());
            assertTrue(det.summary().contains("Honey Granola"));

            List<WasteZeroBundle> bundles = brain.suggestWasteZeroBundles();
            assertEquals(1, bundles.size());
            assertTrue(bundles.get(0).bundleTitle().contains("Greek Yogurt"));

            brain.logWasteEcoEvent(yogurt.getId(), "RESCUED", 10, 24.0, "Bundle rescue");
            WasteEcoImpactSummary eco = brain.getWasteEcoSummary();
            assertEquals(10, eco.rescuedUnits());
            assertEquals(6.5, eco.rescuedKg(), 0.01);
            assertTrue(eco.estimatedCo2SavedKg() > 10.0);

            StoreHealthScoreReport health = brain.computeStoreHealthScore();
            assertTrue(health.overallScore() >= 50 && health.overallScore() <= 100);
            assertTrue(health.headline().contains("/100"));
        } finally {
            db.close();
        }
    }
}
