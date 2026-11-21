package com.stockpro.service;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.InventoryFilterCriteria.StatusFilterChip;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies filter chips, category/price filtering, starter demo loader, and bulk status/delete operations.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class InventoryFilterAndBulkTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("inventory-filter-test.db"));
        inventoryService = new InventoryService(
                new ProductRepository(dbManager),
                new CommandHistoryManager(),
                InventoryEventBus.getInstance()
        );
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Filters catalog by status chips, expiring window, category, search query, and price cap")
    void filtersByStatusChipsCategoryAndQuery() {
        int seeded = inventoryService.loadStarterDemoItems();
        assertEquals(6, seeded);

        InventoryFilterCriteria lowCriteria = new InventoryFilterCriteria();
        lowCriteria.setStatusChip(StatusFilterChip.LOW_STOCK);
        List<Product> lowItems = inventoryService.filterProducts(lowCriteria);
        assertEquals(2, lowItems.size());

        InventoryFilterCriteria expiring = new InventoryFilterCriteria();
        expiring.setStatusChip(StatusFilterChip.EXPIRING);
        assertEquals(3, inventoryService.filterProducts(expiring).size());

        InventoryFilterCriteria dairySearch = new InventoryFilterCriteria();
        dairySearch.setCategory("Dairy");
        dairySearch.setSearchQuery("Milk");
        dairySearch.setMaxSellPrice(2.0);
        List<Product> milkHits = inventoryService.filterProducts(dairySearch);
        assertEquals(1, milkHits.size());
        assertEquals("Milk 1L", milkHits.get(0).getName());
    }

    @Test
    @DisplayName("Bulk status update and bulk delete enforce authorization and update repository cleanly")
    void bulkStatusAndBulkDelete() {
        Product p1 = inventoryService.addProductFromInputs("Soap", "40", "Personal Care", 1.0, 2.0, null).getData();
        Product p2 = inventoryService.addProductFromInputs("Shampoo", "25", "Personal Care", 2.0, 4.0, null).getData();
        List<Long> ids = List.of(p1.getId(), p2.getId());

        InventoryOperationResult<Integer> denied = inventoryService.bulkUpdateStatus(ids, StockStatus.LOW_STOCK, false);
        assertFalse(denied.isSuccess());

        InventoryOperationResult<Integer> statusRes = inventoryService.bulkUpdateStatus(ids, StockStatus.LOW_STOCK, true);
        assertTrue(statusRes.isSuccess());
        assertEquals(2, statusRes.getData());
        assertEquals(StockStatus.LOW_STOCK, inventoryService.findById(p1.getId()).orElseThrow().getStatus());

        InventoryOperationResult<Integer> delRes = inventoryService.bulkDelete(ids, true);
        assertTrue(delRes.isSuccess());
        assertEquals(2, delRes.getData());
        assertTrue(inventoryService.getAllProducts().isEmpty());
    }
}
