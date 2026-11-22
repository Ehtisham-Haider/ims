package com.stockpro.ml;

import com.stockpro.domain.MlSuiteModels.BasketAssociationRule;
import com.stockpro.domain.MlSuiteModels.DynamicPriceSuggestion;
import com.stockpro.domain.MlSuiteModels.ExpiryMarkdownSuggestion;
import com.stockpro.domain.MlSuiteModels.HoltWintersPrediction;
import com.stockpro.domain.MlSuiteModels.LostSaleImputation;
import com.stockpro.domain.MlSuiteModels.SupplierOtifScore;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.Supplier;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;
import com.stockpro.repository.SupplierRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java Advanced Offline ML Suite providing Holt-Winters seasonal forecasting
 * with 95% confidence bands, Supplier OTIF scoring &amp; lead-time prediction,
 * dynamic price optimization, stockout lost-sale imputation, smart 10/20/35% expiry markdowns,
 * and Apriori co-occurrence market-basket rules.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class AdvancedOfflineMlService {

    private static final double HW_ALPHA = 0.35;
    private static final double HW_BETA = 0.15;
    private static final double HW_GAMMA = 0.25;

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository poRepository;
    private final OfflineMlEngine baseMlEngine;

    /**
     * Constructs the Branch 07 Advanced Offline ML analytics service.
     *
     * @param dbManager SQLite connection manager
     * @param productRepository product catalog repository
     * @param supplierRepository supplier repository
     * @param poRepository purchase order repository
     * @param baseMlEngine V1 baseline offline ML forecasting engine
     */
    public AdvancedOfflineMlService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            SupplierRepository supplierRepository,
            PurchaseOrderRepository poRepository,
            OfflineMlEngine baseMlEngine) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
        this.poRepository = poRepository;
        this.baseMlEngine = baseMlEngine;
    }

    /**
     * Computes Holt-Winters additive seasonal 7-day forecast and 95% confidence bands
     * for a raw daily unit demand series.
     *
     * @param product target product descriptor
     * @param dailySeries daily demand values ordered oldest to newest
     * @return {@link HoltWintersPrediction}
     */
    public HoltWintersPrediction forecastHoltWinters(Product product, List<Double> dailySeries) {
        double fallbackDaily = product != null
                ? baseMlEngine.forecastDailyVelocity(dailySeries, product)
                : 1.0;
        if (dailySeries == null || dailySeries.size() < 7) {
            double f7 = round2(fallbackDaily * 7.0);
            double band = Math.max(1.5, f7 * 0.28);
            return new HoltWintersPrediction(
                    product != null ? product.getId() : 0L,
                    product != null ? product.getSku() : "N/A",
                    product != null ? product.getName() : "Catalog Item",
                    f7,
                    round2(Math.max(0.0, f7 - band)),
                    round2(f7 + band),
                    0.0,
                    "Cold-start smoothed baseline (under 7 days history)"
            );
        }

        int m = 7;
        double level = 0.0;
        for (int i = 0; i < m; i++) {
            level += dailySeries.get(i);
        }
        level /= m;

        double trend = 0.0;
        if (dailySeries.size() >= m * 2) {
            double nextMean = 0.0;
            for (int i = m; i < m * 2; i++) {
                nextMean += dailySeries.get(i);
            }
            nextMean /= m;
            trend = (nextMean - level) / m;
        }

        double[] seasonal = new double[m];
        for (int i = 0; i < m; i++) {
            seasonal[i] = dailySeries.get(i) - level;
        }

        double sqErrSum = 0.0;
        int errCount = 0;
        for (int t = m; t < dailySeries.size(); t++) {
            int sIdx = t % m;
            double actual = dailySeries.get(t);
            double oneStep = Math.max(0.0, level + trend + seasonal[sIdx]);
            double err = actual - oneStep;
            sqErrSum += err * err;
            errCount++;

            double prevLevel = level;
            level = HW_ALPHA * (actual - seasonal[sIdx]) + (1.0 - HW_ALPHA) * (level + trend);
            trend = HW_BETA * (level - prevLevel) + (1.0 - HW_BETA) * trend;
            seasonal[sIdx] = HW_GAMMA * (actual - level) + (1.0 - HW_GAMMA) * seasonal[sIdx];
        }

        double forecastSum = 0.0;
        for (int h = 1; h <= 7; h++) {
            int sIdx = (dailySeries.size() + h - 1) % m;
            double pt = Math.max(0.0, level + h * trend + seasonal[sIdx]);
            forecastSum += pt;
        }
        double rmse = errCount > 0 ? Math.sqrt(sqErrSum / errCount) : Math.max(0.5, fallbackDaily * 0.25);
        double halfWidth95 = Math.max(1.0, 1.96 * rmse * Math.sqrt(7.0));
        double lower = Math.max(0.0, forecastSum - halfWidth95);
        double upper = forecastSum + halfWidth95;

        return new HoltWintersPrediction(
                product != null ? product.getId() : 0L,
                product != null ? product.getSku() : "N/A",
                product != null ? product.getName() : "Catalog Item",
                round2(forecastSum),
                round2(lower),
                round2(upper),
                round2(trend),
                trend >= 0.05 ? "Accelerating weekly trend"
                        : trend <= -0.05 ? "Cooling seasonal slope"
                        : "Stable 7-day seasonal pattern"
        );
    }

    /**
     * Generates top Holt-Winters 7-day seasonal predictions across active catalog SKUs.
     *
     * @return ordered list of {@link HoltWintersPrediction}
     */
    public List<HoltWintersPrediction> forecastCatalogHoltWinters() {
        Map<Long, List<Double>> matrix = loadDailySalesMatrix(28);
        List<Product> products = productRepository.findAll(false);
        List<HoltWintersPrediction> out = new ArrayList<>();
        for (Product p : products) {
            List<Double> series = matrix.getOrDefault(p.getId(), List.of());
            out.add(forecastHoltWinters(p, series));
        }
        out.sort(Comparator.comparingDouble(HoltWintersPrediction::next7DaysForecast).reversed());
        return out;
    }

    /**
     * Evaluates Supplier OTIF Reliability Score (0..100) and recency-weighted expected delivery days
     * combining registered vendor profiles with actual Purchase Order &amp; 3-way receipt history.
     *
     * @return sorted list of {@link SupplierOtifScore} highest reliability first
     */
    public List<SupplierOtifScore> scoreSuppliersOtif() {
        List<Supplier> suppliers = supplierRepository.findAll();
        List<PurchaseOrder> allOrders = poRepository.findAll();
        Map<Long, String> receiptStatuses = loadPoReceiptMatchStatuses();

        List<SupplierOtifScore> scores = new ArrayList<>(suppliers.size());
        for (Supplier sup : suppliers) {
            List<PurchaseOrder> supOrders = allOrders.stream()
                    .filter(po -> po.getSupplierId() == sup.getId())
                    .toList();
            int total = supOrders.size();
            int onTimeInFull = 0;
            double weightedDays = 0.0;
            double weightsSum = 0.0;

            for (int i = 0; i < total; i++) {
                PurchaseOrder po = supOrders.get(i);
                double w = total - i; // newer orders weighted higher (newest first in poRepo)
                String match = receiptStatuses.getOrDefault(po.getId(), "MATCHED");
                boolean deliveredClean = "Delivered".equalsIgnoreCase(po.getStatus()) && !"MISMATCH".equalsIgnoreCase(match);
                if (deliveredClean) {
                    onTimeInFull++;
                }
                double observedDays = deliveredClean
                        ? Math.max(1.0, sup.getDeliveryDays())
                        : "MISMATCH".equalsIgnoreCase(match)
                        ? sup.getDeliveryDays() + 2.5
                        : sup.getDeliveryDays() + 0.5;
                weightedDays += observedDays * w;
                weightsSum += w;
            }

            double otifPct;
            if (total == 0) {
                otifPct = sup.getReliabilityScore();
            } else {
                double orderRate = (onTimeInFull * 100.0) / total;
                otifPct = round1(sup.getReliabilityScore() * 0.45 + orderRate * 0.55);
            }
            double predDays = weightsSum > 0.0 ? round1(weightedDays / weightsSum) : Math.max(1.0, sup.getDeliveryDays());
            String tier = otifPct >= 90.0 ? "ELITE" : otifPct >= 75.0 ? "RELIABLE" : "WATCH";
            scores.add(new SupplierOtifScore(
                    sup.getId(), sup.getName(), total, onTimeInFull, otifPct, predDays, tier
            ));
        }
        scores.sort(Comparator.comparingDouble(SupplierOtifScore::otifScorePercent).reversed());
        return scores;
    }

    /**
     * Suggests dynamic selling prices balancing velocity, low stock scarcity, stagnant cover,
     * and preserving positive margin above unit purchase cost.
     *
     * @return list of {@link DynamicPriceSuggestion} entries where adjustments add margin or lift turnover
     */
    public List<DynamicPriceSuggestion> recommendDynamicPrices() {
        List<OfflineMlEngine.ProductForecast> forecasts = baseMlEngine.analyzeCatalog();
        List<DynamicPriceSuggestion> suggestions = new ArrayList<>();
        for (OfflineMlEngine.ProductForecast f : forecasts) {
            Product p = f.product();
            if (p.getSellPrice() <= 0.0) {
                continue;
            }
            double floor = Math.max(0.10, p.getBuyPrice() * 1.08);
            double suggested = p.getSellPrice();
            String rationale = "Optimal equilibrium pricing";

            if (p.getQuantity() > 0 && f.daysUntilEmpty() <= 6.0 && f.dailyVelocity() >= 2.0) {
                suggested = round2(p.getSellPrice() * 1.07);
                rationale = String.format(Locale.US, "High velocity (%.1f/day) & tight cover (%.1fd): capture +7%% scarcity margin.",
                        f.dailyVelocity(), f.daysUntilEmpty());
            } else if (p.getQuantity() >= 30 && f.daysUntilEmpty() >= 90.0 && p.getSellPrice() > floor) {
                double discounted = Math.max(floor, p.getSellPrice() * 0.92);
                if (discounted < p.getSellPrice() - 0.01) {
                    suggested = round2(discounted);
                    rationale = "Overstocked / slow turnover: trim price safely above buy cost to unlock cash.";
                }
            }
            double pct = round1(((suggested - p.getSellPrice()) * 100.0) / p.getSellPrice());
            if (Math.abs(pct) >= 1.0) {
                suggestions.add(new DynamicPriceSuggestion(
                        p.getId(), p.getSku(), p.getName(), p.getSellPrice(), suggested, pct, rationale
                ));
            }
        }
        return suggestions;
    }

    /**
     * Estimates unfulfilled lost demand &amp; missed revenue across Out-of-Stock or critical SKUs
     * by imputing unconstrained velocity from category peers or historical movement cadence.
     *
     * @return list of {@link LostSaleImputation} sorted by missed revenue descending
     */
    public List<LostSaleImputation> imputeStockoutLostSales() {
        List<OfflineMlEngine.ProductForecast> forecasts = baseMlEngine.analyzeCatalog();
        Map<String, Double> categoryAvgVel = new HashMap<>();
        for (OfflineMlEngine.ProductForecast f : forecasts) {
            if (f.product().getQuantity() > 0) {
                String cat = f.product().getCategory() != null ? f.product().getCategory() : "General";
                categoryAvgVel.merge(cat, f.dailyVelocity(), (a, b) -> (a + b) / 2.0);
            }
        }

        List<LostSaleImputation> out = new ArrayList<>();
        for (OfflineMlEngine.ProductForecast f : forecasts) {
            Product p = f.product();
            if (p.getStatus() == StockStatus.OUT_OF_STOCK || p.getQuantity() == 0) {
                String cat = p.getCategory() != null ? p.getCategory() : "General";
                double expectedVel = Math.max(f.dailyVelocity(), categoryAvgVel.getOrDefault(cat, 1.5));
                int stockoutDays = 5;
                double missedUnits = round1(expectedVel * stockoutDays);
                double missedRev = round2(missedUnits * Math.max(0.5, p.getSellPrice()));
                out.add(new LostSaleImputation(
                        p.getId(),
                        p.getSku(),
                        p.getName(),
                        stockoutDays,
                        missedUnits,
                        missedRev,
                        String.format(Locale.US, "Immediate PO: losing ~$%.2f across ~%d stockout day(s).", missedRev, stockoutDays)
                ));
            }
        }
        out.sort(Comparator.comparingDouble(LostSaleImputation::imputedMissedRevenue).reversed());
        return out;
    }

    /**
     * Recommends smart tiered clearance discounts (10%, 20%, or 35%) for items nearing expiry.
     *
     * @return list of {@link ExpiryMarkdownSuggestion} ordered most urgent expiry first
     */
    public List<ExpiryMarkdownSuggestion> suggestExpiryMarkdowns() {
        LocalDate today = LocalDate.now();
        List<ExpiryMarkdownSuggestion> out = new ArrayList<>();
        for (Product p : productRepository.findAll(false)) {
            if (p.getExpiryDate() == null || p.getQuantity() <= 0) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, p.getExpiryDate());
            int discount = 0;
            if (daysLeft <= 7) {
                discount = 35;
            } else if (daysLeft <= 14) {
                discount = 20;
            } else if (daysLeft <= 30) {
                discount = 10;
            }
            if (discount > 0) {
                double clearance = round2(p.getSellPrice() * (1.0 - discount / 100.0));
                double recover = round2(clearance * p.getQuantity());
                out.add(new ExpiryMarkdownSuggestion(
                        p.getId(), p.getSku(), p.getName(), daysLeft, discount, clearance, recover
                ));
            }
        }
        out.sort(Comparator.comparingLong(ExpiryMarkdownSuggestion::daysUntilExpiry));
        return out;
    }

    /**
     * Mines Apriori co-occurrence basket rules ({@code Items Bought Together}) across sales movements
     * grouped by checkout batch note / hour window, falling back cleanly when empty.
     *
     * @param minPairSupportCount minimum co-occurrence count (at least 2 recommended, clamped to 1+)
     * @return ranked list of {@link BasketAssociationRule}
     */
    public List<BasketAssociationRule> mineBoughtTogetherRules(int minPairSupportCount) {
        int minSupport = Math.max(1, minPairSupportCount);
        Map<String, Set<Long>> baskets = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();

        String sql = """
                SELECT product_id, product_name, notes, created_at
                FROM stock_movements
                WHERE movement_type = 'SALE'
                ORDER BY id DESC LIMIT 1000
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long pid = rs.getLong("product_id");
                String name = rs.getString("product_name");
                names.put(pid, name);
                String notes = rs.getString("notes");
                String ts = rs.getString("created_at");
                String hourKey = (ts != null && ts.length() >= 13) ? ts.substring(0, 13) : "unknown";
                String basketKey = (notes != null && !notes.isBlank() && notes.toUpperCase(Locale.ROOT).contains("BASKET"))
                        ? notes.trim()
                        : hourKey;
                baskets.computeIfAbsent(basketKey, k -> new LinkedHashSet<>()).add(pid);
            }
        } catch (SQLException ignored) {
            return List.of();
        }

        if (baskets.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> itemCounts = new HashMap<>();
        Map<String, Integer> pairCounts = new HashMap<>();
        for (Set<Long> items : baskets.values()) {
            for (Long a : items) {
                itemCounts.merge(a, 1, Integer::sum);
            }
            List<Long> sorted = new ArrayList<>(items);
            sorted.sort(Long::compareTo);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    String key = sorted.get(i) + ":" + sorted.get(j);
                    pairCounts.merge(key, 1, Integer::sum);
                }
            }
        }

        double totalBaskets = Math.max(1, baskets.size());
        List<BasketAssociationRule> rules = new ArrayList<>();
        for (Map.Entry<String, Integer> e : pairCounts.entrySet()) {
            int count = e.getValue();
            if (count < minSupport) {
                continue;
            }
            String[] parts = e.getKey().split(":");
            long idA = Long.parseLong(parts[0]);
            long idB = Long.parseLong(parts[1]);
            String nameA = names.getOrDefault(idA, "SKU #" + idA);
            String nameB = names.getOrDefault(idB, "SKU #" + idB);
            int supA = itemCounts.getOrDefault(idA, count);
            double supportPct = round1((count * 100.0) / totalBaskets);
            double confidencePct = round1((count * 100.0) / Math.max(1, supA));
            rules.add(new BasketAssociationRule(
                    idA, nameA, idB, nameB, count, supportPct, confidencePct,
                    String.format(Locale.US, "Customers buying '%s' also add '%s' (%.0f%% confidence, %d co-sales).",
                            nameA, nameB, confidencePct, count)
            ));
        }
        rules.sort(Comparator.comparingDouble(BasketAssociationRule::confidencePercent).reversed());
        return rules;
    }

    private Map<Long, List<Double>> loadDailySalesMatrix(int days) {
        Map<Long, double[]> map = new LinkedHashMap<>();
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        String sql = "SELECT product_id, quantity_delta, created_at FROM stock_movements WHERE movement_type = 'SALE' AND created_at >= ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start.atStartOfDay().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong("product_id");
                    int units = Math.abs(rs.getInt("quantity_delta"));
                    LocalDate day = LocalDateTime.parse(rs.getString("created_at")).toLocalDate();
                    int idx = (int) ChronoUnit.DAYS.between(start, day);
                    if (idx >= 0 && idx < days) {
                        map.computeIfAbsent(pid, k -> new double[days])[idx] += units;
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        Map<Long, List<Double>> res = new LinkedHashMap<>();
        for (Map.Entry<Long, double[]> e : map.entrySet()) {
            List<Double> series = new ArrayList<>(days);
            for (double v : e.getValue()) {
                series.add(v);
            }
            res.put(e.getKey(), series);
        }
        return res;
    }

    private Map<Long, String> loadPoReceiptMatchStatuses() {
        Map<Long, String> statuses = new HashMap<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT po_id, match_status FROM goods_receipts ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                statuses.put(rs.getLong("po_id"), rs.getString("match_status"));
            }
        } catch (SQLException ignored) {
        }
        return statuses;
    }

    private double round1(double val) {
        return Math.round(val * 10.0) / 10.0;
    }

    private double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
