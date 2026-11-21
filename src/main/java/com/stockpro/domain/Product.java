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
     *
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
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
     *
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
     */
    public double getUnitProfit() {
        return sellPrice - buyPrice;
    }

    /**
     * Calculates profit margin percentage relative to selling price.
     *
     * @return margin percentage in the range 0..100, or 0 if selling price is zero
     * @author Ehtisham Haider, Syed Ahmed
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
     * @author Ehtisham Haider, Syed Ahmed
     */
    public double getCostValuation() {
        return Math.max(0, quantity) * Math.max(0.0, buyPrice);
    }

    /**
     * Calculates total retail value of currently held sellable stock.
     *
     * @return quantity multiplied by unit sell price
     * @author Ehtisham Haider, Syed Ahmed
     */
    public double getRetailValuation() {
        return Math.max(0, quantity) * Math.max(0.0, sellPrice);
    }

    /** @return database row identifier @author Ehtisham Haider, Syed Ahmed */
    public long getId() {
        return id;
    }

    /** @param id database row identifier @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) {
        this.id = id;
    }

    /** @return unique product SKU code @author Ehtisham Haider, Syed Ahmed */
    public String getSku() {
        return sku;
    }

    /** @param sku unique product SKU code @author Ehtisham Haider, Syed Ahmed */
    public void setSku(String sku) {
        this.sku = sku;
    }

    /** @return scanned or generated barcode value @author Ehtisham Haider, Syed Ahmed */
    public String getBarcode() {
        return barcode;
    }

    /** @param barcode scanned or generated barcode value @author Ehtisham Haider, Syed Ahmed */
    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    /** @return product display name @author Ehtisham Haider, Syed Ahmed */
    public String getName() {
        return name;
    }

    /** @param name product display name @author Ehtisham Haider, Syed Ahmed */
    public void setName(String name) {
        this.name = name;
    }

    /** @return product category label @author Ehtisham Haider, Syed Ahmed */
    public String getCategory() {
        return category;
    }

    /** @param category product category label @author Ehtisham Haider, Syed Ahmed */
    public void setCategory(String category) {
        this.category = category;
    }

    /** @return comma-separated tags @author Ehtisham Haider, Syed Ahmed */
    public String getTags() {
        return tags;
    }

    /** @param tags comma-separated tags @author Ehtisham Haider, Syed Ahmed */
    public void setTags(String tags) {
        this.tags = tags;
    }

    /** @return sellable unit count @author Ehtisham Haider, Syed Ahmed */
    public int getQuantity() {
        return quantity;
    }

    /** @param quantity sellable unit count @author Ehtisham Haider, Syed Ahmed */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /** @return quarantined damaged unit count @author Ehtisham Haider, Syed Ahmed */
    public int getDamagedQty() {
        return damagedQty;
    }

    /** @param damagedQty quarantined damaged unit count @author Ehtisham Haider, Syed Ahmed */
    public void setDamagedQty(int damagedQty) {
        this.damagedQty = damagedQty;
    }

    /** @return low-stock warning threshold @author Ehtisham Haider, Syed Ahmed */
    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    /** @param lowStockThreshold low-stock warning threshold @author Ehtisham Haider, Syed Ahmed */
    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    /** @return unit purchase cost @author Ehtisham Haider, Syed Ahmed */
    public double getBuyPrice() {
        return buyPrice;
    }

    /** @param buyPrice unit purchase cost @author Ehtisham Haider, Syed Ahmed */
    public void setBuyPrice(double buyPrice) {
        this.buyPrice = buyPrice;
    }

    /** @return unit selling price @author Ehtisham Haider, Syed Ahmed */
    public double getSellPrice() {
        return sellPrice;
    }

    /** @param sellPrice unit selling price @author Ehtisham Haider, Syed Ahmed */
    public void setSellPrice(double sellPrice) {
        this.sellPrice = sellPrice;
    }

    /** @return aisle/shelf code @author Ehtisham Haider, Syed Ahmed */
    public String getShelfLocation() {
        return shelfLocation;
    }

    /** @param shelfLocation aisle/shelf code @author Ehtisham Haider, Syed Ahmed */
    public void setShelfLocation(String shelfLocation) {
        this.shelfLocation = shelfLocation;
    }

    /** @return optional expiration date @author Ehtisham Haider, Syed Ahmed */
    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    /** @param expiryDate optional expiration date @author Ehtisham Haider, Syed Ahmed */
    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    /** @return current stock status @author Ehtisham Haider, Syed Ahmed */
    public StockStatus getStatus() {
        return status;
    }

    /** @param status current stock status @author Ehtisham Haider, Syed Ahmed */
    public void setStatus(StockStatus status) {
        this.status = status;
    }

    /** @return {@code true} if status was manually overridden @author Ehtisham Haider, Syed Ahmed */
    public boolean isManualStatusOverride() {
        return manualStatusOverride;
    }

    /** @param manualStatusOverride {@code true} to preserve manual status @author Ehtisham Haider, Syed Ahmed */
    public void setManualStatusOverride(boolean manualStatusOverride) {
        this.manualStatusOverride = manualStatusOverride;
    }

    /** @return optional local path to product image @author Ehtisham Haider, Syed Ahmed */
    public String getPhotoPath() {
        return photoPath;
    }

    /** @param photoPath optional local path to product image @author Ehtisham Haider, Syed Ahmed */
    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    /** @return SKU of optional backup substitute product @author Ehtisham Haider, Syed Ahmed */
    public String getSubstituteSku() {
        return substituteSku;
    }

    /** @param substituteSku SKU of optional backup substitute product @author Ehtisham Haider, Syed Ahmed */
    public void setSubstituteSku(String substituteSku) {
        this.substituteSku = substituteSku;
    }

    /** @return {@code true} when product is discontinued/archived @author Ehtisham Haider, Syed Ahmed */
    public boolean isArchived() {
        return archived;
    }

    /** @param archived {@code true} when product is discontinued/archived @author Ehtisham Haider, Syed Ahmed */
    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** @return record creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** @param createdAt record creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** @return last update timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** @param updatedAt last update timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
