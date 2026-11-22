package com.stockpro.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplier purchase order tracking requested items, estimated cost, and fulfillment status.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class PurchaseOrder {
    private long id;
    private String poNumber;
    private long supplierId;
    private String supplierName;
    private List<PurchaseOrderItem> items = new ArrayList<>();
    private double totalCost;
    private String status = "Draft";
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Creates a purchase order with default Draft status and current timestamps.
     */
    public PurchaseOrder() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Recomputes {@link #totalCost} from all attached line items.
     */
    public void recalculateTotal() {
        double sum = 0.0;
        for (PurchaseOrderItem item : items) {
            sum += item.getLineTotal();
        }
        this.totalCost = sum;
    }

    /**
     * Returns the purchase order database identifier.
     *
     * @return order id
     */
    public long getId() { return id; }

    /**
     * Sets the purchase order database identifier.
     *
     * @param id order id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the formatted purchase order number.
     *
     * @return formatted PO number
     */
    public String getPoNumber() { return poNumber; }

    /**
     * Sets the formatted purchase order number.
     *
     * @param poNumber formatted PO number
     */
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }

    /**
     * Returns the target supplier identifier.
     *
     * @return target supplier id
     */
    public long getSupplierId() { return supplierId; }

    /**
     * Sets the target supplier identifier.
     *
     * @param supplierId target supplier id
     */
    public void setSupplierId(long supplierId) { this.supplierId = supplierId; }

    /**
     * Returns the target supplier display name.
     *
     * @return target supplier display name
     */
    public String getSupplierName() { return supplierName; }

    /**
     * Sets the target supplier display name.
     *
     * @param supplierName target supplier display name
     */
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    /**
     * Returns the ordered line items.
     *
     * @return line items ordered
     */
    public List<PurchaseOrderItem> getItems() { return items; }

    /**
     * Sets the ordered line items.
     *
     * @param items line items ordered
     */
    public void setItems(List<PurchaseOrderItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /**
     * Returns the total order cost.
     *
     * @return total order cost
     */
    public double getTotalCost() { return totalCost; }

    /**
     * Sets the total order cost.
     *
     * @param totalCost total order cost
     */
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }

    /**
     * Returns the lifecycle status.
     *
     * @return lifecycle status (Draft, Sent, Delivered)
     */
    public String getStatus() { return status; }

    /**
     * Sets the lifecycle status.
     *
     * @param status lifecycle status (Draft, Sent, Delivered)
     */
    public void setStatus(String status) { this.status = status; }

    /**
     * Returns the creation timestamp.
     *
     * @return creation timestamp
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt creation timestamp
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Returns the last update timestamp.
     *
     * @return update timestamp
     */
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt update timestamp
     */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
