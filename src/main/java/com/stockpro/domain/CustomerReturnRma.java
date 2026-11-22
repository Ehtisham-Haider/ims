package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Customer Return Merchandise Authorization (RMA) record capturing return disposition and financial refund.
 *
 * @param id database row identifier
 * @param rmaCode unique RMA code (e.g., {@code RMA-1001})
 * @param productId returned product ID
 * @param branchId branch receiving the customer return
 * @param quantity returned unit count
 * @param disposition disposition outcome ({@code RESTOCK_SELLABLE}, {@code MARK_DAMAGED}, {@code SCRAP})
 * @param reason customer return reason
 * @param refundAmount calculated refund valuation
 * @param processedBy operator display name
 * @param createdAt creation timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record CustomerReturnRma(
        long id,
        String rmaCode,
        long productId,
        long branchId,
        int quantity,
        String disposition,
        String reason,
        double refundAmount,
        String processedBy,
        LocalDateTime createdAt) {
}
