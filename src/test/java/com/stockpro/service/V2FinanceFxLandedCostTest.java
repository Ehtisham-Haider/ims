package com.stockpro.service;

import com.stockpro.domain.CashFlowHorizon;
import com.stockpro.domain.FxRateQuote;
import com.stockpro.domain.LandedCostAllocation;
import com.stockpro.domain.LandedCostAllocation.AllocationStrategy;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.domain.VolumePricingAdvice;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 04:
 * Multi-currency FX conversion &amp; cache, Landed Cost strategies,
 * vendor bulk price tiers + MOQ advisor, and 30/60/90-day cash-flow forecasting.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2FinanceFxLandedCostTest {

    @TempDir
    Path tempDir;

    @Test
    void multiCurrencyFxParsesMockedLiveRatesAndFallsBackCleanlyOffline() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch04-fx.db"));
        try {
            MultiCurrencyFxService offline = new MultiCurrencyFxService(db, url -> null);
            List<FxRateQuote> seeded = offline.listCachedRates();
            assertTrue(seeded.size() >= 6);
            assertEquals(27850.0, offline.convert(100.0, "USD", "PKR"), 0.01);
            assertTrue(offline.refreshRatesFromNetwork().getMessage().contains("offline"));

            String fakeJson = "{\"base\":\"USD\",\"rates\":{\"EUR\":0.90,\"PKR\":280.0,\"GBP\":0.80}}";
            MultiCurrencyFxService onlineMock = new MultiCurrencyFxService(db, url -> fakeJson);
            InventoryOperationResult<List<FxRateQuote>> refreshed = onlineMock.refreshRatesFromNetwork();
            assertTrue(refreshed.isSuccess());
            assertEquals(28000.0, onlineMock.convert(100.0, "USD", "PKR"), 0.01);
            assertEquals(90.0, onlineMock.convert(100.0, "USD", "EUR"), 0.01);
        } finally {
            db.close();
        }
    }

    @Test
    void landedCostAllocatesFreightDutyAndHandlingAcrossStrategies() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch04-landed.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supplierRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            SupplierOrderService supService = new SupplierOrderService(
                    supplierRepo, poRepo, stockOps, auditService, InventoryEventBus.getInstance());

            Supplier sup = supplierRepo.insert(makeSupplier("Global Freight Sup", "0300", "sup@example.com", 4, 95.0, "Groceries"));
            Product p1 = new Product("Basmati Rice 5kg", 10, StockStatus.IN_STOCK);
            p1.setSku("LC-RICE");
            p1.setCategory("Groceries");
            p1.setBuyPrice(10.0);
            p1.setSellPrice(15.0);
            p1 = productRepo.insert(p1);

            Product p2 = new Product("USB-C Cable", 10, StockStatus.IN_STOCK);
            p2.setSku("LC-CABLE");
            p2.setCategory("Accessories");
            p2.setBuyPrice(30.0);
            p2.setSellPrice(45.0);
            p2 = productRepo.insert(p2);

            PurchaseOrder po = supService.createPurchaseOrder(sup.getId(), List.of(
                    new PurchaseOrderItem(p1.getId(), p1.getSku(), p1.getName(), 10, 10.0),  // value $100
                    new PurchaseOrderItem(p2.getId(), p2.getSku(), p2.getName(), 10, 30.0)   // value $300
            )).getData();

            LandedCostEngine engine = new LandedCostEngine(
                    db, poRepo, productRepo, auditService, InventoryEventBus.getInstance());

            LandedCostAllocation byValue = engine.allocateAndPersist(
                    po.getId(), 50.0, 20.0, 10.0, AllocationStrategy.BY_VALUE, true, "tester").getData();
            // Total overhead $80 split 25% ($20 -> +$2/unit) / 75% ($60 -> +$6/unit)
            assertEquals(12.0, byValue.lines().get(0).effectiveLandedUnitCost(), 0.01);
            assertEquals(36.0, byValue.lines().get(1).effectiveLandedUnitCost(), 0.01);
            assertEquals(12.0, productRepo.findById(p1.getId()).orElseThrow().getBuyPrice(), 0.01);

            LandedCostAllocation byQty = engine.calculatePreview(
                    po.getId(), 40.0, 0.0, 0.0, AllocationStrategy.BY_QUANTITY).getData();
            // $40 split 50/50 across equal 10/10 qty on PO base ($10 / $30) -> +$2/unit each
            assertEquals(12.0, byQty.lines().get(0).effectiveLandedUnitCost(), 0.01);
            assertEquals(32.0, byQty.lines().get(1).effectiveLandedUnitCost(), 0.01);
            assertTrue(engine.listAllocations().size() >= 1);
        } finally {
            db.close();
        }
    }

    @Test
    void volumePricingAdvisorEnforcesMoqAndCashFlowProjectsThreeHorizons() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch04-tiers.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supplierRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            SupplierOrderService supService = new SupplierOrderService(
                    supplierRepo, poRepo, stockOps, auditService, InventoryEventBus.getInstance());

            Supplier sup = supplierRepo.insert(makeSupplier("Bulk Supply Co", "0301", "bulk@example.com", 2, 97.0, "Drinks"));
            Product drink = new Product("Cold Brew Can", 2, StockStatus.LOW_STOCK);
            drink.setSku("TIER-BREW");
            drink.setLowStockThreshold(15);
            drink.setBuyPrice(8.0);
            drink.setSellPrice(14.0);
            drink = productRepo.insert(drink);

            VolumePricingTierService tierService = new VolumePricingTierService(db);
            assertTrue(tierService.upsertTier(sup.getId(), drink.getId(), 10, 10, 7.50).isSuccess());
            assertTrue(tierService.upsertTier(sup.getId(), drink.getId(), 50, 10, 6.20).isSuccess());

            VolumePricingAdvice advice = tierService.evaluateOrderQuantity(sup.getId(), drink.getId(), 5, 8.0);
            assertEquals(10, advice.enforcedQty(), "MOQ 10 must lift request of 5");
            assertEquals(7.50, advice.applicableUnitPrice(), 0.001);
            assertEquals(50, advice.nextTierMinQty());
            assertTrue(advice.stepUpTotalSavings() > 0.0);

            supService.createPurchaseOrder(sup.getId(), List.of(
                    new PurchaseOrderItem(drink.getId(), drink.getSku(), drink.getName(), 20, 7.50)
            ));

            CashFlowForecastService cashFlow = new CashFlowForecastService(db, productRepo, poRepo);
            List<CashFlowHorizon> horizons = cashFlow.forecastHorizons();
            assertEquals(3, horizons.size());
            assertEquals(30, horizons.get(0).horizonDays());
            assertEquals(60, horizons.get(1).horizonDays());
            assertEquals(90, horizons.get(2).horizonDays());
            assertEquals(150.0, horizons.get(0).committedPoOutflow(), 0.01);
        } finally {
            db.close();
        }
    }

    private Supplier makeSupplier(String name, String phone, String email, int deliveryDays, double reliability, String cats) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setPhone(phone);
        s.setEmail(email);
        s.setDeliveryDays(deliveryDays);
        s.setReliabilityScore(reliability);
        s.setLinkedCategories(cats);
        return s;
    }
}
