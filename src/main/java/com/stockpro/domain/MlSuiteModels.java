package com.stockpro.domain;

/**
 * Container class holding immutable DTO records produced by the Branch 07
 * Advanced Offline Machine Learning Suite (Holt-Winters, OTIF, Dynamic Pricing,
 * Lost-Sale Imputation, Smart Expiry Markdowns, and Apriori Co-Occurrence Rules).
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class MlSuiteModels {

    private MlSuiteModels() {
    }

    /**
     * Holt-Winters triple exponential smoothing 7-day demand prediction with 95% confidence band.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId product database id
     * @param sku SKU code
     * @param productName product title
     * @param next7DaysForecast point forecast for total units sold next 7 days
     * @param lower95Bound conservative lower 95% interval
     * @param upper95Bound optimistic upper 95% interval
     * @param trendSlope daily velocity trend component
     * @param seasonalityNote plain-English seasonality summary
     */
    public record HoltWintersPrediction(
            long productId,
            String sku,
            String productName,
            double next7DaysForecast,
            double lower95Bound,
            double upper95Bound,
            double trendSlope,
            String seasonalityNote
    ) {
    }

    /**
     * Supplier On-Time In-Full (OTIF) score and recency-weighted lead time estimate.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param supplierId supplier database id
     * @param supplierName supplier display name
     * @param evaluatedOrders count of delivered/open orders scored
     * @param onTimeInFullOrders count of fulfilled orders without variance
     * @param otifScorePercent score on {@code 0..100} scale
     * @param predictedDeliveryDays weighted expected lead time in days
     * @param tier reliability classification ({@code ELITE}, {@code RELIABLE}, {@code WATCH})
     */
    public record SupplierOtifScore(
            long supplierId,
            String supplierName,
            int evaluatedOrders,
            int onTimeInFullOrders,
            double otifScorePercent,
            double predictedDeliveryDays,
            String tier
    ) {
    }

    /**
     * Dynamic pricing adjustment suggestion balancing stock cover, velocity, and cost margin floor.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId product database id
     * @param sku product SKU
     * @param productName product display name
     * @param currentSellPrice current retail unit price
     * @param suggestedSellPrice recommended adjusted price
     * @param changePercent signed percentage change
     * @param rationale business explanation
     */
    public record DynamicPriceSuggestion(
            long productId,
            String sku,
            String productName,
            double currentSellPrice,
            double suggestedSellPrice,
            double changePercent,
            String rationale
    ) {
    }

    /**
     * Censored-demand stockout lost-sale estimation showing revenue missed while empty.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId product database id
     * @param sku product SKU
     * @param productName product name
     * @param estimatedOutDays days spent out of stock or critically stalled
     * @param imputedMissedUnits estimated unfulfilled unit demand
     * @param imputedMissedRevenue estimated missed gross sales revenue
     * @param actionHint restock priority note
     */
    public record LostSaleImputation(
            long productId,
            String sku,
            String productName,
            int estimatedOutDays,
            double imputedMissedUnits,
            double imputedMissedRevenue,
            String actionHint
    ) {
    }

    /**
     * Tiered perishable markdown recommendation ({@code 10%}, {@code 20%}, or {@code 35%}).
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productId product database id
     * @param sku product SKU
     * @param productName product name
     * @param daysUntilExpiry remaining calendar days until expiration
     * @param recommendedDiscountPercent suggested clearance discount ({@code 10}, {@code 20}, or {@code 35})
     * @param clearanceUnitPrice discounted retail price
     * @param recoverableRevenue projected recovery value across current units
     */
    public record ExpiryMarkdownSuggestion(
            long productId,
            String sku,
            String productName,
            long daysUntilExpiry,
            int recommendedDiscountPercent,
            double clearanceUnitPrice,
            double recoverableRevenue
    ) {
    }

    /**
     * Apriori association rule mining products frequently purchased together in close checkout windows.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param productAId anchor product id
     * @param productAName anchor product name
     * @param productBId companion product id
     * @param productBName companion product name
     * @param pairCount number of shared baskets/windows
     * @param supportPercent fraction of all baskets containing both SKUs
     * @param confidencePercent probability companion is bought when anchor is bought
     * @param pitch bundle / cross-sell phrasing
     */
    public record BasketAssociationRule(
            long productAId,
            String productAName,
            long productBId,
            String productBName,
            int pairCount,
            double supportPercent,
            double confidencePercent,
            String pitch
    ) {
    }
}
