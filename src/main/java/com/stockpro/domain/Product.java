package com.stockpro.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Core inventory product entity representing a single SKU in the store catalog.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class Product {
    private long id;
    private String sku;
    private String barcode;
    private String name;
    private String category;
    private String tags;
    private int quantity;
    private int damagedQty;
    private int lowStockThreshold = 10;
    private double buyPrice;
    private double sellPrice;
    private String shelfLocation;
    private LocalDate expiryDate;
    private StockStatus status = StockStatus.IN_STOCK;
    private boolean manualStatusOverride;
    private String photoPath;
    private String substituteSku;
    private boolean archived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Creates an empty product instance with default threshold and timestamps.
     */
    public Product() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Creates a product with core required lab fields and default pricing/category metadata.
     *
     * @param name display name of the item
     * @param quantity current sellable stock quantity
     * @param status initial stock status
     */
    public Product(String name, int quantity, StockStatus status) {
        this();
        this.name = name;
        this.quantity = quantity;
        this.status = status != null ? status : computeAutomaticStatus();
    }

    /**
     * Creates a full copy of an existing product for safe editing or undo snapshots.
     *
     * @param source product instance to clone
     * @return deep value copy of the source product
     */
    public static Product copyOf(Product source) {
        Product copy = new Product();
        copy.id = source.id;
        copy.sku = source.sku;
        copy.barcode = source.barcode;
        copy.name = source.name;
        copy.category = source.category;
        copy.tags = source.tags;
        copy.quantity = source.quantity;
        copy.damagedQty = source.damagedQty;
        copy.lowStockThreshold = source.lowStockThreshold;
        copy.buyPrice = source.buyPrice;
        copy.sellPrice = source.sellPrice;
        copy.shelfLocation = source.shelfLocation;
        copy.expiryDate = source.expiryDate;
        copy.status = source.status;
        copy.manualStatusOverride = source.manualStatusOverride;
        copy.photoPath = source.photoPath;
        copy.substituteSku = source.substituteSku;
        copy.archived = source.archived;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        return copy;
    }

    /**
     * Computes the stock status solely from current quantity and low-stock threshold.
     *
     * @return {@link StockStatus#OUT_OF_STOCK} when quantity &lt;= 0,
     *         {@link StockStatus#LOW_STOCK} when quantity &lt;= threshold, otherwise {@link StockStatus#IN_STOCK}
     */
    public StockStatus computeAutomaticStatus() {
        if (quantity <= 0) {
            return StockStatus.OUT_OF_STOCK;
        }
        if (quantity <= Math.max(1, lowStockThreshold)) {
            return StockStatus.LOW_STOCK;
        }
        return StockStatus.IN_STOCK;
    }

    /**
     * Synchronizes {@link #status} automatically unless manual override is enabled.
     */
    public void refreshStatusIfAutomatic() {
        if (!manualStatusOverride) {
            this.status = computeAutomaticStatus();
        }
    }

    /**
     * Calculates profit earned per sold unit.
     *
     * @return difference between selling price and buying cost
     */
    public double getUnitProfit() {
        return sellPrice - buyPrice;
    }

    /**
     * Calculates profit margin percentage relative to selling price.
     *
     * @return margin percentage in the range 0..100, or 0 if selling price is zero
     */
    public double getMarginPercent() {
        if (sellPrice <= 0.0) {
            return 0.0;
        }
        return ((sellPrice - buyPrice) / sellPrice) * 100.0;
    }

    /**
     * Calculates total cost value of currently held sellable stock.
     *
     * @return quantity multiplied by unit buy price
     */
    public double getCostValuation() {
        return Math.max(0, quantity) * Math.max(0.0, buyPrice);
    }

    /**
     * Calculates total retail value of currently held sellable stock.
     *
     * @return quantity multiplied by unit sell price
     */
    public double getRetailValuation() {
        return Math.max(0, quantity) * Math.max(0.0, sellPrice);
    }

    /**
     * Returns the database row identifier.
     *
     * @return database row identifier
     */
    public long getId() {
        return id;
    }

    /**
     * Sets the database row identifier.
     *
     * @param id database row identifier
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * Returns the unique product SKU code.
     *
     * @return unique product SKU code
     */
    public String getSku() {
        return sku;
    }

    /**
     * Sets the unique product SKU code.
     *
     * @param sku unique product SKU code
     */
    public void setSku(String sku) {
        this.sku = sku;
    }

    /**
     * Returns the scanned or generated barcode value.
     *
     * @return scanned or generated barcode value
     */
    public String getBarcode() {
        return barcode;
    }

    /**
     * Sets the scanned or generated barcode value.
     *
     * @param barcode scanned or generated barcode value
     */
    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    /**
     * Returns the product display name.
     *
     * @return product display name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the product display name.
     *
     * @param name product display name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the product category label.
     *
     * @return product category label
     */
    public String getCategory() {
        return category;
    }

    /**
     * Sets the product category label.
     *
     * @param category product category label
     */
    public void setCategory(String category) {
        this.category = category;
    }

    /**
     * Returns comma-separated product tags.
     *
     * @return comma-separated tags
     */
    public String getTags() {
        return tags;
    }

    /**
     * Sets comma-separated product tags.
     *
     * @param tags comma-separated tags
     */
    public void setTags(String tags) {
        this.tags = tags;
    }

    /**
     * Returns the sellable unit count.
     *
     * @return sellable unit count
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * Sets the sellable unit count.
     *
     * @param quantity sellable unit count
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /**
     * Returns the quarantined damaged unit count.
     *
     * @return quarantined damaged unit count
     */
    public int getDamagedQty() {
        return damagedQty;
    }

    /**
     * Sets the quarantined damaged unit count.
     *
     * @param damagedQty quarantined damaged unit count
     */
    public void setDamagedQty(int damagedQty) {
        this.damagedQty = damagedQty;
    }

    /**
     * Returns the low-stock warning threshold.
     *
     * @return low-stock warning threshold
     */
    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    /**
     * Sets the low-stock warning threshold.
     *
     * @param lowStockThreshold low-stock warning threshold
     */
    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    /**
     * Returns the unit purchase cost.
     *
     * @return unit purchase cost
     */
    public double getBuyPrice() {
        return buyPrice;
    }

    /**
     * Sets the unit purchase cost.
     *
     * @param buyPrice unit purchase cost
     */
    public void setBuyPrice(double buyPrice) {
        this.buyPrice = buyPrice;
    }

    /**
     * Returns the unit selling price.
     *
     * @return unit selling price
     */
    public double getSellPrice() {
        return sellPrice;
    }

    /**
     * Sets the unit selling price.
     *
     * @param sellPrice unit selling price
     */
    public void setSellPrice(double sellPrice) {
        this.sellPrice = sellPrice;
    }

    /**
     * Returns the aisle or shelf code.
     *
     * @return aisle/shelf code
     */
    public String getShelfLocation() {
        return shelfLocation;
    }

    /**
     * Sets the aisle or shelf code.
     *
     * @param shelfLocation aisle/shelf code
     */
    public void setShelfLocation(String shelfLocation) {
        this.shelfLocation = shelfLocation;
    }

    /**
     * Returns the optional expiration date.
     *
     * @return optional expiration date
     */
    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    /**
     * Sets the optional expiration date.
     *
     * @param expiryDate optional expiration date
     */
    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    /**
     * Returns the current stock status.
     *
     * @return current stock status
     */
    public StockStatus getStatus() {
        return status;
    }

    /**
     * Sets the current stock status.
     *
     * @param status current stock status
     */
    public void setStatus(StockStatus status) {
        this.status = status;
    }

    /**
     * Indicates whether status was manually overridden.
     *
     * @return {@code true} if status was manually overridden
     */
    public boolean isManualStatusOverride() {
        return manualStatusOverride;
    }

    /**
     * Sets whether manual stock status override is enabled.
     *
     * @param manualStatusOverride {@code true} to preserve manual status
     */
    public void setManualStatusOverride(boolean manualStatusOverride) {
        this.manualStatusOverride = manualStatusOverride;
    }

    /**
     * Returns the optional local path to the product image.
     *
     * @return optional local path to product image
     */
    public String getPhotoPath() {
        return photoPath;
    }

    /**
     * Sets the optional local path to the product image.
     *
     * @param photoPath optional local path to product image
     */
    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    /**
     * Returns the SKU of the optional backup substitute product.
     *
     * @return SKU of optional backup substitute product
     */
    public String getSubstituteSku() {
        return substituteSku;
    }

    /**
     * Sets the SKU of the optional backup substitute product.
     *
     * @param substituteSku SKU of optional backup substitute product
     */
    public void setSubstituteSku(String substituteSku) {
        this.substituteSku = substituteSku;
    }

    /**
     * Indicates whether the product is discontinued or archived.
     *
     * @return {@code true} when product is discontinued/archived
     */
    public boolean isArchived() {
        return archived;
    }

    /**
     * Marks the product as discontinued or active.
     *
     * @param archived {@code true} when product is discontinued/archived
     */
    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /**
     * Returns the record creation timestamp.
     *
     * @return record creation timestamp
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the record creation timestamp.
     *
     * @param createdAt record creation timestamp
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the last update timestamp.
     *
     * @return last update timestamp
     */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt last update timestamp
     */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
