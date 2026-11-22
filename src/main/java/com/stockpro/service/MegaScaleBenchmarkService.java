package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.dsa.LruProductCache;
import com.stockpro.dsa.ProductTrie;
import com.stockpro.dsa.SkuBarcodeIndex;
import com.stockpro.repository.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mega-scale performance service providing SQLite indexed {@code LIMIT/OFFSET} cursor pagination,
 * hot LRU cache acceleration, and an isolated 100,000-SKU search &amp; memory benchmark suite
 * proving sub-50ms query responsiveness without mutating production tables.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class MegaScaleBenchmarkService {

    /**
     * Immutable paginated SQLite catalog slice.
     *
     * @param pageIndex zero-based page number
     * @param pageSize requested page size
     * @param totalItems total matching rows in SQLite
     * @param totalPages total computed page count
     * @param items hydrated products belonging to this page
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record PagedCatalogResult(
            int pageIndex,
            int pageSize,
            int totalItems,
            int totalPages,
            List<Product> items
    ) {
        /**
         * Canonical constructor guarding against null lists.
         */
        public PagedCatalogResult {
            items = items == null ? List.of() : Collections.unmodifiableList(items);
        }
    }

    /**
     * Result metrics from an isolated 100,000-SKU index &amp; search benchmark.
     *
     * @param totalIndexedSkus total synthetic SKUs indexed (default 100,000)
     * @param indexBuildTimeMs total time to index SKUs and prefixes in milliseconds
     * @param coldSearchMs cold index search latency across 100k SKUs in milliseconds
     * @param cachedLookupMs warm LRU cache hit latency in milliseconds
     * @param cacheHitRatePercent measured LRU cache hit percentage
     * @param heapUsedMb approximate JVM heap footprint after indexing in megabytes
     * @param sub50msPassed {@code true} when search latency stays strictly under 50 ms
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record MegaScaleBenchmarkResult(
            int totalIndexedSkus,
            double indexBuildTimeMs,
            double coldSearchMs,
            double cachedLookupMs,
            double cacheHitRatePercent,
            long heapUsedMb,
            boolean sub50msPassed
    ) {
    }

    private final DatabaseManager dbManager;
    private final LruProductCache<String, Product> productCache;

    /**
     * Constructs the mega-scale pagination and benchmark service.
     *
     * @param dbManager SQLite connection manager
     * @param productCache shared bounded LRU product cache
     */
    public MegaScaleBenchmarkService(DatabaseManager dbManager, LruProductCache<String, Product> productCache) {
        this.dbManager = dbManager;
        this.productCache = productCache;
    }

    /**
     * Executes an indexed SQLite {@code LIMIT/OFFSET} page query for responsive large-catalog browsing.
     *
     * @param pageIndex zero-based page index
     * @param pageSize rows per page (clamped {@code 1..500})
     * @param includeArchived {@code true} to include archived rows
     * @return {@link PagedCatalogResult} containing hydrated page slice
     */
    public PagedCatalogResult fetchPage(int pageIndex, int pageSize, boolean includeArchived) {
        int safeIndex = Math.max(0, pageIndex);
        int safeSize = Math.max(1, Math.min(500, pageSize));
        String where = includeArchived ? "" : " WHERE archived = 0";

        int total = 0;
        String countSql = "SELECT COUNT(*) FROM products" + where;
        String dataSql = "SELECT * FROM products" + where + " ORDER BY id ASC LIMIT ? OFFSET ?";
        List<Product> pageItems = new ArrayList<>();

        try (Connection conn = dbManager.getConnection()) {
            try (PreparedStatement countPs = conn.prepareStatement(countSql);
                 ResultSet crs = countPs.executeQuery()) {
                if (crs.next()) {
                    total = crs.getInt(1);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(dataSql)) {
                ps.setInt(1, safeSize);
                ps.setInt(2, safeIndex * safeSize);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Product p = mapProductRow(rs);
                        pageItems.add(p);
                        if (p.getSku() != null) {
                            productCache.put(p.getSku(), p);
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed paginated catalog fetch", ex);
        }

        int totalPages = total == 0 ? 1 : (int) Math.ceil(total / (double) safeSize);
        return new PagedCatalogResult(safeIndex, safeSize, total, totalPages, pageItems);
    }

    /**
     * Benchmarks 100,000-SKU indexing, exact HashMap + Trie lookup latency, LRU cache warming,
     * and JVM memory usage in isolation (never modifying production SQLite tables).
     *
     * @param skuCount number of synthetic SKUs to synthesize (minimum 1,000; standard 100,000)
     * @return {@link MegaScaleBenchmarkResult} telemetry snapshot
     */
    public MegaScaleBenchmarkResult benchmarkScaleSearch(int skuCount) {
        int count = Math.max(1000, skuCount);
        List<Product> synthetic = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String code = "MEGA-" + String.format("%06d", i);
            Product p = new Product("Mega Item #" + i, (i % 40) + 1, StockStatus.IN_STOCK);
            p.setId(i + 1L);
            p.setSku(code);
            p.setBarcode(code);
            p.setCategory("Benchmark");
            p.setSellPrice(9.99);
            synthetic.add(p);
        }

        SkuBarcodeIndex skuIndex = new SkuBarcodeIndex();
        ProductTrie trie = new ProductTrie();
        LruProductCache<String, Product> benchCache = new LruProductCache<>(500);

        long buildStart = System.nanoTime();
        skuIndex.rebuild(synthetic);
        trie.rebuild(synthetic);
        double buildMs = round2((System.nanoTime() - buildStart) / 1_000_000.0);

        String targetKey = "MEGA-" + String.format("%06d", count - 1);
        long coldStart = System.nanoTime();
        Product found = skuIndex.findByBarcode(targetKey).orElse(null);
        List<Product> prefixHits = trie.autocomplete("MEGA-099", 10);
        double coldMs = round3((System.nanoTime() - coldStart) / 1_000_000.0);

        if (found != null) {
            benchCache.put(targetKey, found);
        }
        for (Product hit : prefixHits) {
            benchCache.put(hit.getSku(), hit);
        }

        long warmStart = System.nanoTime();
        for (int r = 0; r < 100; r++) {
            benchCache.get(targetKey);
        }
        double warmMs = round3(((System.nanoTime() - warmStart) / 1_000_000.0) / 100.0);

        Runtime rt = Runtime.getRuntime();
        long usedMb = Math.max(1L, (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
        boolean sub50 = coldMs < 50.0 && warmMs < 5.0;

        return new MegaScaleBenchmarkResult(
                count,
                buildMs,
                coldMs,
                warmMs,
                benchCache.getHitRatePercent(),
                usedMb,
                sub50
        );
    }

    private static Product mapProductRow(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getLong("id"));
        p.setSku(rs.getString("sku"));
        p.setBarcode(rs.getString("barcode"));
        p.setName(rs.getString("name"));
        p.setCategory(rs.getString("category"));
        p.setTags(rs.getString("tags"));
        p.setQuantity(rs.getInt("quantity"));
        p.setDamagedQty(rs.getInt("damaged_qty"));
        p.setLowStockThreshold(rs.getInt("low_stock_threshold"));
        p.setBuyPrice(rs.getDouble("buy_price"));
        p.setSellPrice(rs.getDouble("sell_price"));
        p.setShelfLocation(rs.getString("shelf_location"));
        String exp = rs.getString("expiry_date");
        if (exp != null && !exp.isBlank()) {
            p.setExpiryDate(LocalDate.parse(exp));
        }
        p.setStatus(StockStatus.fromString(rs.getString("status")));
        p.setManualStatusOverride(rs.getInt("manual_status_override") == 1);
        p.setPhotoPath(rs.getString("photo_path"));
        p.setSubstituteSku(rs.getString("substitute_sku"));
        p.setArchived(rs.getInt("archived") == 1);
        String created = rs.getString("created_at");
        if (created != null && !created.isBlank()) {
            p.setCreatedAt(LocalDateTime.parse(created));
        }
        String updated = rs.getString("updated_at");
        if (updated != null && !updated.isBlank()) {
            p.setUpdatedAt(LocalDateTime.parse(updated));
        }
        return p;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
