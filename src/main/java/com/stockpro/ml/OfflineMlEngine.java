package com.stockpro.ml;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java offline machine learning engine performing demand forecasting (WMA + Exponential Smoothing),
 * dynamic safety-stock &amp; reorder recommendations, low-stock threshold tuning, days-until-empty countdowns,
 * Z-score/IQR theft &amp; typo anomaly detection, and ABC Pareto velocity grading.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class OfflineMlEngine {
    private static final double EXP_SMOOTHING_ALPHA = 0.35;
    private static final int DEFAULT_LEAD_TIME_DAYS = 3;
    private static final double SERVICE_Z_95 = 1.65;

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;

    /**
     * Per-SKU offline ML forecast, restock plan, tuned threshold, and ABC tier.
     *
     * @param product target product
     * @param dailyVelocity predicted units sold per day
     * @param forecast7Days predicted 7-day unit demand
     * @param forecast30Days predicted 30-day unit demand
     * @param daysUntilEmpty estimated days before stock reaches zero ({@code 999.0} if negligible velocity)
     * @param safetyStock recommended safety buffer units
     * @param recommendedReorderQty smart restock order quantity
     * @param tunedLowStockThreshold ML-suggested low-stock alert threshold
     * @param revenue30Days projected 30-day revenue contribution
     * @param abcGrade Pareto classification ({@code "A"}, {@code "B"}, or {@code "C"})
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record ProductForecast(
            Product product,
            double dailyVelocity,
            double forecast7Days,
            double forecast30Days,
            double daysUntilEmpty,
            int safetyStock,
            int recommendedReorderQty,
            int tunedLowStockThreshold,
            double revenue30Days,
            String abcGrade
    ) {
    }

    /**
     * Statistical outlier warning detected via Z-score or Interquartile Range (IQR) analysis.
     *
     * @param product target product (or {@code null} for global event)
     * @param severity severity level ({@code HIGH} or {@code MEDIUM})
     * @param category anomaly category ({@code QUANTITY_TYPO}, {@code SHRINKAGE_SPIKE})
     * @param description plain-English warning explanation
     * @param zScore computed statistical Z-score
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record AnomalyAlert(
            Product product,
            String severity,
            String category,
            String description,
            double zScore
    ) {
    }

    /**
     * Creates an offline ML engine bound to database and product repositories.
     *
     * @param dbManager database manager
     * @param productRepository product repository
     */
    public OfflineMlEngine(DatabaseManager dbManager, ProductRepository productRepository) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
    }

    /**
     * Runs demand forecasting, safety stock sizing, threshold tuning, countdown, and ABC grading across all SKUs.
     *
     * @return ordered list of {@link ProductForecast} entries sorted by urgency (shortest countdown first)
     */
    public List<ProductForecast> analyzeCatalog() {
        List<Product> products = productRepository.findAll(false);
        if (products.isEmpty()) {
            return List.of();
        }

        Map<Long, List<Double>> dailySalesMap = load30DayDailySalesMatrix();
        List<ProductForecast> rawList = new ArrayList<>();

        for (Product p : products) {
            List<Double> series = dailySalesMap.getOrDefault(p.getId(), Collections.emptyList());
            double velocity = forecastDailyVelocity(series, p);
            double f7 = round1(velocity * 7.0);
            double f30 = round1(velocity * 30.0);
            double daysLeft = velocity <= 0.05
                    ? (p.getQuantity() <= 0 ? 0.0 : 999.0)
                    : round1(p.getQuantity() / velocity);

            double stdDev = computeStdDev(series, velocity);
            int safetyStock = Math.max(2, (int) Math.ceil(SERVICE_Z_95 * stdDev * Math.sqrt(DEFAULT_LEAD_TIME_DAYS)));
            int targetCoverUnits = (int) Math.ceil(velocity * 14.0) + safetyStock;
            int reorderQty = Math.max(0, targetCoverUnits - p.getQuantity());
            int tunedThreshold = Math.max(3, (int) Math.ceil(velocity * DEFAULT_LEAD_TIME_DAYS) + safetyStock);
            double rev30 = f30 * Math.max(0.1, p.getSellPrice());

            rawList.add(new ProductForecast(
                    p,
                    round2(velocity),
                    f7,
                    f30,
                    daysLeft,
                    safetyStock,
                    reorderQty,
                    tunedThreshold,
                    round2(rev30),
                    "C"
            ));
        }

        // Assign ABC Pareto Grades by descending projected 30-day revenue
        List<ProductForecast> graded = assignAbcGrades(rawList);
        graded.sort(Comparator.comparingDouble(ProductForecast::daysUntilEmpty));
        return graded;
    }

    /**
     * Computes daily sales velocity using Weighted Moving Average blended with Single Exponential Smoothing.
     *
     * @param dailySeries 30-day chronological sales series
     * @param product fallback product context when no historical sales exist
     * @return predicted daily unit sales rate
     */
    public double forecastDailyVelocity(List<Double> dailySeries, Product product) {
        if (dailySeries == null || dailySeries.isEmpty()) {
            // Conservative cold-start heuristic based on current shelf depth
            return Math.max(0.3, Math.min(3.0, product.getQuantity() * 0.04));
        }
        double totalSales = 0.0;
        for (double v : dailySeries) {
            totalSales += v;
        }
        if (totalSales <= 0.0) {
            return Math.max(0.2, Math.min(2.0, product.getQuantity() * 0.03));
        }

        // Weighted Moving Average giving higher weight to recent days
        int n = dailySeries.size();
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (int i = 0; i < n; i++) {
            double weight = i + 1;
            weightedSum += dailySeries.get(i) * weight;
            weightTotal += weight;
        }
        double wma = weightTotal > 0 ? weightedSum / weightTotal : 0.0;

        // Exponential Smoothing
        double smoothed = dailySeries.get(0);
        for (int i = 1; i < n; i++) {
            smoothed = EXP_SMOOTHING_ALPHA * dailySeries.get(i) + (1.0 - EXP_SMOOTHING_ALPHA) * smoothed;
        }
        return Math.max(0.1, (wma * 0.5) + (smoothed * 0.5));
    }

    /**
     * Detects fat-finger quantity typos and abnormal shrinkage spikes across catalog quantities and movement history
     * using Z-score and IQR outlier rules.
     *
     * @return list of detected {@link AnomalyAlert} items
     */
    public List<AnomalyAlert> detectAnomalies() {
        List<AnomalyAlert> alerts = new ArrayList<>();
        List<Product> products = productRepository.findAll(false);

        if (products.size() >= 3) {
            List<Double> quantities = products.stream().map(p -> (double) p.getQuantity()).sorted().toList();
            double median = percentile(quantities, 50);

            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                double qty = p.getQuantity();
                List<Double> others = new ArrayList<>(products.size() - 1);
                for (int j = 0; j < products.size(); j++) {
                    if (i != j) {
                        others.add((double) products.get(j).getQuantity());
                    }
                }
                double otherMean = others.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                double otherStd = Math.max(5.0, computeStdDev(others, otherMean));
                double looZ = (qty - otherMean) / otherStd;

                if (qty >= 100 && looZ >= 3.0 && qty >= Math.max(otherMean * 4.0, median * 4.0)) {
                    alerts.add(new AnomalyAlert(
                            p,
                            "HIGH",
                            "QUANTITY_TYPO",
                            String.format("Possible fat-finger typo: '%s' has %d units vs store median ~%.0f.",
                                    p.getName(), p.getQuantity(), median),
                            round2(looZ)
                    ));
                }
            }
        }

        // Check movement history for abnormal shrinkage / lost / damaged spikes
        String sql = "SELECT * FROM stock_movements ORDER BY id DESC LIMIT 200";
        List<StockMovement> recent = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                StockMovement m = new StockMovement();
                m.setProductId(rs.getLong("product_id"));
                m.setProductName(rs.getString("product_name"));
                m.setMovementType(MovementType.fromString(rs.getString("movement_type")));
                m.setQuantityDelta(rs.getInt("quantity_delta"));
                recent.add(m);
            }
        } catch (SQLException ignored) {
        }

        if (recent.size() >= 3) {
            List<Double> magnitudes = recent.stream().map(m -> (double) Math.abs(m.getQuantityDelta())).toList();
            double meanM = magnitudes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double stdM = computeStdDev(magnitudes, meanM);

            Map<Long, Product> byId = new LinkedHashMap<>();
            for (Product p : products) {
                byId.put(p.getId(), p);
            }

            for (StockMovement m : recent) {
                double mag = Math.abs(m.getQuantityDelta());
                double z = stdM > 0 ? (mag - meanM) / stdM : 0.0;
                if ((m.getMovementType() == MovementType.LOST || m.getMovementType() == MovementType.DAMAGED)
                        && (z >= 1.8 || mag >= Math.max(25.0, meanM * 3.0))) {
                    alerts.add(new AnomalyAlert(
                            byId.get(m.getProductId()),
                            "MEDIUM",
                            "SHRINKAGE_SPIKE",
                            String.format("Unusual %s drop on '%s': %d units (normal avg %.1f).",
                                    m.getMovementType().getDisplayName(), m.getProductName(), (int) mag, meanM),
                            round2(z)
                    ));
                }
            }
        }
        return alerts;
    }

    /**
     * Checks whether a proposed input quantity looks like a fat-finger outlier relative to the catalog.
     *
     * @param proposedQty candidate quantity entered by user
     * @return optional warning message if statistical outlier detected
     */
    public String checkProposedQuantityTypo(int proposedQty) {
        List<Product> products = productRepository.findAll(false);
        if (products.size() < 3) {
            return proposedQty > 5000 ? "Unusually high quantity (" + proposedQty + "). Verify before saving." : null;
        }
        List<Double> vals = products.stream().map(p -> (double) p.getQuantity()).sorted().toList();
        double median = percentile(vals, 50);
        List<Double> absDevs = vals.stream().map(v -> Math.abs(v - median)).sorted().toList();
        double mad = percentile(absDevs, 50);
        double scale = Math.max(5.0, 1.4826 * mad);
        double modifiedZ = (proposedQty - median) / scale;
        if (proposedQty >= 500 && modifiedZ >= 3.5 && proposedQty > median * 4.0) {
            return String.format("Typo guard warning: %d units is %.1fx above store median (%.0f).",
                    proposedQty, proposedQty / Math.max(1.0, median), median);
        }
        return null;
    }

    /**
     * Grades items into Pareto ABC tiers (A = top 70% projected revenue, B = next 20%, C = bottom 10%).
     *
     * @param forecasts ungraded forecast list
     * @return graded list of {@link ProductForecast} records
     */
    private List<ProductForecast> assignAbcGrades(List<ProductForecast> forecasts) {
        List<ProductForecast> byRevenue = new ArrayList<>(forecasts);
        byRevenue.sort(Comparator.comparingDouble(ProductForecast::revenue30Days).reversed());

        double totalRev = byRevenue.stream().mapToDouble(ProductForecast::revenue30Days).sum();
        if (totalRev <= 0.0) {
            return byRevenue;
        }

        double cumulative = 0.0;
        List<ProductForecast> out = new ArrayList<>(byRevenue.size());
        for (int i = 0; i < byRevenue.size(); i++) {
            ProductForecast f = byRevenue.get(i);
            cumulative += f.revenue30Days();
            double ratio = cumulative / totalRev;
            String grade = (i == 0 || ratio <= 0.70) ? "A" : (ratio <= 0.90 ? "B" : "C");
            out.add(new ProductForecast(
                    f.product(),
                    f.dailyVelocity(),
                    f.forecast7Days(),
                    f.forecast30Days(),
                    f.daysUntilEmpty(),
                    f.safetyStock(),
                    f.recommendedReorderQty(),
                    f.tunedLowStockThreshold(),
                    f.revenue30Days(),
                    grade
            ));
        }
        return out;
    }

    /**
     * Loads a 30-day chronological sales quantity vector per product from SQLite {@code stock_movements}.
     *
     * @return map of product ID to 30 daily sales quantities
     */
    private Map<Long, List<Double>> load30DayDailySalesMatrix() {
        Map<Long, double[]> matrix = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(29);

        String sql = """
                SELECT product_id, quantity_delta, created_at
                FROM stock_movements
                WHERE movement_type = 'SALE' AND created_at >= ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.atStartOfDay().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong("product_id");
                    int units = Math.abs(rs.getInt("quantity_delta"));
                    LocalDate day = LocalDateTime.parse(rs.getString("created_at")).toLocalDate();
                    int idx = (int) java.time.temporal.ChronoUnit.DAYS.between(start, day);
                    if (idx >= 0 && idx < 30) {
                        double[] arr = matrix.computeIfAbsent(pid, k -> new double[30]);
                        arr[idx] += units;
                    }
                }
            }
        } catch (SQLException ignored) {
        }

        Map<Long, List<Double>> out = new LinkedHashMap<>();
        for (Map.Entry<Long, double[]> entry : matrix.entrySet()) {
            List<Double> list = new ArrayList<>(30);
            for (double d : entry.getValue()) {
                list.add(d);
            }
            out.put(entry.getKey(), list);
        }
        return out;
    }

    /**
     * Computes sample/population standard deviation around a mean.
     *
     * @param values numeric series
     * @param mean mean value
     * @return standard deviation
     */
    private double computeStdDev(List<Double> values, double mean) {
        if (values == null || values.isEmpty()) {
            return 0.5;
        }
        double sumSq = 0.0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / values.size());
    }

    /**
     * Computes linear percentile from a sorted list.
     *
     * @param sorted sorted ascending values
     * @param pct percentile in range 0..100
     * @return interpolated percentile value
     */
    private double percentile(List<Double> sorted, double pct) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        double index = (pct / 100.0) * (sorted.size() - 1);
        int lo = (int) Math.floor(index);
        int hi = (int) Math.ceil(index);
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (index - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    /**
     * Rounds to 1 decimal place.
     *
     * @param v raw value
     * @return rounded value
     */
    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Rounds to 2 decimal places.
     *
     * @param v raw value
     * @return rounded value
     */
    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
