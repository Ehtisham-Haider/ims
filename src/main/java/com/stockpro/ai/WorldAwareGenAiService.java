package com.stockpro.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.WorldAwareModels.HolidayDemandBoost;
import com.stockpro.domain.WorldAwareModels.LocalTrendMatch;
import com.stockpro.domain.WorldAwareModels.ProductCopySeo;
import com.stockpro.domain.WorldAwareModels.WeatherStockAlert;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.DashboardMetricsService;
import com.stockpro.service.FinanceService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * World-Aware AI &amp; Generative AI Assistant connecting Open-Meteo 7-day weather forecasts,
 * Nager.Date &amp; regional festival planners, Google Trends RSS matching, Groq-backed SEO copywriting,
 * Executive Report narratives, WhatsApp/SMS clearance promos, and an interactive In-App Help Guide.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class WorldAwareGenAiService {

    private static final Pattern RSS_TITLE = Pattern.compile("(?is)<item>.*?<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>");

    private final ProductRepository productRepository;
    private final GroqApiClient groqApiClient;
    private final DashboardMetricsService metricsService;
    private final FinanceService financeService;
    private final Function<String, String> httpFetcher;

    /**
     * Constructs the World-Aware &amp; Generative AI service using default JDK HTTP client.
     *
     * @param productRepository product catalog repository
     * @param groqApiClient optional Groq cloud LLM client
     * @param metricsService dashboard metrics snapshot aggregator
     * @param financeService store financial summary engine
     */
    public WorldAwareGenAiService(
            ProductRepository productRepository,
            GroqApiClient groqApiClient,
            DashboardMetricsService metricsService,
            FinanceService financeService) {
        this(productRepository, groqApiClient, metricsService, financeService, WorldAwareGenAiService::defaultHttpGet);
    }

    /**
     * Constructs the service with an injectable HTTP transport for deterministic offline verification.
     *
     * @param productRepository product catalog repository
     * @param groqApiClient optional Groq cloud client
     * @param metricsService metrics aggregator
     * @param financeService financial engine
     * @param httpFetcher HTTP GET function returning raw response body or {@code null}
     */
    public WorldAwareGenAiService(
            ProductRepository productRepository,
            GroqApiClient groqApiClient,
            DashboardMetricsService metricsService,
            FinanceService financeService,
            Function<String, String> httpFetcher) {
        this.productRepository = productRepository;
        this.groqApiClient = groqApiClient;
        this.metricsService = metricsService;
        this.financeService = financeService;
        this.httpFetcher = httpFetcher != null ? httpFetcher : WorldAwareGenAiService::defaultHttpGet;
    }

    /**
     * Queries Open-Meteo 7-day forecast for store coordinates and computes weather-smart stocking multipliers.
     *
     * @param latitude store geographic latitude (e.g., {@code 31.5204} for Lahore)
     * @param longitude store geographic longitude (e.g., {@code 74.3587})
     * @return {@link WeatherStockAlert} with category boost guidance and offline fallback
     */
    public WeatherStockAlert analyzeWeatherStocking(double latitude, double longitude) {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&daily=temperature_2m_max,precipitation_sum&forecast_days=7&timezone=auto",
                latitude, longitude);
        double maxTemp = 33.5;
        double totalRain = 2.0;
        String source = "OFFLINE_SEASONAL_FALLBACK";

        try {
            String raw = httpFetcher.apply(url);
            if (raw != null && !raw.isBlank()) {
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("daily") && root.get("daily").isJsonObject()) {
                    JsonObject daily = root.getAsJsonObject("daily");
                    if (daily.has("temperature_2m_max") && daily.get("temperature_2m_max").isJsonArray()) {
                        JsonArray temps = daily.getAsJsonArray("temperature_2m_max");
                        double peak = Double.NEGATIVE_INFINITY;
                        for (JsonElement el : temps) {
                            if (el.isJsonPrimitive()) {
                                peak = Math.max(peak, el.getAsDouble());
                            }
                        }
                        if (peak > -80.0) {
                            maxTemp = peak;
                            source = "OPEN_METEO_LIVE";
                        }
                    }
                    if (daily.has("precipitation_sum") && daily.get("precipitation_sum").isJsonArray()) {
                        JsonArray rains = daily.getAsJsonArray("precipitation_sum");
                        double sum = 0.0;
                        for (JsonElement el : rains) {
                            if (el.isJsonPrimitive()) {
                                sum += Math.max(0.0, el.getAsDouble());
                            }
                        }
                        totalRain = sum;
                    }
                }
            }
        } catch (Exception ignored) {
            // Retain deterministic seasonal fallback
        }

        List<String> categories;
        double mult;
        String headline;
        String advice;
        if (maxTemp >= 32.0) {
            headline = String.format(Locale.US, "Warm / Heat Spike (Peak %.1fC)", maxTemp);
            mult = 1.35;
            categories = List.of("Drinks", "Dairy", "Snacks");
            advice = "Boost chilled beverages, water, and cold dairy buffer by +35% for the next 7 days.";
        } else if (totalRain >= 15.0) {
            headline = String.format(Locale.US, "Heavy Rain Forecast (%.1fmm total)", totalRain);
            mult = 1.25;
            categories = List.of("Household", "Groceries", "Drinks");
            advice = "Stock rain-week essentials, coffee/tea, and household staples (+25% demand buffer).";
        } else {
            headline = String.format(Locale.US, "Moderate Week (Peak %.1fC, Rain %.1fmm)", maxTemp, totalRain);
            mult = 1.10;
            categories = List.of("Groceries", "Bakery");
            advice = "Maintain steady +10% fresh bakery & daily grocery shelf replenishment.";
        }

        List<String> matchedSkus = productRepository.findAll(false).stream()
                .filter(p -> p.getCategory() != null && categories.stream()
                        .anyMatch(c -> c.equalsIgnoreCase(p.getCategory().trim())))
                .limit(6)
                .map(Product::getName)
                .toList();

        return new WeatherStockAlert(
                headline, round1(maxTemp), round1(totalRain), mult, categories, matchedSkus, source, advice
        );
    }

    /**
     * Fetches upcoming public holidays from Nager.Date and blends curated retail festival windows
     * (Ramadan, Eid, Back-to-School) relative to {@code referenceDate}.
     *
     * @param countryCode 2-letter ISO country code (e.g., {@code US}, {@code PK}, {@code GB})
     * @param referenceDate baseline calendar date
     * @return sorted list of {@link HolidayDemandBoost} entries
     */
    public List<HolidayDemandBoost> forecastUpcomingHolidays(String countryCode, LocalDate referenceDate) {
        LocalDate base = referenceDate != null ? referenceDate : LocalDate.now();
        String code = (countryCode != null && !countryCode.isBlank()) ? countryCode.trim().toUpperCase(Locale.ROOT) : "PK";
        List<HolidayDemandBoost> results = new ArrayList<>();

        try {
            String raw = httpFetcher.apply("https://date.nager.at/api/v3/NextPublicHolidays/" + code);
            if (raw != null && raw.trim().startsWith("[")) {
                JsonArray arr = JsonParser.parseString(raw).getAsJsonArray();
                for (int i = 0; i < Math.min(4, arr.size()); i++) {
                    JsonObject obj = arr.get(i).getAsJsonObject();
                    String dateStr = obj.has("date") ? obj.get("date").getAsString() : base.plusDays(14).toString();
                    String name = obj.has("name") ? obj.get("name").getAsString() : "Public Holiday";
                    LocalDate holDate = LocalDate.parse(dateStr);
                    long days = Math.max(0L, ChronoUnit.DAYS.between(base, holDate));
                    results.add(new HolidayDemandBoost(
                            name, dateStr, days, 1.35, List.of("Groceries", "Snacks", "Drinks"), "NAGER_DATE_LIVE"
                    ));
                }
            }
        } catch (Exception ignored) {
            // Merge built-in festival planner below cleanly
        }

        if (results.isEmpty()) {
            results.add(new HolidayDemandBoost(
                    "Festival & Eid Groceries Surge Window",
                    base.plusDays(12).toString(),
                    12,
                    1.55,
                    List.of("Groceries", "Dairy", "Drinks", "Bakery"),
                    "CULTURAL_CALENDAR_CACHE"
            ));
            results.add(new HolidayDemandBoost(
                    "Back-to-School & Office Pantry Peak",
                    base.plusDays(25).toString(),
                    25,
                    1.30,
                    List.of("Accessories", "Snacks", "Drinks"),
                    "CULTURAL_CALENDAR_CACHE"
            ));
            results.add(new HolidayDemandBoost(
                    "Monthly Payday Household Restock Weekend",
                    base.plusDays(6).toString(),
                    6,
                    1.25,
                    List.of("Household", "Groceries"),
                    "CULTURAL_CALENDAR_CACHE"
            ));
        }
        return results;
    }

    /**
     * Parses regional Google Trends RSS items (with offline consumer trend fallback)
     * and matches active SKUs in StockPro inventory.
     *
     * @param geoCode 2-letter trend region code (e.g., {@code PK} or {@code US})
     * @return list of matched {@link LocalTrendMatch} opportunities
     */
    public List<LocalTrendMatch> spotLocalTrends(String geoCode) {
        String geo = (geoCode != null && !geoCode.isBlank()) ? geoCode.trim().toUpperCase(Locale.ROOT) : "PK";
        List<String> keywords = new ArrayList<>();
        String source = "OFFLINE_RETAIL_PULSE";

        try {
            String xml = httpFetcher.apply("https://trends.google.com/trending/rss?geo=" + geo);
            if (xml != null && xml.contains("<item>")) {
                Matcher m = RSS_TITLE.matcher(xml);
                while (m.find() && keywords.size() < 8) {
                    String kw = m.group(1).trim();
                    if (!kw.isBlank()) {
                        keywords.add(kw);
                    }
                }
                if (!keywords.isEmpty()) {
                    source = "GOOGLE_TRENDS_RSS";
                }
            }
        } catch (Exception ignored) {
        }

        if (keywords.isEmpty()) {
            keywords.addAll(List.of("Coffee", "Milk", "Rice", "Wireless", "Chocolate", "Soap"));
        }

        List<Product> products = productRepository.findAll(false);
        List<LocalTrendMatch> matches = new ArrayList<>();
        for (String kw : keywords) {
            String lower = kw.toLowerCase(Locale.ROOT);
            List<String> hits = products.stream()
                    .filter(p -> (p.getName() != null && p.getName().toLowerCase(Locale.ROOT).contains(lower))
                            || (p.getCategory() != null && p.getCategory().toLowerCase(Locale.ROOT).contains(lower))
                            || (p.getTags() != null && p.getTags().toLowerCase(Locale.ROOT).contains(lower)))
                    .limit(4)
                    .map(Product::getName)
                    .toList();
            matches.add(new LocalTrendMatch(
                    kw,
                    source,
                    hits,
                    hits.isEmpty()
                            ? "Regional search spike for '" + kw + "' — consider sourcing trial SKUs."
                            : "Highlight matched SKU(s) " + hits + " on front aisle end-caps while search volume is rising."
            ));
        }
        return matches;
    }

    /**
     * Generates marketing copy and searchable SEO tags for a product form entry,
     * powered by Groq cloud when configured or crisp deterministic retail phrasing offline.
     *
     * @param productName product title
     * @param category product category
     * @param sellPrice retail selling price
     * @return {@link ProductCopySeo} containing copy and comma-separated SEO tags
     */
    public ProductCopySeo generateProductDescriptionAndSeo(String productName, String category, double sellPrice) {
        String cleanName = productName != null && !productName.isBlank() ? productName.trim() : "Store Product";
        String cleanCat = category != null && !category.isBlank() ? category.trim() : "General";

        if (groqApiClient != null && groqApiClient.isApiKeyConfigured()) {
            var cloud = groqApiClient.complete(
                    "Reply in 2 lines only. Line 1: DESCRIPTION: <2 crisp sentences>. Line 2: TAGS: <5 comma-separated lowercase SEO tags>.",
                    "Product: " + cleanName + ", Category: " + cleanCat + ", Price: $" + String.format(Locale.US, "%.2f", sellPrice)
            );
            if (cloud.success() && cloud.content() != null && cloud.content().contains("TAGS:")) {
                String[] parts = cloud.content().split("TAGS:", 2);
                String desc = parts[0].replace("DESCRIPTION:", "").trim();
                String tags = parts[1].trim();
                if (!desc.isBlank() && !tags.isBlank()) {
                    return new ProductCopySeo(desc, tags, true);
                }
            }
        }

        String desc = cleanName + " delivers reliable everyday value in our curated " + cleanCat
                + " collection. Priced competitively at $" + String.format(Locale.US, "%.2f", Math.max(0.0, sellPrice))
                + " with fast aisle availability and verified quality assurance.";
        String baseSlug = cleanName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim().replaceAll("\\s+", ", ");
        String tags = cleanCat.toLowerCase(Locale.ROOT) + ", " + (baseSlug.isBlank() ? "essential" : baseSlug) + ", bestseller, stockpro";
        return new ProductCopySeo(desc, tags, false);
    }

    /**
     * Writes a structured executive narrative for monthly PDF/Analytics reporting combining
     * store metrics, financial margins, and weather/holiday readiness.
     *
     * @param periodLabel report date window label (e.g., {@code Last 30 Days})
     * @return executive narrative text
     */
    public String writeMonthlyExecutiveNarrative(String periodLabel) {
        List<Product> products = productRepository.findAll(false);
        long lowCount = products.stream().filter(p -> p.getStatus() == StockStatus.LOW_STOCK).count();
        long outCount = products.stream().filter(p -> p.getStatus() == StockStatus.OUT_OF_STOCK).count();
        var fin = financeService.computeSummary(60, 15.0);
        var snap = metricsService.loadSnapshot(30);
        WeatherStockAlert weather = analyzeWeatherStocking(31.5204, 74.3587);

        String offlineDraft = String.format(Locale.US,
                "Executive Summary (%s): Managing %d active SKUs valued at $%.2f cost (avg margin %.1f%%). "
                        + "%d low-stock and %d out-of-stock SKUs need prioritized restocking; 30d turnover stands at %d moved units. "
                        + "World-Aware Outlook: %s (%s).",
                periodLabel, products.size(), fin.totalCostValue(), fin.averageMarginPercent(),
                lowCount, outCount, snap.totalProducts(), weather.conditionHeadline(), weather.guidance());

        if (groqApiClient != null && groqApiClient.isApiKeyConfigured()) {
            var cloud = groqApiClient.complete(
                    "Rewrite this retail store executive summary cleanly into 3 concise management bullets without inventing numbers.",
                    offlineDraft
            );
            if (cloud.success() && !cloud.content().isBlank()) {
                return cloud.content().trim();
            }
        }
        return offlineDraft;
    }

    /**
     * Generates ready-to-send clearance campaign copy (WhatsApp, SMS, or Instagram) for stagnant/expiring stock.
     *
     * @param channel distribution medium ({@code WhatsApp}, {@code SMS}, or {@code Instagram})
     * @param product target product to promote
     * @param discountPercent markdown percentage ({@code 10..50})
     * @return formatted promo ad string
     */
    public String generateClearancePromoCopy(String channel, Product product, int discountPercent) {
        String medium = channel != null && !channel.isBlank() ? channel.trim() : "WhatsApp";
        String itemName = product != null ? product.getName() : "Selected Store Favorites";
        double orig = product != null ? Math.max(1.0, product.getSellPrice()) : 10.0;
        int disc = Math.max(5, Math.min(70, discountPercent));
        double sale = orig * (1.0 - disc / 100.0);

        if (groqApiClient != null && groqApiClient.isApiKeyConfigured()) {
            var cloud = groqApiClient.complete(
                    "Write one punchy promotional blurb suitable for " + medium + " using exact figures provided.",
                    String.format(Locale.US, "%s on sale at %d%% OFF! Was $%.2f, Now $%.2f. Limited stock.", itemName, disc, orig, sale)
            );
            if (cloud.success() && !cloud.content().isBlank()) {
                return cloud.content().trim();
            }
        }
        return String.format(Locale.US,
                "[%s Flash Offer] Grab %s today at %d%% OFF — Only $%.2f (was $%.2f)! While current shelf stock lasts.",
                medium, itemName, disc, sale, orig);
    }

    /**
     * Answers user questions about how to operate StockPro modules across Version 1 and Version 2.
     *
     * @param userQuestion help topic query (e.g., {@code "How do I use the 2D warehouse map?"})
     * @return clear step-by-step operator guidance
     */
    public String answerInAppHelpQuestion(String userQuestion) {
        String q = userQuestion == null ? "" : userQuestion.toLowerCase(Locale.ROOT);
        if (q.contains("map") || q.contains("pick") || q.contains("bin") || q.contains("shelf")) {
            return "Warehouse Map Guide: Open 'Warehouse Map' in the sidebar -> click any colored aisle bin (Red = >=85% full) -> inspect weight/volume rings or move SKUs in one click, or click '+ Pick' to compute a Dijkstra shortest walking path.";
        }
        if (q.contains("vision") || q.contains("camera") || q.contains("barcode") || q.contains("drag")) {
            return "Vision Studio Guide: Open Inventory -> click 'AI Vision Studio' (or drag-and-drop any PNG/JPG photo directly onto the Inventory table). Switch among all 6 tabs (Product Recognizer, Shelf Counter, Empty Gap, Damaged Box, Receipt Scanner, Handwritten Note) or test OpenFoodFacts barcodes.";
        }
        if (q.contains("voice") || q.contains("whisper") || q.contains("mic")) {
            return "Voice Control Guide: Click the top-bar 'Voice' button or the Mic icon in AI Chat Studio. Say commands like 'Add 10 Coffee', 'Restock 5 Milk', 'Open Warehouse Map', or 'Find Basmati Rice'.";
        }
        if (q.contains("transfer") || q.contains("branch") || q.contains("rma") || q.contains("3-way")) {
            return "Multi-Branch, RMA & 3-Way Match Guide: Use the header Branch Switcher to switch locations, Stock Operations -> 'Transfers, RMA & ATP' tab for inter-branch transfers and customer returns, and Suppliers & Orders -> '3-Way Check' button to reconcile POs against blind dock counts and invoices.";
        }
        return "StockPro Quick Guide: Use Inventory for CRUD, Variants & Bundles; Stock Operations for Restock/Sales/Transfers/RMA; Warehouse Map for 2D bins & Dijkstra picking; Suppliers & Orders for POs/FX/Landed Cost/Cash Flow; Settings for Groq API keys, Pexels, Encrypted Backups, and the 8 mandatory 30B Lab QA checks.";
    }

    private static String defaultHttpGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "StockProIMS/2.0")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300 ? resp.body() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
