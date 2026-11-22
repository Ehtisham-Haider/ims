package com.stockpro.ui.components;

import javafx.scene.paint.Color;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Factory for crisp Feather vector icons replacing legacy bitmap glyphs across StockPro screens.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
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
     * Creates a vector {@link FontIcon} colored with an explicit hex string.
     *
     * @param feather Feather glyph constant
     * @param size pixel icon size
     * @param hexColor hex color such as {@code #FFFFFF}
     * @return configured {@link FontIcon}
     */
    public static FontIcon of(Feather feather, int size, String hexColor) {
        FontIcon icon = new FontIcon(feather);
        icon.setIconSize(size);
        if (hexColor != null && !hexColor.isBlank()) {
            icon.setIconColor(Color.web(hexColor));
        }
        return icon;
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
