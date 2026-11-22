package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Tracks an inter-branch inventory transfer across {@code REQUESTED}, {@code IN_TRANSIT}, and {@code RECEIVED}
 * states so company-wide stock valuation never dips while goods are on the road.
 *
 * @param id database row identifier
 * @param transferCode unique transfer code (e.g., {@code TRF-1001})
 * @param sourceBranchId dispatching branch ID
 * @param targetBranchId receiving branch ID
 * @param productId transferred product ID
 * @param quantity units transferred
 * @param unitCost unit valuation cost
 * @param status lifecycle state ({@code REQUESTED}, {@code IN_TRANSIT}, {@code RECEIVED})
 * @param notes operational transfer notes
 * @param createdBy operator username/display name
 * @param createdAt creation timestamp
 * @param updatedAt last transition timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record StockTransfer(
        long id,
        String transferCode,
        long sourceBranchId,
        long targetBranchId,
        long productId,
        int quantity,
        double unitCost,
        String status,
        String notes,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
