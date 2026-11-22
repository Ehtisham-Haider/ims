package com.stockpro.ui.controllers;

/**
 * Primary navigation destinations in the StockPro left sidebar.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public enum NavigationSection {
    /** Executive KPI dashboard and trends. */
    DASHBOARD("Dashboard"),
    /** Full inventory table, filters, and details drawer. */
    INVENTORY("Inventory"),
    /** Restock, sales, damaged, returned, lost, physical count, and audit trail. */
    STOCK_OPERATIONS("Stock Operations"),
    /** Interactive 2D store floorplan, shelf saturation meters, and shortest picking tour. */
    WAREHOUSE_MAP("Warehouse Map"),
    /** Supplier directory, purchase order builder, and finance engine. */
    SUPPLIERS_ORDERS("Suppliers & Orders"),
    /** Business analytics charts and PDF/Excel report exports. */
    ANALYTICS_REPORTS("Analytics & Reports"),
    /** Natural-language assistant, ML radar, and agent approval inbox. */
    AI_ASSISTANT("AI Assistant"),
    /** Store settings, staff roles, backups, telemetry, and viva test runner. */
    SETTINGS("Settings");

    private final String label;

    /**
     * Creates a navigation section enum constant.
     *
     * @param label display text rendered in the sidebar
     */
    NavigationSection(String label) {
        this.label = label;
    }

    /**
     * Returns the sidebar display label.
     *
     * @return navigation button text
     */
    public String getLabel() {
        return label;
    }
}
