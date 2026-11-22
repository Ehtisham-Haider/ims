package com.stockpro.dsa;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryService;
import com.stockpro.service.StockOperationService;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies all 5 explicit DS&amp;A data structures (HashMap, Compressed Trie,
 * BK-Tree, Min-Heap, TreeMap)
 * and all 6 pure-Java offline ML engines (Forecasting, Smart Restock, Threshold
 * Tuner, Countdown,
 * Anomaly Guard, and ABC Velocity Grading).
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class DsaAndOfflineMlTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private ProductRepository productRepo;
    private InventoryService inventoryService;
    private StockOperationService stockService;
    private CatalogSearchEngine searchEngine;
    private OfflineMlEngine mlEngine;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("dsa-ml-test.db"));
        productRepo = new ProductRepository(dbManager);
        inventoryService = new InventoryService(productRepo, new CommandHistoryManager(),
                InventoryEventBus.getInstance());
        stockService = new StockOperationService(
                productRepo,
                new StockMovementRepository(dbManager),
                new AuditService(new AuditLogRepository(dbManager)),
                InventoryEventBus.getInstance());
        searchEngine = new CatalogSearchEngine(productRepo, InventoryEventBus.getInstance());
        mlEngine = new OfflineMlEngine(dbManager, productRepo);
    }

    @AfterEach
    void tearDown() {
        searchEngine.close();
        dbManager.close();
    }

    @Test
    @DisplayName("Exercises HashMap O(1), Compressed Trie, BK-Tree typo search, Min-Heap, and TreeMap range queries")
    void verifiesAllFiveDataStructures() {
        Product keyboard = new Product("Wireless Keyboard", 40, StockStatus.IN_STOCK);
        keyboard.setBuyPrice(15.0);
        keyboard.setSellPrice(29.99);
        inventoryService.addProduct(keyboard);

        Product mouse = new Product("Wireless Mouse", 4, StockStatus.LOW_STOCK);
        mouse.setLowStockThreshold(10);
        mouse.setBuyPrice(8.0);
        mouse.setSellPrice(18.50);
        inventoryService.addProduct(mouse);

        Product milk = new Product("Fresh Milk", 0, StockStatus.OUT_OF_STOCK);
        milk.setBuyPrice(1.0);
        milk.setSellPrice(1.50);
        milk.setExpiryDate(LocalDate.now().plusDays(2));
        inventoryService.addProduct(milk);

        searchEngine.rebuildIndices();

        // 1. HashMap O(1) SKU lookup
        assertTrue(searchEngine.getSkuBarcodeIndex().containsSku("PRD-1001"));
        assertEquals("Wireless Keyboard",
                searchEngine.getSkuBarcodeIndex().findBySku("PRD-1001").orElseThrow().getName());

        // 2. Compressed Trie prefix autocomplete
        List<Product> prefixHits = searchEngine.getProductTrie().autocomplete("wire", 5);
        assertEquals(2, prefixHits.size());

        // 3. BK-Tree + Levenshtein typo search ("keybord" -> "Wireless Keyboard")
        List<Product> fuzzyHits = searchEngine.getBkTree().searchFuzzy("keybord", 2, 5);
        assertFalse(fuzzyHits.isEmpty());
        assertEquals("Wireless Keyboard", fuzzyHits.get(0).getName());

        // 4. Min-Heap Urgency Tracker (Out-of-stock milk at 0 score before low-stock
        // mouse)
        List<UrgencyMinHeap.UrgencyEntry> topUrgent = searchEngine.getUrgencyMinHeap().topCritical(2);
        assertEquals(2, topUrgent.size());
        assertEquals("Fresh Milk", topUrgent.get(0).product().getName());
        assertEquals("Wireless Mouse", topUrgent.get(1).product().getName());

        // 5. Red-Black TreeMap Range Filter (price $10..$25)
        List<Product> midPriced = searchEngine.getRangeIndexTree().findByPriceRange(10.0, 25.0);
        assertEquals(1, midPriced.size());
        assertEquals("Wireless Mouse", midPriced.get(0).getName());
    }

    @Test
    @DisplayName("Runs offline ML forecasting, smart restock, countdown, ABC grading, and Z-score typo anomaly guard")
    void verifiesOfflineMlAlgorithms() {
        Product laptopStand = new Product("Laptop Stand", 12, StockStatus.IN_STOCK);
        laptopStand.setBuyPrice(12.0);
        laptopStand.setSellPrice(30.0);
        Product stand = inventoryService.addProduct(laptopStand).getData();

        Product pen = new Product("Blue Pen", 25, StockStatus.IN_STOCK);
        pen.setBuyPrice(0.2);
        pen.setSellPrice(0.5);
        inventoryService.addProduct(pen);

        Product typoItem = new Product("USB Cable", 5000, StockStatus.IN_STOCK);
        typoItem.setBuyPrice(1.0);
        typoItem.setSellPrice(2.0);
        inventoryService.addProduct(typoItem);

        // Record recent sales to drive velocity & ABC differentiation
        stockService.recordOperation(stand.getId(), MovementType.SALE, "6", "Order #1", "", "Faheen Ahmad Khan");

        List<OfflineMlEngine.ProductForecast> forecasts = mlEngine.analyzeCatalog();
        assertEquals(3, forecasts.size());

        OfflineMlEngine.ProductForecast standForecast = forecasts.stream()
                .filter(f -> f.product().getId() == stand.getId())
                .findFirst().orElseThrow();
        assertTrue(standForecast.forecast7Days() > 0);
        assertTrue(standForecast.forecast30Days() > standForecast.forecast7Days());
        assertTrue(standForecast.safetyStock() >= 2);
        assertTrue(standForecast.tunedLowStockThreshold() >= 3);
        assertTrue(List.of("A", "B", "C").contains(standForecast.abcGrade()));

        // Typo guard detects 5000 units outlier
        List<OfflineMlEngine.AnomalyAlert> anomalies = mlEngine.detectAnomalies();
        assertFalse(anomalies.isEmpty());
        assertTrue(anomalies.stream().anyMatch(a -> "QUANTITY_TYPO".equals(a.category())));
        assertNotNull(mlEngine.checkProposedQuantityTypo(5000));
    }
}
