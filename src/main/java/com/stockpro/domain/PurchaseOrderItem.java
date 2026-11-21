package com.stockpro.domain;

/**
 * Single line item inside a supplier purchase order.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class PurchaseOrderItem {
    private long productId;
    private String productSku;
    private String productName;
    private int quantity;
    private double unitCost;

    /**
     * Creates an empty purchase order line item.
     *
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
     */
    public double getLineTotal() {
        return Math.max(0, quantity) * Math.max(0.0, unitCost);
    }

    /** @return product id @author Ehtisham Haider, Syed Ahmed */
    public long getProductId() { return productId; }

    /** @param productId product id @author Ehtisham Haider, Syed Ahmed */
    public void setProductId(long productId) { this.productId = productId; }

    /** @return product SKU @author Ehtisham Haider, Syed Ahmed */
    public String getProductSku() { return productSku; }

    /** @param productSku product SKU @author Ehtisham Haider, Syed Ahmed */
    public void setProductSku(String productSku) { this.productSku = productSku; }

    /** @return product name @author Ehtisham Haider, Syed Ahmed */
    public String getProductName() { return productName; }

    /** @param productName product name @author Ehtisham Haider, Syed Ahmed */
    public void setProductName(String productName) { this.productName = productName; }

    /** @return ordered quantity @author Ehtisham Haider, Syed Ahmed */
    public int getQuantity() { return quantity; }

    /** @param quantity ordered quantity @author Ehtisham Haider, Syed Ahmed */
    public void setQuantity(int quantity) { this.quantity = quantity; }

    /** @return unit cost @author Ehtisham Haider, Syed Ahmed */
    public double getUnitCost() { return unitCost; }

    /** @param unitCost unit cost @author Ehtisham Haider, Syed Ahmed */
    public void setUnitCost(double unitCost) { this.unitCost = unitCost; }
}
