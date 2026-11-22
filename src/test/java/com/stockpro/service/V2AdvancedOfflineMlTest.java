package com.stockpro.service;

import com.stockpro.domain.MlSuiteModels.BasketAssociationRule;
import com.stockpro.domain.MlSuiteModels.DynamicPriceSuggestion;
import com.stockpro.domain.MlSuiteModels.ExpiryMarkdownSuggestion;
import com.stockpro.domain.MlSuiteModels.HoltWintersPrediction;
import com.stockpro.domain.MlSuiteModels.LostSaleImputation;
import com.stockpro.domain.MlSuiteModels.SupplierOtifScore;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.dsa.FenwickTimeAggregator;
import com.stockpro.ml.AdvancedOfflineMlService;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 07:
 * Fenwick Tree {@code O(log n)} range queries, Holt-Winters seasonal confidence intervals,
 * Supplier OTIF &amp; lead time prediction, dynamic pricing, stockout lost-sale imputation,
 * tiered 10/20/35% expiry markdowns, and Apriori co-occurrence rules.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2AdvancedOfflineMlTest {

    @TempDir
    Path tempDir;

    @Test
    void fenwickTimeAggregatorComputesExactPrefixAndRangeSumsInLogNTime() {
        FenwickTimeAggregator bit = new FenwickTimeAggregator(10);
        double[] sample = {12.5, 8.0, 15.0, 4.5, 20.0, 10.0, 3.0, 7.0, 11.0, 9.0};
        for (int i = 0; i < sample.length; i++) {
            bit.addDelta(i, sample[i]);
        }
        assertEquals(40.0, bit.prefixSum(3), 0.001);
        assertEquals(39.5, bit.rangeSum(2, 4), 0.001);
        bit.setValue(3, 14.5);
        assertEquals(49.5, bit.rangeSum(2, 4), 0.001);
    }

    @Test
    void holtWintersConfidenceIntervalsExpiryDiscountsAndLostSaleImputationWorkOffline() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch07-hw.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            OfflineMlEngine baseMl = new OfflineMlEngine(db, productRepo);
            AdvancedOfflineMlService ml = new AdvancedOfflineMlService(db, productRepo, supRepo, poRepo, baseMl);

            Product milk = new Product("Fresh Milk 1L", 18, StockStatus.IN_STOCK);
            milk.setSku("ML-MILK");
            milk.setCategory("Dairy");
            milk.setBuyPrice(1.2);
            milk.setSellPrice(2.5);
            milk.setExpiryDate(LocalDate.now().plusDays(5)); // <=7 days -> 35% discount
            milk = productRepo.insert(milk);

            Product oosCoffee = new Product("Arabica Espresso", 0, StockStatus.OUT_OF_STOCK);
            oosCoffee.setSku("ML-OOS");
            oosCoffee.setCategory("Dairy");
            oosCoffee.setBuyPrice(5.0);
            oosCoffee.setSellPrice(10.0);
            productRepo.insert(oosCoffee);

            List<Double> series = List.of(
                    4.0, 5.0, 6.0, 5.0, 7.0, 9.0, 8.0,
                    5.0, 6.0, 7.0, 6.0, 8.0, 10.0, 9.0,
                    6.0, 7.0, 8.0, 7.0, 9.0, 11.0, 10.0
            );
            HoltWintersPrediction hw = ml.forecastHoltWinters(milk, series);
            assertTrue(hw.next7DaysForecast() > 0.0);
            assertTrue(hw.lower95Bound() <= hw.next7DaysForecast());
            assertTrue(hw.next7DaysForecast() <= hw.upper95Bound());

            List<ExpiryMarkdownSuggestion> markdowns = ml.suggestExpiryMarkdowns();
            assertEquals(1, markdowns.size());
            assertEquals(35, markdowns.get(0).recommendedDiscountPercent());

            List<LostSaleImputation> lostSales = ml.imputeStockoutLostSales();
            assertFalse(lostSales.isEmpty());
            assertEquals("ML-OOS", lostSales.get(0).sku());
            assertTrue(lostSales.get(0).imputedMissedRevenue() > 0.0);
        } finally {
            db.close();
        }
    }

    @Test
    void supplierOtifDynamicPricingAndAprioriBasketRulesProduceActionableInsights() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch07-apriori.db"));
        try {
            ProductRepository productRepo = new ProductRepository(db);
            SupplierRepository supRepo = new SupplierRepository(db);
            PurchaseOrderRepository poRepo = new PurchaseOrderRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());
            SupplierOrderService supService = new SupplierOrderService(
                    supRepo, poRepo, stockOps, auditService, InventoryEventBus.getInstance());

            Supplier s = new Supplier();
            s.setName("OnTime Foods");
            s.setDeliveryDays(3);
            s.setReliabilityScore(94.0);
            s = supRepo.insert(s);

            Product chips = new Product("Sea Salt Chips", 4, StockStatus.LOW_STOCK);
            chips.setSku("AP-CHIPS");
            chips.setBuyPrice(1.0);
            chips.setSellPrice(2.5);
            chips = productRepo.insert(chips);

            Product cola = new Product("Zero Sugar Cola", 120, StockStatus.IN_STOCK);
            cola.setSku("AP-COLA");
            cola.setBuyPrice(0.5);
            cola.setSellPrice(2.0);
            cola = productRepo.insert(cola);

            PurchaseOrder po = supService.createPurchaseOrder(s.getId(), List.of(
                    new PurchaseOrderItem(chips.getId(), chips.getSku(), chips.getName(), 10, 1.0)
            )).getData();
            supService.updateOrderStatus(po.getId(), "Delivered", "tester");

            for (int b = 1; b <= 3; b++) {
                String tag = "BASKET-" + b;
                assertTrue(stockOps.recordOperation(chips.getId(), MovementType.SALE, "1", "POS", tag, "cashier").isSuccess());
                assertTrue(stockOps.recordOperation(cola.getId(), MovementType.SALE, "1", "POS", tag, "cashier").isSuccess());
            }

            OfflineMlEngine baseMl = new OfflineMlEngine(db, productRepo);
            AdvancedOfflineMlService ml = new AdvancedOfflineMlService(db, productRepo, supRepo, poRepo, baseMl);

            List<SupplierOtifScore> otifs = ml.scoreSuppliersOtif();
            assertFalse(otifs.isEmpty());
            assertTrue(otifs.get(0).otifScorePercent() >= 90.0);
            assertEquals("ELITE", otifs.get(0).tier());

            List<BasketAssociationRule> rules = ml.mineBoughtTogetherRules(2);
            assertFalse(rules.isEmpty(), "Co-occurring SALE movements with BASKET tags should produce Apriori rule");
            assertTrue(rules.get(0).pairCount() >= 3);
            assertEquals(100.0, rules.get(0).confidencePercent(), 0.01);

            List<DynamicPriceSuggestion> prices = ml.recommendDynamicPrices();
            assertTrue(prices.stream().allMatch(dp -> dp.suggestedSellPrice() >= 0.5));
        } finally {
            db.close();
        }
    }
}
