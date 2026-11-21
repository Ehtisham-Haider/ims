package com.stockpro.domain;

/**
 * Classifies inventory quantity changes recorded in the stock movement ledger.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public enum MovementType {
    /** Incoming shipment or supplier restock increasing sellable inventory. */
    RESTOCK("Restock"),
    /** Outgoing customer sale reducing sellable inventory. */
    SALE("Sale"),
    /** Units moved into damaged/quarantined stock. */
    DAMAGED("Damaged"),
    /** Customer return restoring sellable units. */
    RETURNED("Returned"),
    /** Shrinkage or misplaced units deducted from stock. */
    LOST("Lost"),
    /** Reconciliation change produced by a physical shelf count. */
    PHYSICAL_COUNT("Physical Count"),
    /** Direct catalog edit or manual quantity adjustment. */
    ADJUSTMENT("Adjustment"),
    /** Initial stock recorded when a product is created. */
    INITIAL("Initial");

    private final String displayName;

    /**
     * Creates a stock movement type constant.
     *
     * @param displayName friendly operation label
     * @author Ehtisham Haider, Syed Ahmed
     */
    MovementType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the display label for tables and badges.
     *
     * @return movement type label
     * @author Ehtisham Haider, Syed Ahmed
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parses a movement type from storage or UI selection.
     *
     * @param value stored movement identifier
     * @return matching {@link MovementType}, or {@link #ADJUSTMENT} if unrecognized
     * @author Ehtisham Haider, Syed Ahmed
     */
    public static MovementType fromString(String value) {
        if (value == null || value.isBlank()) {
            return ADJUSTMENT;
        }
        for (MovementType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())
                    || type.displayName.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return ADJUSTMENT;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
