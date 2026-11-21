package com.stockpro.dsa;

import com.stockpro.domain.Product;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Red-Black Tree ({@link TreeMap}) multi-index supporting fast sorted range queries over selling prices,
 * stock quantities, and expiration dates in O(log n + k) time.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class RangeIndexTree {
    private final TreeMap<Double, List<Product>> byPrice = new TreeMap<>();
    private final TreeMap<Integer, List<Product>> byQuantity = new TreeMap<>();
    private final TreeMap<LocalDate, List<Product>> byExpiry = new TreeMap<>();

    /**
     * Creates an empty Red-Black Tree range index.
     */
    public RangeIndexTree() {
    }

    /**
     * Rebuilds all three sorted Red-Black Tree indices from active catalog items.
     *
     * @param products catalog items to index
     */
    public synchronized void rebuild(Collection<Product> products) {
        byPrice.clear();
        byQuantity.clear();
        byExpiry.clear();
        if (products == null) {
            return;
        }
        for (Product p : products) {
            byPrice.computeIfAbsent(p.getSellPrice(), k -> new ArrayList<>()).add(p);
            byQuantity.computeIfAbsent(p.getQuantity(), k -> new ArrayList<>()).add(p);
            if (p.getExpiryDate() != null) {
                byExpiry.computeIfAbsent(p.getExpiryDate(), k -> new ArrayList<>()).add(p);
            }
        }
    }

    /**
     * Returns products whose selling price lies inside {@code [minPrice, maxPrice]} inclusive.
     *
     * @param minPrice inclusive minimum sell price
     * @param maxPrice inclusive maximum sell price
     * @return matching products sorted ascending by price
     */
    public synchronized List<Product> findByPriceRange(double minPrice, double maxPrice) {
        if (maxPrice < minPrice) {
            return List.of();
        }
        NavigableMap<Double, List<Product>> sub = byPrice.subMap(minPrice, true, maxPrice, true);
        return flatten(sub.values());
    }

    /**
     * Returns products whose stock quantity lies inside {@code [minQty, maxQty]} inclusive.
     *
     * @param minQty inclusive minimum quantity
     * @param maxQty inclusive maximum quantity
     * @return matching products sorted ascending by quantity
     */
    public synchronized List<Product> findByQuantityRange(int minQty, int maxQty) {
        if (maxQty < minQty) {
            return List.of();
        }
        NavigableMap<Integer, List<Product>> sub = byQuantity.subMap(minQty, true, maxQty, true);
        return flatten(sub.values());
    }

    /**
     * Returns products expiring inside {@code [startDate, endDate]} inclusive.
     *
     * @param startDate inclusive start date
     * @param endDate inclusive end date
     * @return matching products ordered chronologically by expiry date
     */
    public synchronized List<Product> findByExpiryRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            return List.of();
        }
        NavigableMap<LocalDate, List<Product>> sub = byExpiry.subMap(startDate, true, endDate, true);
        return flatten(sub.values());
    }

    /**
     * Flattens bucket lists into a single result list.
     *
     * @param buckets nested product buckets
     * @return flattened product list
     */
    private List<Product> flatten(Collection<List<Product>> buckets) {
        List<Product> out = new ArrayList<>();
        for (List<Product> bucket : buckets) {
            out.addAll(bucket);
        }
        return out;
    }
}
