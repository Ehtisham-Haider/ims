package com.stockpro.domain;

import java.util.List;

/**
 * Result of a 3-way supplier invoice verification cross-checking Purchase Order, Goods Received Note,
 * and Supplier Invoice totals.
 *
 * @param poId target purchase order ID
 * @param poNumber formatted purchase order number
 * @param invoiceNumber supplier invoice code
 * @param poTotal expected total cost on the Purchase Order
 * @param grnAcceptedValue physical received value computed from blind dock counts and PO unit costs
 * @param invoiceTotal total billed on the vendor invoice
 * @param matchStatus {@code MATCHED} or {@code DISCREPANCY_FLAGGED}
 * @param discrepancies detected quantity or billing variance alerts
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record ThreeWayMatchResult(
        long poId,
        String poNumber,
        String invoiceNumber,
        double poTotal,
        double grnAcceptedValue,
        double invoiceTotal,
        String matchStatus,
        List<String> discrepancies) {
}
