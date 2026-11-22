package com.stockpro.domain;

/**
 * Single line item inside a supplier purchase order.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class PurchaseOrderItem {
    private long productId;
    private String productSku;
    private String productName;
    private int quantity;
    private double unitCost;

    /**
     * Creates an empty purchase order line item.
     */
    public PurchaseOrderItem() {
    }

    /**
     * Creates a populated purchase order line item.
     *
     * @param productId product database id
     * @param productSku product SKU
     * @param productName product display name
     * @param quantity ordered quantity
     * @param unitCost unit buy price
     */
    public PurchaseOrderItem(long productId, String productSku, String productName, int quantity, double unitCost) {
        this.productId = productId;
        this.productSku = productSku;
        this.productName = productName;
        this.quantity = quantity;
        this.unitCost = unitCost;
    }

    /**
     * Calculates extended cost for this line item.
     *
     * @return quantity multiplied by unit cost
     */
    public double getLineTotal() {
        return Math.max(0, quantity) * Math.max(0.0, unitCost);
    }

    /**
     * Returns the product database identifier.
     *
     * @return product id
     */
    public long getProductId() { return productId; }

    /**
     * Sets the product database identifier.
     *
     * @param productId product id
     */
    public void setProductId(long productId) { this.productId = productId; }

    /**
     * Returns the product SKU.
     *
     * @return product SKU
     */
    public String getProductSku() { return productSku; }

    /**
     * Sets the product SKU.
     *
     * @param productSku product SKU
     */
    public void setProductSku(String productSku) { this.productSku = productSku; }

    /**
     * Returns the product display name.
     *
     * @return product name
     */
    public String getProductName() { return productName; }

    /**
     * Sets the product display name.
     *
     * @param productName product name
     */
    public void setProductName(String productName) { this.productName = productName; }

    /**
     * Returns the ordered quantity.
     *
     * @return ordered quantity
     */
    public int getQuantity() { return quantity; }

    /**
     * Sets the ordered quantity.
     *
     * @param quantity ordered quantity
     */
    public void setQuantity(int quantity) { this.quantity = quantity; }

    /**
     * Returns the unit buy cost.
     *
     * @return unit cost
     */
    public double getUnitCost() { return unitCost; }

    /**
     * Sets the unit buy cost.
     *
     * @param unitCost unit cost
     */
    public void setUnitCost(double unitCost) { this.unitCost = unitCost; }
}
