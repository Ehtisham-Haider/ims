package com.stockpro.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.FxRateQuote;
import com.stockpro.repository.DatabaseManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Zero-cost Multi-Currency Foreign Exchange service using Frankfurter ({@code api.frankfurter.dev})
 * with automatic SQLite caching in {@code fx_rates_cache} and deterministic offline fallback rates.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class MultiCurrencyFxService {

    private static final String FRANKFURTER_URL = "https://api.frankfurter.dev/v1/latest?base=USD";
    private static final String ER_API_FALLBACK_URL = "https://open.er-api.com/v6/latest/USD";

    private static final Map<String, Double> DEFAULT_OFFLINE_RATES = new LinkedHashMap<>();

    static {
        DEFAULT_OFFLINE_RATES.put("USD", 1.00);
        DEFAULT_OFFLINE_RATES.put("EUR", 0.92);
        DEFAULT_OFFLINE_RATES.put("GBP", 0.79);
        DEFAULT_OFFLINE_RATES.put("CNY", 7.24);
        DEFAULT_OFFLINE_RATES.put("PKR", 278.50);
        DEFAULT_OFFLINE_RATES.put("AED", 3.67);
        DEFAULT_OFFLINE_RATES.put("CAD", 1.39);
        DEFAULT_OFFLINE_RATES.put("JPY", 153.80);
    }

    private final DatabaseManager dbManager;
    private final Function<String, String> httpFetcher;

    /**
     * Constructs the multi-currency FX service using default JDK {@link HttpClient}.
     *
     * @param dbManager SQLite connection manager
     */
    public MultiCurrencyFxService(DatabaseManager dbManager) {
        this(dbManager, MultiCurrencyFxService::defaultHttpGet);
    }

    /**
     * Constructs the FX service with an injectable HTTP transport (useful for deterministic offline tests).
     *
     * @param dbManager SQLite connection manager
     * @param httpFetcher function fetching JSON payload for a given URL
     */
    public MultiCurrencyFxService(DatabaseManager dbManager, Function<String, String> httpFetcher) {
        this.dbManager = dbManager;
        this.httpFetcher = httpFetcher != null ? httpFetcher : MultiCurrencyFxService::defaultHttpGet;
        seedDefaultsIfEmpty();
    }

    /**
     * Seeds default offline fallback rates into {@code fx_rates_cache} if missing.
     */
    public void seedDefaultsIfEmpty() {
        String now = LocalDateTime.now().toString();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR IGNORE INTO fx_rates_cache (currency_code, rate_from_usd, source, updated_at) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, Double> e : DEFAULT_OFFLINE_RATES.entrySet()) {
                ps.setString(1, e.getKey());
                ps.setDouble(2, e.getValue());
                ps.setString(3, "OFFLINE_FALLBACK");
                ps.setString(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to seed offline FX rates.", ex);
        }
    }

    /**
     * Attempts to pull live FX rates relative to USD from Frankfurter or open.er-api.com
     * and caches all received quotes in SQLite; retains cached offline rates on network failure.
     *
     * @return operation result summarizing updated quotes or offline fallback notice
     */
    public InventoryOperationResult<List<FxRateQuote>> refreshRatesFromNetwork() {
        String payload = null;
        String sourceName = "FRANKFURTER_LIVE";
        try {
            payload = httpFetcher.apply(FRANKFURTER_URL);
        } catch (Exception ignored) {
            payload = null;
        }
        if (payload == null || payload.isBlank()) {
            try {
                payload = httpFetcher.apply(ER_API_FALLBACK_URL);
                sourceName = "OPEN_ER_API_LIVE";
            } catch (Exception ignored) {
                payload = null;
            }
        }
        if (payload == null || payload.isBlank()) {
            return InventoryOperationResult.ok(listCachedRates(),
                    "Live FX endpoint unreachable; using cached offline FX rates.");
        }

        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            JsonObject rates = root.has("rates") && root.get("rates").isJsonObject()
                    ? root.getAsJsonObject("rates")
                    : null;
            if (rates == null || rates.entrySet().isEmpty()) {
                return InventoryOperationResult.ok(listCachedRates(),
                        "Live FX response missing rates object; using cached FX rates.");
            }
            String now = LocalDateTime.now().toString();
            int count = 0;
            upsertRate("USD", 1.0, sourceName, now);
            for (Map.Entry<String, JsonElement> entry : rates.entrySet()) {
                String code = entry.getKey().trim().toUpperCase(Locale.ROOT);
                if (code.length() == 3 && entry.getValue().isJsonPrimitive()) {
                    double rate = entry.getValue().getAsDouble();
                    if (rate > 0.0) {
                        upsertRate(code, rate, sourceName, now);
                        count++;
                    }
                }
            }
            return InventoryOperationResult.ok(listCachedRates(),
                    "Refreshed " + count + " FX rates from " + sourceName + ".");
        } catch (Exception ex) {
            return InventoryOperationResult.ok(listCachedRates(),
                    "Could not parse live FX response (" + ex.getMessage() + "); retaining cached FX rates.");
        }
    }

    /**
     * Inserts or updates a currency conversion rate relative to {@code 1.0 USD}.
     *
     * @param currencyCode 3-letter ISO currency code
     * @param rateFromUsd units of target currency per USD
     * @param source attribution label
     * @return persisted {@link FxRateQuote} wrapped in {@link InventoryOperationResult}
     */
    public InventoryOperationResult<FxRateQuote> upsertRate(String currencyCode, double rateFromUsd, String source) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return InventoryOperationResult.fail("Currency code cannot be blank.");
        }
        if (rateFromUsd <= 0.0) {
            return InventoryOperationResult.fail("FX rate must be positive.");
        }
        String code = currencyCode.trim().toUpperCase(Locale.ROOT);
        String src = source != null && !source.isBlank() ? source : "MANUAL_OVERRIDE";
        String now = LocalDateTime.now().toString();
        upsertRate(code, rateFromUsd, src, now);
        return InventoryOperationResult.ok(new FxRateQuote(code, rateFromUsd, src, now),
                "Saved FX rate 1 USD = " + rateFromUsd + " " + code);
    }

    /**
     * Lists all cached currency quotes sorted alphabetically with USD first.
     *
     * @return list of {@link FxRateQuote}
     */
    public List<FxRateQuote> listCachedRates() {
        List<FxRateQuote> quotes = new ArrayList<>();
        String sql = "SELECT currency_code, rate_from_usd, source, updated_at FROM fx_rates_cache "
                + "ORDER BY CASE WHEN currency_code = 'USD' THEN 0 ELSE 1 END, currency_code ASC";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                quotes.add(new FxRateQuote(
                        rs.getString("currency_code"),
                        rs.getDouble("rate_from_usd"),
                        rs.getString("source"),
                        rs.getString("updated_at")
                ));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load cached FX rates.", ex);
        }
        return quotes;
    }

    /**
     * Resolves an FX quote for the given ISO currency code, falling back cleanly if missing.
     *
     * @param currencyCode ISO currency code such as {@code PKR} or {@code EUR}
     * @return matching {@link FxRateQuote}
     */
    public Optional<FxRateQuote> findRate(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return Optional.empty();
        }
        String code = currencyCode.trim().toUpperCase(Locale.ROOT);
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT currency_code, rate_from_usd, source, updated_at FROM fx_rates_cache WHERE currency_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new FxRateQuote(
                            rs.getString("currency_code"),
                            rs.getDouble("rate_from_usd"),
                            rs.getString("source"),
                            rs.getString("updated_at")
                    ));
                }
            }
        } catch (SQLException ex) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Converts a monetary amount between any two supported currencies via USD pivot.
     *
     * @param amount source monetary value
     * @param fromCurrency source ISO currency code
     * @param toCurrency target ISO currency code
     * @return converted amount rounded to 4 decimal places
     */
    public double convert(double amount, String fromCurrency, String toCurrency) {
        String from = fromCurrency == null ? "USD" : fromCurrency.trim().toUpperCase(Locale.ROOT);
        String to = toCurrency == null ? "USD" : toCurrency.trim().toUpperCase(Locale.ROOT);
        if (from.equals(to)) {
            return roundFour(amount);
        }
        double fromRate = findRate(from).map(FxRateQuote::rateFromUsd)
                .orElse(DEFAULT_OFFLINE_RATES.getOrDefault(from, 1.0));
        double toRate = findRate(to).map(FxRateQuote::rateFromUsd)
                .orElse(DEFAULT_OFFLINE_RATES.getOrDefault(to, 1.0));
        double usd = fromRate <= 0.0 ? amount : amount / fromRate;
        return roundFour(usd * toRate);
    }

    private void upsertRate(String code, double rate, String source, String now) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT OR REPLACE INTO fx_rates_cache (currency_code, rate_from_usd, source, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, code);
            ps.setDouble(2, rate);
            ps.setString(3, source);
            ps.setString(4, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to upsert FX rate for " + code, ex);
        }
    }

    private static String defaultHttpGet(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() >= 200 && resp.statusCode() < 300 ? resp.body() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private double roundFour(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
