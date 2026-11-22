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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated verification of all 8 mandatory lab test cases (30B.docx), SKU auto-generation,
 * manual status overrides, and Command Pattern Undo/Redo stacks.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class InventoryCrudOelTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("oel-crud-test.db"));
        ProductRepository repository = new ProductRepository(dbManager);
        inventoryService = new InventoryService(repository, new CommandHistoryManager(), InventoryEventBus.getInstance());
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("OEL Case 1: Add a valid item assigns PRD-1001 SKU and automatic status")
    void addValidItem() {
        InventoryOperationResult<Product> result = inventoryService.addProductFromInputs(
                "Rice 5kg", "120", "Groceries", 2.50, 3.50, null);

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("PRD-1001", result.getData().getSku());
        assertEquals(120, result.getData().getQuantity());
        assertEquals(StockStatus.IN_STOCK, result.getData().getStatus());
        assertEquals(1, inventoryService.getAllProducts().size());
    }

    @Test
    @DisplayName("OEL Case 2: Add an item with empty or whitespace name is blocked with validation message")
    void addItemWithEmptyNameBlocked() {
        InventoryOperationResult<Product> blankResult = inventoryService.addProductFromInputs(
                "   ", "15", "General", 1.0, 2.0, null);
        assertFalse(blankResult.isSuccess());
        assertTrue(blankResult.getValidationResult().getFieldErrors().containsKey("name"));
        assertTrue(blankResult.getMessage().toLowerCase().contains("name"));
        assertTrue(inventoryService.getAllProducts().isEmpty());
    }

    @Test
    @DisplayName("OEL Case 3: Add an item with negative, decimal, non-numeric, or overflow quantity is blocked")
    void addItemWithInvalidQuantityBlocked() {
        InventoryOperationResult<Product> negResult = inventoryService.addProductFromInputs(
                "Milk 1L", "-5", "Dairy", 1.0, 1.5, null);
        assertFalse(negResult.isSuccess());
        assertTrue(negResult.getMessage().toLowerCase().contains("negative"));

        InventoryOperationResult<Product> alphaResult = inventoryService.addProductFromInputs(
                "Milk 1L", "abc", "Dairy", 1.0, 1.5, null);
        assertFalse(alphaResult.isSuccess());
        assertTrue(alphaResult.getMessage().toLowerCase().contains("number"));

        InventoryOperationResult<Product> decResult = inventoryService.addProductFromInputs(
                "Milk 1L", "4.5", "Dairy", 1.0, 1.5, null);
        assertFalse(decResult.isSuccess());
        assertTrue(decResult.getMessage().toLowerCase().contains("integer"));

        InventoryOperationResult<Product> overflowResult = inventoryService.addProductFromInputs(
                "Milk 1L", "999999999999", "Dairy", 1.0, 1.5, null);
        assertFalse(overflowResult.isSuccess());
        assertEquals(0, inventoryService.getAllProducts().size());
    }

    @Test
    @DisplayName("OEL Case 4: View all items returns complete stored catalog")
    void viewAllItems() {
        inventoryService.addProductFromInputs("Rice 5kg", "120", "Groceries", 2.5, 3.5, null);
        inventoryService.addProductFromInputs("Milk 1L", "5", "Dairy", 1.0, 1.5, null);
        inventoryService.addProductFromInputs("Bread", "0", "Bakery", 0.7, 1.0, null);

        List<Product> all = inventoryService.getAllProducts();
        assertEquals(3, all.size());
        assertEquals("PRD-1001", all.get(0).getSku());
        assertEquals("PRD-1002", all.get(1).getSku());
        assertEquals("PRD-1003", all.get(2).getSku());
        assertEquals(StockStatus.IN_STOCK, all.get(0).getStatus());
        assertEquals(StockStatus.LOW_STOCK, all.get(1).getStatus());
        assertEquals(StockStatus.OUT_OF_STOCK, all.get(2).getStatus());
    }

    @Test
    @DisplayName("OEL Case 5: View an empty inventory handles gracefully with informative message")
    void viewEmptyInventory() {
        List<Product> items = inventoryService.getAllProducts();
        assertNotNull(items);
        assertTrue(items.isEmpty());
        String summary = inventoryService.viewInventorySummaryMessage();
        assertTrue(summary.toLowerCase().contains("empty"));
    }

    @Test
    @DisplayName("OEL Case 6: Update an existing item updates details, quantity, and auto-computed status")
    void updateExistingItem() {
        Product created = inventoryService.addProductFromInputs(
                "Wireless Mouse", "25", "Electronics", 10.0, 18.0, null).getData();

        created.setName("Wireless Mouse Pro");
        created.setQuantity(4);
        created.setSellPrice(22.0);
        created.setShelfLocation("A2-04");
        InventoryOperationResult<Product> updateResult = inventoryService.updateProduct(created);

        assertTrue(updateResult.isSuccess());
        Product reloaded = inventoryService.findById(created.getId()).orElseThrow();
        assertEquals("Wireless Mouse Pro", reloaded.getName());
        assertEquals(4, reloaded.getQuantity());
        assertEquals(22.0, reloaded.getSellPrice());
        assertEquals("A2-04", reloaded.getShelfLocation());
        assertEquals(StockStatus.LOW_STOCK, reloaded.getStatus());
    }

    @Test
    @DisplayName("OEL Case 7: Update or delete a non-existing item is handled gracefully without crash")
    void updateAndDeleteNonExistingItemHandledGracefully() {
        Product ghost = new Product("Ghost Item", 10, StockStatus.IN_STOCK);
        ghost.setId(99999L);

        InventoryOperationResult<Product> updateResult = inventoryService.updateProduct(ghost);
        assertFalse(updateResult.isSuccess());
        assertTrue(updateResult.getMessage().toLowerCase().contains("not found"));

        InventoryOperationResult<Product> deleteResult = inventoryService.deleteProduct(99999L);
        assertFalse(deleteResult.isSuccess());
        assertTrue(deleteResult.getMessage().toLowerCase().contains("does not exist"));
    }

    @Test
    @DisplayName("OEL Case 8: Delete an existing item removes it cleanly from inventory")
    void deleteExistingItem() {
        Product created = inventoryService.addProductFromInputs(
                "Chips", "50", "Snacks", 1.2, 1.8, null).getData();

        InventoryOperationResult<Product> deleteResult = inventoryService.deleteProduct(created.getId());
        assertTrue(deleteResult.isSuccess());
        assertTrue(inventoryService.findById(created.getId()).isEmpty());
        assertTrue(inventoryService.getAllProducts().isEmpty());
    }

    @Test
    @DisplayName("Manual stock status override respects role permissions and preserves overridden status")
    void manualStatusOverride() {
        Product created = inventoryService.addProductFromInputs(
                "Eggs (12)", "50", "Dairy", 2.0, 2.8, null).getData();
        assertEquals(StockStatus.IN_STOCK, created.getStatus());

        InventoryOperationResult<Product> denied = inventoryService.setStockStatusOverride(
                created.getId(), StockStatus.LOW_STOCK, true, false);
        assertFalse(denied.isSuccess());

        InventoryOperationResult<Product> allowed = inventoryService.setStockStatusOverride(
                created.getId(), StockStatus.LOW_STOCK, true, true);
        assertTrue(allowed.isSuccess());
        assertEquals(StockStatus.LOW_STOCK, inventoryService.findById(created.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Undo and Redo dual stack reverses Add, Update, and Delete cleanly")
    void undoAndRedoHistory() {
        Product created = inventoryService.addProductFromInputs(
                "Cola 330ml", "100", "Drinks", 0.8, 1.2, null).getData();
        assertEquals(1, inventoryService.getAllProducts().size());

        created.setQuantity(3);
        inventoryService.updateProduct(created);
        assertEquals(3, inventoryService.findById(created.getId()).orElseThrow().getQuantity());

        inventoryService.deleteProduct(created.getId());
        assertTrue(inventoryService.getAllProducts().isEmpty());

        assertTrue(inventoryService.undoLastAction().isSuccess()); // Undo Delete
        assertEquals(3, inventoryService.findById(created.getId()).orElseThrow().getQuantity());

        assertTrue(inventoryService.undoLastAction().isSuccess()); // Undo Update
        assertEquals(100, inventoryService.findById(created.getId()).orElseThrow().getQuantity());

        assertTrue(inventoryService.undoLastAction().isSuccess()); // Undo Add
        assertTrue(inventoryService.getAllProducts().isEmpty());

        assertTrue(inventoryService.redoLastAction().isSuccess()); // Redo Add
        assertEquals(100, inventoryService.findById(created.getId()).orElseThrow().getQuantity());
    }
}
