package com.stockpro.domain;

/**
 * Filter criteria applied to the Inventory catalog table and Smart Natural Search.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class InventoryFilterCriteria {
    /**
     * Quick status chip options displayed above the inventory table.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public enum StatusFilterChip {
        /** Display all non-archived items. */
        ALL,
        /** Filter to items marked In Stock. */
        IN_STOCK,
        /** Filter to items marked Low Stock. */
        LOW_STOCK,
        /** Filter to items marked Out of Stock. */
        OUT_OF_STOCK,
        /** Filter to items expiring within 30 days or already expired. */
        EXPIRING
    }

    private StatusFilterChip statusChip = StatusFilterChip.ALL;
    private String category = "All Categories";
    private String searchQuery = "";
    private Double maxSellPrice;

    /**
     * Creates default filter criteria showing all active products.
     */
    public InventoryFilterCriteria() {
    }

    /**
     * Returns the selected status filter chip.
     *
     * @return status filter chip
     */
    public StatusFilterChip getStatusChip() {
        return statusChip;
    }

    /**
     * Sets the active status filter chip.
     *
     * @param statusChip target status chip
     */
    public void setStatusChip(StatusFilterChip statusChip) {
        this.statusChip = statusChip != null ? statusChip : StatusFilterChip.ALL;
    }

    /**
     * Returns the active category filter label.
     *
     * @return category filter string
     */
    public String getCategory() {
        return category;
    }

    /**
     * Sets the active category filter label.
     *
     * @param category category filter string
     */
    public void setCategory(String category) {
        this.category = category != null && !category.isBlank() ? category : "All Categories";
    }

    /**
     * Returns the free-text search query.
     *
     * @return search query string
     */
    public String getSearchQuery() {
        return searchQuery;
    }

    /**
     * Sets the free-text search query.
     *
     * @param searchQuery search text
     */
    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery != null ? searchQuery.trim() : "";
    }

    /**
     * Returns optional maximum selling price filter ceiling.
     *
     * @return max sell price or {@code null} when unbounded
     */
    public Double getMaxSellPrice() {
        return maxSellPrice;
    }

    /**
     * Sets an optional maximum selling price ceiling.
     *
     * @param maxSellPrice price ceiling or {@code null}
     */
    public void setMaxSellPrice(Double maxSellPrice) {
        this.maxSellPrice = maxSellPrice;
    }
}
