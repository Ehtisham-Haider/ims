package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Represents a single Bill-of-Materials (BOM) directed edge from a parent bundle/kit
 * to one required component product in a DAG structure.
 *
 * @param id database row identifier
 * @param bundleProductId parent bundle product ID
 * @param componentProductId required component product ID
 * @param componentSku resolved component SKU
 * @param componentName resolved component product name
 * @param componentQty units of component consumed per bundle unit
 * @param unitName unit label for component consumption
 * @param createdAt creation timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record BundleComponent(
        long id,
        long bundleProductId,
        long componentProductId,
        String componentSku,
        String componentName,
        int componentQty,
        String unitName,
        LocalDateTime createdAt) {
}
