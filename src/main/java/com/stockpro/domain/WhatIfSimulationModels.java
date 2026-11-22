package com.stockpro.domain;

import java.util.Collections;
import java.util.List;

/**
 * Immutable scenario inputs, per-SKU stress profiles, and percentile summary models
 * powering the Monte Carlo What-If Time Machine simulator.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class WhatIfSimulationModels {

    /**
     * Prevents instantiation of model container class.
     */
    private WhatIfSimulationModels() {
    }

    /**
     * Immutable Monte Carlo scenario parameters constructed via {@link Builder}.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public static final class SimulationScenarioInput {
        private final double demandSurgePercent;
        private final int supplierDelayDays;
        private final double monthlyBudgetCap;
        private final double safetyBufferPercent;
        private final int horizonDays;
        private final int trialCount;
        private final long seed;

        private SimulationScenarioInput(Builder builder) {
            this.demandSurgePercent = builder.demandSurgePercent;
            this.supplierDelayDays = builder.supplierDelayDays;
            this.monthlyBudgetCap = builder.monthlyBudgetCap;
            this.safetyBufferPercent = builder.safetyBufferPercent;
            this.horizonDays = builder.horizonDays;
            this.trialCount = builder.trialCount;
            this.seed = builder.seed;
        }

        /**
         * Creates a new scenario builder initialized with realistic baseline defaults.
         *
         * @return {@link Builder} instance
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Returns expected macro demand shift percentage (for example {@code 25.0} for +25%).
         *
         * @return demand surge percentage
         */
        public double getDemandSurgePercent() {
            return demandSurgePercent;
        }

        /**
         * Returns added supplier delivery delay in days.
         *
         * @return supplier delay days
         */
        public int getSupplierDelayDays() {
            return supplierDelayDays;
        }

        /**
         * Returns maximum monthly replenishment capital cap in store currency.
         *
         * @return monthly budget cap
         */
        public double getMonthlyBudgetCap() {
            return monthlyBudgetCap;
        }

        /**
         * Returns safety buffer uplift percentage applied to reorder thresholds.
         *
         * @return safety buffer percentage
         */
        public double getSafetyBufferPercent() {
            return safetyBufferPercent;
        }

        /**
         * Returns simulation horizon length in days.
         *
         * @return horizon days (defaults to 30)
         */
        public int getHorizonDays() {
            return horizonDays;
        }

        /**
         * Returns number of stochastic Monte Carlo iterations to evaluate.
         *
         * @return trial count (defaults to 1,000)
         */
        public int getTrialCount() {
            return trialCount;
        }

        /**
         * Returns pseudo-random generator seed used for reproducible simulations.
         *
         * @return PRNG seed
         */
        public long getSeed() {
            return seed;
        }

        /**
         * Fluent builder for {@link SimulationScenarioInput}.
         *
         * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
         */
        public static final class Builder {
            private double demandSurgePercent = 0.0;
            private int supplierDelayDays = 0;
            private double monthlyBudgetCap = 5000.0;
            private double safetyBufferPercent = 15.0;
            private int horizonDays = 30;
            private int trialCount = 1000;
            private long seed = 42L;

            /**
             * Constructs a scenario builder with default baseline settings.
             */
            public Builder() {
            }

            /**
             * Sets macro demand surge or drop percentage (clamped {@code -60%..+250%}).
             *
             * @param demandSurgePercent expected demand change percent
             * @return this builder
             */
            public Builder demandSurgePercent(double demandSurgePercent) {
                this.demandSurgePercent = Math.max(-60.0, Math.min(250.0, demandSurgePercent));
                return this;
            }

            /**
             * Sets additional supplier shipping delay in days (clamped {@code 0..45}).
             *
             * @param supplierDelayDays added lead-time delay in days
             * @return this builder
             */
            public Builder supplierDelayDays(int supplierDelayDays) {
                this.supplierDelayDays = Math.max(0, Math.min(45, supplierDelayDays));
                return this;
            }

            /**
             * Sets available monthly restock budget cap (minimum {@code 0.0}).
             *
             * @param monthlyBudgetCap monthly replenishment capital ceiling
             * @return this builder
             */
            public Builder monthlyBudgetCap(double monthlyBudgetCap) {
                this.monthlyBudgetCap = Math.max(0.0, monthlyBudgetCap);
                return this;
            }

            /**
             * Sets candidate safety stock buffer percentage (clamped {@code 0..100}).
             *
             * @param safetyBufferPercent safety buffer uplift percentage
             * @return this builder
             */
            public Builder safetyBufferPercent(double safetyBufferPercent) {
                this.safetyBufferPercent = Math.max(0.0, Math.min(100.0, safetyBufferPercent));
                return this;
            }

            /**
             * Sets forecast horizon in days (minimum {@code 1}).
             *
             * @param horizonDays simulation window length in days
             * @return this builder
             */
            public Builder horizonDays(int horizonDays) {
                this.horizonDays = Math.max(1, Math.min(365, horizonDays));
                return this;
            }

            /**
             * Sets Monte Carlo sample size (clamped {@code 100..10000}).
             *
             * @param trialCount number of simulation iterations
             * @return this builder
             */
            public Builder trialCount(int trialCount) {
                this.trialCount = Math.max(100, Math.min(10000, trialCount));
                return this;
            }

            /**
             * Sets deterministic PRNG seed for test reproducibility.
             *
             * @param seed random seed value
             * @return this builder
             */
            public Builder seed(long seed) {
                this.seed = seed;
                return this;
            }

            /**
             * Builds an immutable {@link SimulationScenarioInput}.
             *
             * @return built scenario parameters
             */
            public SimulationScenarioInput build() {
                return new SimulationScenarioInput(this);
            }
        }
    }

    /**
     * Per-SKU stress exposure row calculated across Monte Carlo runs.
     *
     * @param productId product database ID
     * @param sku product SKU code
     * @param name product display name
     * @param currentQty current on-hand stock units
     * @param currentThreshold current low-stock alert threshold
     * @param recommendedThreshold suggested new safety threshold under stress scenario
     * @param projectedDemandUnits mean expected unit demand over horizon
     * @param stockoutProbabilityPercent percentage of trials experiencing stockout ({@code 0..100})
     * @param expectedRevenue mean fulfilled SKU revenue over horizon
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record SkuSimulationRow(
            long productId,
            String sku,
            String name,
            int currentQty,
            int currentThreshold,
            int recommendedThreshold,
            double projectedDemandUnits,
            double stockoutProbabilityPercent,
            double expectedRevenue
    ) {
    }

    /**
     * Aggregated 1,000-trial Monte Carlo simulation report comparing Baseline vs Simulated trajectories.
     *
     * @param input executed scenario parameters
     * @param trialCount executed simulation trial count
     * @param baselineRevenue deterministic baseline catalog revenue without stress shock
     * @param simulatedMeanRevenue mean simulated fulfilled revenue across all trials
     * @param revenueP10 conservative 10th-percentile simulated revenue outcome
     * @param revenueP50 median 50th-percentile simulated revenue outcome
     * @param revenueP90 optimistic 90th-percentile simulated revenue outcome
     * @param profitP50 median simulated gross profit after restock and holding costs
     * @param stockoutRiskPercent percentage of SKU-trial observations resulting in stockout
     * @param holdingCostEstimate expected inventory holding cost over the horizon
     * @param budgetUtilizationPercent fraction of monthly budget cap consumed by replenishment
     * @param recommendedSafetyBufferPercent suggested safety stock buffer percentage to mitigate risk
     * @param atRiskSkuCount count of active SKUs with at least 20% stockout probability
     * @param skuRows per-SKU stress outcomes sorted by descending stockout probability
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record SimulationResultSummary(
            SimulationScenarioInput input,
            int trialCount,
            double baselineRevenue,
            double simulatedMeanRevenue,
            double revenueP10,
            double revenueP50,
            double revenueP90,
            double profitP50,
            double stockoutRiskPercent,
            double holdingCostEstimate,
            double budgetUtilizationPercent,
            int recommendedSafetyBufferPercent,
            int atRiskSkuCount,
            List<SkuSimulationRow> skuRows
    ) {
        /**
         * Canonical constructor defending against null SKU row lists.
         */
        public SimulationResultSummary {
            skuRows = skuRows == null ? List.of() : Collections.unmodifiableList(skuRows);
        }
    }
}
