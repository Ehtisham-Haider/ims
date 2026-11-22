package com.stockpro.domain;

/**
 * Supplier quantity-break pricing tier and Minimum Order Quantity (MOQ) record.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param id database tier row id
 * @param supplierId linked supplier primary key
 * @param productId linked product primary key
 * @param minQty minimum units needed to unlock this discounted price
 * @param moq supplier minimum order quantity floor
 * @param unitPrice discounted unit cost unlocked at {@code minQty}
 * @param updatedAt ISO-8601 timestamp
 */
public record VendorPriceTier(
        long id,
        long supplierId,
        long productId,
        int minQty,
        int moq,
        double unitPrice,
        String updatedAt
) {
}
