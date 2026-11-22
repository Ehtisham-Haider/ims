package com.stockpro.service;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.Supplier;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
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
 * Verifies supplier CRUD, multi-item purchase order lifecycle and PDF generation,
 * store valuation, dead-money detection, and tax calculations.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class SuppliersOrdersFinanceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;
    private StockOperationService stockService;
    private SupplierOrderService supplierService;
    private FinanceService financeService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("suppliers-finance-test.db"));
        ProductRepository prodRepo = new ProductRepository(dbManager);
        StockMovementRepository moveRepo = new StockMovementRepository(dbManager);
        AuditService auditService = new AuditService(new AuditLogRepository(dbManager));
        inventoryService = new InventoryService(prodRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        stockService = new StockOperationService(prodRepo, moveRepo, auditService, InventoryEventBus.getInstance());
        supplierService = new SupplierOrderService(
                new SupplierRepository(dbManager),
                new PurchaseOrderRepository(dbManager),
                stockService,
                auditService,
                InventoryEventBus.getInstance()
        );
        financeService = new FinanceService(dbManager, prodRepo);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Creates supplier, multi-item PO, delivers PO to auto-restock, and exports PDF")
    void purchaseOrderLifecycleAndPdfExport() throws Exception {
        Supplier sup = new Supplier();
        sup.setName("TechGlobal Ltd");
        sup.setPhone("+92 300 1234567");
        sup.setEmail("orders@techglobal.example");
        sup.setDeliveryDays(2);
        Supplier savedSup = supplierService.saveSupplier(sup).getData();
        assertTrue(savedSup.getId() > 0);

        Product mouse = inventoryService.addProductFromInputs(
                "Wireless Mouse", "10", "Electronics", 12.0, 20.0, null).getData();

        PurchaseOrderItem line = new PurchaseOrderItem(mouse.getId(), mouse.getSku(), mouse.getName(), 40, 12.0);
        PurchaseOrder po = supplierService.createPurchaseOrder(savedSup.getId(), List.of(line)).getData();
        assertEquals("PO-1001", po.getPoNumber());
        assertEquals(480.0, po.getTotalCost(), 0.01);
        assertEquals("Draft", po.getStatus());

        assertTrue(supplierService.updateOrderStatus(po.getId(), "Sent", "Syed Ehtisham Haider Bukhari").isSuccess());
        assertTrue(supplierService.updateOrderStatus(po.getId(), "Delivered", "Syed Ehtisham Haider Bukhari").isSuccess());

        // Delivery should have restocked +40 units (10 -> 50)
        assertEquals(50, inventoryService.findById(mouse.getId()).orElseThrow().getQuantity());

        Path pdfOut = tempDir.resolve("PO-1001.pdf");
        supplierService.exportPurchaseOrderPdf(po, pdfOut);
        assertTrue(Files.exists(pdfOut));
        assertTrue(Files.size(pdfOut) > 500);
    }

    @Test
    @DisplayName("Computes total valuation, profit, tax/GST, and identifies stagnant dead-money items")
    void financeEngineAndDeadMoneyFinder() {
        Product fastMover = inventoryService.addProductFromInputs(
                "USB-C Cable", "20", "Accessories", 2.0, 5.0, null).getData();
        Product stagnant = inventoryService.addProductFromInputs(
                "Legacy Adapter", "15", "Accessories", 10.0, 15.0, null).getData();

        stockService.recordOperation(fastMover.getId(), MovementType.SALE, "5", "Customer checkout", "", "Sara");

        FinanceService.FinanceSummary summary = financeService.computeSummary(60, 15.0);
        // fastMover: 15 * 2 = 30 cost, 15 * 5 = 75 retail; stagnant: 15 * 10 = 150 cost, 15 * 15 = 225 retail
        assertEquals(180.0, summary.totalCostValue(), 0.01);
        assertEquals(300.0, summary.totalRetailValue(), 0.01);
        assertEquals(120.0, summary.totalPotentialProfit(), 0.01);
        assertEquals(45.0, summary.estimatedTaxAmount(), 0.01);
        assertEquals(345.0, summary.retailValueWithTax(), 0.01);
        assertEquals(1, summary.deadMoneyItems().size());
        assertEquals(stagnant.getSku(), summary.deadMoneyItems().get(0).getSku());
    }
}
