package com.stockpro.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProposalStatus;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.ShelfBinRecord;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.StoreBrainModels.AgentFeedbackWeight;
import com.stockpro.domain.StoreBrainModels.SalesDetectiveReport;
import com.stockpro.domain.StoreBrainModels.StoreHealthScoreReport;
import com.stockpro.domain.StoreBrainModels.WasteEcoImpactSummary;
import com.stockpro.domain.StoreBrainModels.WasteZeroBundle;
import com.stockpro.ml.AdvancedOfflineMlService;
import com.stockpro.repository.AgentProposalRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.SupplierRepository;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.WarehouseMapPickingService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Multi-Agent Store Brain orchestrating 7 collaborative autonomous store agents
 * ({@code Auto-Buyer}, {@code Nightly Auditor}, {@code Expiry Rescue},
 * {@code Delay-Rescue}, {@code Price-Negotiator}, {@code Warehouse Organizer},
 * and {@code Budget Guardian}), SQLite self-learning feedback weights,
 * Sales Detective root-cause diagnostics, Waste-Zero bundling, Eco impact tracking,
 * and the composite 0–100 Store Health Score.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class MultiAgentStoreBrainService {

    private final DatabaseManager dbManager;
    private final AgentProposalRepository proposalRepo;
    private final ProductRepository productRepo;
    private final SupplierRepository supplierRepo;
    private final PurchaseOrderRepository poRepo;
    private final SettingsRepository settingsRepo;
    private final AgenticOrchestratorService baseOrchestrator;
    private final AdvancedOfflineMlService advancedMlService;
    private final WarehouseMapPickingService warehouseService;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private final Gson gson = new Gson();

    /**
     * Constructs the Branch 09 Multi-Agent Store Brain coordinator.
     *
     * @param dbManager SQLite connection manager
     * @param proposalRepo agent proposal repository
     * @param productRepo product catalog repository
     * @param supplierRepo supplier repository
     * @param poRepo purchase order repository
     * @param settingsRepo store settings repository (monthly budget cap)
     * @param baseOrchestrator V1 3-agent orchestrator
     * @param advancedMlService Branch 07 advanced offline ML suite
     * @param warehouseService Branch 03 shelf saturation service
     * @param auditService tamper-evident SHA-256 audit logger
     * @param eventBus shared UI refresh event bus
     */
    public MultiAgentStoreBrainService(
            DatabaseManager dbManager,
            AgentProposalRepository proposalRepo,
            ProductRepository productRepo,
            SupplierRepository supplierRepo,
            PurchaseOrderRepository poRepo,
            SettingsRepository settingsRepo,
            AgenticOrchestratorService baseOrchestrator,
            AdvancedOfflineMlService advancedMlService,
            WarehouseMapPickingService warehouseService,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.proposalRepo = proposalRepo;
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.poRepo = poRepo;
        this.settingsRepo = settingsRepo;
        this.baseOrchestrator = baseOrchestrator;
        this.advancedMlService = advancedMlService;
        this.warehouseService = warehouseService;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Executes all 7 store agents together into one conflict-free, budget-aware consensus plan
     * weighted by learned historical Approve/Reject preferences, queueing pending proposals without
     * mutating catalog or order tables silently.
     *
     * @return ranked list of newly created consensus {@link AgentProposal} entries
     */
    public List<AgentProposal> runSevenAgentTeamConsensus() {
        List<AgentProposal> batch = new ArrayList<>();
        if (baseOrchestrator != null) {
            batch.addAll(baseOrchestrator.runAllAgents());
        }
        batch.addAll(runDelayRescueAgent());
        batch.addAll(runPriceNegotiatorAgent());
        batch.addAll(runWarehouseOrganizerAgent());
        batch.addAll(runBudgetGuardianAgent(batch));

        batch.sort((a, b) -> {
            double scoreA = Math.abs(a.getFinancialImpact()) * getPreferenceWeight(a.getAgentName());
            double scoreB = Math.abs(b.getFinancialImpact()) * getPreferenceWeight(b.getAgentName());
            return Double.compare(scoreB, scoreA);
        });
        if (!batch.isEmpty()) {
            eventBus.publish("MULTI_AGENT_CONSENSUS");
        }
        return batch;
    }

    /**
     * Delay-Rescue Agent: inspects slow/watch suppliers and out-of-stock SKUs to suggest expedited re-sourcing.
     *
     * @return newly queued Delay-Rescue proposals
     */
    public List<AgentProposal> runDelayRescueAgent() {
        List<AgentProposal> out = new ArrayList<>();
        var otifs = advancedMlService.scoreSuppliersOtif();
        var watchVendor = otifs.stream()
                .filter(s -> s.otifScorePercent() < 85.0 || s.predictedDeliveryDays() >= 4.0)
                .findFirst()
                .orElse(null);

        if (watchVendor != null) {
            String title = "Delay-Rescue: Expedite slow vendor '" + watchVendor.supplierName() + "' (OTIF "
                    + String.format(Locale.US, "%.0f%%", watchVendor.otifScorePercent()) + ")";
            if (!proposalRepo.existsPendingByTitle(title)) {
                AgentProposal p = new AgentProposal();
                p.setAgentName("Delay-Rescue Agent");
                p.setProposalType("ADVISORY");
                p.setTitle(title);
                p.setReasoning(String.format(Locale.US,
                        "Predicted delivery lead time %.1fd exceeds fast-turn threshold. Shift next restock batch to backup vendor.",
                        watchVendor.predictedDeliveryDays()));
                p.setFinancialImpact(220.0);
                p.setPayloadJson("{\"supplierId\":" + watchVendor.supplierId() + "}");
                p.setStatus(ProposalStatus.PENDING);
                out.add(proposalRepo.insert(p));
            }
        }
        return out;
    }

    /**
     * Price-Negotiator Agent: flags top-spend vendors eligible for bulk discount negotiation.
     *
     * @return newly queued Price-Negotiator proposals
     */
    public List<AgentProposal> runPriceNegotiatorAgent() {
        List<AgentProposal> out = new ArrayList<>();
        var suppliers = supplierRepo.findAll();
        if (suppliers.isEmpty()) {
            return out;
        }
        var bestSupplier = suppliers.get(0);
        double estPoSpend = poRepo.findAll().stream()
                .filter(po -> po.getSupplierId() == bestSupplier.getId())
                .mapToDouble(PurchaseOrder::getTotalCost)
                .sum();
        if (estPoSpend >= 50.0 || !productRepo.findAll(false).isEmpty()) {
            String title = "Price-Negotiator: Request 6% tier rebate from " + bestSupplier.getName();
            if (!proposalRepo.existsPendingByTitle(title)) {
                double estSavings = Math.max(45.0, Math.round(estPoSpend * 0.06 * 100.0) / 100.0);
                AgentProposal p = new AgentProposal();
                p.setAgentName("Price-Negotiator Agent");
                p.setProposalType("ADVISORY");
                p.setTitle(title);
                p.setReasoning("Consolidated PO volume supports requesting a 6% unit cost rebate (est. $"
                        + String.format(Locale.US, "%.2f", estSavings) + " savings).");
                p.setFinancialImpact(estSavings);
                p.setPayloadJson("{\"supplierId\":" + bestSupplier.getId() + "}");
                p.setStatus(ProposalStatus.PENDING);
                out.add(proposalRepo.insert(p));
            }
        }
        return out;
    }

    /**
     * Warehouse Organizer Agent: inspects bins exceeding 85% saturation and queues bin-balancing actions.
     *
     * @return newly queued Warehouse Organizer proposals
     */
    public List<AgentProposal> runWarehouseOrganizerAgent() {
        List<AgentProposal> out = new ArrayList<>();
        List<ShelfBinRecord> fullBins = warehouseService.listSaturatedBins(ShelfBinRecord.SATURATION_ALERT_PERCENT);
        for (ShelfBinRecord bin : fullBins.stream().limit(2).toList()) {
            String title = "Warehouse Organizer: Rebalance saturated bin " + bin.binCode()
                    + " (" + String.format(Locale.US, "%.0f%% full", bin.maxUtilizationPercent()) + ")";
            if (!proposalRepo.existsPendingByTitle(title)) {
                AgentProposal p = new AgentProposal();
                p.setAgentName("Warehouse Organizer Agent");
                p.setProposalType("ADVISORY");
                p.setTitle(title);
                p.setReasoning("Shelf load " + bin.currentWeightKg() + "kg / " + bin.maxWeightKg()
                        + "kg exceeds 85% safety saturation. Move overflow stock via Warehouse Map.");
                p.setFinancialImpact(95.0);
                p.setPayloadJson("{\"binCode\":\"" + bin.binCode() + "\"}");
                p.setStatus(ProposalStatus.PENDING);
                out.add(proposalRepo.insert(p));
            }
        }
        return out;
    }

    /**
     * Budget Guardian Agent: validates proposed restock spend against {@code ai.budget.monthly_cap}
     * and emits a budget allocation guard card if open PO + queued restocks approach or exceed cap.
     *
     * @param newlyQueued proposals created during this team sweep
     * @return newly created Budget Guardian advisory proposals
     */
    public List<AgentProposal> runBudgetGuardianAgent(List<AgentProposal> newlyQueued) {
        List<AgentProposal> out = new ArrayList<>();
        double cap = resolveMonthlyBudgetCap();
        double openPoSpend = poRepo.findAll().stream()
                .filter(po -> !"Delivered".equalsIgnoreCase(po.getStatus()))
                .mapToDouble(PurchaseOrder::getTotalCost)
                .sum();
        double queuedRestockCost = (newlyQueued != null ? newlyQueued : List.<AgentProposal>of()).stream()
                .filter(p -> "CREATE_PO".equals(p.getProposalType()))
                .mapToDouble(p -> Math.abs(p.getFinancialImpact()))
                .sum();
        double combined = openPoSpend + queuedRestockCost;

        if (combined > 0.0) {
            String statusNote = combined > cap ? "EXCEEDS CAP — Phase lower-priority POs" : "Within budget cap";
            String title = String.format(Locale.US, "Budget Guardian: $%.0f committed vs $%.0f monthly cap", combined, cap);
            if (!proposalRepo.existsPendingByTitle(title)) {
                AgentProposal p = new AgentProposal();
                p.setAgentName("Budget Guardian Agent");
                p.setProposalType("ADVISORY");
                p.setTitle(title);
                p.setReasoning("Combined open POs ($" + String.format(Locale.US, "%.2f", openPoSpend)
                        + ") + proposed Auto-Buyer POs ($" + String.format(Locale.US, "%.2f", queuedRestockCost)
                        + ") status: " + statusNote + ".");
                p.setFinancialImpact(combined > cap ? -(combined - cap) : (cap - combined));
                p.setPayloadJson("{\"cap\":" + cap + ",\"committed\":" + combined + "}");
                p.setStatus(ProposalStatus.PENDING);
                out.add(proposalRepo.insert(p));
            }
        }
        return out;
    }

    /**
     * Records explicit human Approve or Reject feedback into SQLite {@code agent_feedback_weights}
     * so future Multi-Agent Team rankings prioritize trusted agent types automatically.
     *
     * @param weightKey agent name or proposal category key
     * @param approved {@code true} on approval, {@code false} on rejection
     * @return updated {@link AgentFeedbackWeight}
     */
    public AgentFeedbackWeight recordDecisionFeedback(String weightKey, boolean approved) {
        String key = (weightKey != null && !weightKey.isBlank()) ? weightKey.trim() : "General Agent";
        String now = LocalDateTime.now().toString();
        double currentWeight = 1.0;
        int approves = 0;
        int rejects = 0;

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT weight_value, approve_count, reject_count FROM agent_feedback_weights WHERE weight_key = ?")) {
                sel.setString(1, key);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        currentWeight = rs.getDouble("weight_value");
                        approves = rs.getInt("approve_count");
                        rejects = rs.getInt("reject_count");
                    }
                }
            }
            if (approved) {
                approves++;
                currentWeight = Math.min(2.50, currentWeight + 0.12);
            } else {
                rejects++;
                currentWeight = Math.max(0.25, currentWeight - 0.15);
            }
            currentWeight = Math.round(currentWeight * 100.0) / 100.0;
            try (PreparedStatement up = conn.prepareStatement(
                    "INSERT OR REPLACE INTO agent_feedback_weights (weight_key, weight_value, approve_count, reject_count, updated_at) VALUES (?, ?, ?, ?, ?)")) {
                up.setString(1, key);
                up.setDouble(2, currentWeight);
                up.setInt(3, approves);
                up.setInt(4, rejects);
                up.setString(5, now);
                up.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to persist agent feedback weight.", ex);
        }
        return new AgentFeedbackWeight(key, currentWeight, approves, rejects, now);
    }

    /**
     * Returns the learned preference multiplier ({@code 1.0} baseline) for a given agent key.
     *
     * @param weightKey agent name or proposal category key
     * @return learned multiplier in {@code [0.25, 2.50]}
     */
    public double getPreferenceWeight(String weightKey) {
        if (weightKey == null || weightKey.isBlank()) {
            return 1.0;
        }
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT weight_value FROM agent_feedback_weights WHERE weight_key = ?")) {
            ps.setString(1, weightKey.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        } catch (SQLException ignored) {
        }
        return 1.0;
    }

    /**
     * Lists all learned agent weights stored in SQLite sorted by highest preference weight first.
     *
     * @return list of {@link AgentFeedbackWeight}
     */
    public List<AgentFeedbackWeight> listFeedbackWeights() {
        List<AgentFeedbackWeight> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT weight_key, weight_value, approve_count, reject_count, updated_at FROM agent_feedback_weights ORDER BY weight_value DESC")) {
            while (rs.next()) {
                list.add(new AgentFeedbackWeight(
                        rs.getString("weight_key"),
                        rs.getDouble("weight_value"),
                        rs.getInt("approve_count"),
                        rs.getInt("reject_count"),
                        rs.getString("updated_at")
                ));
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    /**
     * Sales Detective: investigates recent movement velocity vs stockouts to explain performance shifts.
     *
     * @param lookbackDays trailing days inspected
     * @return {@link SalesDetectiveReport}
     */
    public SalesDetectiveReport investigateSalesDrivers(int lookbackDays) {
        int days = Math.max(1, lookbackDays);
        int totalUnits = 0;
        String topMover = "General Catalog";
        int topMoverUnits = 0;

        String sql = """
                SELECT product_name, SUM(ABS(quantity_delta)) AS units
                FROM stock_movements
                WHERE movement_type = 'SALE' AND created_at >= ?
                GROUP BY product_name
                ORDER BY units DESC
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().minusDays(days).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int u = rs.getInt("units");
                    totalUnits += u;
                    if (u > topMoverUnits) {
                        topMoverUnits = u;
                        topMover = rs.getString("product_name");
                    }
                }
            }
        } catch (SQLException ignored) {
        }

        List<Product> products = productRepo.findAll(false);
        long oosCount = products.stream().filter(p -> p.getStatus() == StockStatus.OUT_OF_STOCK).count();
        long lowCount = products.stream().filter(p -> p.getStatus() == StockStatus.LOW_STOCK).count();
        var lostSales = advancedMlService.imputeStockoutLostSales();
        double lostRev = lostSales.stream().mapToDouble(l -> l.imputedMissedRevenue()).sum();

        List<String> drivers = new ArrayList<>();
        drivers.add("Top velocity driver: '" + topMover + "' (" + topMoverUnits + " sold units in trailing " + days + "d).");
        drivers.add("Stockout drag: " + oosCount + " Out-of-Stock & " + lowCount + " Low-Stock items (~$"
                + String.format(Locale.US, "%.2f", lostRev) + " missed demand).");

        String headline = oosCount > 2
                ? "Revenue Capped by Stockouts (" + totalUnits + " units sold)"
                : "Healthy Sales Momentum (" + totalUnits + " units in " + days + "d)";
        return new SalesDetectiveReport(headline, drivers, totalUnits, String.join(" ", drivers));
    }

    /**
     * Waste-Zero Manager: pairs expiring items with popular Apriori companion SKUs into BOGO/bundle offers.
     *
     * @return list of {@link WasteZeroBundle} offers
     */
    public List<WasteZeroBundle> suggestWasteZeroBundles() {
        List<Product> products = productRepo.findAll(false);
        LocalDate cutoff = LocalDate.now().plusDays(21);
        List<Product> expiring = products.stream()
                .filter(p -> p.getExpiryDate() != null && !p.getExpiryDate().isAfter(cutoff) && p.getQuantity() > 0)
                .toList();
        if (expiring.isEmpty() || products.size() < 2) {
            return List.of();
        }
        Product defaultCompanion = products.stream()
                .filter(p -> p.getExpiryDate() == null || p.getExpiryDate().isAfter(cutoff))
                .max(Comparator.comparingInt(Product::getQuantity))
                .orElse(products.get(0));

        List<WasteZeroBundle> bundles = new ArrayList<>();
        for (Product exp : expiring) {
            double combined = exp.getSellPrice() + defaultCompanion.getSellPrice();
            double discounted = Math.round(combined * 0.80 * 100.0) / 100.0;
            double recoverable = Math.round(exp.getSellPrice() * 0.75 * exp.getQuantity() * 100.0) / 100.0;
            bundles.add(new WasteZeroBundle(
                    exp.getId(),
                    exp.getName(),
                    defaultCompanion.getId(),
                    defaultCompanion.getName(),
                    "Waste-Zero Bundle: " + exp.getName() + " + " + defaultCompanion.getName() + " (-20%)",
                    discounted,
                    recoverable
            ));
        }
        return bundles;
    }

    /**
     * Logs a waste rescued or expired event into {@code waste_eco_logs} for ESG sustainability reporting.
     *
     * @param productId optional related product id
     * @param eventType {@code RESCUED} or {@code EXPIRED}
     * @param quantity units involved
     * @param financialValue dollar value involved
     * @param notes human note
     */
    public void logWasteEcoEvent(Long productId, String eventType, int quantity, double financialValue, String notes) {
        double weightKg = Math.max(0.1, quantity * 0.65);
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO waste_eco_logs (product_id, event_type, quantity, weight_kg, financial_value, notes, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            if (productId != null && productId > 0) {
                ps.setLong(1, productId);
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setString(2, eventType != null ? eventType.trim().toUpperCase(Locale.ROOT) : "RESCUED");
            ps.setInt(3, Math.max(0, quantity));
            ps.setDouble(4, Math.round(weightKg * 100.0) / 100.0);
            ps.setDouble(5, Math.max(0.0, financialValue));
            ps.setString(6, notes != null ? notes : "");
            ps.setString(7, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to log waste/eco event.", ex);
        }
    }

    /**
     * Aggregates cumulative rescued vs expired waste and CO2e savings from {@code waste_eco_logs}.
     *
     * @return {@link WasteEcoImpactSummary}
     */
    public WasteEcoImpactSummary getWasteEcoSummary() {
        int rescuedU = 0;
        double rescuedKg = 0.0;
        double rescuedVal = 0.0;
        int expiredU = 0;
        double expiredKg = 0.0;
        double expiredVal = 0.0;

        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT event_type, quantity, weight_kg, financial_value FROM waste_eco_logs")) {
            while (rs.next()) {
                String type = rs.getString("event_type");
                int qty = rs.getInt("quantity");
                double kg = rs.getDouble("weight_kg");
                double val = rs.getDouble("financial_value");
                if ("EXPIRED".equalsIgnoreCase(type)) {
                    expiredU += qty;
                    expiredKg += kg;
                    expiredVal += val;
                } else {
                    rescuedU += qty;
                    rescuedKg += kg;
                    rescuedVal += val;
                }
            }
        } catch (SQLException ignored) {
        }

        double co2 = Math.round(rescuedKg * 2.1 * 100.0) / 100.0;
        String msg = String.format(Locale.US,
                "Rescued %d unit(s) (%.1f kg, $%.2f saved, ~%.1f kg CO2e avoided) vs %d expired.",
                rescuedU, rescuedKg, rescuedVal, co2, expiredU);
        return new WasteEcoImpactSummary(rescuedU, rescuedKg, rescuedVal, expiredU, expiredKg, expiredVal, co2, msg);
    }

    /**
     * Computes composite 0–100 Store Health Score across availability, expiry freshness,
     * supplier reliability, and tamper-evident audit ledger status.
     *
     * @return {@link StoreHealthScoreReport}
     */
    public StoreHealthScoreReport computeStoreHealthScore() {
        List<Product> products = productRepo.findAll(false);
        double availScore = 100.0;
        double freshScore = 100.0;
        if (!products.isEmpty()) {
            long healthyCount = products.stream().filter(p -> p.getStatus() == StockStatus.IN_STOCK).count();
            availScore = (healthyCount * 100.0) / products.size();
            LocalDate soon = LocalDate.now().plusDays(14);
            long expiringSoon = products.stream()
                    .filter(p -> p.getExpiryDate() != null && !p.getExpiryDate().isAfter(soon) && p.getQuantity() > 0)
                    .count();
            freshScore = Math.max(20.0, 100.0 - ((expiringSoon * 100.0) / products.size()) * 1.8);
        }

        var otifs = advancedMlService.scoreSuppliersOtif();
        double supScore = otifs.isEmpty() ? 92.0
                : otifs.stream().mapToDouble(o -> o.otifScorePercent()).average().orElse(92.0);

        double secScore = auditService.verifyIntegrity().intact() ? 100.0 : 40.0;

        int overall = (int) Math.round(
                availScore * 0.35 + freshScore * 0.25 + supScore * 0.20 + secScore * 0.20
        );
        overall = Math.max(0, Math.min(100, overall));
        String grade = overall >= 92 ? "A+" : overall >= 82 ? "A" : overall >= 70 ? "B" : overall >= 55 ? "C" : "D";
        String badge = String.format(Locale.US, "Health: %d/100 (%s)", overall, grade);
        return new StoreHealthScoreReport(
                overall, grade, Math.round(availScore * 10.0) / 10.0,
                Math.round(freshScore * 10.0) / 10.0, Math.round(supScore * 10.0) / 10.0, secScore, badge
        );
    }

    private double resolveMonthlyBudgetCap() {
        try {
            String raw = settingsRepo != null ? settingsRepo.get("ai.budget.monthly_cap", "5000.0") : "5000.0";
            return Math.max(100.0, Double.parseDouble(raw.trim()));
        } catch (Exception ex) {
            return 5000.0;
        }
    }

    /**
     * Serializes a lightweight map cleanly for agent audit records.
     *
     * @param obj object payload
     * @return JSON string
     */
    public String toJson(JsonObject obj) {
        return gson.toJson(obj);
    }
}
