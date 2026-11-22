package com.stockpro.service;

import com.google.gson.Gson;
import com.stockpro.domain.LandedCostAllocation;
import com.stockpro.domain.LandedCostAllocation.AllocationStrategy;
import com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Landed Cost Engine implementing Strategy Pattern allocation
 * ({@code BY_VALUE},
 * {@code BY_QUANTITY}, {@code BY_WEIGHT}) to allocate freight, customs duties,
 * and
 * handling charges across Purchase Order lines into true effective unit cost.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class LandedCostEngine {

    private final DatabaseManager dbManager;
    private final PurchaseOrderRepository poRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private final Gson gson = new Gson();

    /**
     * Constructs the Landed Cost allocation engine.
     *
     * @param dbManager         SQLite connection manager
     * @param poRepository      purchase order repository
     * @param productRepository product catalog repository
     * @param auditService      tamper-evident audit logger
     * @param eventBus          shared UI refresh event bus
     */
    public LandedCostEngine(
            DatabaseManager dbManager,
            PurchaseOrderRepository poRepository,
            ProductRepository productRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.poRepository = poRepository;
        this.productRepository = productRepository;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Computes a non-persistent preview of landed unit costs across a Purchase
     * Order's items.
     *
     * @param poId        target Purchase Order id
     * @param freightCost freight or shipping fee
     * @param customsDuty import tax or customs duty
     * @param handlingFee handling / port / insurance fee
     * @param strategy    overhead allocation strategy
     * @return operation result with calculated {@link LandedCostAllocation}
     */
    public InventoryOperationResult<LandedCostAllocation> calculatePreview(
            long poId,
            double freightCost,
            double customsDuty,
            double handlingFee,
            AllocationStrategy strategy) {
        if (freightCost < 0.0 || customsDuty < 0.0 || handlingFee < 0.0) {
            return InventoryOperationResult.fail("Freight, customs, and handling fees cannot be negative.");
        }
        Optional<PurchaseOrder> poOpt = poRepository.findById(poId);
        if (poOpt.isEmpty()) {
            return InventoryOperationResult.fail("Purchase Order #" + poId + " does not exist.");
        }
        PurchaseOrder po = poOpt.get();
        if (po.getItems() == null || po.getItems().isEmpty()) {
            return InventoryOperationResult.fail("Purchase Order " + po.getPoNumber() + " has no line items.");
        }
        AllocationStrategy chosen = strategy != null ? strategy : AllocationStrategy.BY_VALUE;
        LandedCostAllocation result = allocateInternal(po, freightCost, customsDuty, handlingFee, chosen,
                LocalDateTime.now().toString());
        return InventoryOperationResult.ok(result,
                "Computed " + chosen + " landed cost preview for " + po.getPoNumber() + ".");
    }

    /**
     * Allocates landed charges across a Purchase Order, persists the breakdown to
     * {@code landed_cost_allocations}, optionally updates catalog unit buy prices,
     * and logs to audit.
     *
     * @param poId                  target Purchase Order id
     * @param freightCost           freight charge
     * @param customsDuty           customs duty
     * @param handlingFee           handling / insurance fee
     * @param strategy              overhead allocation strategy
     * @param updateCatalogBuyPrice whether to update each product's
     *                              {@code buyPrice} to the new landed cost
     * @param actor                 authenticated operator name
     * @return operation result containing persisted {@link LandedCostAllocation}
     */
    public InventoryOperationResult<LandedCostAllocation> allocateAndPersist(
            long poId,
            double freightCost,
            double customsDuty,
            double handlingFee,
            AllocationStrategy strategy,
            boolean updateCatalogBuyPrice,
            String actor) {
        InventoryOperationResult<LandedCostAllocation> previewRes = calculatePreview(poId, freightCost, customsDuty,
                handlingFee, strategy);
        if (!previewRes.isSuccess()) {
            return previewRes;
        }
        LandedCostAllocation calc = previewRes.getData();
        String breakdownJson = gson.toJson(calc.lines());
        long generatedId = 0L;

        try (Connection conn = dbManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        """
                                INSERT INTO landed_cost_allocations (
                                    po_id, freight_cost, customs_duty, handling_fee, strategy, breakdown_json, created_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                                """,
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, calc.poId());
            ps.setDouble(2, calc.freightCost());
            ps.setDouble(3, calc.customsDuty());
            ps.setDouble(4, calc.handlingFee());
            ps.setString(5, calc.strategy().name());
            ps.setString(6, breakdownJson);
            ps.setString(7, calc.createdAt());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    generatedId = keys.getLong(1);
                }
            }
        } catch (SQLException ex) {
            return InventoryOperationResult.fail("Failed to save landed cost allocation: " + ex.getMessage());
        }

        if (updateCatalogBuyPrice) {
            for (LandedCostLineBreakdown line : calc.lines()) {
                productRepository.findById(line.productId()).ifPresent(prod -> {
                    prod.setBuyPrice(line.effectiveLandedUnitCost());
                    prod.setUpdatedAt(LocalDateTime.now());
                    productRepository.update(prod);
                });
            }
        }

        String who = actor != null && !actor.isBlank() ? actor : "system";
        auditService.record(
                who,
                "LANDED_COST",
                "PO#" + poId,
                "Allocated " + String.format("%.2f", calc.totalOverhead()) + " via " + calc.strategy());
        eventBus.publish("LANDED_COST_APPLIED");

        LandedCostAllocation persisted = new LandedCostAllocation(
                generatedId,
                calc.poId(),
                calc.freightCost(),
                calc.customsDuty(),
                calc.handlingFee(),
                calc.strategy(),
                calc.lines(),
                calc.createdAt());
        return InventoryOperationResult.ok(persisted,
                "Saved landed cost allocation #" + generatedId + " (" + calc.strategy() + ").");
    }

    /**
     * Lists persisted landed cost allocations newest first.
     *
     * @return list of {@link LandedCostAllocation}
     */
    public List<LandedCostAllocation> listAllocations() {
        List<LandedCostAllocation> out = new ArrayList<>();
        String sql = "SELECT id, po_id, freight_cost, customs_duty, handling_fee, strategy, breakdown_json, created_at "
                + "FROM landed_cost_allocations ORDER BY id DESC LIMIT 100";
        try (Connection conn = dbManager.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                LandedCostLineBreakdown[] arr = gson.fromJson(rs.getString("breakdown_json"),
                        LandedCostLineBreakdown[].class);
                List<LandedCostLineBreakdown> lines = arr != null ? List.of(arr) : List.of();
                out.add(new LandedCostAllocation(
                        rs.getLong("id"),
                        rs.getLong("po_id"),
                        rs.getDouble("freight_cost"),
                        rs.getDouble("customs_duty"),
                        rs.getDouble("handling_fee"),
                        AllocationStrategy.valueOf(rs.getString("strategy")),
                        lines,
                        rs.getString("created_at")));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list landed cost allocations.", ex);
        }
        return out;
    }

    private LandedCostAllocation allocateInternal(
            PurchaseOrder po,
            double freight,
            double duty,
            double handling,
            AllocationStrategy strategy,
            String now) {
        List<PurchaseOrderItem> items = po.getItems();
        double totalOverhead = freight + duty + handling;
        double[] weights = new double[items.size()];
        double sumWeights = 0.0;

        for (int i = 0; i < items.size(); i++) {
            PurchaseOrderItem item = items.get(i);
            double w = switch (strategy) {
                case BY_VALUE -> Math.max(0.0, item.getQuantity() * item.getUnitCost());
                case BY_QUANTITY -> Math.max(0.0, item.getQuantity());
                case BY_WEIGHT -> {
                    Optional<Product> p = productRepository.findById(item.getProductId());
                    double unitKg = p.map(WarehouseMapPickingService::estimateUnitWeightKg).orElse(0.75);
                    yield Math.max(0.0, item.getQuantity() * unitKg);
                }
            };
            weights[i] = w;
            sumWeights += w;
        }

        List<LandedCostLineBreakdown> lines = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            PurchaseOrderItem item = items.get(i);
            int qty = Math.max(1, item.getQuantity());
            double ratio = sumWeights > 0.0 ? (weights[i] / sumWeights) : (1.0 / items.size());
            double extra = roundFour(totalOverhead * ratio);
            double effectiveUnit = roundFour(item.getUnitCost() + (extra / qty));
            lines.add(new LandedCostLineBreakdown(
                    item.getProductId(),
                    item.getProductSku(),
                    item.getProductName(),
                    item.getQuantity(),
                    item.getUnitCost(),
                    extra,
                    effectiveUnit));
        }
        return new LandedCostAllocation(0L, po.getId(), freight, duty, handling, strategy, lines, now);
    }

    private double roundFour(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}
