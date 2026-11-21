package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Immutable ledger record capturing a single stock quantity mutation.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockMovement {
    private long id;
    private long productId;
    private String productSku;
    private String productName;
    private MovementType movementType;
    private int quantityDelta;
    private int previousQty;
    private int newQty;
    private String reason;
    private String notes;
    private String performedBy;
    private LocalDateTime createdAt;

    /**
     * Creates a new stock movement entry stamped with the current time.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public StockMovement() {
        this.createdAt = LocalDateTime.now();
    }

    /** @return movement database id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id movement database id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return referenced product id @author Ehtisham Haider, Syed Ahmed */
    public long getProductId() { return productId; }

    /** @param productId referenced product id @author Ehtisham Haider, Syed Ahmed */
    public void setProductId(long productId) { this.productId = productId; }

    /** @return product SKU snapshot @author Ehtisham Haider, Syed Ahmed */
    public String getProductSku() { return productSku; }

    /** @param productSku product SKU snapshot @author Ehtisham Haider, Syed Ahmed */
    public void setProductSku(String productSku) { this.productSku = productSku; }

    /** @return product name snapshot @author Ehtisham Haider, Syed Ahmed */
    public String getProductName() { return productName; }

    /** @param productName product name snapshot @author Ehtisham Haider, Syed Ahmed */
    public void setProductName(String productName) { this.productName = productName; }

    /** @return movement classification @author Ehtisham Haider, Syed Ahmed */
    public MovementType getMovementType() { return movementType; }

    /** @param movementType movement classification @author Ehtisham Haider, Syed Ahmed */
    public void setMovementType(MovementType movementType) { this.movementType = movementType; }

    /** @return signed quantity change @author Ehtisham Haider, Syed Ahmed */
    public int getQuantityDelta() { return quantityDelta; }

    /** @param quantityDelta signed quantity change @author Ehtisham Haider, Syed Ahmed */
    public void setQuantityDelta(int quantityDelta) { this.quantityDelta = quantityDelta; }

    /** @return quantity before mutation @author Ehtisham Haider, Syed Ahmed */
    public int getPreviousQty() { return previousQty; }

    /** @param previousQty quantity before mutation @author Ehtisham Haider, Syed Ahmed */
    public void setPreviousQty(int previousQty) { this.previousQty = previousQty; }

    /** @return quantity after mutation @author Ehtisham Haider, Syed Ahmed */
    public int getNewQty() { return newQty; }

    /** @param newQty quantity after mutation @author Ehtisham Haider, Syed Ahmed */
    public void setNewQty(int newQty) { this.newQty = newQty; }

    /** @return mandatory movement reason @author Ehtisham Haider, Syed Ahmed */
    public String getReason() { return reason; }

    /** @param reason mandatory movement reason @author Ehtisham Haider, Syed Ahmed */
    public void setReason(String reason) { this.reason = reason; }

    /** @return optional movement remarks @author Ehtisham Haider, Syed Ahmed */
    public String getNotes() { return notes; }

    /** @param notes optional movement remarks @author Ehtisham Haider, Syed Ahmed */
    public void setNotes(String notes) { this.notes = notes; }

    /** @return username or display name of operator @author Ehtisham Haider, Syed Ahmed */
    public String getPerformedBy() { return performedBy; }

    /** @param performedBy username or display name of operator @author Ehtisham Haider, Syed Ahmed */
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    /** @return timestamp of movement @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @param createdAt timestamp of movement @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
