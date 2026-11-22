package com.stockpro.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.UserRole;
import com.stockpro.repository.AuditLogRepository;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.StockMovementRepository;
import com.stockpro.repository.UserRepository;
import com.stockpro.service.AlertDispatcherService.DispatchLogEntry;
import com.stockpro.service.AlertDispatcherService.TransportResponse;
import com.stockpro.util.I18nManager;
import javafx.geometry.NodeOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic offline integration tests for Branch 11:
 * English/Urdu RTL internationalization bundles, Telegram &amp; Gmail alert dispatch logging,
 * per-user SQLite column preferences, and localhost HMAC-SHA256 JWT REST API server.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
class V2AlertsI18nApiUxTest {

    @TempDir
    Path tempDir;

    @Test
    void i18nBundlesSupportEnglishLtrAndUrduRtlWithSettingsPersistence() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch11-i18n.db"));
        try {
            SettingsRepository settingsRepo = new SettingsRepository(db);
            I18nManager i18n = I18nManager.getInstance();

            assertEquals("Inventory", i18n.get("nav.inventory", "en"));
            assertFalse(i18n.get("nav.inventory", "ur").isBlank());
            assertEquals(NodeOrientation.LEFT_TO_RIGHT, i18n.getNodeOrientation("en"));
            assertEquals(NodeOrientation.RIGHT_TO_LEFT, i18n.getNodeOrientation("ur"));

            String applied = i18n.applyLanguage("ur", null, settingsRepo);
            assertEquals("ur", applied);
            assertEquals("ur", settingsRepo.get("app.language", ""));
            assertEquals(NodeOrientation.RIGHT_TO_LEFT, i18n.getNodeOrientation(applied));
        } finally {
            I18nManager.getInstance().setActiveLanguage("en");
            db.close();
        }
    }

    @Test
    void telegramAndGmailAlertsLogDispatchHistoryAndRespectSeverityGate() {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch11-alerts.db"));
        try {
            SettingsRepository settings = new SettingsRepository(db);
            settings.set("alerts.telegram.token", "123:test-token");
            settings.set("alerts.telegram.chat_id", "-999888");
            settings.set("alerts.gmail.address", "store@example.com");
            settings.set("alerts.gmail.app_password", "app-pass-xyz");
            settings.set("alerts.gmail.recipient", "owner@example.com");

            AlertDispatcherService dispatcher = new AlertDispatcherService(
                    db,
                    settings,
                    (url, payload) -> new TransportResponse(200, "{\"ok\":true}"),
                    (from, pass, to, subj, body) -> "Mock Gmail 250 OK to " + to
            );

            List<DispatchLogEntry> skipped = dispatcher.dispatchMultiChannelAlert(
                    "HIGH", "INFO", "Minor note", "Routine update");
            assertTrue(skipped.isEmpty(), "INFO alert must be suppressed when min severity is HIGH");

            List<DispatchLogEntry> sent = dispatcher.dispatchMultiChannelAlert(
                    "HIGH", "CRITICAL", "Stockout Warning", "Whole Milk 1L reached 0 units");
            assertEquals(2, sent.size());
            assertTrue(sent.stream().allMatch(DispatchLogEntry::delivered));
            assertEquals(2, dispatcher.getRecentLogs(10).size());
        } finally {
            db.close();
        }
    }

    @Test
    void columnPreferencesPersistPerUserAndLoopbackJwtRestApiHandlesAuthItemsAndAdjust() throws Exception {
        DatabaseManager db = DatabaseManager.createForFile(tempDir.resolve("v2-branch11-api.db"));
        LocalRestApiServer apiServer = null;
        try {
            ProductRepository productRepo = new ProductRepository(db);
            UserRepository userRepo = new UserRepository(db);
            SettingsRepository settingsRepo = new SettingsRepository(db);
            StockMovementRepository moveRepo = new StockMovementRepository(db);
            AuditService auditService = new AuditService(new AuditLogRepository(db));
            StockOperationService stockOps = new StockOperationService(
                    productRepo, moveRepo, auditService, InventoryEventBus.getInstance());

            UserColumnPreferenceService colService = new UserColumnPreferenceService(db);
            Map<String, Boolean> prefs = new LinkedHashMap<>();
            prefs.put("SKU", true);
            prefs.put("Buy Price", false);
            prefs.put("Expiry", false);
            colService.savePreferences("ehtisham", prefs);

            Map<String, Boolean> loaded = colService.loadPreferences("ehtisham",
                    Map.of("SKU", true, "Buy Price", true, "Expiry", true));
            assertFalse(loaded.get("Buy Price"));
            assertTrue(loaded.get("SKU"));

            Product tea = new Product("Assam Loose Tea 250g", 10, StockStatus.IN_STOCK);
            tea.setSku("API-TEA-01");
            tea.setSellPrice(5.5);
            Product savedTea = productRepo.insert(tea);

            apiServer = new LocalRestApiServer(productRepo, stockOps, userRepo, settingsRepo);
            int port = apiServer.start(0);
            assertTrue(apiServer.isRunning());
            assertTrue(port > 0);

            HttpClient http = HttpClient.newHttpClient();

            // Unauthenticated /items rejected with 401
            HttpResponse<String> unauth = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/items")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());

            // Issue valid Owner JWT
            String jwt = apiServer.generateJwt("owner", UserRole.OWNER, 600);
            assertTrue(apiServer.verifyJwt(jwt).isPresent());

            // Fetch items with Bearer JWT
            HttpResponse<String> itemsRes = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/items?q=API-TEA"))
                            .header("Authorization", "Bearer " + jwt)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, itemsRes.statusCode());
            JsonObject itemsJson = JsonParser.parseString(itemsRes.body()).getAsJsonObject();
            assertEquals(1, itemsJson.get("count").getAsInt());

            // Adjust stock via REST POST
            String adjustBody = "{\"productId\":" + savedTea.getId()
                    + ",\"type\":\"RESTOCK\",\"quantity\":7,\"reason\":\"REST API POS sync\"}";
            HttpResponse<String> adjustRes = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/stock/adjust"))
                            .header("Authorization", "Bearer " + jwt)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(adjustBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, adjustRes.statusCode());
            assertEquals(17, productRepo.findById(savedTea.getId()).orElseThrow().getQuantity());
        } finally {
            if (apiServer != null) {
                apiServer.stop();
            }
            db.close();
        }
    }
}
