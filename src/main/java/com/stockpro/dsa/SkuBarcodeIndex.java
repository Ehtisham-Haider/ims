package com.stockpro.dsa;

import com.stockpro.domain.Product;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Provides O(1) exact HashMap lookup and duplicate checks across product SKUs and barcodes.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SkuBarcodeIndex {
    private final Map<String, Product> skuMap = new HashMap<>();
    private final Map<String, Product> barcodeMap = new HashMap<>();

    /**
     * Creates an empty SKU and barcode index.
     */
    public SkuBarcodeIndex() {
    }

    /**
     * Rebuilds the O(1) maps from a collection of active products.
     *
     * @param products catalog items to index
     */
    public synchronized void rebuild(Collection<Product> products) {
        skuMap.clear();
        barcodeMap.clear();
        if (products == null) {
            return;
        }
        for (Product p : products) {
            indexProduct(p);
        }
    }

    /**
     * Adds or updates a single product in the SKU and barcode hash maps.
     *
     * @param product product to index
     */
    public synchronized void indexProduct(Product product) {
        if (product == null) {
            return;
        }
        if (product.getSku() != null && !product.getSku().isBlank()) {
            skuMap.put(normalize(product.getSku()), product);
        }
        if (product.getBarcode() != null && !product.getBarcode().isBlank()) {
            barcodeMap.put(normalize(product.getBarcode()), product);
        }
    }

    /**
     * Looks up a product by exact SKU in O(1) average time.
     *
     * @param sku SKU code
     * @return {@link Optional} containing the matched product
     */
    public synchronized Optional<Product> findBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skuMap.get(normalize(sku)));
    }

    /**
     * Looks up a product by exact barcode (falling back to SKU map) in O(1) average time.
     *
     * @param barcode barcode or SKU text
     * @return {@link Optional} containing the matched product
     */
    public synchronized Optional<Product> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        String key = normalize(barcode);
        Product hit = barcodeMap.get(key);
        if (hit == null) {
            hit = skuMap.get(key);
        }
        return Optional.ofNullable(hit);
    }

    /**
     * Checks whether a SKU is already assigned in the catalog (duplicate guard).
     *
     * @param sku candidate SKU string
     * @return {@code true} if the SKU already exists
     */
    public synchronized boolean containsSku(String sku) {
        return sku != null && !sku.isBlank() && skuMap.containsKey(normalize(sku));
    }

    /**
     * Checks whether a barcode is already assigned in the catalog.
     *
     * @param barcode candidate barcode string
     * @return {@code true} if the barcode already exists
     */
    public synchronized boolean containsBarcode(String barcode) {
        return barcode != null && !barcode.isBlank() && barcodeMap.containsKey(normalize(barcode));
    }

    /**
     * Returns the count of indexed SKUs.
     *
     * @return indexed SKU count
     */
    public synchronized int size() {
        return skuMap.size();
    }

    /**
     * Normalizes keys to trimmed uppercase for case-insensitive matching.
     *
     * @param key raw key string
     * @return normalized key
     */
    private String normalize(String key) {
        return key.trim().toUpperCase(Locale.ROOT);
    }
}
