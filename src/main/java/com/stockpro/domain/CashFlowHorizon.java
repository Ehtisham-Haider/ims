package com.stockpro.domain;

/**
 * Cash-flow projection across a 30-, 60-, or 90-day forward operating window.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param horizonDays forecast horizon length in days ({@code 30}, {@code 60}, or {@code 90})
 * @param committedPoOutflow existing open Purchase Order liabilities due in window
 * @param projectedRestockOutflow estimated recurring replenishment spend
 * @param projectedSalesInflow expected sales cash receipts inferred from historical run rate
 * @param netCashFlow {@code projectedSalesInflow - committedPoOutflow - projectedRestockOutflow}
 * @param projectedCapitalPosition estimated cash/inventory position after horizon
 * @param summary human-readable cash health diagnosis
 */
public record CashFlowHorizon(
        int horizonDays,
        double committedPoOutflow,
        double projectedRestockOutflow,
        double projectedSalesInflow,
        double netCashFlow,
        double projectedCapitalPosition,
        String summary
) {
}
