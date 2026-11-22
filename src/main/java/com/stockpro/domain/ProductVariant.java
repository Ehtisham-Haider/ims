package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Represents a parent-child matrix variant option (size, color, flavor, and price adjustment)
 * linked to a configurable base catalog product.
 *
 * @param id database row identifier
 * @param parentProductId parent product ID
 * @param childProductId linked child SKU product ID (optional)
 * @param variantSku unique variant SKU code
 * @param variantName variant display label
 * @param color optional color option
 * @param size optional size option
 * @param flavor optional flavor option
 * @param sellPriceDelta unit sell price adjustment relative to the parent item
 * @param createdAt creation timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record ProductVariant(
        long id,
        long parentProductId,
        Long childProductId,
        String variantSku,
        String variantName,
        String color,
        String size,
        String flavor,
        double sellPriceDelta,
        LocalDateTime createdAt) {
}
