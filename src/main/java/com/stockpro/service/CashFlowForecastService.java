package com.stockpro.service;

import com.stockpro.domain.CashFlowHorizon;
import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.PurchaseOrderRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Projects 30-, 60-, and 90-day cash outflows vs expected inflows from open Purchase Orders,
 * historical sales run-rates, and upcoming low-stock replenishment requirements.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class CashFlowForecastService {

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;
    private final PurchaseOrderRepository poRepository;

    /**
     * Constructs the 30/60/90-day cash-flow projection engine.
     *
     * @param dbManager SQLite connection manager
     * @param productRepository product catalog repository
     * @param poRepository purchase order repository
     */
    public CashFlowForecastService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            PurchaseOrderRepository poRepository) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
        this.poRepository = poRepository;
    }

    /**
     * Computes 30-, 60-, and 90-day cash-flow projections combining committed PO payables,
     * recurring low-stock replenishment spend, and expected retail inflows.
     *
     * @return list of 3 {@link CashFlowHorizon} entries ({@code 30}, {@code 60}, {@code 90} days)
     */
    public List<CashFlowHorizon> forecastHorizons() {
        List<PurchaseOrder> orders = poRepository.findAll();
        double openPoPayable = orders.stream()
                .filter(po -> !"Delivered".equalsIgnoreCase(po.getStatus()))
                .mapToDouble(PurchaseOrder::getTotalCost)
                .sum();

        List<Product> products = productRepository.findAll(false);
        double dailySalesRevenue = estimateDailySalesRevenue(products);
        double reorderGapSpend = estimateLowStockReorderSpend(products);
        double startingWorkingCapital = products.stream().mapToDouble(Product::getCostValuation).sum() * 0.25;

        int[] horizons = {30, 60, 90};
        List<CashFlowHorizon> result = new ArrayList<>(3);
        for (int days : horizons) {
            double cycleFactor = days / 30.0;
            double committedOut = roundTwo(openPoPayable);
            double restockOut = roundTwo(reorderGapSpend * cycleFactor);
            double projectedIn = roundTwo(dailySalesRevenue * days);
            double net = roundTwo(projectedIn - committedOut - restockOut);
            double endingPosition = roundTwo(startingWorkingCapital + net);
            String verdict = net >= 0.0
                    ? String.format(Locale.US, "Surplus +$%.2f over %d days (Healthy Liquidity)", net, days)
                    : String.format(Locale.US, "Deficit -$%.2f over %d days (Phase PO Restocks)", Math.abs(net), days);
            result.add(new CashFlowHorizon(
                    days, committedOut, restockOut, projectedIn, net, endingPosition, verdict
            ));
        }
        return result;
    }

    private double estimateDailySalesRevenue(List<Product> products) {
        String cutoff = LocalDateTime.now().minusDays(30).toString();
        double thirtyDaySales = 0.0;
        String sql = """
                SELECT COALESCE(SUM(ABS(sm.quantity_delta) * p.sell_price), 0.0)
                FROM stock_movements sm
                JOIN products p ON p.id = sm.product_id
                WHERE sm.movement_type = 'SALE' AND sm.created_at >= ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    thirtyDaySales = rs.getDouble(1);
                }
            }
        } catch (SQLException ignored) {
            thirtyDaySales = 0.0;
        }
        if (thirtyDaySales > 0.0) {
            return thirtyDaySales / 30.0;
        }
        // Conservative baseline turnover fallback when no movement history exists yet
        double retailVal = products.stream().mapToDouble(Product::getRetailValuation).sum();
        return retailVal * 0.012;
    }

    private double estimateLowStockReorderSpend(List<Product> products) {
        double spend = 0.0;
        for (Product p : products) {
            int gap = Math.max(0, (p.getLowStockThreshold() * 2) - p.getQuantity());
            if (p.getQuantity() <= p.getLowStockThreshold() && gap > 0) {
                spend += gap * Math.max(0.0, p.getBuyPrice());
            }
        }
        return spend;
    }

    private double roundTwo(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
