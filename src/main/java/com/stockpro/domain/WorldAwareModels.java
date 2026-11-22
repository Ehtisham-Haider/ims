package com.stockpro.domain;

import java.util.List;

/**
 * Container holding immutable DTO records produced by Branch 08
 * World-Aware AI &amp; Generative AI tools (Open-Meteo weather stocking alerts,
 * Nager.Date &amp; festival planners, Google Trends RSS matching, and Product SEO copy).
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class WorldAwareModels {

    private WorldAwareModels() {
    }

    /**
     * Weather-driven category stocking adjustment derived from 7-day temperature and rainfall data.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param conditionHeadline short weather summary (e.g., {@code Heatwave 37C Expected})
     * @param maxTempC peak 7-day forecast temperature in Celsius
     * @param totalRainMm cumulative 7-day rainfall in millimeters
     * @param suggestedMultiplier demand multiplier recommended for affected categories
     * @param impactedCategories store categories affected by upcoming weather
     * @param matchedSkus catalog product names matching affected categories
     * @param source {@code OPEN_METEO_LIVE} or {@code OFFLINE_SEASONAL_FALLBACK}
     * @param guidance actionable inventory stocking note
     */
    public record WeatherStockAlert(
            String conditionHeadline,
            double maxTempC,
            double totalRainMm,
            double suggestedMultiplier,
            List<String> impactedCategories,
            List<String> matchedSkus,
            String source,
            String guidance
    ) {
    }

    /**
     * Upcoming holiday or retail festival surge multiplier window.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param holidayName public holiday or seasonal event name
     * @param dateIso ISO-8601 date string ({@code yyyy-MM-dd})
     * @param daysAway calendar days remaining from reference date
     * @param demandMultiplier expected demand multiplier ({@code >= 1.10})
     * @param targetCategories primary store categories boosted
     * @param source {@code NAGER_DATE_LIVE} or {@code CULTURAL_CALENDAR_CACHE}
     */
    public record HolidayDemandBoost(
            String holidayName,
            String dateIso,
            long daysAway,
            double demandMultiplier,
            List<String> targetCategories,
            String source
    ) {
    }

    /**
     * Rising regional consumer trend topic matched against live catalog items.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param trendKeyword trending search term
     * @param source {@code GOOGLE_TRENDS_RSS} or {@code OFFLINE_RETAIL_PULSE}
     * @param matchingProductNames matching products found in inventory
     * @param opportunityPitch merchandising recommendation
     */
    public record LocalTrendMatch(
            String trendKeyword,
            String source,
            List<String> matchingProductNames,
            String opportunityPitch
    ) {
    }

    /**
     * Auto-generated retail product copy and comma-separated SEO discovery tags.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param description polished 2-sentence product listing summary
     * @param seoTags comma-separated searchable tags
     * @param usedCloudAi {@code true} when generated via Groq Cloud LLM
     */
    public record ProductCopySeo(
            String description,
            String seoTags,
            boolean usedCloudAi
    ) {
    }
}
