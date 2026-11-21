package com.stockpro.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * Renders the crisp isometric 3D cube logo mark matching the StockPro brand identity.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public final class StockProLogo {

    /**
     * Prevents instantiation of UI logo helper.
     */
    private StockProLogo() {
    }

    /**
     * Creates a scalable vector cube badge at the requested pixel size.
     *
     * @param size width and height in pixels
     * @return styled JavaFX {@link Node} containing the StockPro isometric cube
     */
    public static Node create(double size) {
        SVGPath outerHex = new SVGPath();
        outerHex.setContent("M 12 2 L 21 7 L 21 17 L 12 22 L 3 17 L 3 7 Z");
        outerHex.setFill(Color.web("#6366F1"));
        outerHex.setStroke(Color.web("#818CF8"));
        outerHex.setStrokeWidth(1.2);

        SVGPath innerEdges = new SVGPath();
        innerEdges.setContent("M 12 22 L 12 12 M 12 12 L 21 7 M 12 12 L 3 7");
        innerEdges.setFill(Color.TRANSPARENT);
        innerEdges.setStroke(Color.web("#E0E7FF"));
        innerEdges.setStrokeWidth(1.7);

        double scale = size / 24.0;
        outerHex.setScaleX(scale);
        outerHex.setScaleY(scale);
        innerEdges.setScaleX(scale);
        innerEdges.setScaleY(scale);

        StackPane pane = new StackPane(outerHex, innerEdges);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }
}
