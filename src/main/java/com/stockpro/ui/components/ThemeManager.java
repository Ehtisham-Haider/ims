package com.stockpro.ui.components;

import com.stockpro.service.AppSession;
import javafx.scene.Scene;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Single source of truth for the StockPro visual design system.
 *
 * <p>Stylesheets are split into {@code base.css} (every structural rule, written only against
 * looked-up color tokens) and a tiny token file per theme ({@code light-theme.css} /
 * {@code dark-theme.css}). Canvas-rendered charts cannot read CSS tokens, so the same palette is
 * mirrored here as {@link Color} accessors that always reflect the active theme.</p>
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public final class ThemeManager {
    /** Display typeface used for headings and large numerals. */
    public static final String FONT_DISPLAY = "Red Hat Display";
    /** Body typeface used for all UI text. */
    public static final String FONT_BODY = "Albert Sans";
    /** Shared corner radius (px) used by every component in the design system. */
    public static final double RADIUS = 12;

    private static final String[] FONT_FILES = {
            "AlbertSans-Regular", "AlbertSans-Medium", "AlbertSans-SemiBold", "AlbertSans-Bold", "AlbertSans-ExtraBold",
            "RedHatDisplay-Medium", "RedHatDisplay-SemiBold", "RedHatDisplay-Bold", "RedHatDisplay-ExtraBold"
    };

    private static final Map<String, Paint> HATCH_CACHE = new HashMap<>();
    private static boolean fontsLoaded;

    private ThemeManager() {
    }

    /**
     * Registers the bundled Albert Sans and Red Hat Display font files with the JavaFX runtime.
     * Safe to call repeatedly; fonts are only loaded once.
     */
    public static synchronized void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        for (String name : FONT_FILES) {
            try (InputStream in = ThemeManager.class.getResourceAsStream("/fonts/" + name + ".ttf")) {
                if (in != null) {
                    Font.loadFont(in, 13);
                }
            } catch (Exception ignored) {
                // Falls back to the system font stack declared in base.css.
            }
        }
        fontsLoaded = true;
    }

    /**
     * Applies the base stylesheet plus the active theme's token sheet to a scene, replacing any
     * previously applied StockPro sheets. Works for the main window, dialogs and popups alike.
     *
     * @param scene scene to style (ignored when {@code null})
     */
    public static void apply(Scene scene) {
        if (scene == null) {
            return;
        }
        loadFonts();
        Motion.install(scene);
        // Pre-build the hatch tiles so the first chart animation frame doesn't stall on them.
        hatch(ink(), null);
        hatch(accent(), null);
        hatch(ink(), surface());
        scene.getStylesheets().clear();
        addSheet(scene, "/css/base.css");
        addSheet(scene, isDark() ? "/css/dark-theme.css" : "/css/light-theme.css");
    }

    private static void addSheet(Scene scene, String path) {
        URL url = ThemeManager.class.getResource(path);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }

    /**
     * Returns whether the dark theme is currently active.
     *
     * @return {@code true} in dark mode
     */
    public static boolean isDark() {
        return AppSession.getInstance().isDarkMode();
    }

    // ---------------------------------------------------------------------------------------------
    // Canvas palette (mirrors the CSS tokens in light-theme.css / dark-theme.css)
    // ---------------------------------------------------------------------------------------------

    /**
     * Primary ink color (text, solid bars).
     *
     * @return ink color for the active theme
     */
    public static Color ink() {
        return isDark() ? Color.web("#F2F2F2") : Color.web("#121212");
    }

    /**
     * Secondary/muted text color used for axis labels and captions.
     *
     * @return muted ink color
     */
    public static Color inkMuted() {
        return isDark() ? Color.web("#8F8F8F") : Color.web("#7A7A7A");
    }

    /**
     * Card surface color.
     *
     * @return surface color
     */
    public static Color surface() {
        return isDark() ? Color.web("#0F0F0F") : Color.web("#FFFFFF");
    }

    /**
     * Neutral fill for tracks, empty slices and secondary series.
     *
     * @return neutral fill color
     */
    public static Color neutral() {
        return isDark() ? Color.web("#2A2A2A") : Color.web("#D9D9D9");
    }

    /**
     * Component outline color (mirrors the {@code -sp-border} token).
     *
     * @return border color
     */
    public static Color border() {
        return isDark() ? Color.web("#262626") : Color.web("#4A4A4A");
    }

    /**
     * Grid and baseline color.
     *
     * @return grid line color
     */
    public static Color grid() {
        return isDark() ? Color.web("#232323") : Color.web("#E6E6E6");
    }

    /**
     * Brand accent: deep red in light mode, golden yellow in dark mode.
     *
     * @return accent color
     */
    public static Color accent() {
        return isDark() ? Color.web("#FFC21F") : Color.web("#B3121F");
    }

    /**
     * Readable foreground on top of the accent color.
     *
     * @return on-accent color
     */
    public static Color onAccent() {
        return isDark() ? Color.web("#050505") : Color.web("#FFFFFF");
    }

    /**
     * Tooltip / callout background (inverse surface).
     *
     * @return inverse surface color
     */
    public static Color inverse() {
        return isDark() ? Color.web("#F2F2F2") : Color.web("#141414");
    }

    /**
     * Foreground on the inverse surface.
     *
     * @return on-inverse color
     */
    public static Color onInverse() {
        return isDark() ? Color.web("#0A0A0A") : Color.web("#FFFFFF");
    }

    /**
     * Resolves a semantic series token ({@code ink}, {@code accent}, {@code neutral}, {@code muted})
     * to a theme-aware color; any other value is parsed as a CSS color literal.
     *
     * @param token semantic token or color literal
     * @return resolved color
     */
    public static Color resolve(String token) {
        if (token == null) {
            return ink();
        }
        return switch (token.toLowerCase()) {
            case "ink" -> ink();
            case "accent" -> accent();
            case "neutral" -> neutral();
            case "muted" -> inkMuted();
            default -> {
                try {
                    yield Color.web(token);
                } catch (IllegalArgumentException ex) {
                    yield ink();
                }
            }
        };
    }

    /**
     * Builds the diagonal hatch ("slash") pattern used for the tops of bar charts and hatched slices.
     *
     * @param stripe stripe color
     * @param background background color between stripes ({@code null} for transparent)
     * @return repeating image pattern paint
     */
    public static Paint hatch(Color stripe, Color background) {
        String key = stripe + "|" + background;
        return HATCH_CACHE.computeIfAbsent(key, k -> buildHatch(stripe, background));
    }

    private static Paint buildHatch(Color stripe, Color background) {
        int size = 8;
        WritableImage img = new WritableImage(size, size);
        PixelWriter pw = img.getPixelWriter();
        Color bg = background != null ? background : Color.TRANSPARENT;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Anti-aliased 45° stripe roughly 2.2px wide.
                int d = Math.floorMod(x + y, size);
                double coverage = d < 2 ? 1.0 : (d == 2 ? 0.35 : (d == size - 1 ? 0.35 : 0.0));
                pw.setColor(x, y, coverage <= 0 ? bg : bg.interpolate(stripe, coverage));
            }
        }
        return new ImagePattern(img, 0, 0, size, size, false);
    }

    /**
     * Returns the display typeface at the given weight and size for canvas text.
     *
     * @param weight font weight
     * @param size point size
     * @return font instance
     */
    public static Font display(FontWeight weight, double size) {
        loadFonts();
        return Font.font(FONT_DISPLAY, weight, size);
    }

    /**
     * Returns the body typeface at the given weight and size for canvas text.
     *
     * @param weight font weight
     * @param size point size
     * @return font instance
     */
    public static Font body(FontWeight weight, double size) {
        loadFonts();
        return Font.font(FONT_BODY, weight, size);
    }
}
