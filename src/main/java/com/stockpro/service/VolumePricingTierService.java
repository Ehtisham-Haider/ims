package com.stockpro.service;

import com.stockpro.domain.VendorPriceTier;
import com.stockpro.domain.VolumePricingAdvice;
import com.stockpro.repository.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages supplier bulk volume price breaks and Minimum Order Quantity (MOQ) rules
 * in {@code vendor_price_tiers}, advising buyers when small order step-ups cut unit cost.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class VolumePricingTierService {

    private final DatabaseManager dbManager;

    /**
     * Constructs the supplier volume-pricing and MOQ advisor service.
     *
     * @param dbManager SQLite connection manager
     */
    public VolumePricingTierService(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Saves a supplier-product volume break tier, updating any existing tier sharing
     * the same {@code (supplierId, productId, minQty)} key.
     *
     * @param supplierId supplier primary key
     * @param productId product primary key
     * @param minQty minimum unit threshold unlocking {@code unitPrice}
     * @param moq supplier minimum order quantity floor
     * @param unitPrice discounted unit purchase cost
     * @return operation result with persisted {@link VendorPriceTier}
     */
    public InventoryOperationResult<VendorPriceTier> upsertTier(
            long supplierId,
            long productId,
            int minQty,
            int moq,
            double unitPrice) {
        if (supplierId <= 0 || productId <= 0) {
            return InventoryOperationResult.fail("Select a valid supplier and product.");
        }
        if (minQty <= 0 || moq <= 0) {
            return InventoryOperationResult.fail("Minimum quantity and MOQ must be positive.");
        }
        if (unitPrice <= 0.0) {
            return InventoryOperationResult.fail("Tier unit price must be positive.");
        }
        String now = LocalDateTime.now().toString();
        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT id FROM vendor_price_tiers WHERE supplier_id = ? AND product_id = ? AND min_qty = ?")) {
                check.setLong(1, supplierId);
                check.setLong(2, productId);
                check.setInt(3, minQty);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE vendor_price_tiers SET moq = ?, unit_price = ?, updated_at = ? WHERE id = ?")) {
                            upd.setInt(1, moq);
                            upd.setDouble(2, unitPrice);
                            upd.setString(3, now);
                            upd.setLong(4, id);
                            upd.executeUpdate();
                        }
                        return InventoryOperationResult.ok(
                                new VendorPriceTier(id, supplierId, productId, minQty, moq, unitPrice, now),
                                "Updated bulk tier (" + minQty + "+ @ " + unitPrice + ").");
                    }
                }
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO vendor_price_tiers (supplier_id, product_id, min_qty, moq, unit_price, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ins.setLong(1, supplierId);
                ins.setLong(2, productId);
                ins.setInt(3, minQty);
                ins.setInt(4, moq);
                ins.setDouble(5, unitPrice);
                ins.setString(6, now);
                ins.executeUpdate();
                long id = 0L;
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                    }
                }
                return InventoryOperationResult.ok(
                        new VendorPriceTier(id, supplierId, productId, minQty, moq, unitPrice, now),
                        "Added bulk tier (" + minQty + "+ units @ " + unitPrice + ").");
            }
        } catch (SQLException ex) {
            return InventoryOperationResult.fail("Failed to save vendor pricing tier: " + ex.getMessage());
        }
    }

    /**
     * Lists configured volume price breaks for a supplier-product pair ordered ascending by {@code min_qty}.
     *
     * @param supplierId supplier primary key
     * @param productId product primary key
     * @return sorted list of {@link VendorPriceTier}
     */
    public List<VendorPriceTier> listTiersFor(long supplierId, long productId) {
        List<VendorPriceTier> list = new ArrayList<>();
        String sql = "SELECT id, supplier_id, product_id, min_qty, moq, unit_price, updated_at "
                + "FROM vendor_price_tiers WHERE supplier_id = ? AND product_id = ? ORDER BY min_qty ASC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, supplierId);
            ps.setLong(2, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new VendorPriceTier(
                            rs.getLong("id"),
                            rs.getLong("supplier_id"),
                            rs.getLong("product_id"),
                            rs.getInt("min_qty"),
                            rs.getInt("moq"),
                            rs.getDouble("unit_price"),
                            rs.getString("updated_at")
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load vendor price tiers.", ex);
        }
        return list;
    }

    /**
     * Evaluates an order quantity against supplier MOQ constraints and higher bulk tiers,
     * computing unit cost savings available by stepping up to the next threshold.
     *
     * @param supplierId supplier primary key
     * @param productId product primary key
     * @param requestedQty desired purchase quantity
     * @param baselineUnitPrice catalog base unit price used when no lower tier matches
     * @return {@link VolumePricingAdvice} guidance record
     */
    public VolumePricingAdvice evaluateOrderQuantity(
            long supplierId,
            long productId,
            int requestedQty,
            double baselineUnitPrice) {
        int safeReq = Math.max(1, requestedQty);
        List<VendorPriceTier> tiers = listTiersFor(supplierId, productId);
        if (tiers.isEmpty()) {
            return new VolumePricingAdvice(
                    safeReq, safeReq, 1, baselineUnitPrice, baselineUnitPrice,
                    0, baselineUnitPrice, 0.0,
                    "Standard single-tier unit cost applies ($" + String.format(Locale.US, "%.2f", baselineUnitPrice) + ")."
            );
        }

        int effectiveMoq = 1;
        for (VendorPriceTier t : tiers) {
            effectiveMoq = Math.max(effectiveMoq, t.moq());
        }
        int enforcedQty = Math.max(safeReq, effectiveMoq);

        VendorPriceTier unlocked = null;
        VendorPriceTier nextBetter = null;
        for (VendorPriceTier t : tiers) {
            if (enforcedQty >= t.minQty()) {
                unlocked = t;
            } else if (nextBetter == null && t.unitPrice() < (unlocked != null ? unlocked.unitPrice() : baselineUnitPrice)) {
                nextBetter = t;
            }
        }

        double activePrice = unlocked != null ? unlocked.unitPrice() : baselineUnitPrice;
        StringBuilder msg = new StringBuilder();
        if (safeReq < effectiveMoq) {
            msg.append("MOQ enforced: lifted qty from ").append(safeReq).append(" to ").append(enforcedQty).append(". ");
        }
        msg.append(String.format(Locale.US, "Active unit price: $%.2f (base $%.2f).", activePrice, baselineUnitPrice));

        if (nextBetter != null) {
            int stepUpExtraUnits = nextBetter.minQty() - enforcedQty;
            double savingsAtNextQty = (activePrice - nextBetter.unitPrice()) * nextBetter.minQty();
            msg.append(String.format(Locale.US,
                    " Tip: Order +%d unit(s) (reach %d) to drop price to $%.2f/unit and save $%.2f on unit margin.",
                    stepUpExtraUnits, nextBetter.minQty(), nextBetter.unitPrice(), savingsAtNextQty));
            return new VolumePricingAdvice(
                    safeReq, enforcedQty, effectiveMoq, activePrice, baselineUnitPrice,
                    nextBetter.minQty(), nextBetter.unitPrice(), roundTwo(savingsAtNextQty), msg.toString()
            );
        }

        return new VolumePricingAdvice(
                safeReq, enforcedQty, effectiveMoq, activePrice, baselineUnitPrice,
                0, activePrice, 0.0, msg.toString()
        );
    }

    private double roundTwo(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
