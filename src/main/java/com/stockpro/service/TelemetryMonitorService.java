package com.stockpro.service;

import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.repository.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Collects live application speed, memory, search latency, and SQLite storage health telemetry.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class TelemetryMonitorService {
    private final DatabaseManager dbManager;
    private final CatalogSearchEngine searchEngine;

    /**
     * Real-time telemetry snapshot displayed in the Speed &amp; Health Monitor card.
     *
     * @param usedHeapMb currently allocated heap memory in MB
     * @param maxHeapMb maximum JVM heap memory in MB
     * @param trieLatencyMs Compressed Trie autocomplete latency in milliseconds
     * @param hashMapLatencyMs HashMap O(1) SKU lookup latency in milliseconds
     * @param dbSizeKb SQLite file size on disk in KB
     * @param productCount active catalog item count
     * @param movementCount stock movement row count
     * @param supplierCount supplier row count
     * @param auditCount audit log row count
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record TelemetrySnapshot(
            long usedHeapMb,
            long maxHeapMb,
            double trieLatencyMs,
            double hashMapLatencyMs,
            long dbSizeKb,
            int productCount,
            int movementCount,
            int supplierCount,
            int auditCount
    ) {
    }

    /**
     * Constructs a telemetry monitor service.
     *
     * @param dbManager database manager
     * @param searchEngine catalog search engine to benchmark
     */
    public TelemetryMonitorService(DatabaseManager dbManager, CatalogSearchEngine searchEngine) {
        this.dbManager = dbManager;
        this.searchEngine = searchEngine;
    }

    /**
     * Measures live JVM memory, DS&amp;A search latency, SQLite file size, and row counts.
     *
     * @return immutable {@link TelemetrySnapshot}
     */
    public TelemetrySnapshot captureSnapshot() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);

        long t1 = System.nanoTime();
        searchEngine.getProductTrie().autocomplete("wir", 10);
        double trieMs = Math.round(((System.nanoTime() - t1) / 1_000_000.0) * 1000.0) / 1000.0;

        long t2 = System.nanoTime();
        searchEngine.getSkuBarcodeIndex().findBySku("PRD-1001");
        double hashMs = Math.round(((System.nanoTime() - t2) / 1_000_000.0) * 1000.0) / 1000.0;

        long sizeKb = 0L;
        Path dbPath = dbManager.getDatabaseFilePath();
        if (dbPath != null && Files.exists(dbPath)) {
            try {
                sizeKb = Files.size(dbPath) / 1024L;
            } catch (Exception ignored) {
            }
        }

        int products = countTable("products");
        int movements = countTable("stock_movements");
        int suppliers = countTable("suppliers");
        int audits = countTable("audit_logs");

        return new TelemetrySnapshot(usedMb, maxMb, trieMs, hashMs, sizeKb, products, movements, suppliers, audits);
    }

    /**
     * Counts rows inside an internal table safely.
     *
     * @param tableName table identifier
     * @return row count
     */
    private int countTable(String tableName) {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            return 0;
        }
    }
}
