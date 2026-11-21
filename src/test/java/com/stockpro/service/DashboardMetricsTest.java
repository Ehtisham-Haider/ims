package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies real-time SQLite executive dashboard aggregation, status percentages, and expiring alerts.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class DashboardMetricsTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;
    private DashboardMetricsService metricsService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("dashboard-test.db"));
        ProductRepository repo = new ProductRepository(dbManager);
        inventoryService = new InventoryService(repo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        metricsService = new DashboardMetricsService(dbManager, repo);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Aggregates total products, valuation, profit, status percentages, and 7-day expiring alerts")
    void aggregatesLiveDashboardMetrics() {
        Product rice = new Product("Rice 5kg", 100, StockStatus.IN_STOCK);
        rice.setBuyPrice(2.50);
        rice.setSellPrice(3.50);
        inventoryService.addProduct(rice);

        Product milk = new Product("Milk 1L", 5, StockStatus.LOW_STOCK);
        milk.setLowStockThreshold(10);
        milk.setBuyPrice(1.00);
        milk.setSellPrice(1.50);
        milk.setExpiryDate(LocalDate.now().plusDays(3));
        inventoryService.addProduct(milk);

        Product bread = new Product("Bread", 0, StockStatus.OUT_OF_STOCK);
        bread.setBuyPrice(0.70);
        bread.setSellPrice(1.00);
        inventoryService.addProduct(bread);

        DashboardMetricsService.DashboardSnapshot snap = metricsService.loadSnapshot();
        assertEquals(3, snap.totalProducts());
        assertEquals(1, snap.inStockCount());
        assertEquals(1, snap.lowStockCount());
        assertEquals(1, snap.outOfStockCount());
        assertEquals(1, snap.expiringSoonCount());
        assertEquals(255.0, snap.inventoryValue(), 0.01);
        assertEquals(102.5, snap.estimatedProfit(), 0.01);
        assertEquals(7, snap.trendPoints().size());
        assertFalse(snap.briefingLines().isEmpty());
    }
}
