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
     */
    public StockMovement() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Returns the movement database identifier.
     *
     * @return movement database id
     */
    public long getId() { return id; }

    /**
     * Sets the movement database identifier.
     *
     * @param id movement database id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the referenced product identifier.
     *
     * @return referenced product id
     */
    public long getProductId() { return productId; }

    /**
     * Sets the referenced product identifier.
     *
     * @param productId referenced product id
     */
    public void setProductId(long productId) { this.productId = productId; }

    /**
     * Returns the product SKU snapshot.
     *
     * @return product SKU snapshot
     */
    public String getProductSku() { return productSku; }

    /**
     * Sets the product SKU snapshot.
     *
     * @param productSku product SKU snapshot
     */
    public void setProductSku(String productSku) { this.productSku = productSku; }

    /**
     * Returns the product name snapshot.
     *
     * @return product name snapshot
     */
    public String getProductName() { return productName; }

    /**
     * Sets the product name snapshot.
     *
     * @param productName product name snapshot
     */
    public void setProductName(String productName) { this.productName = productName; }

    /**
     * Returns the movement classification.
     *
     * @return movement classification
     */
    public MovementType getMovementType() { return movementType; }

    /**
     * Sets the movement classification.
     *
     * @param movementType movement classification
     */
    public void setMovementType(MovementType movementType) { this.movementType = movementType; }

    /**
     * Returns the signed quantity delta.
     *
     * @return signed quantity change
     */
    public int getQuantityDelta() { return quantityDelta; }

    /**
     * Sets the signed quantity delta.
     *
     * @param quantityDelta signed quantity change
     */
    public void setQuantityDelta(int quantityDelta) { this.quantityDelta = quantityDelta; }

    /**
     * Returns the stock quantity before the mutation.
     *
     * @return quantity before mutation
     */
    public int getPreviousQty() { return previousQty; }

    /**
     * Sets the stock quantity before the mutation.
     *
     * @param previousQty quantity before mutation
     */
    public void setPreviousQty(int previousQty) { this.previousQty = previousQty; }

    /**
     * Returns the stock quantity after the mutation.
     *
     * @return quantity after mutation
     */
    public int getNewQty() { return newQty; }

    /**
     * Sets the stock quantity after the mutation.
     *
     * @param newQty quantity after mutation
     */
    public void setNewQty(int newQty) { this.newQty = newQty; }

    /**
     * Returns the mandatory movement reason.
     *
     * @return mandatory movement reason
     */
    public String getReason() { return reason; }

    /**
     * Sets the mandatory movement reason.
     *
     * @param reason mandatory movement reason
     */
    public void setReason(String reason) { this.reason = reason; }

    /**
     * Returns the optional movement remarks.
     *
     * @return optional movement remarks
     */
    public String getNotes() { return notes; }

    /**
     * Sets the optional movement remarks.
     *
     * @param notes optional movement remarks
     */
    public void setNotes(String notes) { this.notes = notes; }

    /**
     * Returns the operator username or display name.
     *
     * @return username or display name of operator
     */
    public String getPerformedBy() { return performedBy; }

    /**
     * Sets the operator username or display name.
     *
     * @param performedBy username or display name of operator
     */
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    /**
     * Returns the timestamp when the movement was recorded.
     *
     * @return timestamp of movement
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Sets the timestamp when the movement was recorded.
     *
     * @param createdAt timestamp of movement
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
