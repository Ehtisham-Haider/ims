package com.stockpro.service;

import com.stockpro.domain.BlindReceivingReport;
import com.stockpro.domain.BranchStockRecord;
import com.stockpro.domain.CustomerBackorder;
import com.stockpro.domain.CustomerReturnRma;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.StockTransfer;
import com.stockpro.domain.StoreBranch;
import com.stockpro.domain.Supplier;
import com.stockpro.domain.ThreeWayMatchResult;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies multi-branch inventory, valuation-preserving inter-branch transfers, ATP reservation checks,
 * blind receiving, 3-way supplier invoice matching, customer RMA dispositions, and backorder restock notifications.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2MultiBranchTransfersRmaTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private ProductRepository productRepo;
    private SupplierRepository supplierRepo;
    private PurchaseOrderRepository poRepo;
    private AuditService auditService;
    private MultiBranchTransferRmaService branchService;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("v2-branch02.db"));
        productRepo = new ProductRepository(dbManager);
        supplierRepo = new SupplierRepository(dbManager);
        poRepo = new PurchaseOrderRepository(dbManager);
        StockMovementRepository moveRepo = new StockMovementRepository(dbManager);
        auditService = new AuditService(new AuditLogRepository(dbManager));
        branchService = new MultiBranchTransferRmaService(
                dbManager, productRepo, poRepo, moveRepo, auditService, InventoryEventBus.getInstance());
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("Tracks multiple branches, ATP stock reservations, and valuation-safe stock transfers")
    void multiBranchAtpAndInTransitTransfers() {
        List<StoreBranch> branches = branchService.listBranches();
        assertTrue(branches.size() >= 2);
        long mainId = branches.get(0).id();
        long northId = branches.get(1).id();

        Product prod = new Product("USB-C Hub", 20, StockStatus.IN_STOCK);
        prod.setSku("PRD-5001");
        prod.setBuyPrice(10.0);
        prod.setSellPrice(22.0);
        prod = productRepo.insert(prod);

        assertEquals(200.0, branchService.computeCompanyValuationIncludingInTransit(), 0.01);

        InventoryOperationResult<BranchStockRecord> resv = branchService.reserveStock(
                mainId, prod.getId(), 5, "Pending wholesale order", "admin");
        assertTrue(resv.isSuccess());
        assertEquals(20, resv.getData().onHandQty());
        assertEquals(5, resv.getData().reservedQty());
        assertEquals(15, resv.getData().availableToPromise());

        // Block overselling past ATP
        InventoryOperationResult<BranchStockRecord> overResv = branchService.reserveStock(
                mainId, prod.getId(), 16, "Too large", "admin");
        assertFalse(overResv.isSuccess());
        assertTrue(overResv.getMessage().contains("ATP"));

        // Transfer 6 units Main -> North and assert company valuation stays $200 through transit
        InventoryOperationResult<StockTransfer> trCreate = branchService.createTransfer(
                mainId, northId, prod.getId(), 6, "Rebalance North Hub", "admin");
        assertTrue(trCreate.isSuccess());
        assertEquals("REQUESTED", trCreate.getData().status());
        assertEquals(200.0, branchService.computeCompanyValuationIncludingInTransit(), 0.01);

        InventoryOperationResult<StockTransfer> trDisp = branchService.dispatchTransfer(trCreate.getData().id(), "admin");
        assertTrue(trDisp.isSuccess());
        assertEquals("IN_TRANSIT", trDisp.getData().status());
        assertEquals(200.0, branchService.computeCompanyValuationIncludingInTransit(), 0.01);

        InventoryOperationResult<StockTransfer> trRecv = branchService.receiveTransfer(trCreate.getData().id(), "manager");
        assertTrue(trRecv.isSuccess());
        assertEquals("RECEIVED", trRecv.getData().status());
        assertEquals(14, branchService.getBranchStock(mainId, prod.getId()).orElseThrow().onHandQty());
        assertEquals(6, branchService.getBranchStock(northId, prod.getId()).orElseThrow().onHandQty());
        assertEquals(200.0, branchService.computeCompanyValuationIncludingInTransit(), 0.01);
    }

    @Test
    @DisplayName("Flags short shipments during blind dock receiving and catches billing discrepancies in 3-Way Match")
    void blindReceivingAndThreeWayMatch() {
        Supplier sup = new Supplier();
        sup.setName("Pak Distributors");
        sup = supplierRepo.insert(sup);

        Product rice = new Product("Basmati Rice", 10, StockStatus.IN_STOCK);
        rice.setSku("PRD-5100");
        rice.setBuyPrice(5.0);
        rice.setSellPrice(7.5);
        rice = productRepo.insert(rice);

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNumber("PO-5100");
        po.setSupplierId(sup.getId());
        po.setSupplierName(sup.getName());
        po.getItems().add(new PurchaseOrderItem(rice.getId(), rice.getSku(), rice.getName(), 20, 5.0));
        po.recalculateTotal();
        po = poRepo.insert(po);

        InventoryOperationResult<BlindReceivingReport> shortDock = branchService.recordBlindDelivery(
                po.getId(), Map.of("PRD-5100", 17), "DockChecker");
        assertTrue(shortDock.isSuccess());
        assertFalse(shortDock.getData().matchedExactly());
        assertEquals(-3, shortDock.getData().varianceBySku().get("PRD-5100"));

        InventoryOperationResult<ThreeWayMatchResult> mismatch = branchService.performThreeWayMatch(
                po.getId(), "INV-881", 100.0, Map.of("PRD-5100", 17), "Accountant");
        assertTrue(mismatch.isSuccess());
        assertEquals("DISCREPANCY_FLAGGED", mismatch.getData().matchStatus());
        assertEquals(85.0, mismatch.getData().grnAcceptedValue(), 0.01);

        InventoryOperationResult<ThreeWayMatchResult> cleanMatch = branchService.performThreeWayMatch(
                po.getId(), "INV-882", 100.0, Map.of("PRD-5100", 20), "Accountant");
        assertTrue(cleanMatch.isSuccess());
        assertEquals("MATCHED", cleanMatch.getData().matchStatus());
    }

    @Test
    @DisplayName("Processes customer RMA dispositions into audit trail and notifies waiting backorders on restock")
    void rmaDispositionsAndBackorderNotifications() {
        Product phone = new Product("Smartphone Case", 0, StockStatus.OUT_OF_STOCK);
        phone.setSku("PRD-5200");
        phone.setBuyPrice(4.0);
        phone.setSellPrice(12.0);
        phone = productRepo.insert(phone);

        InventoryOperationResult<CustomerBackorder> bo = branchService.recordBackorder(
                phone.getId(), "Ali Raza", "+92 300 0001122", 2);
        assertTrue(bo.isSuccess());
        assertFalse(bo.getData().notified());

        InventoryOperationResult<CustomerReturnRma> dmgRma = branchService.processReturnRma(
                phone.getId(), 1L, 1, "MARK_DAMAGED", "Cracked corner", "admin");
        assertTrue(dmgRma.isSuccess());
        assertEquals(1, productRepo.findById(phone.getId()).orElseThrow().getDamagedQty());
        assertEquals(0, productRepo.findById(phone.getId()).orElseThrow().getQuantity());

        InventoryOperationResult<CustomerReturnRma> restockRma = branchService.processReturnRma(
                phone.getId(), 1L, 3, "RESTOCK_SELLABLE", "Unopened gift return", "admin");
        assertTrue(restockRma.isSuccess());
        assertEquals(3, productRepo.findById(phone.getId()).orElseThrow().getQuantity());

        List<CustomerBackorder> updatedWaitlist = branchService.listBackorders();
        assertEquals("READY_NOTIFY", updatedWaitlist.get(0).status());
        assertTrue(updatedWaitlist.get(0).notified());
        assertTrue(auditService.verifyIntegrity().intact());
    }
}
