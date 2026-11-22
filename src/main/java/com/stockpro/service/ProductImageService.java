package com.stockpro.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.SettingsRepository;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Resolves, caches, and persists product photography using an optional user-supplied
 * Pexels API key (stored privately in local SQLite settings or {@code PEXELS_API_KEY}),
 * with automatic offline high-DPI category artwork generation when offline or unconfigured.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ProductImageService {
    private static final String PEXELS_SEARCH_ENDPOINT = "https://api.pexels.com/v1/search";

    /** Settings key under which the user's private Pexels API key is persisted locally. */
    public static final String SETTINGS_KEY_PEXELS_API = "pexels.api_key";

    private static final int MAX_NETWORK_FETCHES_PER_BATCH = 30;
    private static final int MAX_VARIANTS_PER_QUERY = 5;
    private static final Pattern HASH_NUMBER_PATTERN = Pattern.compile("#\\d+\\b");
    private static final Pattern PACK_SIZE_PATTERN = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:kg|g|ml|l|oz|pack|pcs|lb)\\b");
    private static final Pattern PAREN_COUNT_PATTERN = Pattern.compile("\\(\\s*\\d+\\s*\\)");
    private static final Pattern DEMO_ADJECTIVE_PATTERN = Pattern.compile(
            "(?i)\\b(?:fresh|organic|premium|compact|classic|ultra|family|daily|pro)\\b");
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9]+");

    private static final Map<String, String> VISUAL_QUERY_MAP = buildVisualQueryMap();
    private static final Map<String, Color[]> CATEGORY_PALETTE = buildCategoryPalette();

    private final SettingsRepository settingsRepository;
    private final ProductRepository productRepository;
    private final Path cacheRootDir;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, Object> slugLocks = new ConcurrentHashMap<>();

    /**
     * Outcome returned from testing Pexels API connectivity.
     *
     * @param success {@code true} when Pexels accepted the API key and returned photos
     * @param message actionable diagnostic summary
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record PexelsTestResult(boolean success, String message) {
    }

    /**
     * Constructs an image resolution service using the default local cache under {@code data/product-images}.
     *
     * @param settingsRepository persisted local settings repository
     * @param productRepository product repository for persisting resolved image paths
     */
    public ProductImageService(SettingsRepository settingsRepository, ProductRepository productRepository) {
        this(settingsRepository, productRepository, Path.of("data", "product-images"));
    }

    /**
     * Constructs an image resolution service targeting a specific disk cache directory.
     *
     * @param settingsRepository persisted local settings repository
     * @param productRepository product repository for persisting resolved image paths
     * @param cacheRootDir base folder for {@code pexels/} and {@code generated/} image caches
     */
    public ProductImageService(
            SettingsRepository settingsRepository,
            ProductRepository productRepository,
            Path cacheRootDir) {
        this.settingsRepository = settingsRepository;
        this.productRepository = productRepository;
        this.cacheRootDir = cacheRootDir != null ? cacheRootDir : Path.of("data", "product-images");
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Resolves the active Pexels API key from local SQLite settings or the {@code PEXELS_API_KEY} environment variable.
     *
     * @return trimmed API key, or empty string if unconfigured
     */
    public String resolveApiKey() {
        String configured = settingsRepository != null
                ? settingsRepository.get(SETTINGS_KEY_PEXELS_API, "")
                : "";
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String envKey = System.getenv("PEXELS_API_KEY");
        return envKey != null ? envKey.trim() : "";
    }

    /**
     * Returns whether a Pexels API key is configured for live photo downloads.
     *
     * @return {@code true} when an API key is present
     */
    public boolean isApiKeyConfigured() {
        return !resolveApiKey().isBlank();
    }

    /**
     * Persists the user's private Pexels API key in local SQLite settings.
     *
     * @param apiKey Pexels API key
     */
    public void saveApiKey(String apiKey) {
        if (settingsRepository != null) {
            settingsRepository.set(SETTINGS_KEY_PEXELS_API, apiKey != null ? apiKey.trim() : "");
        }
    }

    /**
     * Clears any saved Pexels API key from local SQLite settings.
     */
    public void clearApiKey() {
        if (settingsRepository != null) {
            settingsRepository.set(SETTINGS_KEY_PEXELS_API, "");
        }
    }

    /**
     * Verifies connectivity and key validity against the Pexels Search API.
     *
     * @param candidateApiKey API key to verify
     * @return structured {@link PexelsTestResult}
     */
    public PexelsTestResult testConnection(String candidateApiKey) {
        String key = candidateApiKey != null ? candidateApiKey.trim() : "";
        if (key.isBlank()) {
            return new PexelsTestResult(false, "Enter your free Pexels API key first.");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(PEXELS_SEARCH_ENDPOINT + "?query=grocery&per_page=1&orientation=square"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", key)
                    .header("Accept", "application/json")
                    .header("User-Agent", "StockPro-IMS/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String url = extractPhotoUrlFromPexelsJson(response.body());
                if (url != null) {
                    return new PexelsTestResult(true, "Connected to Pexels API — real product photos enabled!");
                }
                return new PexelsTestResult(false, "Pexels API connected, but no photo URL was returned.");
            }
            return switch (response.statusCode()) {
                case 401 -> new PexelsTestResult(false, "Invalid Pexels API key (HTTP 401).");
                case 403 -> new PexelsTestResult(false, "Pexels access forbidden (HTTP 403).");
                case 429 -> new PexelsTestResult(false, "Pexels rate limit reached (HTTP 429). Try again shortly.");
                default -> new PexelsTestResult(false, "Pexels HTTP " + response.statusCode() + ".");
            };
        } catch (Exception ex) {
            String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            return new PexelsTestResult(false, "Cannot reach api.pexels.com: " + msg);
        }
    }

    /**
     * Normalizes product names and demo SKUs into clean photography search queries.
     *
     * @param productName product title
     * @param category product category fallback
     * @return concise Pexels search phrase
     */
    public static String buildSearchQuery(String productName, String category) {
        String raw = productName != null ? productName.toLowerCase(Locale.ROOT) : "";
        String cleaned = HASH_NUMBER_PATTERN.matcher(raw).replaceAll(" ");
        cleaned = PAREN_COUNT_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = PACK_SIZE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = DEMO_ADJECTIVE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[^a-z0-9\\s-]", " ").replaceAll("\\s+", " ").trim();

        for (Map.Entry<String, String> entry : VISUAL_QUERY_MAP.entrySet()) {
            if (cleaned.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        if (category != null && !category.isBlank()) {
            return category.trim().toLowerCase(Locale.ROOT);
        }
        return "grocery product";
    }

    /**
     * Converts a search query into a deterministic filesystem-safe cache slug.
     *
     * @param query normalized search phrase
     * @return lowercase hyphenated slug up to 48 characters
     */
    public static String toSlug(String query) {
        if (query == null || query.isBlank()) {
            return "product";
        }
        String slug = NON_ALNUM_PATTERN.matcher(query.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.isBlank()) {
            return "product";
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48).replaceAll("-+$", "");
        }
        return slug.isBlank() ? "product" : slug;
    }

    /**
     * Computes a deterministic visual variant slot in {@code [0, 4]} from the normalized product name
     * (ignoring trailing {@code #123} numbering) so different variants of a product receive distinct photos/art.
     *
     * @param productName product title
     * @return zero-based variant index
     */
    public static int variantSlot(String productName) {
        if (productName == null || productName.isBlank()) {
            return 0;
        }
        String stable = HASH_NUMBER_PATTERN.matcher(productName.toLowerCase(Locale.ROOT))
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
        if (stable.isBlank()) {
            return 0;
        }
        return Math.floorMod(stable.hashCode(), MAX_VARIANTS_PER_QUERY);
    }

    /**
     * Returns the maximum number of distinct photo/art variant slots allocated per product query family.
     *
     * @return variant slot count ({@value #MAX_VARIANTS_PER_QUERY})
     */
    public static int getMaxVariantsPerQuery() {
        return MAX_VARIANTS_PER_QUERY;
    }

    /**
     * Extracts up to {@code maxCount} distinct thumbnail URLs from a Pexels {@code /v1/search} JSON payload.
     *
     * @param responseBody raw JSON payload
     * @param maxCount maximum number of photo URLs to extract
     * @return ordered list of distinct image URLs (possibly empty)
     */
    public static List<String> extractPhotoUrlsFromPexelsJson(String responseBody, int maxCount) {
        if (responseBody == null || responseBody.isBlank() || maxCount <= 0) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!root.has("photos") || !root.get("photos").isJsonArray()) {
                return List.of();
            }
            JsonArray photos = root.getAsJsonArray("photos");
            Set<String> urls = new LinkedHashSet<>();
            for (int i = 0; i < photos.size() && urls.size() < maxCount; i++) {
                if (!photos.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject item = photos.get(i).getAsJsonObject();
                if (!item.has("src") || !item.get("src").isJsonObject()) {
                    continue;
                }
                JsonObject src = item.getAsJsonObject("src");
                for (String variant : List.of("medium", "small", "tiny", "large")) {
                    if (src.has(variant) && !src.get(variant).isJsonNull()) {
                        String url = src.get(variant).getAsString();
                        if (url != null && !url.isBlank()) {
                            urls.add(url.trim());
                            break;
                        }
                    }
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(urls));
        } catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * Extracts the best thumbnail photo URL from a Pexels {@code /v1/search} JSON response.
     *
     * @param responseBody raw JSON payload
     * @return medium/small/tiny image URL, or {@code null} when absent
     */
    public static String extractPhotoUrlFromPexelsJson(String responseBody) {
        List<String> urls = extractPhotoUrlsFromPexelsJson(responseBody, 1);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /**
     * Returns whether the given image path points inside the local generated placeholder cache.
     *
     * @param photoPath stored product image path
     * @return {@code true} if path points to a generated category artwork PNG
     */
    public static boolean isGeneratedPlaceholder(String photoPath) {
        if (photoPath == null || photoPath.isBlank()) {
            return false;
        }
        String norm = photoPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return norm.contains("/generated/") || norm.endsWith("/generated");
    }

    /**
     * Returns whether the given path points to a legacy v1 monogram placeholder outside {@code generated/v2/}.
     *
     * @param photoPath stored product image path
     * @return {@code true} for legacy monogram placeholders eligible for automatic upgrade
     */
    public static boolean isLegacyGeneratedPlaceholder(String photoPath) {
        if (!isGeneratedPlaceholder(photoPath)) {
            return false;
        }
        String norm = photoPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return !norm.contains("/generated/v2/");
    }

    /**
     * Determines whether a product's image path should be generated or upgraded via Pexels.
     *
     * @param photoPath current stored image path
     * @return {@code true} if missing on disk, legacy v1 placeholder, or upgradable when Pexels is active
     */
    public boolean needsImageResolution(String photoPath) {
        if (photoPath == null || photoPath.isBlank()) {
            return true;
        }
        try {
            if (!Files.exists(Path.of(photoPath))) {
                return true;
            }
        } catch (InvalidPathException ex) {
            return true;
        }
        if (isLegacyGeneratedPlaceholder(photoPath)) {
            return true;
        }
        return isApiKeyConfigured() && isGeneratedPlaceholder(photoPath);
    }

    /**
     * Resolves a cached Pexels photo or local high-DPI artwork PNG path for the given item metadata.
     *
     * @param productName product title
     * @param category product category
     * @param allowPexelsNetwork whether live HTTP lookup against Pexels is permitted
     * @return absolute local path to a valid raster image file
     */
    public String resolveImagePath(String productName, String category, boolean allowPexelsNetwork) {
        String query = buildSearchQuery(productName, category);
        String slug = toSlug(query);
        int slot = variantSlot(productName);
        Object lock = slugLocks.computeIfAbsent(slug, k -> new Object());
        synchronized (lock) {
            Path pexelsDir = cacheRootDir.resolve("pexels");
            List<Path> cachedPexels = collectValidPexelsVariants(pexelsDir, slug);
            if (cachedPexels.isEmpty() && allowPexelsNetwork && isApiKeyConfigured()) {
                downloadPexelsVariantPool(query, category, slug);
                cachedPexels = collectValidPexelsVariants(pexelsDir, slug);
            }
            if (!cachedPexels.isEmpty()) {
                Path chosen = cachedPexels.get(Math.floorMod(slot, cachedPexels.size()));
                return chosen.toAbsolutePath().toString();
            }

            String catPrefix = toSlug(category);
            String genSlug = toSlug(catPrefix + "-" + slug + "-v" + slot);
            Path generatedFile = cacheRootDir.resolve("generated").resolve("v2").resolve(genSlug + ".png");
            if (!isValidImageFile(generatedFile)) {
                generateLocalProductArtwork(generatedFile, productName, category, query, slot);
            }
            return generatedFile.toAbsolutePath().toString();
        }
    }

    /**
     * Resolves an image for a product (using Pexels when configured) and persists the path in SQLite if updated.
     *
     * @param product target product
     * @return resolved absolute local image path
     */
    public String ensureProductImageAndPersist(Product product) {
        if (product == null) {
            return null;
        }
        String resolved = resolveImagePath(product.getName(), product.getCategory(), isApiKeyConfigured());
        if (resolved != null && !resolved.equals(product.getPhotoPath())) {
            product.setPhotoPath(resolved);
            if (productRepository != null && product.getId() > 0) {
                productRepository.updatePhotoPath(product.getId(), resolved);
            }
        }
        return resolved;
    }

    /**
     * Resolves missing or placeholder images across active catalog items, grouping by slug so identical
     * product lines share one Pexels API search (yielding up to 5 distinct photos) and stay well below rate limits.
     *
     * @return number of catalog products updated with new image paths
     */
    public int enrichMissingCatalogImages() {
        if (productRepository == null) {
            return 0;
        }
        List<Product> all = productRepository.findAll(false);
        Map<String, List<Product>> bySlug = new LinkedHashMap<>();
        for (Product p : all) {
            if (needsImageResolution(p.getPhotoPath())) {
                String slug = toSlug(buildSearchQuery(p.getName(), p.getCategory()));
                bySlug.computeIfAbsent(slug, k -> new ArrayList<>()).add(p);
            }
        }
        if (bySlug.isEmpty()) {
            return 0;
        }

        int updated = 0;
        int networkFetches = 0;
        boolean hasKey = isApiKeyConfigured();
        Path pexelsDir = cacheRootDir.resolve("pexels");
        for (Map.Entry<String, List<Product>> entry : bySlug.entrySet()) {
            String slug = entry.getKey();
            List<Product> group = entry.getValue();
            Product representative = group.get(0);

            boolean alreadyCached = !collectValidPexelsVariants(pexelsDir, slug).isEmpty();
            if (hasKey && !alreadyCached && networkFetches < MAX_NETWORK_FETCHES_PER_BATCH) {
                resolveImagePath(representative.getName(), representative.getCategory(), true);
                if (!collectValidPexelsVariants(pexelsDir, slug).isEmpty()) {
                    networkFetches++;
                }
            }

            for (Product p : group) {
                String resolved = resolveImagePath(p.getName(), p.getCategory(), false);
                if (resolved != null && !resolved.equals(p.getPhotoPath())) {
                    p.setPhotoPath(resolved);
                    if (productRepository.updatePhotoPath(p.getId(), resolved)) {
                        updated++;
                    }
                }
            }
        }
        return updated;
    }

    private List<Path> collectValidPexelsVariants(Path pexelsDir, String slug) {
        List<Path> found = new ArrayList<>();
        for (int i = 0; i < MAX_VARIANTS_PER_QUERY; i++) {
            Path p = pexelsDir.resolve(slug + "-" + i + ".jpg");
            if (isValidImageFile(p)) {
                found.add(p);
            }
        }
        if (found.isEmpty()) {
            Path legacy = pexelsDir.resolve(slug + ".jpg");
            if (isValidImageFile(legacy)) {
                found.add(legacy);
            }
        }
        return found;
    }

    private int downloadPexelsVariantPool(String primaryQuery, String category, String slug) {
        String key = resolveApiKey();
        if (key.isBlank()) {
            return 0;
        }
        List<String> urls = queryPexelsPhotoUrls(primaryQuery, key, MAX_VARIANTS_PER_QUERY);
        if (urls.isEmpty() && category != null && !category.isBlank()
                && !category.equalsIgnoreCase(primaryQuery)) {
            urls = queryPexelsPhotoUrls(category.trim(), key, MAX_VARIANTS_PER_QUERY);
        }
        if (urls.isEmpty()) {
            return 0;
        }
        Path pexelsDir = cacheRootDir.resolve("pexels");
        int savedIdx = 0;
        for (String url : urls) {
            if (savedIdx >= MAX_VARIANTS_PER_QUERY) {
                break;
            }
            try {
                HttpRequest imgReq = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "StockPro-IMS/1.0")
                        .GET()
                        .build();
                HttpResponse<byte[]> imgResp = httpClient.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());
                if (imgResp.statusCode() >= 200 && imgResp.statusCode() < 300
                        && imgResp.body() != null && imgResp.body().length > 512) {
                    Files.createDirectories(pexelsDir);
                    Path targetFile = pexelsDir.resolve(slug + "-" + savedIdx + ".jpg");
                    Files.write(targetFile, imgResp.body());
                    savedIdx++;
                }
            } catch (Exception ignored) {
                // Continue saving remaining variant candidates cleanly.
            }
        }
        return savedIdx;
    }

    private List<String> queryPexelsPhotoUrls(String query, String key, int maxCount) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(PEXELS_SEARCH_ENDPOINT
                            + "?query=" + encoded
                            + "&per_page=" + maxCount
                            + "&orientation=square"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", key)
                    .header("Accept", "application/json")
                    .header("User-Agent", "StockPro-IMS/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return extractPhotoUrlsFromPexelsJson(resp.body(), maxCount);
            }
        } catch (Exception ignored) {
            // Fall through to category fallback or local artwork.
        }
        return List.of();
    }

    private static boolean isValidImageFile(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) > 256;
        } catch (IOException ex) {
            return false;
        }
    }

    private static void generateLocalProductArtwork(
            Path targetFile,
            String productName,
            String category,
            String cleanedQuery,
            int variantSlot) {
        try {
            Files.createDirectories(targetFile.getParent());
            int size = 240;
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            String catKey = category != null && !category.isBlank() ? category.trim() : "General";
            Color[] baseColors = CATEGORY_PALETTE.getOrDefault(
                    catKey.toLowerCase(Locale.ROOT),
                    new Color[]{new Color(0x4F, 0x46, 0xE5), new Color(0x7C, 0x3A, 0xED)});
            String slugKey = toSlug(cleanedQuery);
            Color[] tuned = tuneVariantPalette(baseColors[0], baseColors[1], slugKey, variantSlot);

            g.setPaint(new GradientPaint(0, 0, tuned[0], size, size, tuned[1]));
            g.fillRect(0, 0, size, size);

            // Soft decorative light rings
            g.setColor(new Color(255, 255, 255, 26));
            g.fill(new Ellipse2D.Double(size * 0.54, -size * 0.14, size * 0.58, size * 0.58));
            g.fill(new Ellipse2D.Double(-size * 0.16, size * 0.62, size * 0.45, size * 0.45));

            // Center translucent emblem badge
            double badgeSize = 88;
            double cx = size / 2.0;
            double cy = 74.0;
            double bx = cx - badgeSize / 2.0;
            double by = cy - badgeSize / 2.0;
            g.setColor(new Color(255, 255, 255, 44));
            g.fill(new Ellipse2D.Double(bx, by, badgeSize, badgeSize));
            g.setColor(new Color(255, 255, 255, 96));
            g.draw(new Ellipse2D.Double(bx, by, badgeSize, badgeSize));

            // Distinct product silhouette icon inside emblem
            drawProductVectorIcon(g, cleanedQuery, cx, cy);

            // Short product label below emblem
            String labelSource = (productName != null && !productName.isBlank()) ? productName : cleanedQuery;
            String shortTitle = formatShortTitle(labelSource, cleanedQuery);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            FontMetrics fmTitle = g.getFontMetrics();
            String fitted = truncateText(shortTitle, fmTitle, size - 26);
            int tx = (size - fmTitle.stringWidth(fitted)) / 2;
            g.drawString(fitted, tx, 150);

            // Bottom rounded category pill
            String catPillText = catKey.toUpperCase(Locale.ROOT);
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            FontMetrics fmCat = g.getFontMetrics();
            int pillW = Math.min(size - 32, fmCat.stringWidth(catPillText) + 24);
            int pillX = (size - pillW) / 2;
            int pillY = 176;
            g.setColor(new Color(15, 23, 42, 76));
            g.fillRoundRect(pillX, pillY, pillW, 26, 26, 26);
            g.setColor(Color.WHITE);
            g.drawString(catPillText, (size - fmCat.stringWidth(catPillText)) / 2, pillY + 17);

            g.dispose();
            ImageIO.write(img, "png", targetFile.toFile());
        } catch (IOException ignored) {
            // Safe fallback if storage is read-only.
        }
    }

    private static Color[] tuneVariantPalette(Color c0, Color c1, String slug, int variantSlot) {
        int hash = slug != null ? Math.abs(slug.hashCode()) : 0;
        float hueOffset = (((hash % 13) - 6) * 0.011f) + ((variantSlot - 2) * 0.018f);
        float brightOffset = ((variantSlot % 3) - 1) * 0.04f;
        return new Color[]{
                shiftColor(c0, hueOffset, brightOffset),
                shiftColor(c1, hueOffset * 0.85f, -brightOffset * 0.75f)
        };
    }

    private static Color shiftColor(Color base, float hueDelta, float brightDelta) {
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        float h = (hsb[0] + hueDelta) % 1.0f;
        if (h < 0f) {
            h += 1.0f;
        }
        float s = Math.max(0.35f, Math.min(0.95f, hsb[1]));
        float b = Math.max(0.38f, Math.min(0.96f, hsb[2] + brightDelta));
        return Color.getHSBColor(h, s, b);
    }

    private static void drawProductVectorIcon(Graphics2D g, String cleanedQuery, double cx, double cy) {
        String q = cleanedQuery != null ? cleanedQuery.toLowerCase(Locale.ROOT) : "";
        Stroke prevStroke = g.getStroke();
        g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.WHITE);
        try {
            if (q.contains("milk") || q.contains("oil") || q.contains("soap")
                    || q.contains("detergent") || q.contains("cola") || q.contains("bottle")) {
                g.draw(new RoundRectangle2D.Double(cx - 5, cy - 20, 10, 5, 3, 3));
                g.drawLine((int) Math.round(cx - 4), (int) Math.round(cy - 15), (int) Math.round(cx - 9), (int) Math.round(cy - 8));
                g.drawLine((int) Math.round(cx + 4), (int) Math.round(cy - 15), (int) Math.round(cx + 9), (int) Math.round(cy - 8));
                g.draw(new RoundRectangle2D.Double(cx - 10, cy - 8, 20, 27, 7, 7));
                g.drawLine((int) Math.round(cx - 6), (int) Math.round(cy + 3), (int) Math.round(cx + 6), (int) Math.round(cy + 3));
            } else if (q.contains("coffee") || q.contains("tea") || q.contains("cup")) {
                g.draw(new RoundRectangle2D.Double(cx - 12, cy - 5, 20, 20, 6, 6));
                g.draw(new Arc2D.Double(cx + 5, cy - 2, 10, 13, -90, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Double(cx - 7, cy - 17, 6, 9, 40, 140, Arc2D.OPEN));
                g.draw(new Arc2D.Double(cx + 1, cy - 17, 6, 9, 40, 140, Arc2D.OPEN));
            } else if (q.contains("bread") || q.contains("croissant") || q.contains("bakery")) {
                g.draw(new Arc2D.Double(cx - 16, cy - 12, 32, 24, 0, 180, Arc2D.OPEN));
                g.draw(new RoundRectangle2D.Double(cx - 16, cy - 1, 32, 14, 8, 8));
                g.drawLine((int) Math.round(cx - 8), (int) Math.round(cy - 7), (int) Math.round(cx - 4), (int) Math.round(cy - 2));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 8), (int) Math.round(cx + 4), (int) Math.round(cy - 2));
                g.drawLine((int) Math.round(cx + 8), (int) Math.round(cy - 7), (int) Math.round(cx + 11), (int) Math.round(cy - 2));
            } else if (q.contains("cheese")) {
                Polygon wedge = new Polygon(
                        new int[]{(int) Math.round(cx - 16), (int) Math.round(cx + 16), (int) Math.round(cx + 8)},
                        new int[]{(int) Math.round(cy + 12), (int) Math.round(cy + 12), (int) Math.round(cy - 14)},
                        3);
                g.draw(wedge);
                g.draw(new Ellipse2D.Double(cx - 3, cy + 1, 6, 6));
                g.draw(new Ellipse2D.Double(cx + 5, cy - 3, 4, 4));
            } else if (q.contains("egg")) {
                g.draw(new Ellipse2D.Double(cx - 13, cy - 14, 11, 13));
                g.draw(new Ellipse2D.Double(cx + 2, cy - 14, 11, 13));
                g.draw(new Ellipse2D.Double(cx - 13, cy + 2, 11, 13));
                g.draw(new Ellipse2D.Double(cx + 2, cy + 2, 11, 13));
            } else if (q.contains("yogurt") || q.contains("bowl")) {
                g.draw(new Arc2D.Double(cx - 16, cy - 11, 32, 26, 180, 180, Arc2D.CHORD));
                g.drawLine((int) Math.round(cx + 4), (int) Math.round(cy - 2), (int) Math.round(cx + 14), (int) Math.round(cy - 15));
            } else if (q.contains("chocolate")) {
                g.draw(new RoundRectangle2D.Double(cx - 11, cy - 17, 22, 34, 4, 4));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 16), (int) Math.round(cx), (int) Math.round(cy + 16));
                g.drawLine((int) Math.round(cx - 10), (int) Math.round(cy - 5), (int) Math.round(cx + 10), (int) Math.round(cy - 5));
                g.drawLine((int) Math.round(cx - 10), (int) Math.round(cy + 6), (int) Math.round(cx + 10), (int) Math.round(cy + 6));
            } else if (q.contains("speaker") || q.contains("bluetooth")) {
                g.draw(new RoundRectangle2D.Double(cx - 12, cy - 17, 24, 34, 6, 6));
                g.draw(new Ellipse2D.Double(cx - 7, cy - 1, 14, 14));
                g.fill(new Ellipse2D.Double(cx - 2.5, cy - 12, 5, 5));
            } else if (q.contains("mouse")) {
                g.draw(new RoundRectangle2D.Double(cx - 10, cy - 16, 20, 32, 18, 18));
                g.drawLine((int) Math.round(cx - 10), (int) Math.round(cy - 2), (int) Math.round(cx + 10), (int) Math.round(cy - 2));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 12), (int) Math.round(cx), (int) Math.round(cy - 6));
            } else if (q.contains("keyboard")) {
                g.draw(new RoundRectangle2D.Double(cx - 17, cy - 10, 34, 20, 5, 5));
                for (int kx = -11; kx <= 11; kx += 7) {
                    g.fill(new RoundRectangle2D.Double(cx + kx - 2, cy - 5, 4, 4, 1, 1));
                }
                g.drawLine((int) Math.round(cx - 8), (int) Math.round(cy + 4), (int) Math.round(cx + 8), (int) Math.round(cy + 4));
            } else if (q.contains("usb") || q.contains("cable")) {
                g.draw(new RoundRectangle2D.Double(cx - 6, cy - 16, 12, 11, 3, 3));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 5), (int) Math.round(cx), (int) Math.round(cy + 16));
                g.draw(new Arc2D.Double(cx - 10, cy + 2, 20, 14, 180, 180, Arc2D.OPEN));
            } else if (q.contains("power bank")) {
                g.draw(new RoundRectangle2D.Double(cx - 10, cy - 15, 20, 31, 6, 6));
                g.drawLine((int) Math.round(cx - 4), (int) Math.round(cy - 18), (int) Math.round(cx + 4), (int) Math.round(cy - 18));
                Polygon bolt = new Polygon(
                        new int[]{(int) Math.round(cx + 1), (int) Math.round(cx - 4), (int) Math.round(cx), (int) Math.round(cx - 1), (int) Math.round(cx + 4), (int) Math.round(cx)},
                        new int[]{(int) Math.round(cy - 7), (int) Math.round(cy + 1), (int) Math.round(cy + 1), (int) Math.round(cy + 9), (int) Math.round(cy + 1), (int) Math.round(cy + 1)},
                        6);
                g.fill(bolt);
            } else if (q.contains("bulb") || q.contains("led")) {
                g.draw(new Arc2D.Double(cx - 11, cy - 16, 22, 22, -45, 270, Arc2D.OPEN));
                g.draw(new RoundRectangle2D.Double(cx - 6, cy + 6, 12, 9, 3, 3));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 21), (int) Math.round(cx), (int) Math.round(cy - 18));
                g.drawLine((int) Math.round(cx - 16), (int) Math.round(cy - 11), (int) Math.round(cx - 13), (int) Math.round(cy - 9));
                g.drawLine((int) Math.round(cx + 13), (int) Math.round(cy - 9), (int) Math.round(cx + 16), (int) Math.round(cy - 11));
            } else if (q.contains("stand") || q.contains("smartphone")) {
                g.draw(new RoundRectangle2D.Double(cx - 9, cy - 16, 18, 25, 4, 4));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy + 9), (int) Math.round(cx), (int) Math.round(cy + 16));
                g.drawLine((int) Math.round(cx - 10), (int) Math.round(cy + 16), (int) Math.round(cx + 10), (int) Math.round(cy + 16));
            } else if (q.contains("notebook")) {
                g.draw(new RoundRectangle2D.Double(cx - 11, cy - 16, 22, 32, 4, 4));
                g.drawLine((int) Math.round(cx - 4), (int) Math.round(cy - 7), (int) Math.round(cx + 7), (int) Math.round(cy - 7));
                g.drawLine((int) Math.round(cx - 4), (int) Math.round(cy), (int) Math.round(cx + 7), (int) Math.round(cy));
                g.drawLine((int) Math.round(cx - 4), (int) Math.round(cy + 7), (int) Math.round(cx + 5), (int) Math.round(cy + 7));
            } else if (q.contains("rice") || q.contains("chips") || q.contains("almond")
                    || q.contains("grain") || q.contains("snack")) {
                g.drawLine((int) Math.round(cx - 10), (int) Math.round(cy - 14), (int) Math.round(cx + 10), (int) Math.round(cy - 14));
                g.draw(new RoundRectangle2D.Double(cx - 12, cy - 10, 24, 26, 8, 8));
                g.draw(new Ellipse2D.Double(cx - 4, cy - 2, 8, 8));
            } else {
                g.draw(new RoundRectangle2D.Double(cx - 14, cy - 14, 28, 28, 6, 6));
                g.drawLine((int) Math.round(cx - 14), (int) Math.round(cy - 4), (int) Math.round(cx + 14), (int) Math.round(cy - 4));
                g.drawLine((int) Math.round(cx), (int) Math.round(cy - 14), (int) Math.round(cx), (int) Math.round(cy - 4));
            }
        } finally {
            g.setStroke(prevStroke);
        }
    }

    private static String formatShortTitle(String productName, String cleanedQuery) {
        String source = (productName != null && !productName.isBlank()) ? productName : cleanedQuery;
        String stripped = HASH_NUMBER_PATTERN.matcher(source).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        if (stripped.isBlank()) {
            return "Store Item";
        }
        return stripped;
    }

    private static String truncateText(String text, FontMetrics fm, int maxWidth) {
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String out = text;
        while (out.length() > 3 && fm.stringWidth(out + "...") > maxWidth) {
            out = out.substring(0, out.length() - 1).trim();
        }
        return out + "...";
    }

    private static Map<String, String> buildVisualQueryMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("rice", "rice grain bag");
        m.put("milk", "milk bottle");
        m.put("croissant", "butter croissant bakery");
        m.put("bread", "artisan bread loaf");
        m.put("chips", "potato chips snack");
        m.put("cola", "cola soda drink");
        m.put("eggs", "fresh eggs carton");
        m.put("usb-c cable", "usb cable");
        m.put("usb cable", "usb cable");
        m.put("usb-c", "usb cable");
        m.put("phone stand", "smartphone desk stand");
        m.put("desk stand", "smartphone desk stand");
        m.put("olive oil", "olive oil bottle");
        m.put("coffee beans", "roasted coffee beans");
        m.put("green tea", "green tea cup");
        m.put("yogurt", "yogurt bowl");
        m.put("cheese", "cheddar cheese");
        m.put("laundry gel", "laundry detergent bottle");
        m.put("laundry detergent", "laundry detergent bottle");
        m.put("hand soap", "liquid hand soap bottle");
        m.put("bluetooth speaker", "bluetooth speaker");
        m.put("power bank", "portable power bank");
        m.put("led bulb", "led light bulb");
        m.put("keyboard", "computer keyboard");
        m.put("mouse", "wireless computer mouse");
        m.put("notebook", "spiral notebook");
        m.put("almond", "roasted almonds nuts");
        m.put("chocolate", "dark chocolate bar");
        return m;
    }

    private static Map<String, Color[]> buildCategoryPalette() {
        Map<String, Color[]> p = new LinkedHashMap<>();
        p.put("groceries", new Color[]{new Color(0x05, 0x96, 0x69), new Color(0x10, 0xB9, 0x81)});
        p.put("dairy", new Color[]{new Color(0x02, 0x84, 0xC7), new Color(0x38, 0xBD, 0xF8)});
        p.put("bakery", new Color[]{new Color(0xD9, 0x77, 0x06), new Color(0xF5, 0x9E, 0x0B)});
        p.put("snacks", new Color[]{new Color(0xE1, 0x1D, 0x48), new Color(0xF9, 0x73, 0x16)});
        p.put("drinks", new Color[]{new Color(0x08, 0x91, 0xB2), new Color(0x25, 0x63, 0xEB)});
        p.put("electronics", new Color[]{new Color(0x4F, 0x46, 0xE5), new Color(0x7C, 0x3A, 0xED)});
        p.put("accessories", new Color[]{new Color(0x7C, 0x3A, 0xED), new Color(0xC0, 0x26, 0xD3)});
        p.put("household", new Color[]{new Color(0x0F, 0x76, 0x6E), new Color(0x02, 0x84, 0xC7)});
        return p;
    }
}
