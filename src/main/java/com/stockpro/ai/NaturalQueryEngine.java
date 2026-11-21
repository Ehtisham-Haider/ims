package com.stockpro.ai;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.InventoryFilterCriteria.StatusFilterChip;
import com.stockpro.domain.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates natural-language filter queries into {@link InventoryFilterCriteria}
 * and builds or parses structured {@link ChartSpec} JSON payloads for live JavaFX chart rendering.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class NaturalQueryEngine {
    private static final Gson GSON = new Gson();
    private static final Pattern PRICE_UNDER = Pattern.compile("(?:under|below|less than|<)\\s*\\$?(\\d+(?:\\.\\d+)?)");

    /**
     * Prevents instantiation of stateless natural-language parser.
     */
    private NaturalQueryEngine() {
    }

    /**
     * Converts casual search phrases (e.g. {@code "cheap snacks running low"}) into active table filter criteria.
     *
     * @param phrase casual natural-language search phrase
     * @param knownCategories catalog categories currently present in store
     * @return populated {@link InventoryFilterCriteria}
     */
    public static InventoryFilterCriteria translateNaturalSearch(String phrase, List<String> knownCategories) {
        InventoryFilterCriteria criteria = new InventoryFilterCriteria();
        if (phrase == null || phrase.isBlank()) {
            return criteria;
        }
        String lower = phrase.toLowerCase(Locale.ROOT).trim();

        // Status detection
        if (lower.contains("out of stock") || lower.contains("empty") || lower.contains("zero stock")) {
            criteria.setStatusChip(StatusFilterChip.OUT_OF_STOCK);
        } else if (lower.contains("running low") || lower.contains("low stock") || lower.contains("low")) {
            criteria.setStatusChip(StatusFilterChip.LOW_STOCK);
        } else if (lower.contains("expir")) {
            criteria.setStatusChip(StatusFilterChip.EXPIRING);
        } else if (lower.contains("in stock") || lower.contains("healthy")) {
            criteria.setStatusChip(StatusFilterChip.IN_STOCK);
        }

        // Price ceiling detection
        Matcher priceMatcher = PRICE_UNDER.matcher(lower);
        if (priceMatcher.find()) {
            criteria.setMaxSellPrice(Double.parseDouble(priceMatcher.group(1)));
        } else if (lower.contains("cheap") || lower.contains("budget") || lower.contains("affordable")) {
            criteria.setMaxSellPrice(5.0);
        }

        // Category detection
        List<String> candidates = knownCategories != null && !knownCategories.isEmpty()
                ? knownCategories
                : List.of("Groceries", "Dairy", "Bakery", "Snacks", "Drinks", "Electronics", "Accessories");
        String matchedCat = null;
        for (String cat : candidates) {
            if (cat != null && !cat.isBlank() && lower.contains(cat.toLowerCase(Locale.ROOT))) {
                matchedCat = cat;
                criteria.setCategory(cat);
                break;
            }
        }

        // Extract remaining meaningful keywords if no category/status matched everything
        if (matchedCat == null && criteria.getStatusChip() == StatusFilterChip.ALL && criteria.getMaxSellPrice() == null) {
            criteria.setSearchQuery(phrase.trim());
        }
        return criteria;
    }

    /**
     * Extracts a {@link ChartSpec} from raw JSON text (if returned by Groq) or returns {@code null} if invalid.
     *
     * @param jsonText raw model output possibly containing JSON
     * @return parsed {@link ChartSpec}, or {@code null}
     */
    public static ChartSpec tryParseChartJson(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return null;
        }
        int start = jsonText.indexOf('{');
        int end = jsonText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String slice = jsonText.substring(start, end + 1);
        try {
            ChartSpec spec = GSON.fromJson(slice, ChartSpec.class);
            if (spec != null && spec.points() != null && !spec.points().isEmpty()) {
                return spec;
            }
        } catch (JsonSyntaxException ignored) {
        }
        return null;
    }

    /**
     * Builds an accurate live {@link ChartSpec} directly from SQLite products based on the user's prompt.
     *
     * @param prompt natural prompt (e.g. {@code "Show a bar chart of top 5 valuable categories"})
     * @param products active catalog products
     * @return live {@link ChartSpec}
     */
    public static ChartSpec buildOfflineChartSpec(String prompt, List<Product> products) {
        String lower = prompt != null ? prompt.toLowerCase(Locale.ROOT) : "";
        String chartType = lower.contains("pie") || lower.contains("donut")
                ? "PIE"
                : (lower.contains("line") || lower.contains("trend") ? "LINE" : "BAR");

        if (lower.contains("categor")) {
            Map<String, Double> byCat = new LinkedHashMap<>();
            for (Product p : products) {
                String cat = p.getCategory() != null && !p.getCategory().isBlank() ? p.getCategory() : "General";
                double val = lower.contains("count") || lower.contains("qty")
                        ? p.getQuantity()
                        : p.getRetailValuation();
                byCat.merge(cat, val, Double::sum);
            }
            List<ChartSpec.ChartPoint> pts = byCat.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> new ChartSpec.ChartPoint(e.getKey(), Math.round(e.getValue() * 100.0) / 100.0))
                    .toList();
            return new ChartSpec(chartType, "Top 5 Categories by Value", pts);
        }

        if (lower.contains("low") || lower.contains("shortage") || lower.contains("empty")) {
            List<ChartSpec.ChartPoint> pts = products.stream()
                    .sorted(Comparator.comparingInt(Product::getQuantity))
                    .limit(6)
                    .map(p -> new ChartSpec.ChartPoint(p.getName(), p.getQuantity()))
                    .toList();
            return new ChartSpec(chartType, "Lowest Stock Items (Units)", pts);
        }

        List<ChartSpec.ChartPoint> pts = products.stream()
                .sorted(Comparator.comparingDouble(Product::getRetailValuation).reversed())
                .limit(5)
                .map(p -> new ChartSpec.ChartPoint(p.getName(), Math.round(p.getRetailValuation() * 100.0) / 100.0))
                .toList();
        if (pts.isEmpty()) {
            pts = new ArrayList<>(List.of(new ChartSpec.ChartPoint("No Items", 0)));
        }
        return new ChartSpec(chartType, "Top 5 Products by Retail Value", pts);
    }
}
