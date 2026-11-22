package com.stockpro.ml;

import com.stockpro.domain.Product;
import com.stockpro.domain.WhatIfSimulationModels.SimulationResultSummary;
import com.stockpro.domain.WhatIfSimulationModels.SimulationScenarioInput;
import com.stockpro.domain.WhatIfSimulationModels.SkuSimulationRow;
import com.stockpro.ml.OfflineMlEngine.ProductForecast;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Pure-Java 1,000-trial Monte Carlo inventory stress simulator evaluating stochastic
 * demand surges, supplier lead-time delays, monthly capital constraints, and safety buffer policies,
 * with one-click application of recommended reorder thresholds.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class MonteCarloSimulator {

    private static final double DAILY_HOLDING_COST_RATE = 0.0015;

    private final ProductRepository productRepository;
    private final OfflineMlEngine offlineMlEngine;
    private final SettingsRepository settingsRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;

    /**
     * Constructs a Monte Carlo What-If Simulator wired to catalog and persistence services.
     *
     * @param productRepository product catalog repository
     * @param offlineMlEngine offline ML velocity forecasting engine
     * @param settingsRepository key-value settings repository
     * @param auditService tamper-evident audit logger
     * @param eventBus inventory change notification bus
     */
    public MonteCarloSimulator(
            ProductRepository productRepository,
            OfflineMlEngine offlineMlEngine,
            SettingsRepository settingsRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.productRepository = productRepository;
        this.offlineMlEngine = offlineMlEngine;
        this.settingsRepository = settingsRepository;
        this.auditService = auditService;
        this.eventBus = eventBus;
    }

    /**
     * Executes a stochastic Monte Carlo simulation across all active products and returns
     * baseline vs simulated revenue, P10/P50/P90 quantiles, stockout risk, and threshold recommendations.
     *
     * @param input immutable scenario input parameters (defaults used if {@code null})
     * @return aggregated {@link SimulationResultSummary}
     */
    public SimulationResultSummary runSimulation(SimulationScenarioInput input) {
        SimulationScenarioInput scenario = input != null
                ? input
                : SimulationScenarioInput.builder().build();

        List<Product> products = productRepository.findAll(false);
        if (products.isEmpty()) {
            return new SimulationResultSummary(
                    scenario, scenario.getTrialCount(),
                    0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    0.0, 0.0, 0.0, (int) Math.round(scenario.getSafetyBufferPercent()), 0, List.of()
            );
        }

        Map<Long, ProductForecast> forecastMap = new HashMap<>();
        for (ProductForecast f : offlineMlEngine.analyzeCatalog()) {
            if (f.product() != null) {
                forecastMap.put(f.product().getId(), f);
            }
        }

        int n = products.size();
        double[] baseDailyVel = new double[n];
        double[] sellPrices = new double[n];
        double[] buyCosts = new double[n];
        int[] startQty = new int[n];
        int[] thresholds = new int[n];
        double totalBaseCostDemand = 0.0;
        double baselineRevenue = 0.0;

        int horizon = scenario.getHorizonDays();
        for (int i = 0; i < n; i++) {
            Product p = products.get(i);
            ProductForecast pf = forecastMap.get(p.getId());
            double vel = pf != null ? Math.max(0.25, pf.dailyVelocity()) : Math.max(0.35, p.getQuantity() * 0.04);
            baseDailyVel[i] = vel;
            sellPrices[i] = Math.max(0.5, p.getSellPrice());
            buyCosts[i] = Math.max(0.1, p.getBuyPrice() > 0 ? p.getBuyPrice() : sellPrices[i] * 0.6);
            startQty[i] = Math.max(0, p.getQuantity());
            thresholds[i] = Math.max(1, p.getLowStockThreshold());
            double expectedBaseDemand = vel * horizon;
            double baselineCap = startQty[i] + Math.max(0.0, expectedBaseDemand - startQty[i]) * 0.85;
            baselineRevenue += Math.min(expectedBaseDemand, baselineCap) * sellPrices[i];
            totalBaseCostDemand += Math.max(1.0, expectedBaseDemand - startQty[i]) * buyCosts[i];
        }

        int trials = scenario.getTrialCount();
        double[] trialRevenues = new double[trials];
        double[] trialProfits = new double[trials];
        double[] trialHoldingCosts = new double[trials];
        double[] trialBudgetUsed = new double[trials];
        int[] skuStockoutHits = new int[n];
        double[] skuRevenueAccum = new double[n];
        double[] skuDemandAccum = new double[n];

        double demandMultiplierCenter = Math.max(0.2, 1.0 + scenario.getDemandSurgePercent() / 100.0);
        double bufferMultiplier = 1.0 + scenario.getSafetyBufferPercent() / 100.0;
        double budgetCap = Math.max(0.0, scenario.getMonthlyBudgetCap());
        double delayDays = scenario.getSupplierDelayDays();
        Random rng = new Random(scenario.getSeed());
        long totalSkuObservations = (long) trials * n;
        long totalStockoutObservations = 0L;

        for (int t = 0; t < trials; t++) {
            double macroDemandFactor = clamp(demandMultiplierCenter * (1.0 + rng.nextGaussian() * 0.16), 0.25, 4.5);
            double effectiveDelay = Math.max(0.0, delayDays * clamp(1.0 + rng.nextGaussian() * 0.20, 0.5, 1.8));
            double deliveryAvailabilityFraction = clamp((horizon - effectiveDelay) / (double) horizon, 0.05, 1.0);
            double budgetScale = totalBaseCostDemand <= 0.0
                    ? 1.0
                    : clamp(budgetCap / Math.max(1.0, totalBaseCostDemand), 0.0, 1.0);

            double revSum = 0.0;
            double costSum = 0.0;
            double holdingSum = 0.0;
            double spendSum = 0.0;

            for (int i = 0; i < n; i++) {
                double skuNoise = clamp(1.0 + rng.nextGaussian() * 0.22, 0.35, 2.6);
                double simulatedDemand = baseDailyVel[i] * horizon * macroDemandFactor * skuNoise;
                double plannedRestockUnits = Math.max(0.0, (baseDailyVel[i] * horizon * bufferMultiplier) - startQty[i]);
                double fundedRestockUnits = plannedRestockUnits * budgetScale * deliveryAvailabilityFraction;
                double totalAvailable = startQty[i] + fundedRestockUnits;

                double unitsSold = Math.min(simulatedDemand, totalAvailable);
                if (simulatedDemand > totalAvailable + 0.01) {
                    skuStockoutHits[i]++;
                    totalStockoutObservations++;
                }

                double skuRev = unitsSold * sellPrices[i];
                double restockSpend = fundedRestockUnits * buyCosts[i];
                double avgRemaining = Math.max(0.0, totalAvailable - unitsSold * 0.65);
                double holding = avgRemaining * buyCosts[i] * DAILY_HOLDING_COST_RATE * horizon;

                revSum += skuRev;
                costSum += unitsSold * buyCosts[i];
                holdingSum += holding;
                spendSum += restockSpend;

                skuRevenueAccum[i] += skuRev;
                skuDemandAccum[i] += simulatedDemand;
            }

            trialRevenues[t] = revSum;
            trialProfits[t] = revSum - costSum - holdingSum;
            trialHoldingCosts[t] = holdingSum;
            trialBudgetUsed[t] = budgetCap > 0.0 ? Math.min(100.0, (spendSum / budgetCap) * 100.0) : 100.0;
        }

        Arrays.sort(trialRevenues);
        Arrays.sort(trialProfits);

        double meanRev = average(trialRevenues);
        double p10 = percentile(trialRevenues, 0.10);
        double p50 = percentile(trialRevenues, 0.50);
        double p90 = percentile(trialRevenues, 0.90);
        double profitP50 = percentile(trialProfits, 0.50);
        double holdingAvg = average(trialHoldingCosts);
        double budgetUtilAvg = average(trialBudgetUsed);
        double overallStockoutRisk = totalSkuObservations > 0
                ? (totalStockoutObservations * 100.0) / totalSkuObservations
                : 0.0;

        int recBuffer = (int) Math.round(clamp(
                scenario.getSafetyBufferPercent()
                        + scenario.getSupplierDelayDays() * 2.2
                        + overallStockoutRisk * 0.30,
                10.0,
                85.0
        ));

        List<SkuSimulationRow> rows = new ArrayList<>();
        int atRiskCount = 0;
        for (int i = 0; i < n; i++) {
            Product p = products.get(i);
            double prob = round1((skuStockoutHits[i] * 100.0) / trials);
            if (prob >= 20.0) {
                atRiskCount++;
            }
            double meanDem = round1(skuDemandAccum[i] / trials);
            double leadDays = 3.0 + scenario.getSupplierDelayDays();
            int suggestedThreshold = Math.max(
                    thresholds[i],
                    (int) Math.ceil((meanDem / horizon) * leadDays * (1.0 + recBuffer / 100.0))
            );
            rows.add(new SkuSimulationRow(
                    p.getId(),
                    p.getSku(),
                    p.getName(),
                    startQty[i],
                    thresholds[i],
                    suggestedThreshold,
                    meanDem,
                    prob,
                    round2(skuRevenueAccum[i] / trials)
            ));
        }

        rows.sort(Comparator.comparingDouble(SkuSimulationRow::stockoutProbabilityPercent).reversed()
                .thenComparingDouble(SkuSimulationRow::expectedRevenue).reversed());

        return new SimulationResultSummary(
                scenario,
                trials,
                round2(baselineRevenue),
                round2(meanRev),
                round2(p10),
                round2(p50),
                round2(p90),
                round2(profitP50),
                round1(overallStockoutRisk),
                round2(holdingAvg),
                round1(budgetUtilAvg),
                recBuffer,
                atRiskCount,
                rows
        );
    }

    /**
     * Applies the simulator's recommended safety buffer percentage and upgrades low-stock thresholds
     * on high-risk SKUs while logging an explicit audit trail event.
     *
     * @param summary completed {@link SimulationResultSummary}
     * @param actor authenticated username performing the explicit approval
     * @return operation result reporting how many SKU thresholds were updated
     */
    public InventoryOperationResult<Integer> applyRecommendedBuffer(SimulationResultSummary summary, String actor) {
        if (summary == null) {
            return InventoryOperationResult.fail("Run a simulation before applying buffer adjustments.");
        }
        settingsRepository.set(
                "ai.recommended_safety_buffer_pct",
                String.valueOf(summary.recommendedSafetyBufferPercent())
        );
        if (summary.input() != null && summary.input().getMonthlyBudgetCap() > 0) {
            settingsRepository.set(
                    "ai.budget.monthly_cap",
                    String.format(java.util.Locale.US, "%.2f", summary.input().getMonthlyBudgetCap())
            );
        }

        int updatedCount = 0;
        for (SkuSimulationRow row : summary.skuRows()) {
            if (row.stockoutProbabilityPercent() < 20.0 || row.recommendedThreshold() <= row.currentThreshold()) {
                continue;
            }
            Product p = productRepository.findById(row.productId()).orElse(null);
            if (p != null && row.recommendedThreshold() > p.getLowStockThreshold()) {
                p.setLowStockThreshold(row.recommendedThreshold());
                if (productRepository.update(p)) {
                    updatedCount++;
                }
            }
        }

        auditService.record(
                actor == null || actor.isBlank() ? "System" : actor,
                "WHATIF_BUFFER_APPLIED",
                "CATALOG",
                "Applied " + summary.recommendedSafetyBufferPercent() + "% safety buffer across "
                        + updatedCount + " at-risk SKUs (risk " + summary.stockoutRiskPercent() + "%)"
        );
        eventBus.publish("STOCK_UPDATED");
        return InventoryOperationResult.ok(
                updatedCount,
                "Applied " + summary.recommendedSafetyBufferPercent() + "% safety buffer to "
                        + updatedCount + " high-risk SKUs."
        );
    }

    private static double percentile(double[] sorted, double p) {
        if (sorted.length == 0) {
            return 0.0;
        }
        int idx = (int) Math.floor(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    private static double average(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
