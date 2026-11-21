package com.stockpro.ai;

import java.util.List;

/**
 * Structured chart specification produced by natural-language chart prompts and rendered in JavaFX.
 *
 * @param chartType chart style ({@code "BAR"}, {@code "LINE"}, or {@code "PIE"})
 * @param title chart display title
 * @param points labeled numeric data points
 * @author Ehtisham Haider, Syed Ahmed
 */
public record ChartSpec(String chartType, String title, List<ChartPoint> points) {

    /**
     * Single labeled numeric coordinate inside a {@link ChartSpec}.
     *
     * @param label category, product, or day label
     * @param value numeric metric value
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record ChartPoint(String label, double value) {
    }
}
