package com.stockpro.domain;

/**
 * Single ordered stop inside an optimized warehouse picking tour.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param stepNumber 1-based stop order in the route
 * @param binCode normalized bin coordinate such as {@code B2-04}
 * @param aisle warehouse aisle letter
 * @param rowIndex aisle row number ({@code 1} to {@code 4})
 * @param colIndex shelf column bay ({@code 1} to {@code 12})
 * @param productId target product primary key
 * @param sku product SKU code
 * @param productName product display name
 * @param pickQuantity unit quantity to collect at this stop
 * @param legDistanceMeters walking distance in meters from previous stop
 * @param cumulativeDistanceMeters total walking distance in meters up to this stop
 * @param instruction clear navigation instruction for picker staff
 */
public record PickRouteStep(
        int stepNumber,
        String binCode,
        String aisle,
        int rowIndex,
        int colIndex,
        long productId,
        String sku,
        String productName,
        int pickQuantity,
        double legDistanceMeters,
        double cumulativeDistanceMeters,
        String instruction
) {
}
