package com.stockpro.domain;

import java.util.List;

/**
 * Landed-cost allocation summary breaking freight, customs duty, and handling fees
 * across every Purchase Order line item using a chosen allocation strategy.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param id persisted allocation id (or {@code 0} for unsaved preview)
 * @param poId target purchase order id
 * @param freightCost total shipping / freight charge
 * @param customsDuty total customs / import tariff charge
 * @param handlingFee total port / warehouse handling fee
 * @param strategy allocation strategy ({@link AllocationStrategy})
 * @param lines per-SKU allocated overhead and effective unit landed cost
 * @param createdAt ISO-8601 timestamp
 */
public record LandedCostAllocation(
        long id,
        long poId,
        double freightCost,
        double customsDuty,
        double handlingFee,
        AllocationStrategy strategy,
        List<LandedCostLineBreakdown> lines,
        String createdAt
) {
    /**
     * Strategy governing how shared import/freight overhead distributes across PO lines.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public enum AllocationStrategy {
        /** Allocates overhead proportionally to line merchandise monetary value. */
        BY_VALUE,
        /** Allocates overhead proportionally to total ordered unit count. */
        BY_QUANTITY,
        /** Allocates overhead proportionally to total line physical weight in kg. */
        BY_WEIGHT
    }

    /**
     * Per-line landed cost calculation output.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId product database id
     * @param sku product SKU
     * @param productName product display name
     * @param quantity ordered units
     * @param baseUnitCost original supplier unit price
     * @param allocatedExtraTotal total extra overhead assigned to this line
     * @param effectiveLandedUnitCost final unit cost including overhead
     */
    public record LandedCostLineBreakdown(
            long productId,
            String sku,
            String productName,
            int quantity,
            double baseUnitCost,
            double allocatedExtraTotal,
            double effectiveLandedUnitCost
    ) {
    }

    /**
     * Returns combined extra charges distributed across the Purchase Order.
     *
     * @return sum of freight, customs duty, and handling fee
     */
    public double totalOverhead() {
        return freightCost + customsDuty + handlingFee;
    }
}
