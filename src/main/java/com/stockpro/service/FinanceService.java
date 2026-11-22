package com.stockpro.service;

import com.stockpro.domain.Product;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes store financial valuations, gross unit margins, tax/GST totals, and dead-money stagnant stock.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class FinanceService {
    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;

    /**
     * Aggregated balance-sheet and tax snapshot across active inventory.
     *
     * @param totalCostValue sum of quantity * buyPrice
     * @param totalRetailValue sum of quantity * sellPrice
     * @param totalPotentialProfit difference between retail and cost valuations
     * @param averageMarginPercent average profit margin percentage across priced SKUs
     * @param taxRatePercent configured store tax/GST percentage
     * @param estimatedTaxAmount tax calculated on total retail value
     * @param retailValueWithTax total retail value including tax
     * @param deadMoneyValue total cost tied up in stagnant items
     * @param deadMoneyItems list of in-stock items with zero sales over the lookback window
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record FinanceSummary(
            double totalCostValue,
            double totalRetailValue,
            double totalPotentialProfit,
            double averageMarginPercent,
            double taxRatePercent,
            double estimatedTaxAmount,
            double retailValueWithTax,
            double deadMoneyValue,
            List<Product> deadMoneyItems
    ) {
    }

    /**
     * Constructs a financial engine service.
     *
     * @param dbManager database manager
     * @param productRepository product repository
     */
    public FinanceService(DatabaseManager dbManager, ProductRepository productRepository) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
    }

    /**
     * Calculates complete store balance-sheet valuation, tax/GST totals, and stagnant dead-money stock.
     *
     * @param lookbackDays zero-sales window in days (e.g., 60 or 90)
     * @param taxRatePercent configured tax/GST percentage
     * @return immutable {@link FinanceSummary}
     */
    public FinanceSummary computeSummary(int lookbackDays, double taxRatePercent) {
        List<Product> products = productRepository.findAll(false);
        double costSum = 0.0;
        double retailSum = 0.0;
        double marginSum = 0.0;
        int pricedCount = 0;

        for (Product p : products) {
            costSum += p.getCostValuation();
            retailSum += p.getRetailValuation();
            if (p.getSellPrice() > 0) {
                marginSum += p.getMarginPercent();
                pricedCount++;
            }
        }

        double avgMargin = pricedCount == 0 ? 0.0 : marginSum / pricedCount;
        double tax = calculateTax(retailSum, taxRatePercent);
        List<Product> deadStock = findDeadMoneyProducts(lookbackDays);
        double deadCost = deadStock.stream().mapToDouble(Product::getCostValuation).sum();

        return new FinanceSummary(
                costSum,
                retailSum,
                retailSum - costSum,
                avgMargin,
                taxRatePercent,
                tax,
                retailSum + tax,
                deadCost,
                deadStock
        );
    }

    /**
     * Identifies in-stock products with zero recorded {@code SALE} movements inside the requested lookback window.
     *
     * @param lookbackDays lookback window in days
     * @return list of stagnant items sorted by tied-up cost valuation descending
     */
    public List<Product> findDeadMoneyProducts(int lookbackDays) {
        int days = Math.max(1, lookbackDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        Set<Long> soldIds = new HashSet<>();

        String sql = "SELECT DISTINCT product_id FROM stock_movements WHERE movement_type = 'SALE' AND created_at >= ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    soldIds.add(rs.getLong(1));
                }
            }
        } catch (SQLException ignored) {
        }

        return productRepository.findAll(false).stream()
                .filter(p -> p.getQuantity() > 0 && !soldIds.contains(p.getId()))
                .sorted(Comparator.comparingDouble(Product::getCostValuation).reversed())
                .toList();
    }

    /**
     * Computes tax amount for a subtotal and tax percentage.
     *
     * @param subtotal pre-tax amount
     * @param taxRatePercent tax percentage (e.g. 15.0)
     * @return computed tax amount
     */
    public double calculateTax(double subtotal, double taxRatePercent) {
        if (subtotal <= 0.0 || taxRatePercent <= 0.0) {
            return 0.0;
        }
        return subtotal * (taxRatePercent / 100.0);
    }

    /**
     * Computes tax-inclusive total for a subtotal and tax percentage.
     *
     * @param subtotal pre-tax amount
     * @param taxRatePercent tax percentage
     * @return total with tax applied
     */
    public double calculateTotalWithTax(double subtotal, double taxRatePercent) {
        return Math.max(0.0, subtotal) + calculateTax(subtotal, taxRatePercent);
    }
}
