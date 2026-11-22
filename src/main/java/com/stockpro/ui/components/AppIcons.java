package com.stockpro.ui.components;

import javafx.scene.paint.Color;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Factory for crisp Feather vector icons replacing legacy bitmap glyphs across StockPro screens.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public final class AppIcons {

    /**
     * Prevents instantiation of vector icon helper.
     */
    private AppIcons() {
    }

    /**
     * Creates a vector {@link FontIcon} at the requested pixel size inheriting CSS icon fill.
     *
     * @param feather Feather glyph constant
     * @param size pixel icon size
     * @return configured {@link FontIcon}
     */
    public static FontIcon of(Feather feather, int size) {
        FontIcon icon = new FontIcon(feather);
        icon.setIconSize(size);
        return icon;
    }

    /**
     * Creates a vector {@link FontIcon} whose requested hex color is translated into a semantic,
     * theme-reactive tone class ({@code icon-success}, {@code icon-warning}, {@code icon-danger},
     * {@code icon-accent}, {@code icon-muted}). The concrete color therefore always comes from the
     * active theme's tokens instead of a hardcoded value; white/neutral requests inherit the
     * color of their container (e.g. buttons, inverse cards).
     *
     * @param feather Feather glyph constant
     * @param size pixel icon size
     * @param hexColor legacy hex color hint such as {@code #10B981}
     * @return configured {@link FontIcon}
     */
    public static FontIcon of(Feather feather, int size, String hexColor) {
        FontIcon icon = of(feather, size);
        String tone = toneFor(hexColor);
        if (tone != null) {
            icon.getStyleClass().add(tone);
        }
        return icon;
    }

    /**
     * Maps a legacy hex color hint onto a semantic tone style class.
     *
     * @param hexColor hex color string
     * @return tone class name or {@code null} to inherit the container color
     */
    static String toneFor(String hexColor) {
        if (hexColor == null || hexColor.isBlank()) {
            return null;
        }
        Color c;
        try {
            c = Color.web(hexColor);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (c.getSaturation() < 0.35) {
            // White / slate greys: white inherits container ink, greys become muted.
            return c.getBrightness() > 0.9 ? null : "icon-muted";
        }
        double hue = c.getHue();
        if (hue < 20 || hue >= 330) {
            return "icon-danger";
        }
        if (hue < 55) {
            return "icon-warning";
        }
        if (hue < 170) {
            return "icon-success";
        }
        // Blues, indigos and purples were the old brand color: map them to the new accent.
        return "icon-accent";
    }

    /**
     * Creates a vector {@link FontIcon} tagged with theme-reactive CSS style classes.
     *
     * @param feather Feather glyph constant
     * @param size pixel icon size
     * @param styleClasses CSS classes to attach
     * @return configured {@link FontIcon}
     */
    public static FontIcon styled(Feather feather, int size, String... styleClasses) {
        FontIcon icon = of(feather, size);
        if (styleClasses != null) {
            icon.getStyleClass().addAll(styleClasses);
        }
        return icon;
    }
}
