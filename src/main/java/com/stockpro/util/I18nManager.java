package com.stockpro.util;

import com.stockpro.repository.SettingsRepository;
import javafx.geometry.NodeOrientation;
import javafx.scene.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UTF-8 internationalization manager supporting English (LTR) and Urdu (RTL) resource bundles
 * with live JavaFX {@link NodeOrientation} switching and SQLite preference persistence.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class I18nManager {

    private static final I18nManager INSTANCE = new I18nManager();

    private final Map<String, ResourceBundle> bundleCache = new ConcurrentHashMap<>();
    private volatile String activeLanguage = "en";

    private I18nManager() {
    }

    /**
     * Returns the singleton {@link I18nManager}.
     *
     * @return shared internationalization manager
     */
    public static I18nManager getInstance() {
        return INSTANCE;
    }

    /**
     * Normalizes supported language codes into {@code "en"} or {@code "ur"}.
     *
     * @param languageCode raw language code or display string
     * @return normalized {@code "en"} or {@code "ur"}
     */
    public String normalizeLanguage(String languageCode) {
        if (languageCode == null) {
            return "en";
        }
        String lower = languageCode.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("ur") || lower.contains("urdu")) {
            return "ur";
        }
        return "en";
    }

    /**
     * Returns the active ISO language code ({@code "en"} or {@code "ur"}).
     *
     * @return current language code
     */
    public String getActiveLanguage() {
        return activeLanguage;
    }

    /**
     * Sets the active language in memory without modifying persisted settings.
     *
     * @param languageCode target language code
     */
    public void setActiveLanguage(String languageCode) {
        this.activeLanguage = normalizeLanguage(languageCode);
    }

    /**
     * Resolves a localized string using the currently active language bundle.
     *
     * @param key translation bundle key
     * @return localized string, or {@code key} if missing
     */
    public String get(String key) {
        return get(key, activeLanguage);
    }

    /**
     * Resolves a localized string for a specific language code using UTF-8 properties loading.
     *
     * @param key translation bundle key
     * @param languageCode language code ({@code "en"} or {@code "ur"})
     * @return localized string, or {@code key} if missing
     */
    public String get(String key, String languageCode) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String norm = normalizeLanguage(languageCode);
        ResourceBundle bundle = loadBundle(norm);
        if (bundle != null && bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        ResourceBundle fallback = loadBundle("en");
        if (fallback != null && fallback.containsKey(key)) {
            return fallback.getString(key);
        }
        return key;
    }

    /**
     * Determines the layout direction for a language code.
     *
     * @param languageCode target language code
     * @return {@link NodeOrientation#RIGHT_TO_LEFT} for Urdu, otherwise {@link NodeOrientation#LEFT_TO_RIGHT}
     */
    public NodeOrientation getNodeOrientation(String languageCode) {
        return "ur".equals(normalizeLanguage(languageCode))
                ? NodeOrientation.RIGHT_TO_LEFT
                : NodeOrientation.LEFT_TO_RIGHT;
    }

    /**
     * Applies layout orientation onto a JavaFX root node without writing settings.
     *
     * @param languageCode target language code
     * @param rootNode target JavaFX root container (nullable)
     */
    public void applyOrientationOnly(String languageCode, Node rootNode) {
        String norm = normalizeLanguage(languageCode);
        this.activeLanguage = norm;
        if (rootNode != null) {
            rootNode.setNodeOrientation(getNodeOrientation(norm));
        }
    }

    /**
     * Switches the active language, updates root {@link NodeOrientation}, and persists {@code app.language}.
     *
     * @param languageCode target language code ({@code "en"} or {@code "ur"})
     * @param rootNode optional JavaFX root node to flip immediately
     * @param settingsRepository optional settings repository to persist selection
     * @return normalized active language code
     */
    public String applyLanguage(String languageCode, Node rootNode, SettingsRepository settingsRepository) {
        String norm = normalizeLanguage(languageCode);
        applyOrientationOnly(norm, rootNode);
        if (settingsRepository != null) {
            settingsRepository.set("app.language", norm);
        }
        return norm;
    }

    private ResourceBundle loadBundle(String normLang) {
        return bundleCache.computeIfAbsent(normLang, code -> {
            String path = "/i18n/messages_" + code + ".properties";
            try (InputStream is = I18nManager.class.getResourceAsStream(path)) {
                if (is == null) {
                    return null;
                }
                return new PropertyResourceBundle(new InputStreamReader(is, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                return null;
            }
        });
    }
}
