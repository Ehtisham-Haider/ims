package com.stockpro.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;
import com.stockpro.repository.UserRepository;
import com.stockpro.util.AppLogger;
import com.stockpro.util.PasswordUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Embedded pure-JDK loopback HTTP REST API server ({@code 127.0.0.1}) secured with HMAC-SHA256 JWT
 * tokens, enabling external POS terminals or scripts to inspect health, fetch catalog items,
 * and submit audited stock adjustments without leaving localhost.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class LocalRestApiServer {

    private static final Logger LOG = AppLogger.get(LocalRestApiServer.class);
    private static final Gson GSON = new Gson();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private final ProductRepository productRepository;
    private final StockOperationService stockOperationService;
    private final UserRepository userRepository;
    private final SettingsRepository settingsRepository;

    private volatile HttpServer server;
    private volatile int activePort = -1;
    private volatile String hmacSecret;

    /**
     * Decoded JWT principal claims extracted after HMAC signature validation.
     *
     * @param username subject username
     * @param role staff {@link UserRole}
     * @param expiresAtEpochSec expiration UNIX timestamp
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     */
    public record JwtClaims(String username, UserRole role, long expiresAtEpochSec) {
    }

    /**
     * Constructs a local REST API server bound to repositories and stock mutation services.
     *
     * @param productRepository catalog repository
     * @param stockOperationService audited stock mutation service
     * @param userRepository staff authentication repository
     * @param settingsRepository settings repository storing secret key &amp; port
     */
    public LocalRestApiServer(
            ProductRepository productRepository,
            StockOperationService stockOperationService,
            UserRepository userRepository,
            SettingsRepository settingsRepository) {
        this.productRepository = productRepository;
        this.stockOperationService = stockOperationService;
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.hmacSecret = loadOrInitSecret();
    }

    /**
     * Starts the loopback REST server on {@code 127.0.0.1} at the given port (use {@code 0} for ephemeral port).
     *
     * @param port local TCP port (0..65535)
     * @return bound local port number
     * @throws IOException if socket binding fails
     */
    public synchronized int start(int port) throws IOException {
        if (server != null) {
            return activePort;
        }
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Math.max(0, port));
        HttpServer created = HttpServer.create(loopback, 0);
        created.setExecutor(Executors.newFixedThreadPool(4));
        created.createContext("/api/v1/health", this::handleHealth);
        created.createContext("/api/v1/auth/token", this::handleToken);
        created.createContext("/api/v1/items", this::handleItems);
        created.createContext("/api/v1/stock/adjust", this::handleStockAdjust);
        created.start();
        this.server = created;
        this.activePort = created.getAddress().getPort();
        LOG.info("Local REST API server listening on http://127.0.0.1:{}", activePort);
        return activePort;
    }

    /**
     * Stops the loopback REST server gracefully if running.
     */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            activePort = -1;
            LOG.info("Local REST API server stopped.");
        }
    }

    /**
     * Returns whether the embedded HTTP server is currently running.
     *
     * @return {@code true} when active
     */
    public boolean isRunning() {
        return server != null;
    }

    /**
     * Returns the bound localhost TCP port, or {@code -1} when stopped.
     *
     * @return active port number
     */
    public int getPort() {
        return activePort;
    }

    /**
     * Generates a signed HMAC-SHA256 JWT token valid for the specified lifetime.
     *
     * @param username staff username
     * @param role staff role
     * @param ttlSeconds token time-to-live in seconds
     * @return compact {@code header.payload.signature} JWT string
     */
    public String generateJwt(String username, UserRole role, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        long exp = now + Math.max(60L, ttlSeconds);
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", username);
        payload.put("role", role.name());
        payload.put("iat", now);
        payload.put("exp", exp);

        String headerPart = B64_ENC.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadPart = B64_ENC.encodeToString(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = headerPart + "." + payloadPart;
        String sigPart = B64_ENC.encodeToString(hmacSha256(signingInput));
        return signingInput + "." + sigPart;
    }

    /**
     * Verifies signature integrity and expiration on an incoming Bearer JWT token.
     *
     * @param token compact JWT string
     * @return {@link Optional} containing {@link JwtClaims} if valid, otherwise empty
     */
    public Optional<JwtClaims> verifyJwt(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] expectedSig = hmacSha256(parts[0] + "." + parts[1]);
            byte[] actualSig = B64_DEC.decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return Optional.empty();
            }
            String payloadStr = new String(B64_DEC.decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(payloadStr).getAsJsonObject();
            long exp = obj.get("exp").getAsLong();
            if (Instant.now().getEpochSecond() > exp) {
                return Optional.empty();
            }
            String sub = obj.get("sub").getAsString();
            UserRole role = UserRole.valueOf(obj.get("role").getAsString());
            return Optional.of(new JwtClaims(sub, role, exp));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        sendJson(exchange, 200, Map.of(
                "status", "UP",
                "service", "StockPro Local REST API",
                "version", "2.0",
                "host", "127.0.0.1",
                "port", activePort
        ));
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        try {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
            String username = body.has("username") ? body.get("username").getAsString() : "";
            String password = body.has("password") ? body.get("password").getAsString() : "";

            Optional<User> userOpt = userRepository.findByUsername(username.trim());
            if (userOpt.isEmpty() || !userOpt.get().isActive()
                    || !PasswordUtil.verify(password, userOpt.get().getPasswordHash())) {
                sendJson(exchange, 401, Map.of("error", "Invalid credentials"));
                return;
            }
            User user = userOpt.get();
            String token = generateJwt(user.getUsername(), user.getRole(), 3600L);
            sendJson(exchange, 200, Map.of(
                    "token", token,
                    "username", user.getUsername(),
                    "role", user.getRole().name(),
                    "expiresInSeconds", 3600
            ));
        } catch (Exception ex) {
            sendJson(exchange, 400, Map.of("error", "Malformed JSON auth request"));
        }
    }

    private void handleItems(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "GET required"));
            return;
        }
        Optional<JwtClaims> auth = authenticateBearer(exchange);
        if (auth.isEmpty()) {
            sendJson(exchange, 401, Map.of("error", "Missing or invalid Bearer JWT"));
            return;
        }

        String queryStr = exchange.getRequestURI().getQuery();
        String search = parseQueryParam(queryStr, "q").toLowerCase(Locale.ROOT);
        List<Product> all = productRepository.findAll(false);
        List<Map<String, Object>> payload = new java.util.ArrayList<>();
        for (Product p : all) {
            if (!search.isEmpty()
                    && !p.getName().toLowerCase(Locale.ROOT).contains(search)
                    && !p.getSku().toLowerCase(Locale.ROOT).contains(search)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.getId());
            item.put("sku", p.getSku());
            item.put("name", p.getName());
            item.put("category", p.getCategory());
            item.put("quantity", p.getQuantity());
            item.put("sellPrice", p.getSellPrice());
            item.put("status", p.getStatus().getDisplayName());
            payload.add(item);
        }
        sendJson(exchange, 200, Map.of("count", payload.size(), "items", payload));
    }

    private void handleStockAdjust(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        Optional<JwtClaims> auth = authenticateBearer(exchange);
        if (auth.isEmpty()) {
            sendJson(exchange, 401, Map.of("error", "Missing or invalid Bearer JWT"));
            return;
        }
        if (!auth.get().role().canManageInventory()) {
            sendJson(exchange, 403, Map.of("error", "Role lacks stock adjustment permission"));
            return;
        }

        try {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject body = JsonParser.parseString(raw).getAsJsonObject();
            long productId = body.has("productId") ? body.get("productId").getAsLong() : -1L;
            if (productId <= 0 && body.has("sku")) {
                productId = productRepository.findByBarcode(body.get("sku").getAsString())
                        .map(Product::getId)
                        .orElse(-1L);
            }
            MovementType type = MovementType.valueOf(body.get("type").getAsString().trim().toUpperCase(Locale.ROOT));
            String qtyStr = String.valueOf(body.get("quantity").getAsInt());
            String reason = body.has("reason") ? body.get("reason").getAsString() : "Local REST API update";

            InventoryOperationResult<StockMovement> res = stockOperationService.recordOperation(
                    productId, type, qtyStr, reason, "JWT:" + auth.get().username(), auth.get().username());
            if (!res.isSuccess()) {
                sendJson(exchange, 400, Map.of("error", res.getMessage()));
                return;
            }
            sendJson(exchange, 200, Map.of(
                    "message", res.getMessage(),
                    "productId", productId,
                    "newQuantity", res.getData().getNewQty()
            ));
        } catch (Exception ex) {
            sendJson(exchange, 400, Map.of("error", "Invalid stock adjust JSON payload"));
        }
    }

    private Optional<JwtClaims> authenticateBearer(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return verifyJwt(header.substring("Bearer ".length()).trim());
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    private String loadOrInitSecret() {
        String existing = settingsRepository.get("api.local.jwt_secret", "");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String generated = B64_ENC.encodeToString(bytes);
        settingsRepository.set("api.local.jwt_secret", generated);
        return generated;
    }

    private static String parseQueryParam(String query, String key) {
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equalsIgnoreCase(key)) {
                return pair.substring(eq + 1);
            }
        }
        return "";
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
