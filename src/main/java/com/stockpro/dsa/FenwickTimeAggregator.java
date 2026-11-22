package com.stockpro.dsa;

import com.stockpro.repository.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 1-indexed Binary Indexed Tree (Fenwick Tree) supporting {@code O(log n)} point updates,
 * prefix queries, and arbitrary closed {@code [fromIdx, toIdx]} range aggregations over
 * time-bucketed sales revenue or unit counts.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class FenwickTimeAggregator {

    private final int size;
    private final double[] tree;
    private final double[] values;

    /**
     * Constructs a Fenwick Tree covering {@code size} zero-based time buckets.
     *
     * @param size number of discrete time buckets (must be at least 1)
     */
    public FenwickTimeAggregator(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Fenwick tree size must be positive.");
        }
        this.size = size;
        this.tree = new double[size + 1];
        this.values = new double[size];
    }

    /**
     * Builds a daily sales revenue Fenwick aggregator over the trailing {@code windowDays} days
     * from SQLite {@code stock_movements} joined with {@code products}.
     *
     * @param dbManager SQLite connection manager
     * @param windowDays number of daily buckets ending today
     * @return populated {@link FenwickTimeAggregator}
     */
    public static FenwickTimeAggregator fromDailySalesRevenue(DatabaseManager dbManager, int windowDays) {
        int days = Math.max(7, windowDays);
        FenwickTimeAggregator bit = new FenwickTimeAggregator(days);
        LocalDate start = LocalDate.now().minusDays(days - 1L);

        String sql = """
                SELECT sm.quantity_delta, sm.created_at, p.sell_price
                FROM stock_movements sm
                JOIN products p ON p.id = sm.product_id
                WHERE sm.movement_type = 'SALE' AND sm.created_at >= ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.atStartOfDay().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double units = Math.abs(rs.getInt("quantity_delta"));
                    double price = Math.max(0.0, rs.getDouble("sell_price"));
                    LocalDate day = LocalDateTime.parse(rs.getString("created_at")).toLocalDate();
                    int idx = (int) ChronoUnit.DAYS.between(start, day);
                    if (idx >= 0 && idx < days) {
                        bit.addDelta(idx, units * price);
                    }
                }
            }
        } catch (SQLException ignored) {
            // Return zeroed Fenwick tree cleanly when table empty
        }
        return bit;
    }

    /**
     * Adds {@code delta} to bucket {@code zeroBasedIndex} in {@code O(log n)} time.
     *
     * @param zeroBasedIndex target day/bucket index ({@code 0 .. size - 1})
     * @param delta additive amount
     */
    public void addDelta(int zeroBasedIndex, double delta) {
        validateIndex(zeroBasedIndex);
        values[zeroBasedIndex] += delta;
        for (int i = zeroBasedIndex + 1; i <= size; i += (i & -i)) {
            tree[i] += delta;
        }
    }

    /**
     * Replaces the stored value at {@code zeroBasedIndex} in {@code O(log n)} time.
     *
     * @param zeroBasedIndex target bucket index
     * @param newValue updated absolute amount
     */
    public void setValue(int zeroBasedIndex, double newValue) {
        validateIndex(zeroBasedIndex);
        double delta = newValue - values[zeroBasedIndex];
        addDelta(zeroBasedIndex, delta);
    }

    /**
     * Returns cumulative sum across {@code [0 .. inclusiveZeroIndex]} in {@code O(log n)} time.
     *
     * @param inclusiveZeroIndex inclusive upper bucket index
     * @return prefix aggregate sum
     */
    public double prefixSum(int inclusiveZeroIndex) {
        if (inclusiveZeroIndex < 0) {
            return 0.0;
        }
        int bounded = Math.min(size - 1, inclusiveZeroIndex);
        double sum = 0.0;
        for (int i = bounded + 1; i > 0; i -= (i & -i)) {
            sum += tree[i];
        }
        return sum;
    }

    /**
     * Returns exact inclusive range sum over {@code [fromZeroIdx .. toZeroIdx]} in {@code O(log n)} time.
     *
     * @param fromZeroIdx inclusive start index
     * @param toZeroIdx inclusive end index
     * @return range sum ({@code 0.0} if {@code fromZeroIdx > toZeroIdx})
     */
    public double rangeSum(int fromZeroIdx, int toZeroIdx) {
        if (toZeroIdx < fromZeroIdx || toZeroIdx < 0 || fromZeroIdx >= size) {
            return 0.0;
        }
        int lo = Math.max(0, fromZeroIdx);
        int hi = Math.min(size - 1, toZeroIdx);
        return prefixSum(hi) - (lo > 0 ? prefixSum(lo - 1) : 0.0);
    }

    /**
     * Returns total number of indexed buckets managed by this Fenwick tree.
     *
     * @return bucket capacity
     */
    public int size() {
        return size;
    }

    private void validateIndex(int idx) {
        if (idx < 0 || idx >= size) {
            throw new IndexOutOfBoundsException("Bucket index " + idx + " out of bounds for size " + size);
        }
    }
}
