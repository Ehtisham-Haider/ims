package com.stockpro.domain;

import java.util.List;

/**
 * Aggregated forward and backward traceability snapshot for a recalled or audited manufacturing batch lot.
 *
 * @param lot originating supplier batch lot metadata
 * @param productSku product SKU associated with the batch
 * @param productName product display name
 * @param supplierName originating supplier name (or {@code Unlinked Supplier})
 * @param shelfLocation active warehouse shelf bin
 * @param linkedSerials all unit serial numbers manufactured under this batch
 * @param relatedMovements recent stock movements referencing this product/batch
 * @param activeStockCount current sellable stock on hand
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record BatchRecallTrace(
        BatchLot lot,
        String productSku,
        String productName,
        String supplierName,
        String shelfLocation,
        List<SerialNumberRecord> linkedSerials,
        List<StockMovement> relatedMovements,
        int activeStockCount) {
}
