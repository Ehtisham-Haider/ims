package com.stockpro.service;

import com.google.gson.Gson;
import com.stockpro.domain.BlindReceivingReport;
import com.stockpro.domain.BranchStockRecord;
import com.stockpro.domain.CustomerBackorder;
import com.stockpro.domain.CustomerReturnRma;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockTransfer;
import com.stockpro.domain.StoreBranch;
import com.stockpro.domain.ThreeWayMatchResult;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.StockMovementRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manages multi-branch inventory tracking, valuation-safe inter-branch stock transfers,
 * Available-to-Promise (ATP) reservations, blind dock receiving, 3-way supplier invoice matching,
 * customer RMA returns with disposition workflows, and customer backorders.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class MultiBranchTransferRmaService {
    private static final Gson GSON = new Gson();

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockMovementRepository movementRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;

    /**
     * Constructs the multi-branch, transfer, receiving, and RMA operations service.
     *
     * @param dbManager database manager
     * @param productRepository product repository
     * @param purchaseOrderRepository purchase order repository
     * @param movementRepository stock movement repository
     * @param auditService SHA-256 audit logger
     * @param eventBus shared UI refresh event bus
     */
    public MultiBranchTransferRmaService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            StockMovementRepository movementRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.eventBus = eventBus != null ? eventBus : InventoryEventBus.getInstance();
    }

    /**
     * Lists all active physical store branches and regional hubs.
     *
     * @return list of active {@link StoreBranch} records ordered by ID
     */
    public List<StoreBranch> listBranches() {
        String sql = "SELECT id, branch_code, name, address, manager_name, active, created_at FROM branches WHERE active = 1 ORDER BY id ASC";
        List<StoreBranch> out = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new StoreBranch(
                        rs.getLong("id"),
                        rs.getString("branch_code"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getString("manager_name"),
                        rs.getInt("active") == 1,
                        LocalDateTime.parse(rs.getString("created_at"))
                ));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list store branches", ex);
        }
        return out;
    }

    /**
     * Registers a new physical store branch or distribution warehouse.
     *
     * @param branchCode short uppercase branch code
     * @param name store display name
     * @param address street location
     * @param managerName branch manager name
     * @return wrapped {@link StoreBranch}
     */
    public InventoryOperationResult<StoreBranch> createBranch(
            String branchCode,
            String name,
            String address,
            String managerName) {
        if (branchCode == null || branchCode.isBlank() || name == null || name.isBlank()) {
            return InventoryOperationResult.fail("Branch code and branch name are required.");
        }
        String code = branchCode.trim().toUpperCase(Locale.ROOT);
        String now = LocalDateTime.now().toString();
        String sql = "INSERT INTO branches (branch_code, name, address, manager_name, active, created_at) VALUES (?, ?, ?, ?, 1, ?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setString(2, name.trim());
            ps.setString(3, address != null ? address.trim() : "");
            ps.setString(4, managerName != null ? managerName.trim() : "");
            ps.setString(5, now);
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            eventBus.publish("BRANCH_CREATED");
            return InventoryOperationResult.ok(
                    new StoreBranch(id, code, name.trim(), address != null ? address.trim() : "",
                            managerName != null ? managerName.trim() : "", true, LocalDateTime.parse(now)),
                    "Created branch '" + name.trim() + " (" + code + ")'.");
        } catch (SQLException ex) {
            return InventoryOperationResult.fail("Branch creation failed: " + ex.getMessage());
        }
    }

    /**
     * Sets explicit on-hand and reserved quantities for a product at a specific branch.
     *
     * @param branchId target branch ID
     * @param productId target product ID
     * @param onHandQty physical on-hand count
     * @param reservedQty reserved/committed count
     * @return updated {@link BranchStockRecord}
     */
    public InventoryOperationResult<BranchStockRecord> setBranchStock(
            long branchId,
            long productId,
            int onHandQty,
            int reservedQty) {
        if (onHandQty < 0 || reservedQty < 0) {
            return InventoryOperationResult.fail("Branch stock and reserved quantities cannot be negative.");
        }
        if (reservedQty > onHandQty) {
            return InventoryOperationResult.fail("Reserved stock cannot exceed on-hand quantity.");
        }
        String sql = """
                INSERT INTO branch_stock (branch_id, product_id, on_hand_qty, reserved_qty)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(branch_id, product_id) DO UPDATE SET
                    on_hand_qty = excluded.on_hand_qty,
                    reserved_qty = excluded.reserved_qty
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            ps.setInt(3, onHandQty);
            ps.setInt(4, reservedQty);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update branch stock", ex);
        }
        return getBranchStock(branchId, productId)
                .map(rec -> InventoryOperationResult.ok(rec, "Updated branch stock for " + rec.sku() + "."))
                .orElseGet(() -> InventoryOperationResult.fail("Branch or product not found."));
    }

    /**
     * Reads on-hand, reserved, and ATP stock counts for a specific product and branch.
     *
     * @param branchId target branch ID
     * @param productId target product ID
     * @return optional {@link BranchStockRecord}
     */
    public Optional<BranchStockRecord> getBranchStock(long branchId, long productId) {
        String sql = """
                SELECT b.id AS branch_id, b.branch_code, b.name AS branch_name,
                       p.id AS product_id, p.sku, p.name AS product_name,
                       COALESCE(bs.on_hand_qty, CASE WHEN b.id = 1 THEN p.quantity ELSE 0 END) AS on_hand_qty,
                       COALESCE(bs.reserved_qty, 0) AS reserved_qty
                FROM branches b
                CROSS JOIN products p
                LEFT JOIN branch_stock bs ON bs.branch_id = b.id AND bs.product_id = p.id
                WHERE b.id = ? AND p.id = ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapBranchStock(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load branch stock", ex);
        }
        return Optional.empty();
    }

    /**
     * Lists all active product stock levels at a branch including ATP availability.
     *
     * @param branchId target branch ID
     * @return list of {@link BranchStockRecord} items
     */
    public List<BranchStockRecord> listBranchStock(long branchId) {
        String sql = """
                SELECT b.id AS branch_id, b.branch_code, b.name AS branch_name,
                       p.id AS product_id, p.sku, p.name AS product_name,
                       COALESCE(bs.on_hand_qty, CASE WHEN b.id = 1 THEN p.quantity ELSE 0 END) AS on_hand_qty,
                       COALESCE(bs.reserved_qty, 0) AS reserved_qty
                FROM branches b
                JOIN products p ON p.archived = 0
                LEFT JOIN branch_stock bs ON bs.branch_id = b.id AND bs.product_id = p.id
                WHERE b.id = ?
                ORDER BY p.id ASC
                """;
        List<BranchStockRecord> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapBranchStock(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list branch inventory", ex);
        }
        return list;
    }

    /**
     * Reserves stock at a branch without deducting physical on-hand inventory, preventing overselling
     * once Available-to-Promise ({@code onHand - reserved}) drops below requested units.
     *
     * @param branchId branch ID
     * @param productId product ID
     * @param qty units to commit
     * @param reason reservation note
     * @param actor operator name
     * @return updated {@link BranchStockRecord} or ATP error
     */
    public InventoryOperationResult<BranchStockRecord> reserveStock(
            long branchId,
            long productId,
            int qty,
            String reason,
            String actor) {
        if (qty <= 0) {
            return InventoryOperationResult.fail("Reservation quantity must be positive.");
        }
        Optional<BranchStockRecord> currentOpt = getBranchStock(branchId, productId);
        if (currentOpt.isEmpty()) {
            return InventoryOperationResult.fail("Branch or product record not found.");
        }
        BranchStockRecord current = currentOpt.get();
        if (current.availableToPromise() < qty) {
            return InventoryOperationResult.fail(
                    "Insufficient Available-to-Promise (ATP): requested " + qty
                            + ", available " + current.availableToPromise()
                            + " (On-Hand: " + current.onHandQty()
                            + ", Reserved: " + current.reservedQty() + ").");
        }
        InventoryOperationResult<BranchStockRecord> updated =
                setBranchStock(branchId, productId, current.onHandQty(), current.reservedQty() + qty);
        if (updated.isSuccess() && auditService != null) {
            auditService.record(actor, "STOCK_RESERVE", current.sku(),
                    "Reserved " + qty + " units at branch " + current.branchCode()
                            + (reason != null && !reason.isBlank() ? " (" + reason.trim() + ")" : ""));
        }
        return updated;
    }

    /**
     * Releases previously reserved units back into Available-to-Promise (ATP) capacity.
     *
     * @param branchId branch ID
     * @param productId product ID
     * @param qty units to release
     * @return updated {@link BranchStockRecord}
     */
    public InventoryOperationResult<BranchStockRecord> releaseReservation(
            long branchId,
            long productId,
            int qty) {
        if (qty <= 0) {
            return InventoryOperationResult.fail("Release quantity must be positive.");
        }
        Optional<BranchStockRecord> currentOpt = getBranchStock(branchId, productId);
        if (currentOpt.isEmpty()) {
            return InventoryOperationResult.fail("Branch or product record not found.");
        }
        BranchStockRecord current = currentOpt.get();
        int nextReserved = Math.max(0, current.reservedQty() - qty);
        return setBranchStock(branchId, productId, current.onHandQty(), nextReserved);
    }

    /**
     * Initiates a branch-to-branch stock transfer in {@code REQUESTED} state, reserving and moving
     * source branch stock into transit without reducing total enterprise valuation.
     *
     * @param sourceBranchId dispatching branch ID
     * @param targetBranchId destination branch ID
     * @param productId product ID
     * @param quantity units to transfer
     * @param notes optional transfer reason
     * @param actor operator display name
     * @return created {@link StockTransfer}
     */
    public InventoryOperationResult<StockTransfer> createTransfer(
            long sourceBranchId,
            long targetBranchId,
            long productId,
            int quantity,
            String notes,
            String actor) {
        if (sourceBranchId == targetBranchId) {
            return InventoryOperationResult.fail("Source and destination branches must differ.");
        }
        if (quantity <= 0) {
            return InventoryOperationResult.fail("Transfer quantity must be positive.");
        }
        Optional<Product> prodOpt = productRepository.findById(productId);
        Optional<BranchStockRecord> srcOpt = getBranchStock(sourceBranchId, productId);
        if (prodOpt.isEmpty() || srcOpt.isEmpty()) {
            return InventoryOperationResult.fail("Source branch or product not found.");
        }
        BranchStockRecord src = srcOpt.get();
        if (src.availableToPromise() < quantity) {
            return InventoryOperationResult.fail(
                    "Insufficient ATP stock at " + src.branchCode() + " (" + src.availableToPromise() + " available).");
        }

        // Persist explicit row on source branch (deducted immediately into transit pool)
        setBranchStock(sourceBranchId, productId, src.onHandQty() - quantity, src.reservedQty());
        getBranchStock(targetBranchId, productId).ifPresent(t ->
                setBranchStock(targetBranchId, productId, t.onHandQty(), t.reservedQty()));

        Product prod = prodOpt.get();
        String code = "TRF-" + (1000 + Math.abs((int) (System.currentTimeMillis() % 8999)));
        String now = LocalDateTime.now().toString();
        String actorName = (actor != null && !actor.isBlank()) ? actor.trim() : "System";
        String sql = """
                INSERT INTO stock_transfers (
                    transfer_code, source_branch_id, target_branch_id, product_id, quantity,
                    unit_cost, status, notes, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, code);
            ps.setLong(2, sourceBranchId);
            ps.setLong(3, targetBranchId);
            ps.setLong(4, productId);
            ps.setInt(5, quantity);
            ps.setDouble(6, prod.getBuyPrice());
            ps.setString(7, notes != null ? notes.trim() : "");
            ps.setString(8, actorName);
            ps.setString(9, now);
            ps.setString(10, now);
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            if (auditService != null) {
                auditService.record(actorName, "TRANSFER_REQUESTED", code,
                        "Requested transfer of " + quantity + "x " + prod.getSku()
                                + " from branch " + sourceBranchId + " to " + targetBranchId);
            }
            eventBus.publish("TRANSFER_UPDATED");
            return InventoryOperationResult.ok(
                    findTransferById(id).orElse(new StockTransfer(id, code, sourceBranchId, targetBranchId,
                            productId, quantity, prod.getBuyPrice(), "REQUESTED", notes, actorName,
                            LocalDateTime.parse(now), LocalDateTime.parse(now))),
                    "Created transfer " + code + " (" + quantity + " units).");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create inter-branch transfer", ex);
        }
    }

    /**
     * Dispatches a {@code REQUESTED} transfer into {@code IN_TRANSIT} status.
     *
     * @param transferId transfer row ID
     * @param actor operator display name
     * @return updated {@link StockTransfer}
     */
    public InventoryOperationResult<StockTransfer> dispatchTransfer(long transferId, String actor) {
        Optional<StockTransfer> trOpt = findTransferById(transferId);
        if (trOpt.isEmpty()) {
            return InventoryOperationResult.fail("Transfer not found.");
        }
        StockTransfer tr = trOpt.get();
        if (!"REQUESTED".equalsIgnoreCase(tr.status())) {
            return InventoryOperationResult.fail("Only REQUESTED transfers may be dispatched.");
        }
        updateTransferStatus(transferId, "IN_TRANSIT");
        if (auditService != null) {
            auditService.record(actor, "TRANSFER_DISPATCHED", tr.transferCode(),
                    tr.quantity() + " units in transit to branch " + tr.targetBranchId());
        }
        eventBus.publish("TRANSFER_UPDATED");
        return InventoryOperationResult.ok(findTransferById(transferId).orElse(tr),
                "Dispatched transfer " + tr.transferCode() + " (IN_TRANSIT).");
    }

    /**
     * Marks an {@code IN_TRANSIT} transfer as {@code RECEIVED} and credits destination branch stock.
     *
     * @param transferId transfer row ID
     * @param actor operator display name
     * @return updated {@link StockTransfer}
     */
    public InventoryOperationResult<StockTransfer> receiveTransfer(long transferId, String actor) {
        Optional<StockTransfer> trOpt = findTransferById(transferId);
        if (trOpt.isEmpty()) {
            return InventoryOperationResult.fail("Transfer not found.");
        }
        StockTransfer tr = trOpt.get();
        if (!"IN_TRANSIT".equalsIgnoreCase(tr.status()) && !"REQUESTED".equalsIgnoreCase(tr.status())) {
            return InventoryOperationResult.fail("Transfer has already been received.");
        }
        BranchStockRecord target = getBranchStock(tr.targetBranchId(), tr.productId()).orElse(null);
        int currOnHand = target != null ? target.onHandQty() : 0;
        int currRes = target != null ? target.reservedQty() : 0;
        setBranchStock(tr.targetBranchId(), tr.productId(), currOnHand + tr.quantity(), currRes);
        updateTransferStatus(transferId, "RECEIVED");
        if (auditService != null) {
            auditService.record(actor, "TRANSFER_RECEIVED", tr.transferCode(),
                    "Received " + tr.quantity() + " units at branch " + tr.targetBranchId());
        }
        eventBus.publish("TRANSFER_UPDATED");
        return InventoryOperationResult.ok(findTransferById(transferId).orElse(tr),
                "Received transfer " + tr.transferCode() + " at target branch.");
    }

    /**
     * Computes total cost valuation across all branches plus active {@code REQUESTED}/{@code IN_TRANSIT} stock.
     *
     * @return total multi-branch inventory valuation
     */
    public double computeCompanyValuationIncludingInTransit() {
        double branchVal = 0.0;
        List<StoreBranch> branches = listBranches();
        for (StoreBranch b : branches) {
            Map<Long, Double> buyById = new LinkedHashMap<>();
            for (Product p : productRepository.findAll(false)) {
                buyById.put(p.getId(), p.getBuyPrice());
            }
            for (BranchStockRecord rec : listBranchStock(b.id())) {
                branchVal += Math.max(0, rec.onHandQty()) * Math.max(0.0, buyById.getOrDefault(rec.productId(), 0.0));
            }
        }

        double transitVal = 0.0;
        String sql = "SELECT COALESCE(SUM(quantity * unit_cost), 0.0) FROM stock_transfers WHERE status IN ('REQUESTED', 'IN_TRANSIT')";
        try (Connection conn = dbManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                transitVal = rs.getDouble(1);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to sum in-transit valuation", ex);
        }
        return Math.round((branchVal + transitVal) * 100.0) / 100.0;
    }

    /**
     * Lists recent stock transfers across all branches.
     *
     * @return ordered list of {@link StockTransfer} records
     */
    public List<StockTransfer> listTransfers() {
        String sql = "SELECT * FROM stock_transfers ORDER BY id DESC LIMIT 100";
        List<StockTransfer> out = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(mapTransfer(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list transfers", ex);
        }
        return out;
    }

    private Optional<StockTransfer> findTransferById(long transferId) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM stock_transfers WHERE id = ?")) {
            ps.setLong(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapTransfer(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load transfer", ex);
        }
        return Optional.empty();
    }

    private void updateTransferStatus(long transferId, String newStatus) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE stock_transfers SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, newStatus);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, transferId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update transfer status", ex);
        }
    }

    /**
     * Records an unbiased blind physical receiving count against a Purchase Order and flags any short/over shipments.
     *
     * @param poId target Purchase Order ID
     * @param physicalCountsBySku physical dock counts entered without seeing expected PO quantities
     * @param receivedBy dock operator name
     * @return wrapped {@link BlindReceivingReport}
     */
    public InventoryOperationResult<BlindReceivingReport> recordBlindDelivery(
            long poId,
            Map<String, Integer> physicalCountsBySku,
            String receivedBy) {
        PurchaseOrder po = purchaseOrderRepository.findAll().stream()
                .filter(p -> p.getId() == poId)
                .findFirst().orElse(null);
        if (po == null) {
            return InventoryOperationResult.fail("Purchase order not found.");
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (PurchaseOrderItem item : po.getItems()) {
            expected.merge(item.getProductSku(), item.getQuantity(), Integer::sum);
        }
        Map<String, Integer> counted = physicalCountsBySku != null ? new LinkedHashMap<>(physicalCountsBySku) : Map.of();
        Map<String, Integer> variance = new LinkedHashMap<>();
        List<String> discrepancies = new ArrayList<>();

        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            String sku = e.getKey();
            int expQty = e.getValue();
            int gotQty = Math.max(0, counted.getOrDefault(sku, 0));
            int delta = gotQty - expQty;
            variance.put(sku, delta);
            if (delta < 0) {
                discrepancies.add(sku + ": short shipment of " + Math.abs(delta) + " unit(s) (expected " + expQty + ", counted " + gotQty + ")");
            } else if (delta > 0) {
                discrepancies.add(sku + ": over shipment of +" + delta + " unit(s) (expected " + expQty + ", counted " + gotQty + ")");
            }
        }
        for (Map.Entry<String, Integer> e : counted.entrySet()) {
            if (!expected.containsKey(e.getKey()) && e.getValue() != null && e.getValue() > 0) {
                variance.put(e.getKey(), e.getValue());
                discrepancies.add(e.getKey() + ": unexpected extra SKU (" + e.getValue() + " units)");
            }
        }

        boolean matched = discrepancies.isEmpty();
        String matchStatus = matched ? "BLIND_MATCHED" : "SHORTAGE_OR_VARIANCE";
        String now = LocalDateTime.now().toString();
        String actorName = (receivedBy != null && !receivedBy.isBlank()) ? receivedBy.trim() : "Dock Staff";
        long receiptId = 0;
        String sql = """
                INSERT INTO goods_receipts (po_id, blind_counts_json, invoice_number, invoice_total, discrepancy_notes, match_status, received_by, received_at)
                VALUES (?, ?, NULL, 0.0, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, poId);
            ps.setString(2, GSON.toJson(counted));
            ps.setString(3, String.join("; ", discrepancies));
            ps.setString(4, matchStatus);
            ps.setString(5, actorName);
            ps.setString(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    receiptId = keys.getLong(1);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to record blind delivery", ex);
        }

        if (auditService != null) {
            auditService.record(actorName, "BLIND_RECEIVING", po.getPoNumber(),
                    matched ? "Exact physical dock match" : "Flagged variances: " + String.join("; ", discrepancies));
        }
        return InventoryOperationResult.ok(
                new BlindReceivingReport(receiptId, poId, po.getPoNumber(), matched, expected, counted, variance, discrepancies),
                matched ? "Blind dock receiving matched PO cleanly." : "Blind receiving flagged " + discrepancies.size() + " variance(s).");
    }

    /**
     * Performs 3-Way Match comparing Purchase Order value, blind Goods Received physical value,
     * and vendor Invoice total, flagging quantity or price overbilling discrepancies.
     *
     * @param poId Purchase Order ID
     * @param invoiceNumber supplier invoice number
     * @param invoiceTotal total billed on vendor invoice
     * @param blindCountsBySku accepted physical dock quantities by SKU
     * @param verifiedBy finance/receiving verifier display name
     * @return {@link ThreeWayMatchResult}
     */
    public InventoryOperationResult<ThreeWayMatchResult> performThreeWayMatch(
            long poId,
            String invoiceNumber,
            double invoiceTotal,
            Map<String, Integer> blindCountsBySku,
            String verifiedBy) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            return InventoryOperationResult.fail("Supplier invoice number is required for 3-Way Match.");
        }
        PurchaseOrder po = purchaseOrderRepository.findAll().stream()
                .filter(p -> p.getId() == poId)
                .findFirst().orElse(null);
        if (po == null) {
            return InventoryOperationResult.fail("Purchase order not found.");
        }

        double grnValue = 0.0;
        List<String> discrepancies = new ArrayList<>();
        Map<String, Integer> counts = blindCountsBySku != null ? blindCountsBySku : Map.of();
        for (PurchaseOrderItem item : po.getItems()) {
            int accepted = Math.max(0, counts.getOrDefault(item.getProductSku(), 0));
            grnValue += accepted * item.getUnitCost();
            if (accepted != item.getQuantity()) {
                discrepancies.add(item.getProductSku() + ": ordered " + item.getQuantity() + " vs received " + accepted);
            }
        }
        grnValue = Math.round(grnValue * 100.0) / 100.0;
        double poTotal = Math.round(po.getTotalCost() * 100.0) / 100.0;
        double invTotal = Math.round(invoiceTotal * 100.0) / 100.0;

        if (Math.abs(invTotal - grnValue) > 0.05) {
            discrepancies.add(String.format("Billing mismatch: Invoice $%.2f vs GRN received value $%.2f", invTotal, grnValue));
        }
        if (Math.abs(invTotal - poTotal) > 0.05) {
            discrepancies.add(String.format("PO variance: Invoice $%.2f vs PO total $%.2f", invTotal, poTotal));
        }

        String status = discrepancies.isEmpty() ? "MATCHED" : "DISCREPANCY_FLAGGED";
        String now = LocalDateTime.now().toString();
        String actorName = (verifiedBy != null && !verifiedBy.isBlank()) ? verifiedBy.trim() : "Finance Officer";
        String sql = """
                INSERT INTO goods_receipts (po_id, blind_counts_json, invoice_number, invoice_total, discrepancy_notes, match_status, received_by, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, poId);
            ps.setString(2, GSON.toJson(counts));
            ps.setString(3, invoiceNumber.trim());
            ps.setDouble(4, invTotal);
            ps.setString(5, String.join("; ", discrepancies));
            ps.setString(6, status);
            ps.setString(7, actorName);
            ps.setString(8, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to persist 3-way match record", ex);
        }

        if (auditService != null) {
            auditService.record(actorName, "THREE_WAY_MATCH", invoiceNumber.trim(),
                    po.getPoNumber() + " -> " + status + (discrepancies.isEmpty() ? "" : " (" + discrepancies.size() + " issue(s))"));
        }
        return InventoryOperationResult.ok(
                new ThreeWayMatchResult(poId, po.getPoNumber(), invoiceNumber.trim(), poTotal, grnValue, invTotal, status, discrepancies),
                "3-Way Match result: " + status);
    }

    /**
     * Processes a customer return (RMA) under an explicit disposition ({@code RESTOCK_SELLABLE},
     * {@code MARK_DAMAGED}, or {@code SCRAP}), updates inventory/quarantine, records a movement and SHA-256
     * audit log entry, and notifies waiting customer backorders when sellable stock returns.
     *
     * @param productId returned product ID
     * @param branchId branch receiving the return
     * @param qty returned quantity
     * @param disposition {@code RESTOCK_SELLABLE}, {@code MARK_DAMAGED}, or {@code SCRAP}
     * @param reason customer return explanation
     * @param actor operator name
     * @return wrapped {@link CustomerReturnRma}
     */
    public InventoryOperationResult<CustomerReturnRma> processReturnRma(
            long productId,
            long branchId,
            int qty,
            String disposition,
            String reason,
            String actor) {
        if (qty <= 0) {
            return InventoryOperationResult.fail("Return quantity must be positive.");
        }
        Optional<Product> prodOpt = productRepository.findById(productId);
        if (prodOpt.isEmpty()) {
            return InventoryOperationResult.fail("Product not found.");
        }
        Product product = prodOpt.get();
        String disp = disposition != null && !disposition.isBlank()
                ? disposition.trim().toUpperCase(Locale.ROOT)
                : "RESTOCK_SELLABLE";
        String cleanReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Customer Return";
        String actorName = (actor != null && !actor.isBlank()) ? actor.trim() : "System";
        long targetBranch = branchId > 0 ? branchId : 1L;
        int prevQty = product.getQuantity();

        MovementType mvType;
        int delta = 0;
        switch (disp) {
            case "RESTOCK_SELLABLE" -> {
                product.setQuantity(prevQty + qty);
                product.refreshStatusIfAutomatic();
                BranchStockRecord br = getBranchStock(targetBranch, productId).orElse(null);
                if (br != null) {
                    setBranchStock(targetBranch, productId, br.onHandQty() + qty, br.reservedQty());
                }
                mvType = MovementType.RETURNED;
                delta = qty;
            }
            case "MARK_DAMAGED" -> {
                product.setDamagedQty(product.getDamagedQty() + qty);
                mvType = MovementType.DAMAGED;
            }
            case "SCRAP" -> {
                mvType = MovementType.LOST;
            }
            default -> {
                return InventoryOperationResult.fail("Unsupported RMA disposition: " + disp);
            }
        }
        productRepository.update(product);

        if (movementRepository != null) {
            StockMovement mv = new StockMovement();
            mv.setProductId(productId);
            mv.setProductSku(product.getSku());
            mv.setProductName(product.getName());
            mv.setMovementType(mvType);
            mv.setQuantityDelta(delta);
            mv.setPreviousQty(prevQty);
            mv.setNewQty(product.getQuantity());
            mv.setReason("RMA [" + disp + "]: " + cleanReason);
            mv.setPerformedBy(actorName);
            movementRepository.insert(mv);
        }

        double refund = Math.round(qty * Math.max(0.0, product.getSellPrice()) * 100.0) / 100.0;
        String rmaCode = "RMA-" + (1000 + Math.abs((int) (System.currentTimeMillis() % 8999)));
        String now = LocalDateTime.now().toString();
        long id = 0;
        String sql = """
                INSERT INTO returns_rma (rma_code, product_id, branch_id, quantity, disposition, reason, refund_amount, processed_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rmaCode);
            ps.setLong(2, productId);
            ps.setLong(3, targetBranch);
            ps.setInt(4, qty);
            ps.setString(5, disp);
            ps.setString(6, cleanReason);
            ps.setDouble(7, refund);
            ps.setString(8, actorName);
            ps.setString(9, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to record RMA return", ex);
        }

        if (auditService != null) {
            auditService.record(actorName, "RMA_" + disp, rmaCode,
                    qty + "x " + product.getSku() + " (" + cleanReason + ")");
        }
        if ("RESTOCK_SELLABLE".equals(disp)) {
            notifyWaitingCustomersOnRestock(productId);
        }
        eventBus.publish("RMA_PROCESSED");
        return InventoryOperationResult.ok(
                new CustomerReturnRma(id, rmaCode, productId, targetBranch, qty, disp, cleanReason, refund, actorName,
                        LocalDateTime.parse(now)),
                "Processed " + rmaCode + " (" + disp + ") for " + qty + "x " + product.getName() + ".");
    }

    /**
     * Records a waiting customer backorder entry (typically when a requested item is out of stock).
     *
     * @param productId target product ID
     * @param customerName customer display name
     * @param customerContact phone or email address
     * @param requestedQty units wanted
     * @return wrapped {@link CustomerBackorder}
     */
    public InventoryOperationResult<CustomerBackorder> recordBackorder(
            long productId,
            String customerName,
            String customerContact,
            int requestedQty) {
        if (customerName == null || customerName.isBlank()) {
            return InventoryOperationResult.fail("Customer name is required for backorder waitlist.");
        }
        if (requestedQty <= 0) {
            return InventoryOperationResult.fail("Backorder quantity must be positive.");
        }
        if (productRepository.findById(productId).isEmpty()) {
            return InventoryOperationResult.fail("Product not found.");
        }
        String now = LocalDateTime.now().toString();
        String contact = customerContact != null ? customerContact.trim() : "";
        long id = 0;
        String sql = """
                INSERT INTO backorders (product_id, customer_name, customer_contact, requested_qty, status, notified, created_at, fulfilled_at)
                VALUES (?, ?, ?, ?, 'WAITING', 0, ?, NULL)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, productId);
            ps.setString(2, customerName.trim());
            ps.setString(3, contact);
            ps.setInt(4, requestedQty);
            ps.setString(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            eventBus.publish("BACKORDER_ADDED");
            return InventoryOperationResult.ok(
                    new CustomerBackorder(id, productId, customerName.trim(), contact, requestedQty,
                            "WAITING", false, LocalDateTime.parse(now), null),
                    "Added '" + customerName.trim() + "' to waiting customer backorder list.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create backorder entry", ex);
        }
    }

    /**
     * Transitions all {@code WAITING} backorders for a product to {@code READY_NOTIFY} when sellable
     * inventory becomes available after restock or return.
     *
     * @param productId restocked product ID
     * @return newly notified {@link CustomerBackorder} entries
     */
    public List<CustomerBackorder> notifyWaitingCustomersOnRestock(long productId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null || product.getQuantity() <= 0) {
            return List.of();
        }
        List<CustomerBackorder> ready = new ArrayList<>();
        String selectSql = "SELECT * FROM backorders WHERE product_id = ? AND status = 'WAITING' ORDER BY id ASC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement psSel = conn.prepareStatement(selectSql)) {
            psSel.setLong(1, productId);
            try (ResultSet rs = psSel.executeQuery()) {
                while (rs.next()) {
                    ready.add(mapBackorder(rs));
                }
            }
            if (!ready.isEmpty()) {
                try (PreparedStatement psUpd = conn.prepareStatement(
                        "UPDATE backorders SET status = 'READY_NOTIFY', notified = 1 WHERE product_id = ? AND status = 'WAITING'")) {
                    psUpd.setLong(1, productId);
                    psUpd.executeUpdate();
                }
                List<CustomerBackorder> updated = new ArrayList<>();
                for (CustomerBackorder bo : ready) {
                    updated.add(new CustomerBackorder(
                            bo.id(), bo.productId(), bo.customerName(), bo.customerContact(),
                            bo.requestedQty(), "READY_NOTIFY", true, bo.createdAt(), bo.fulfilledAt()));
                }
                if (auditService != null) {
                    auditService.record("BackorderNotifier", "BACKORDER_READY", product.getSku(),
                            "Restock arrived (" + product.getQuantity() + " units); notified " + updated.size() + " customer(s)");
                }
                eventBus.publish("BACKORDER_READY_NOTIFY");
                return updated;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to notify waiting backorders", ex);
        }
        return List.of();
    }

    /**
     * Lists all recorded backorder waitlist entries.
     *
     * @return list of {@link CustomerBackorder} rows
     */
    public List<CustomerBackorder> listBackorders() {
        List<CustomerBackorder> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM backorders ORDER BY id DESC")) {
            while (rs.next()) {
                list.add(mapBackorder(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list backorders", ex);
        }
        return list;
    }

    private static BranchStockRecord mapBranchStock(ResultSet rs) throws SQLException {
        return new BranchStockRecord(
                rs.getLong("branch_id"),
                rs.getString("branch_code"),
                rs.getString("branch_name"),
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getInt("on_hand_qty"),
                rs.getInt("reserved_qty")
        );
    }

    private static StockTransfer mapTransfer(ResultSet rs) throws SQLException {
        return new StockTransfer(
                rs.getLong("id"),
                rs.getString("transfer_code"),
                rs.getLong("source_branch_id"),
                rs.getLong("target_branch_id"),
                rs.getLong("product_id"),
                rs.getInt("quantity"),
                rs.getDouble("unit_cost"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getString("created_by"),
                LocalDateTime.parse(rs.getString("created_at")),
                LocalDateTime.parse(rs.getString("updated_at"))
        );
    }

    private static CustomerBackorder mapBackorder(ResultSet rs) throws SQLException {
        String fulfilled = rs.getString("fulfilled_at");
        return new CustomerBackorder(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("customer_name"),
                rs.getString("customer_contact"),
                rs.getInt("requested_qty"),
                rs.getString("status"),
                rs.getInt("notified") == 1,
                LocalDateTime.parse(rs.getString("created_at")),
                fulfilled != null && !fulfilled.isBlank() ? LocalDateTime.parse(fulfilled) : null
        );
    }
}
