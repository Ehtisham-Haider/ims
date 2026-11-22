package com.stockpro.domain;

/**
 * Defines staff access levels within StockPro.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public enum UserRole {
    /** Full administrative control including settings, staff management, and approvals. */
    OWNER("Owner"),
    /** Operational management over inventory, stock movements, and purchase orders. */
    MANAGER("Manager"),
    /** Frontline point-of-sale access for stock lookups and sales recording. */
    CASHIER("Cashier");

    private final String displayName;

    /**
     * Creates a role enum constant.
     *
     * @param displayName friendly role label shown in the login form and sidebar
     */
    UserRole(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the display label of the role.
     *
     * @return formatted role name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Indicates whether the role may modify settings, delete items, or approve agent proposals.
     *
     * @return {@code true} for Owner and Manager roles
     */
    public boolean canManageInventory() {
        return this == OWNER || this == MANAGER;
    }

    /**
     * Indicates whether the role holds full administrative privileges.
     *
     * @return {@code true} only for {@link #OWNER}
     */
    public boolean isAdmin() {
        return this == OWNER;
    }

    /**
     * Parses a role string safely from persistence or UI input.
     *
     * @param value role name or label
     * @return resolved {@link UserRole}, defaulting to {@link #CASHIER}
     */
    public static UserRole fromString(String value) {
        if (value == null || value.isBlank()) {
            return CASHIER;
        }
        String normalized = value.trim();
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(normalized)
                    || role.displayName.equalsIgnoreCase(normalized)) {
                return role;
            }
        }
        return CASHIER;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
