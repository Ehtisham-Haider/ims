package com.stockpro.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplier purchase order tracking requested items, estimated cost, and fulfillment status.
 *
 * @author Ehtisham Haider, Syed Ahmed
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
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public PurchaseOrder() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Recomputes {@link #totalCost} from all attached line items.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public void recalculateTotal() {
        double sum = 0.0;
        for (PurchaseOrderItem item : items) {
            sum += item.getLineTotal();
        }
        this.totalCost = sum;
    }

    /** @return order id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id order id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return formatted PO number @author Ehtisham Haider, Syed Ahmed */
    public String getPoNumber() { return poNumber; }

    /** @param poNumber formatted PO number @author Ehtisham Haider, Syed Ahmed */
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }

    /** @return target supplier id @author Ehtisham Haider, Syed Ahmed */
    public long getSupplierId() { return supplierId; }

    /** @param supplierId target supplier id @author Ehtisham Haider, Syed Ahmed */
    public void setSupplierId(long supplierId) { this.supplierId = supplierId; }

    /** @return target supplier display name @author Ehtisham Haider, Syed Ahmed */
    public String getSupplierName() { return supplierName; }

    /** @param supplierName target supplier display name @author Ehtisham Haider, Syed Ahmed */
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    /** @return line items ordered @author Ehtisham Haider, Syed Ahmed */
    public List<PurchaseOrderItem> getItems() { return items; }

    /** @param items line items ordered @author Ehtisham Haider, Syed Ahmed */
    public void setItems(List<PurchaseOrderItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /** @return total order cost @author Ehtisham Haider, Syed Ahmed */
    public double getTotalCost() { return totalCost; }

    /** @param totalCost total order cost @author Ehtisham Haider, Syed Ahmed */
    public void setTotalCost(double totalCost) { this.totalCost = totalCost; }

    /** @return lifecycle status (Draft, Sent, Delivered) @author Ehtisham Haider, Syed Ahmed */
    public String getStatus() { return status; }

    /** @param status lifecycle status (Draft, Sent, Delivered) @author Ehtisham Haider, Syed Ahmed */
    public void setStatus(String status) { this.status = status; }

    /** @return creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @param createdAt creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** @return update timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** @param updatedAt update timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
