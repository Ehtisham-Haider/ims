package com.stockpro.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;

/**
 * Reusable vector donut chart displaying proportional segments, a center KPI label, and a clean legend.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockDonutChart extends HBox {
    private final Pane ringCanvas;
    private final Label centerPrimaryLabel;
    private final Label centerSecondaryLabel;
    private final VBox legendBox;

    /**
     * Immutable slice specification rendered inside {@link StockDonutChart}.
     *
     * @param label legend label
     * @param valueDisplay formatted value or percentage text
     * @param fraction fraction in range 0..1
     * @param hexColor hex fill/stroke color
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record Slice(String label, String valueDisplay, double fraction, String hexColor) {
    }

    /**
     * Creates a donut chart widget with default dimensions.
     */
    public StockDonutChart() {
        setSpacing(20);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(6, 4, 6, 4));

        ringCanvas = new Pane();
        ringCanvas.setMinSize(148, 148);
        ringCanvas.setPrefSize(148, 148);
        ringCanvas.setMaxSize(148, 148);

        centerPrimaryLabel = new Label("0");
        centerPrimaryLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        centerSecondaryLabel = new Label("Total");
        centerSecondaryLabel.getStyleClass().add("muted-text");
        centerSecondaryLabel.setStyle("-fx-font-size: 11px;");

        VBox centerBox = new VBox(2, centerPrimaryLabel, centerSecondaryLabel);
        centerBox.setAlignment(Pos.CENTER);

        StackPane donutStack = new StackPane(ringCanvas, centerBox);
        donutStack.setMinSize(148, 148);
        donutStack.setPrefSize(148, 148);

        legendBox = new VBox(10);
        legendBox.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(donutStack, legendBox);
    }

    /**
     * Rebuilds the donut ring arcs and legend rows from updated slice proportions.
     *
     * @param centerValue headline text rendered inside the ring
     * @param centerSub secondary text rendered below the headline
     * @param slices ordered slices totaling roughly 1.0
     */
    public void setData(String centerValue, String centerSub, Slice... slices) {
        centerPrimaryLabel.setText(centerValue);
        centerSecondaryLabel.setText(centerSub);
        ringCanvas.getChildren().clear();
        legendBox.getChildren().clear();

        double cx = 74;
        double cy = 74;
        double radius = 56;

        Circle track = new Circle(cx, cy, radius);
        track.setFill(Color.TRANSPARENT);
        track.setStroke(Color.web("#E2E8F0"));
        track.setStrokeWidth(18);
        ringCanvas.getChildren().add(track);

        double startAngle = 90.0;
        for (Slice slice : slices) {
            if (slice.fraction() > 0.001) {
                double sweep = -360.0 * Math.min(1.0, slice.fraction());
                Arc arc = new Arc(cx, cy, radius, radius, startAngle, sweep);
                arc.setType(ArcType.OPEN);
                arc.setFill(Color.TRANSPARENT);
                arc.setStroke(Color.web(slice.hexColor()));
                arc.setStrokeWidth(18);
                ringCanvas.getChildren().add(arc);
                startAngle += sweep;
            }

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Circle dot = new Circle(5, Color.web(slice.hexColor()));
            Label nameLbl = new Label(slice.label());
            nameLbl.setMinWidth(92);
            Label valLbl = new Label(slice.valueDisplay());
            valLbl.setStyle("-fx-font-weight: bold;");
            row.getChildren().addAll(dot, nameLbl, valLbl);
            legendBox.getChildren().add(row);
        }
    }
}
