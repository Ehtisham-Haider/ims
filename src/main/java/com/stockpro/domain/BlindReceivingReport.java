package com.stockpro.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of a blind physical dock receiving check comparing unbiased counted quantities against expected PO lines.
 *
 * @param receiptId persisted goods receipt row ID
 * @param poId target purchase order ID
 * @param poNumber formatted purchase order reference
 * @param matchedExactly {@code true} when every ordered SKU matches physical dock counts cleanly
 * @param expectedBySku expected quantities keyed by SKU
 * @param countedBySku physical dock counts keyed by SKU
 * @param varianceBySku signed delta ({@code counted - expected}) keyed by SKU
 * @param discrepancies human-readable short/over/unexpected shipment notes
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record BlindReceivingReport(
        long receiptId,
        long poId,
        String poNumber,
        boolean matchedExactly,
        Map<String, Integer> expectedBySku,
        Map<String, Integer> countedBySku,
        Map<String, Integer> varianceBySku,
        List<String> discrepancies) {
}
