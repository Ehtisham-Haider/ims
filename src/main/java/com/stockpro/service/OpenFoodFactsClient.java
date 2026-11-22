package com.stockpro.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Free no-key Open Food Facts REST adapter resolving global retail barcodes (EAN-13 / UPC-A)
 * into product titles, brands, and categories with deterministic offline fallback entries.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class OpenFoodFactsClient {

    private static final String OFF_ENDPOINT_PREFIX = "https://world.openfoodfacts.org/api/v2/product/";

    private static final Map<String, BarcodeLookupResult> OFFLINE_SEED_BARCODES = Map.of(
            "5449000000996", new BarcodeLookupResult(true, "5449000000996", "Coca-Cola Original Taste 330ml", "Drinks", "Coca-Cola", "OFFLINE_CACHE"),
            "3017620422003", new BarcodeLookupResult(true, "3017620422003", "Nutella Hazelnut Spread 400g", "Groceries", "Ferrero", "OFFLINE_CACHE"),
            "7622210449283", new BarcodeLookupResult(true, "7622210449283", "Oreo Original Cookies 154g", "Snacks", "Mondelez", "OFFLINE_CACHE"),
            "8901058852427", new BarcodeLookupResult(true, "8901058852427", "Maggi 2-Minute Noodles 70g", "Groceries", "Nestle", "OFFLINE_CACHE")
    );

    private final Function<String, String> httpFetcher;

    /**
     * Structured Open Food Facts barcode lookup result.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param found {@code true} when barcode metadata was resolved
     * @param barcode queried barcode digits
     * @param productName resolved product name
     * @param category mapped StockPro store category
     * @param brand manufacturer brand
     * @param source {@code OPENFOODFACTS_LIVE} or {@code OFFLINE_CACHE}
     */
    public record BarcodeLookupResult(
            boolean found,
            String barcode,
            String productName,
            String category,
            String brand,
            String source
    ) {
    }

    /**
     * Constructs an Open Food Facts client using the default JDK {@link HttpClient}.
     */
    public OpenFoodFactsClient() {
        this(OpenFoodFactsClient::defaultHttpGet);
    }

    /**
     * Constructs an Open Food Facts client with an injectable HTTP transport for offline tests.
     *
     * @param httpFetcher JSON GET helper taking full endpoint URL
     */
    public OpenFoodFactsClient(Function<String, String> httpFetcher) {
        this.httpFetcher = httpFetcher != null ? httpFetcher : OpenFoodFactsClient::defaultHttpGet;
    }

    /**
     * Resolves a retail barcode via Open Food Facts v2 API, falling back seamlessly to offline catalog entries.
     *
     * @param barcode EAN-13, UPC-A, or Code-128 numeric string
     * @return {@link BarcodeLookupResult}
     */
    public BarcodeLookupResult lookupBarcode(String barcode) {
        String clean = barcode == null ? "" : barcode.replaceAll("[^0-9A-Za-z-]", "").trim();
        if (clean.isBlank()) {
            return new BarcodeLookupResult(false, "", "", "General", "", "NONE");
        }
        try {
            String raw = httpFetcher.apply(OFF_ENDPOINT_PREFIX + clean + ".json");
            if (raw != null && !raw.isBlank()) {
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                int status = root.has("status") ? root.get("status").getAsInt() : 0;
                if (status == 1 && root.has("product") && root.get("product").isJsonObject()) {
                    JsonObject prod = root.getAsJsonObject("product");
                    String name = prod.has("product_name") ? prod.get("product_name").getAsString().trim() : "";
                    String brands = prod.has("brands") ? prod.get("brands").getAsString().trim() : "Retail Brand";
                    String rawCats = prod.has("categories") ? prod.get("categories").getAsString().toLowerCase() : "";
                    String mappedCat = mapCategory(rawCats);
                    if (!name.isBlank()) {
                        return new BarcodeLookupResult(true, clean, name, mappedCat, brands, "OPENFOODFACTS_LIVE");
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through cleanly to deterministic offline cache
        }
        return OFFLINE_SEED_BARCODES.getOrDefault(
                clean,
                new BarcodeLookupResult(false, clean, "", "General", "", "OFFLINE_MISS")
        );
    }

    private String mapCategory(String rawCats) {
        if (rawCats.contains("beverag") || rawCats.contains("drink") || rawCats.contains("soda") || rawCats.contains("tea")) {
            return "Drinks";
        }
        if (rawCats.contains("dairy") || rawCats.contains("milk") || rawCats.contains("cheese") || rawCats.contains("yogurt")) {
            return "Dairy";
        }
        if (rawCats.contains("snack") || rawCats.contains("biscuit") || rawCats.contains("chocolate")) {
            return "Snacks";
        }
        if (rawCats.contains("bread") || rawCats.contains("bak")) {
            return "Bakery";
        }
        return "Groceries";
    }

    private static String defaultHttpGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "StockProIMS/2.0 (inventory-app)")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300 ? resp.body() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
