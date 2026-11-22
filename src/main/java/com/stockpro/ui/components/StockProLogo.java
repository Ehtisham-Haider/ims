package com.stockpro.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/**
 * Renders the StockPro brand mark: an outlined isometric cube on a flat accent tile.
 * Colors come from the theme tokens ({@code .logo-tile} / {@code .logo-glyph}), so the tile is
 * signal orange in light mode and neon green in dark mode.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public final class StockProLogo {

    /**
     * Prevents instantiation of UI logo helper.
     */
    private StockProLogo() {
    }

    /**
     * Creates the accent logo tile at the requested pixel size.
     *
     * @param size width and height in pixels
     * @return styled JavaFX {@link Node} containing the StockPro cube mark
     */
    public static Node create(double size) {
        SVGPath cube = new SVGPath();
        cube.setContent("M 12 2 L 21 7 L 21 17 L 12 22 L 3 17 L 3 7 Z M 12 22 L 12 12 M 12 12 L 21 7 M 12 12 L 3 7");
        cube.getStyleClass().add("logo-glyph");
        cube.setStrokeWidth(2.0);
        cube.setStrokeLineCap(StrokeLineCap.ROUND);
        cube.setStrokeLineJoin(StrokeLineJoin.ROUND);
        double scale = (size * 0.56) / 24.0;
        cube.setScaleX(scale);
        cube.setScaleY(scale);

        StackPane pane = new StackPane(new Group(cube));
        pane.getStyleClass().add("logo-tile");
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }
}
