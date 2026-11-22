package com.stockpro.domain;

/**
 * Per-branch stock position separating On-Hand, Reserved/Committed, and Available-to-Promise (ATP) quantities.
 *
 * @param branchId owning branch ID
 * @param branchCode branch code
 * @param branchName branch name
 * @param productId catalog product ID
 * @param sku product SKU
 * @param productName product display name
 * @param onHandQty physical stock currently sitting at the branch
 * @param reservedQty stock committed to open orders and pending allocations
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record BranchStockRecord(
        long branchId,
        String branchCode,
        String branchName,
        long productId,
        String sku,
        String productName,
        int onHandQty,
        int reservedQty) {

    /**
     * Computes Available-to-Promise quantity safe for new customer orders ({@code max(0, onHand - reserved)}).
     *
     * @return non-negative available sellable quantity
     */
    public int availableToPromise() {
        return Math.max(0, onHandQty - reservedQty);
    }
}
