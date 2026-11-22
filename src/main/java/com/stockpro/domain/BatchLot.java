package com.stockpro.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents an incoming supplier manufacturing batch/lot supporting recall tracing.
 *
 * @param id database row identifier
 * @param batchCode unique supplier lot code
 * @param productId linked catalog product ID
 * @param supplierId originating supplier ID (nullable)
 * @param quantityReceived total units received in this batch
 * @param expiryDate optional batch expiry date
 * @param recalled whether the batch is currently flagged for recall
 * @param recallReason explanation when flagged for recall
 * @param receivedAt timestamp of batch receipt
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record BatchLot(
        long id,
        String batchCode,
        long productId,
        Long supplierId,
        int quantityReceived,
        LocalDate expiryDate,
        boolean recalled,
        String recallReason,
        LocalDateTime receivedAt) {
}
