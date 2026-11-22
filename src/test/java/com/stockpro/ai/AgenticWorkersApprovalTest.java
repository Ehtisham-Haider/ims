package com.stockpro.ai;

import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProposalStatus;
import com.stockpro.domain.StockStatus;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AgentProposalRepository;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.InventoryService;
import com.stockpro.service.StockOperationService;
import com.stockpro.service.SupplierOrderService;
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
 * Verifies Auto-Buyer, Nightly Auditor, and Expiry Rescue background workers plus the
 * Human Approval Safety Inbox workflow ensuring zero silent database mutations.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
class AgenticWorkersApprovalTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private InventoryService inventoryService;
    private SupplierOrderService supplierService;
    private AgenticOrchestratorService orchestrator;

    @BeforeEach
    void setUp() {
        dbManager = DatabaseManager.createForFile(tempDir.resolve("agentic-inbox-test.db"));
        ProductRepository prodRepo = new ProductRepository(dbManager);
        SupplierRepository supRepo = new SupplierRepository(dbManager);
        PurchaseOrderRepository poRepo = new PurchaseOrderRepository(dbManager);
        AuditService auditService = new AuditService(new AuditLogRepository(dbManager));
        inventoryService = new InventoryService(prodRepo, new CommandHistoryManager(), InventoryEventBus.getInstance());
        StockOperationService stockService = new StockOperationService(
                prodRepo, new StockMovementRepository(dbManager), auditService, InventoryEventBus.getInstance());
        supplierService = new SupplierOrderService(
                supRepo, poRepo, stockService, auditService, InventoryEventBus.getInstance());
        OfflineMlEngine mlEngine = new OfflineMlEngine(dbManager, prodRepo);
        orchestrator = new AgenticOrchestratorService(
                new AgentProposalRepository(dbManager),
                prodRepo,
                supRepo,
                supplierService,
                mlEngine,
                auditService,
                InventoryEventBus.getInstance()
        );
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    @DisplayName("All 3 agents generate proposals without mutating DB until human Approve is clicked")
    void agentsQueueProposalsWithHumanSafetyGate() {
        // Low stock item -> Auto-Buyer trigger
        inventoryService.addProductFromInputs("Wireless Mouse", "3", "Electronics", 10.0, 20.0, null);
        // Zero sell price item -> Nightly Auditor trigger
        inventoryService.addProductFromInputs("Unpriced Cable", "15", "Accessories", 2.0, 0.0, null);
        // Expiring item -> Expiry Rescue trigger
        Product yogurt = new Product("Greek Yogurt", 10, StockStatus.IN_STOCK);
        yogurt.setCategory("Dairy");
        yogurt.setBuyPrice(1.50);
        yogurt.setSellPrice(4.00);
        yogurt.setExpiryDate(LocalDate.now().plusDays(4));
        Product savedYogurt = inventoryService.addProduct(yogurt).getData();

        List<AgentProposal> created = orchestrator.runAllAgents();
        assertTrue(created.size() >= 3);
        assertTrue(created.stream().anyMatch(p -> "AI Auto-Buyer Agent".equals(p.getAgentName())));
        assertTrue(created.stream().anyMatch(p -> "AI Nightly Auditor Agent".equals(p.getAgentName())));
        assertTrue(created.stream().anyMatch(p -> "AI Expiry Rescue Agent".equals(p.getAgentName())));

        // Verify ZERO silent writes occurred prior to human approval
        assertEquals(0, supplierService.getAllOrders().size());
        assertEquals(4.00, inventoryService.findById(savedYogurt.getId()).orElseThrow().getSellPrice(), 0.01);

        // Approve Auto-Buyer proposal -> creates PO
        AgentProposal poProposal = created.stream()
                .filter(p -> "CREATE_PO".equals(p.getProposalType()))
                .findFirst().orElseThrow();
        InventoryOperationResult<AgentProposal> approvedPo = orchestrator.approveProposal(
                poProposal.getId(), "Syed Ehtisham Haider Bukhari", true);
        assertTrue(approvedPo.isSuccess());
        assertEquals(ProposalStatus.APPROVED, approvedPo.getData().getStatus());
        assertEquals(1, supplierService.getAllOrders().size());

        // Reject Expiry Rescue proposal -> price remains untouched
        AgentProposal expiryProp = created.stream()
                .filter(p -> "EXPIRY_DISCOUNT".equals(p.getProposalType()))
                .findFirst().orElseThrow();
        InventoryOperationResult<AgentProposal> rejected = orchestrator.rejectProposal(
                expiryProp.getId(), "Syed Ehtisham Haider Bukhari");
        assertTrue(rejected.isSuccess());
        assertEquals(ProposalStatus.REJECTED, rejected.getData().getStatus());
        assertEquals(4.00, inventoryService.findById(savedYogurt.getId()).orElseThrow().getSellPrice(), 0.01);
    }
}
