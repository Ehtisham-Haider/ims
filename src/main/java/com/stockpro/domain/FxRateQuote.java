package com.stockpro.domain;

/**
 * Cached or live foreign-exchange quote relative to USD ({@code 1 USD = rateFromUsd units}).
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param currencyCode ISO-4217 uppercase currency code (e.g., {@code EUR}, {@code GBP}, {@code PKR})
 * @param rateFromUsd units of target currency per {@code 1.0 USD}
 * @param source quote source ({@code FRANKFURTER_LIVE}, {@code OFFLINE_FALLBACK}, {@code MANUAL_OVERRIDE})
 * @param updatedAt ISO-8601 timestamp when quote was recorded
 */
public record FxRateQuote(
        String currencyCode,
        double rateFromUsd,
        String source,
        String updatedAt
) {
    /**
     * Converts a USD base amount into this currency.
     *
     * @param usdAmount amount denominated in USD
     * @return converted amount in this currency
     */
    public double fromUsd(double usdAmount) {
        return usdAmount * rateFromUsd;
    }

    /**
     * Converts a foreign amount denominated in this currency back to USD.
     *
     * @param foreignAmount amount denominated in {@link #currencyCode()}
     * @return equivalent USD amount
     */
    public double toUsd(double foreignAmount) {
        return rateFromUsd <= 0.0 ? foreignAmount : foreignAmount / rateFromUsd;
    }
}
