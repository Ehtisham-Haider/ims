package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Customer backorder waitlist entry created when a desired catalog item is out of stock.
 *
 * @param id database row identifier
 * @param productId requested product ID
 * @param customerName waiting customer name
 * @param customerContact phone or email contact
 * @param requestedQty units requested
 * @param status waitlist state ({@code WAITING}, {@code READY_NOTIFY}, {@code FULFILLED})
 * @param notified {@code true} once staff/customer restock notification has fired
 * @param createdAt creation timestamp
 * @param fulfilledAt fulfillment timestamp (nullable)
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record CustomerBackorder(
        long id,
        long productId,
        String customerName,
        String customerContact,
        int requestedQty,
        String status,
        boolean notified,
        LocalDateTime createdAt,
        LocalDateTime fulfilledAt) {
}
