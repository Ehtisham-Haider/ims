package com.stockpro.domain;

import java.util.List;

/**
 * Container class holding immutable DTO records for Branch 09
 * Multi-Agent Store Brain, Self-Learning Feedback Weights, Sales Detective,
 * Waste-Zero BOGO Bundles, Eco Impact Tracking, and Store Health Score.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class StoreBrainModels {

    private StoreBrainModels() {
    }

    /**
     * Learned manager preference weight persisted in SQLite {@code agent_feedback_weights}.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param weightKey agent name or proposal type key
     * @param weightValue preference multiplier clamped to {@code [0.25, 2.50]}
     * @param approveCount historical explicit approvals
     * @param rejectCount historical rejections
     * @param updatedAt ISO-8601 timestamp
     */
    public record AgentFeedbackWeight(
            String weightKey,
            double weightValue,
            int approveCount,
            int rejectCount,
            String updatedAt
    ) {
    }

    /**
     * Root-cause sales diagnostic report produced by the Sales Detective.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param headline concise diagnosis title
     * @param drivers key contributing store factors
     * @param totalSalesUnits total units moved across lookback window
     * @param summary plain-English executive summary
     */
    public record SalesDetectiveReport(
            String headline,
            List<String> drivers,
            int totalSalesUnits,
            String summary
    ) {
    }

    /**
     * Waste-Zero bundle pairing an expiring item with a fast-moving companion SKU.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param expiringProductId expiring SKU database id
     * @param expiringProductName expiring product display name
     * @param companionProductId paired companion SKU id
     * @param companionProductName companion product display name
     * @param bundleTitle promotional bundle name
     * @param bundlePrice discounted bundle retail price
     * @param recoverableValue projected gross cash recovered
     */
    public record WasteZeroBundle(
            long expiringProductId,
            String expiringProductName,
            long companionProductId,
            String companionProductName,
            String bundleTitle,
            double bundlePrice,
            double recoverableValue
    ) {
    }

    /**
     * Cumulative food/merchandise waste reduction and sustainability telemetry from {@code waste_eco_logs}.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param rescuedUnits total units rescued via clearance or BOGO bundles
     * @param rescuedKg estimated physical weight rescued in kilograms
     * @param rescuedValue total monetary value recovered
     * @param expiredUnits total units discarded/expired
     * @param expiredKg total wasted weight in kilograms
     * @param expiredValue total wasted cost value
     * @param estimatedCo2SavedKg approximate CO2e emissions avoided via rescue ({@code kg * 2.1})
     * @param summary human-readable eco summary
     */
    public record WasteEcoImpactSummary(
            int rescuedUnits,
            double rescuedKg,
            double rescuedValue,
            int expiredUnits,
            double expiredKg,
            double expiredValue,
            double estimatedCo2SavedKg,
            String summary
    ) {
    }

    /**
     * Overall composite operational health score ({@code 0..100}) across availability,
     * freshness, supplier OTIF, and SHA-256 ledger integrity.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param overallScore weighted composite integer score ({@code 0..100})
     * @param letterGrade grade letter ({@code A+}, {@code A}, {@code B}, {@code C}, {@code D})
     * @param availabilityScore stock availability subscore ({@code 0..100})
     * @param freshnessScore expiry &amp; shrinkage freshness subscore ({@code 0..100})
     * @param supplierScore vendor OTIF subscore ({@code 0..100})
     * @param securityScore audit seal integrity subscore ({@code 0..100})
     * @param headline diagnostic badge text
     */
    public record StoreHealthScoreReport(
            int overallScore,
            String letterGrade,
            double availabilityScore,
            double freshnessScore,
            double supplierScore,
            double securityScore,
            String headline
    ) {
    }
}
