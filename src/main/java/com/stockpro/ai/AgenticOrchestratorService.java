package com.stockpro.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProposalStatus;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.repository.AgentProposalRepository;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.AppSession;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.SupplierOrderService;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Coordinates the three autonomous background store agents ({@code Auto-Buyer}, {@code Nightly Auditor},
 * and {@code Expiry Rescue}) and enforces the Human Approval Safety Switch so zero store records change
 * without explicit user confirmation.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AgenticOrchestratorService {
    private final AgentProposalRepository proposalRepo;
    private final ProductRepository productRepo;
    private final SupplierRepository supplierRepo;
    private final SupplierOrderService supplierOrderService;
    private final OfflineMlEngine mlEngine;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private final Gson gson = new Gson();

    /**
     * Constructs the autonomous worker orchestrator.
     *
     * @param proposalRepo agent proposal repository
     * @param productRepo product repository
     * @param supplierRepo supplier repository
     * @param supplierOrderService supplier &amp; purchase order service
     * @param mlEngine offline ML engine
     * @param auditService tamper-evident audit logger
     * @param eventBus shared UI refresh event bus
     */
    public AgenticOrchestratorService(
            AgentProposalRepository proposalRepo,
            ProductRepository productRepo,
            SupplierRepository supplierRepo,
            SupplierOrderService supplierOrderService,
            OfflineMlEngine mlEngine,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.proposalRepo = proposalRepo;
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.supplierOrderService = supplierOrderService;
        this.mlEngine = mlEngine;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Runs all three autonomous analysis workers without modifying store catalog or order tables.
     *
     * @return list of newly queued {@link AgentProposal} entries awaiting approval
     */
    public List<AgentProposal> runAllAgents() {
        List<AgentProposal> created = new ArrayList<>();
        created.addAll(runAutoBuyerAgent());
        created.addAll(runNightlyAuditorAgent());
        created.addAll(runExpiryRescueAgent());
        if (!created.isEmpty()) {
            eventBus.publish("AGENT_PROPOSALS_QUEUED");
        }
        return created;
    }

    /**
     * AI Auto-Buyer Agent: scans stockout countdowns and low/out-of-stock products, matches the best supplier,
     * and queues draft Purchase Order proposals for human review.
     *
     * @return newly created Auto-Buyer proposals
     */
    public List<AgentProposal> runAutoBuyerAgent() {
        supplierOrderService.ensureSampleSuppliersIfEmpty();
        List<Supplier> suppliers = supplierRepo.findAll();
        if (suppliers.isEmpty()) {
            return List.of();
        }
        List<OfflineMlEngine.ProductForecast> forecasts = mlEngine.analyzeCatalog();
        List<AgentProposal> queued = new ArrayList<>();

        for (OfflineMlEngine.ProductForecast f : forecasts) {
            Product p = f.product();
            if (p.getStatus() == StockStatus.IN_STOCK && f.daysUntilEmpty() > 10.0) {
                continue;
            }
            int orderQty = Math.max(20, f.recommendedReorderQty());
            Supplier matched = matchBestSupplier(p, suppliers);
            String title = String.format("Restock %d units of %s from %s", orderQty, p.getName(), matched.getName());
            if (proposalRepo.existsPendingByTitle(title)) {
                continue;
            }

            double unitCost = p.getBuyPrice() > 0 ? p.getBuyPrice() : Math.max(1.0, p.getSellPrice() * 0.65);
            double estCost = Math.round(orderQty * unitCost * 100.0) / 100.0;

            JsonObject payload = new JsonObject();
            payload.addProperty("supplierId", matched.getId());
            payload.addProperty("productId", p.getId());
            payload.addProperty("productSku", p.getSku());
            payload.addProperty("productName", p.getName());
            payload.addProperty("quantity", orderQty);
            payload.addProperty("unitCost", unitCost);

            AgentProposal proposal = new AgentProposal();
            proposal.setAgentName("AI Auto-Buyer Agent");
            proposal.setProposalType("CREATE_PO");
            proposal.setTitle(title);
            proposal.setReasoning(String.format(
                    "%s currently has %d unit(s) (%s, %.1f days until empty). %s offers %d-day delivery with %.0f%% reliability.",
                    p.getName(), p.getQuantity(), p.getStatus().getDisplayName(),
                    f.daysUntilEmpty(), matched.getName(), matched.getDeliveryDays(), matched.getReliabilityScore()));
            proposal.setFinancialImpact(-estCost);
            proposal.setPayloadJson(gson.toJson(payload));
            proposal.setStatus(ProposalStatus.PENDING);
            queued.add(proposalRepo.insert(proposal));
        }
        return queued;
    }

    /**
     * AI Nightly Auditor Agent: checks missing/zero pricing, duplicate item names, statistical quantity anomalies,
     * and SHA-256 ledger integrity, queuing corrective proposals without modifying records silently.
     *
     * @return newly created Nightly Auditor proposals
     */
    public List<AgentProposal> runNightlyAuditorAgent() {
        List<Product> products = productRepo.findAll(false);
        List<AgentProposal> queued = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();

        for (Product p : products) {
            String key = p.getName() != null ? p.getName().trim().toLowerCase(Locale.ROOT) : "";
            nameCounts.merge(key, 1, Integer::sum);

            if (p.getSellPrice() <= 0.0) {
                String title = "Fix missing sell price on " + p.getName() + " (" + p.getSku() + ")";
                if (!proposalRepo.existsPendingByTitle(title)) {
                    double suggestedSell = p.getBuyPrice() > 0 ? Math.round(p.getBuyPrice() * 1.35 * 100.0) / 100.0 : 5.00;
                    JsonObject payload = new JsonObject();
                    payload.addProperty("productId", p.getId());
                    payload.addProperty("suggestedSellPrice", suggestedSell);

                    AgentProposal prop = new AgentProposal();
                    prop.setAgentName("AI Nightly Auditor Agent");
                    prop.setProposalType("AUDIT_FIX");
                    prop.setTitle(title);
                    prop.setReasoning("Product has $0.00 sell price, risking unbilled revenue. Proposing 35% margin price ("
                            + AppSession.getInstance().formatMoney(suggestedSell) + ").");
                    prop.setFinancialImpact(suggestedSell * Math.max(1, p.getQuantity()));
                    prop.setPayloadJson(gson.toJson(payload));
                    prop.setStatus(ProposalStatus.PENDING);
                    queued.add(proposalRepo.insert(prop));
                }
            }
        }

        for (Map.Entry<String, Integer> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 1 && !entry.getKey().isBlank()) {
                String title = "Review duplicate product name: '" + entry.getKey() + "'";
                if (!proposalRepo.existsPendingByTitle(title)) {
                    AgentProposal prop = new AgentProposal();
                    prop.setAgentName("AI Nightly Auditor Agent");
                    prop.setProposalType("AUDIT_REVIEW");
                    prop.setTitle(title);
                    prop.setReasoning("Detected " + entry.getValue() + " active SKUs sharing the name '" + entry.getKey()
                            + "'. Approve to acknowledge audit alert and merge/archive duplicates manually.");
                    prop.setFinancialImpact(0.0);
                    prop.setPayloadJson("{}");
                    prop.setStatus(ProposalStatus.PENDING);
                    queued.add(proposalRepo.insert(prop));
                }
            }
        }

        for (OfflineMlEngine.AnomalyAlert alert : mlEngine.detectAnomalies()) {
            String title = "Verify statistical outlier: " + (alert.product() != null ? alert.product().getSku() : "Ledger");
            if (!proposalRepo.existsPendingByTitle(title)) {
                AgentProposal prop = new AgentProposal();
                prop.setAgentName("AI Nightly Auditor Agent");
                prop.setProposalType("AUDIT_REVIEW");
                prop.setTitle(title);
                prop.setReasoning(alert.description() + " (Z-score: " + alert.zScore() + ").");
                prop.setFinancialImpact(0.0);
                prop.setPayloadJson("{}");
                prop.setStatus(ProposalStatus.PENDING);
                queued.add(proposalRepo.insert(prop));
            }
        }

        AuditService.SealVerificationResult seal = auditService.verifyIntegrity();
        if (!seal.intact()) {
            String title = "Urgent: Audit hash chain mismatch at entry #" + seal.tamperedEntryId();
            if (!proposalRepo.existsPendingByTitle(title)) {
                AgentProposal prop = new AgentProposal();
                prop.setAgentName("AI Nightly Auditor Agent");
                prop.setProposalType("AUDIT_REVIEW");
                prop.setTitle(title);
                prop.setReasoning(seal.message());
                prop.setFinancialImpact(0.0);
                prop.setPayloadJson("{}");
                prop.setStatus(ProposalStatus.PENDING);
                queued.add(proposalRepo.insert(prop));
            }
        }
        return queued;
    }

    /**
     * AI Expiry Rescue Agent: locates items expiring within 14 days and proposes a 25% clearance discount
     * or promotional bundle to recover value before spoilage.
     *
     * @return newly created Expiry Rescue proposals
     */
    public List<AgentProposal> runExpiryRescueAgent() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(14);
        List<AgentProposal> queued = new ArrayList<>();

        for (Product p : productRepo.findAll(false)) {
            if (p.getQuantity() <= 0 || p.getExpiryDate() == null || p.getExpiryDate().isAfter(horizon)) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, p.getExpiryDate());
            String title = "Apply 25% expiry rescue discount on " + p.getName();
            if (proposalRepo.existsPendingByTitle(title)) {
                continue;
            }
            double currentSell = p.getSellPrice() > 0 ? p.getSellPrice() : 2.0;
            double discounted = Math.round(currentSell * 0.75 * 100.0) / 100.0;
            double recoverable = Math.round(discounted * p.getQuantity() * 100.0) / 100.0;

            JsonObject payload = new JsonObject();
            payload.addProperty("productId", p.getId());
            payload.addProperty("discountedSellPrice", discounted);

            AgentProposal prop = new AgentProposal();
            prop.setAgentName("AI Expiry Rescue Agent");
            prop.setProposalType("EXPIRY_DISCOUNT");
            prop.setTitle(title);
            prop.setReasoning(String.format(
                    "%d unit(s) expire in %d day(s) (%s). Lowering sell price from %s to %s accelerates FEFO clearance and recovers %s.",
                    p.getQuantity(), daysLeft, p.getExpiryDate(),
                    AppSession.getInstance().formatMoney(currentSell),
                    AppSession.getInstance().formatMoney(discounted),
                    AppSession.getInstance().formatMoney(recoverable)));
            prop.setFinancialImpact(recoverable);
            prop.setPayloadJson(gson.toJson(payload));
            prop.setStatus(ProposalStatus.PENDING);
            queued.add(proposalRepo.insert(prop));
        }
        return queued;
    }

    /**
     * Explicitly approves and executes a pending AI proposal inside the database, logging an immutable audit record.
     *
     * @param proposalId target proposal database ID
     * @param actor approving staff display name
     * @param authorized whether the current role may approve automated changes
     * @return execution outcome message
     */
    public InventoryOperationResult<AgentProposal> approveProposal(long proposalId, String actor, boolean authorized) {
        if (!authorized) {
            return InventoryOperationResult.fail("Permission denied: only Owner or Manager may approve AI proposals.");
        }
        Optional<AgentProposal> opt = proposalRepo.findById(proposalId);
        if (opt.isEmpty()) {
            return InventoryOperationResult.fail("Proposal not found.");
        }
        AgentProposal proposal = opt.get();
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            return InventoryOperationResult.fail("Proposal was already " + proposal.getStatus().getDisplayName() + ".");
        }

        String executionSummary = executeProposalPayload(proposal);
        proposalRepo.updateStatus(proposalId, ProposalStatus.APPROVED);
        proposal.setStatus(ProposalStatus.APPROVED);

        auditService.record(
                actor,
                "AGENT_APPROVE",
                proposal.getAgentName(),
                proposal.getTitle() + " -> " + executionSummary
        );
        eventBus.publish("AGENT_PROPOSAL_APPROVED");
        return InventoryOperationResult.ok(proposal, "Approved: " + executionSummary);
    }

    /**
     * Explicitly rejects a pending AI proposal without touching any product, stock, or order records.
     *
     * @param proposalId target proposal database ID
     * @param actor rejecting staff display name
     * @return confirmation result
     */
    public InventoryOperationResult<AgentProposal> rejectProposal(long proposalId, String actor) {
        Optional<AgentProposal> opt = proposalRepo.findById(proposalId);
        if (opt.isEmpty()) {
            return InventoryOperationResult.fail("Proposal not found.");
        }
        AgentProposal proposal = opt.get();
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            return InventoryOperationResult.fail("Proposal was already " + proposal.getStatus().getDisplayName() + ".");
        }
        proposalRepo.updateStatus(proposalId, ProposalStatus.REJECTED);
        proposal.setStatus(ProposalStatus.REJECTED);
        auditService.record(actor, "AGENT_REJECT", proposal.getAgentName(), "Rejected: " + proposal.getTitle());
        eventBus.publish("AGENT_PROPOSAL_REJECTED");
        return InventoryOperationResult.ok(proposal, "Rejected proposal '" + proposal.getTitle() + "' (no database changes made).");
    }

    /**
     * Lists proposals filtered by pending-only or full review history.
     *
     * @param pendingOnly {@code true} to list only awaiting proposals
     * @return list of {@link AgentProposal} rows
     */
    public List<AgentProposal> listProposals(boolean pendingOnly) {
        return pendingOnly ? proposalRepo.findByStatus(ProposalStatus.PENDING) : proposalRepo.findAll();
    }

    /**
     * Applies the approved JSON payload against store repositories.
     *
     * @param proposal approved proposal
     * @return summary of executed store mutation
     */
    private String executeProposalPayload(AgentProposal proposal) {
        JsonObject obj = proposal.getPayloadJson() != null && !proposal.getPayloadJson().isBlank()
                ? JsonParser.parseString(proposal.getPayloadJson()).getAsJsonObject()
                : new JsonObject();

        return switch (proposal.getProposalType()) {
            case "CREATE_PO" -> {
                long supplierId = obj.get("supplierId").getAsLong();
                long productId = obj.get("productId").getAsLong();
                String sku = obj.get("productSku").getAsString();
                String name = obj.get("productName").getAsString();
                int qty = obj.get("quantity").getAsInt();
                double unitCost = obj.get("unitCost").getAsDouble();
                var res = supplierOrderService.createPurchaseOrder(
                        supplierId, List.of(new PurchaseOrderItem(productId, sku, name, qty, unitCost)));
                yield res.isSuccess()
                        ? "Created Purchase Order " + res.getData().getPoNumber()
                        : res.getMessage();
            }
            case "EXPIRY_DISCOUNT" -> {
                long productId = obj.get("productId").getAsLong();
                double newSell = obj.get("discountedSellPrice").getAsDouble();
                Optional<Product> pOpt = productRepo.findById(productId);
                if (pOpt.isPresent()) {
                    Product p = Product.copyOf(pOpt.get());
                    p.setSellPrice(newSell);
                    productRepo.update(p);
                    yield "Discounted " + p.getName() + " sell price to " + AppSession.getInstance().formatMoney(newSell);
                }
                yield "Product no longer present.";
            }
            case "AUDIT_FIX" -> {
                long productId = obj.get("productId").getAsLong();
                double newSell = obj.get("suggestedSellPrice").getAsDouble();
                Optional<Product> pOpt = productRepo.findById(productId);
                if (pOpt.isPresent()) {
                    Product p = Product.copyOf(pOpt.get());
                    p.setSellPrice(newSell);
                    productRepo.update(p);
                    yield "Updated sell price of " + p.getName() + " to " + AppSession.getInstance().formatMoney(newSell);
                }
                yield "Product no longer present.";
            }
            default -> "Audit item reviewed and acknowledged.";
        };
    }

    /**
     * Selects the best supplier whose linked categories match the product, falling back to highest reliability.
     *
     * @param product target product
     * @param suppliers available suppliers
     * @return best matching {@link Supplier}
     */
    private Supplier matchBestSupplier(Product product, List<Supplier> suppliers) {
        String cat = product.getCategory() != null ? product.getCategory().toLowerCase(Locale.ROOT) : "";
        return suppliers.stream()
                .filter(s -> s.getLinkedCategories() != null
                        && !cat.isBlank()
                        && s.getLinkedCategories().toLowerCase(Locale.ROOT).contains(cat))
                .findFirst()
                .orElse(suppliers.get(0));
    }
}
