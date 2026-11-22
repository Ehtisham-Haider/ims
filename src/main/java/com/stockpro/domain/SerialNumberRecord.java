package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Tracks an individual factory serial number for high-value catalog hardware
 * along with its current lifecycle status and optional supplier batch reference.
 *
 * @param id database row identifier
 * @param productId owning catalog product ID
 * @param serialCode unique hardware serial string
 * @param batchCode supplier batch/lot identifier
 * @param status lifecycle state ({@code IN_STOCK}, {@code SOLD}, {@code RMA_RETURNED}, {@code DEFECTIVE})
 * @param branchId branch holding or having shipped the unit
 * @param notes audit notes
 * @param updatedAt last state update timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record SerialNumberRecord(
        long id,
        long productId,
        String serialCode,
        String batchCode,
        String status,
        long branchId,
        String notes,
        LocalDateTime updatedAt) {
}
