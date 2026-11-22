package com.stockpro.domain;

/**
 * Purchasing volume-pricing evaluation advising whether increasing order quantity
 * unlocks a cheaper supplier price bracket or enforces an MOQ floor.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param requestedQty originally entered quantity
 * @param enforcedQty quantity after enforcing supplier MOQ floor
 * @param moq minimum order quantity constraint
 * @param applicableUnitPrice active unit cost at {@code enforcedQty}
 * @param baselineUnitPrice standard unit cost prior to volume discount
 * @param nextTierMinQty minimum threshold of next better price break (or {@code 0} if top tier reached)
 * @param nextTierUnitPrice discounted unit cost of next price break
 * @param stepUpTotalSavings net total order savings or unit-cost drop from stepping up (non-negative)
 * @param advisorMessage clear purchasing guidance summary
 */
public record VolumePricingAdvice(
        int requestedQty,
        int enforcedQty,
        int moq,
        double applicableUnitPrice,
        double baselineUnitPrice,
        int nextTierMinQty,
        double nextTierUnitPrice,
        double stepUpTotalSavings,
        String advisorMessage
) {
}
