package com.stockpro.domain;

/**
 * Immutable warehouse shelf bin snapshot with 2D grid coordinates, load metrics,
 * and saturation helper calculations for the interactive store map.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param binCode unique shelf bin code such as {@code A1-01}
 * @param aisle warehouse aisle letter ({@code A} through {@code E})
 * @param rowIndex shelf row index ({@code 1} through {@code 4})
 * @param colIndex shelf bay column ({@code 1} through {@code 12})
 * @param maxWeightKg maximum safe weight load in kilograms
 * @param currentWeightKg currently stored weight in kilograms
 * @param maxVolumeDm3 maximum cubic volume capacity in cubic decimeters
 * @param currentVolumeDm3 currently occupied cubic volume in cubic decimeters
 * @param skuCount number of distinct SKUs assigned to this bin
 * @param totalUnits total unit count currently stocked in this bin
 */
public record ShelfBinRecord(
        String binCode,
        String aisle,
        int rowIndex,
        int colIndex,
        double maxWeightKg,
        double currentWeightKg,
        double maxVolumeDm3,
        double currentVolumeDm3,
        int skuCount,
        int totalUnits
) {
    /** Saturation alert threshold percentage at which shelf warnings trigger. */
    public static final double SATURATION_ALERT_PERCENT = 85.0;

    /**
     * Returns current weight utilization percentage capped smoothly above zero.
     *
     * @return weight utilization percentage
     */
    public double weightPercent() {
        return maxWeightKg <= 0.0 ? 0.0 : (currentWeightKg * 100.0) / maxWeightKg;
    }

    /**
     * Returns current volume utilization percentage capped smoothly above zero.
     *
     * @return volume utilization percentage
     */
    public double volumePercent() {
        return maxVolumeDm3 <= 0.0 ? 0.0 : (currentVolumeDm3 * 100.0) / maxVolumeDm3;
    }

    /**
     * Returns the governing utilization percentage between weight and volume constraints.
     *
     * @return maximum of {@link #weightPercent()} and {@link #volumePercent()}
     */
    public double maxUtilizationPercent() {
        return Math.max(weightPercent(), volumePercent());
    }

    /**
     * Indicates whether the shelf bin has reached or exceeded the 85% saturation threshold.
     *
     * @return {@code true} when weight or volume is at least {@code 85%}
     */
    public boolean isNearlyFull() {
        return maxUtilizationPercent() >= SATURATION_ALERT_PERCENT;
    }

    /**
     * Classifies bin load into visual heatmap tiers ({@code EMPTY}, {@code LOW}, {@code MODERATE}, {@code FULL}).
     *
     * @return heatmap tier name
     */
    public String capacityTier() {
        double pct = maxUtilizationPercent();
        if (pct >= SATURATION_ALERT_PERCENT) {
            return "FULL";
        }
        if (pct >= 50.0) {
            return "MODERATE";
        }
        if (pct > 0.0 || skuCount > 0) {
            return "LOW";
        }
        return "EMPTY";
    }
}
