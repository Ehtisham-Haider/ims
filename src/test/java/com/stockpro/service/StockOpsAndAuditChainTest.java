package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockStatus;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.service.command.CommandHistoryManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies all 6 stock operations, mandatory reason checks, damaged quarantine,
 * FEFO expiry sorting, and SHA-256 tamper detection.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
class StockOpsAndAuditChainTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;
    private StockOperationService stockService;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("stock-ops-test.db"));
        ProductRepository prodRepo = new ProductRepository(dbManager);
        StockMovementRepository moveRepo = new StockMovementRepository(dbManager);
        AuditLogRepository auditRepo = new AuditLogRepository(dbManager);
        auditService = new AuditService(auditRepo);
        inventoryService = new InventoryService(prodRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        stockService = new StockOperationService(prodRepo, moveRepo, auditService, InventoryEventBus.getInstance());
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Executes Restock, Sale, Damaged quarantine, Returned, Lost, and Physical Count safely")
    void executesAllSixStockOperationsWithMandatoryReasons() {
        Product item = inventoryService.addProductFromInputs(
                "Rice 5kg", "50", "Groceries", 2.5, 3.5, null).getData();

        // Mandatory reason enforcement
        InventoryOperationResult<StockMovement> missingReason = stockService.recordOperation(
                item.getId(), MovementType.RESTOCK, "10", "   ", "", "Syed Ahmed");
        assertFalse(missingReason.isSuccess());

        // Restock +20 -> 70
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.RESTOCK, "20", "Supplier delivery", "", "Syed Ahmed").isSuccess());
        assertEquals(70, inventoryService.findById(item.getId()).orElseThrow().getQuantity());

        // Sale -15 -> 55
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.SALE, "15", "POS checkout", "", "Sara").isSuccess());
        assertEquals(55, inventoryService.findById(item.getId()).orElseThrow().getQuantity());

        // Damaged 5 -> sellable 50, quarantined damaged 5
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.DAMAGED, "5", "Torn bag", "", "Syed Ahmed").isSuccess());
        Product afterDmg = inventoryService.findById(item.getId()).orElseThrow();
        assertEquals(50, afterDmg.getQuantity());
        assertEquals(5, afterDmg.getDamagedQty());
        assertEquals(1, stockService.getDamagedGoods().size());

        // Returned +2 -> 52
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.RETURNED, "2", "Customer return", "", "Sara").isSuccess());
        assertEquals(52, inventoryService.findById(item.getId()).orElseThrow().getQuantity());

        // Lost -2 -> 50
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.LOST, "2", "Shrinkage", "", "Syed Ahmed").isSuccess());
        assertEquals(50, inventoryService.findById(item.getId()).orElseThrow().getQuantity());

        // Physical Count 8 -> delta -42 -> Low Stock
        assertTrue(stockService.recordOperation(
                item.getId(), MovementType.PHYSICAL_COUNT, "8", "Shelf audit", "", "Syed Ahmed").isSuccess());
        Product counted = inventoryService.findById(item.getId()).orElseThrow();
        assertEquals(8, counted.getQuantity());
        assertEquals(StockStatus.LOW_STOCK, counted.getStatus());

        assertTrue(auditService.verifyIntegrity().intact());
    }

    @Test
    @DisplayName("FEFO orders soonest-expiring products first and SHA-256 seal detects SQL tampering")
    void fefoOrderingAndSha256TamperDetection() throws Exception {
        Product later = new Product("Eggs (12)", 20, StockStatus.IN_STOCK);
        later.setExpiryDate(LocalDate.now().plusDays(15));
        inventoryService.addProduct(later);

        Product urgent = new Product("Fresh Milk", 12, StockStatus.IN_STOCK);
        urgent.setExpiryDate(LocalDate.now().plusDays(2));
        Product savedUrgent = inventoryService.addProduct(urgent).getData();

        List<Product> fefo = stockService.getFefoExpiringProducts(30);
        assertEquals(2, fefo.size());
        assertEquals("Fresh Milk", fefo.get(0).getName());

        stockService.recordOperation(savedUrgent.getId(), MovementType.SALE, "2", "Morning sale", "", "Sara");
        stockService.recordOperation(savedUrgent.getId(), MovementType.RESTOCK, "5", "Midday restock", "", "Ahmed");
        assertTrue(auditService.verifyIntegrity().intact());

        // Simulate direct unauthorized tampering of audit_logs row #1
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE audit_logs SET details = 'Tampered amount 999' WHERE id = 1");
        }

        AuditService.SealVerificationResult tampered = auditService.verifyIntegrity();
        assertFalse(tampered.intact());
        assertEquals(1L, tampered.tamperedEntryId());
    }
}
