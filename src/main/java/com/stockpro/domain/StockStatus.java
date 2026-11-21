package com.stockpro.domain;

/**
 * Represents the availability state of an inventory product.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public enum StockStatus {
    /** Product quantity is above its configured low-stock threshold. */
    IN_STOCK("In Stock"),
    /** Product quantity is positive but at or below its low-stock threshold. */
    LOW_STOCK("Low Stock"),
    /** Product has zero sellable units remaining. */
    OUT_OF_STOCK("Out of Stock");

    private final String displayName;

    /**
     * Creates a stock status constant with its human-readable label.
     *
     * @param displayName label displayed in tables, filters, and reports
     */
    StockStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the formatted label used in UI pills and exports.
     *
     * @return human-readable status label
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Resolves a {@link StockStatus} from either its enum name or display label.
     *
     * @param value text representation of the status
     * @return matching {@link StockStatus}, defaulting to {@link #IN_STOCK} when unrecognized
     */
    public static StockStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return IN_STOCK;
        }
        String normalized = value.trim();
        for (StockStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)
                    || status.displayName.equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return IN_STOCK;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
