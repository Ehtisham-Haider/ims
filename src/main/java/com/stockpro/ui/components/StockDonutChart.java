package com.stockpro.ui.components;

import com.stockpro.service.AppSession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;

import java.util.Arrays;
import java.util.List;

/**
 * High-DPI Canvas Donut Chart displaying proportional slices, crisp slice separation,
 * centered headline KPI text, right-aligned percentage legend, and Light/Dark theme sync.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class StockDonutChart extends HBox {
    private static final double SIZE = 148;

    private final Canvas ringCanvas;
    private final Label centerPrimaryLabel;
    private final Label centerSecondaryLabel;
    private final VBox legendBox;

    private List<Slice> lastSlices = List.of();

    /**
     * Immutable slice specification rendered inside {@link StockDonutChart}.
     *
     * @param label legend label
     * @param valueDisplay formatted value or percentage text
     * @param fraction fraction in range 0..1
     * @param hexColor hex fill/stroke color
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
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

        ringCanvas = new Canvas(SIZE, SIZE);

        centerPrimaryLabel = new Label("0");
        centerPrimaryLabel.getStyleClass().add("section-title");
        centerPrimaryLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        centerSecondaryLabel = new Label("Total");
        centerSecondaryLabel.getStyleClass().add("muted-text");
        centerSecondaryLabel.setStyle("-fx-font-size: 11px;");

        VBox centerBox = new VBox(1, centerPrimaryLabel, centerSecondaryLabel);
        centerBox.setAlignment(Pos.CENTER);

        StackPane donutStack = new StackPane(ringCanvas, centerBox);
        donutStack.setMinSize(SIZE, SIZE);
        donutStack.setPrefSize(SIZE, SIZE);
        donutStack.setMaxSize(SIZE, SIZE);

        legendBox = new VBox(9);
        legendBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(legendBox, Priority.ALWAYS);

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
        this.lastSlices = slices != null ? Arrays.asList(slices) : List.of();
        redraw();
    }

    /**
     * Repaints the donut arcs and legend rows using the active Light or Dark palette.
     */
    public void redraw() {
        GraphicsContext gc = ringCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);

        boolean dark = AppSession.getInstance().isDarkMode();
        double strokeW = 18.0;
        double inset = strokeW / 2.0 + 6.0;
        double d = SIZE - (inset * 2.0);

        gc.setLineWidth(strokeW);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setStroke(Color.web(dark ? "#334155" : "#E2E8F0"));
        gc.strokeOval(inset, inset, d, d);

        legendBox.getChildren().clear();
        double startAngle = 90.0;
        long nonZero = lastSlices.stream().filter(s -> s.fraction() > 0.004).count();
        double gapDeg = nonZero > 1 ? 2.4 : 0.0;

        for (Slice slice : lastSlices) {
            if (slice.fraction() > 0.004) {
                double fullSweep = -360.0 * Math.min(1.0, slice.fraction());
                double sweep = fullSweep + (fullSweep < -gapDeg ? gapDeg : 0.0);
                gc.setStroke(Color.web(slice.hexColor()));
                gc.setLineCap(StrokeLineCap.ROUND);
                gc.strokeArc(inset, inset, d, d, startAngle - gapDeg / 2.0, sweep, ArcType.OPEN);
                startAngle += fullSweep;
            }

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            Circle dot = new Circle(5, Color.web(slice.hexColor()));
            Label nameLbl = new Label(slice.label());
            nameLbl.setMinWidth(88);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label valLbl = new Label(slice.valueDisplay());
            valLbl.getStyleClass().add("section-title");
            valLbl.setStyle("-fx-font-size: 12.5px; -fx-font-weight: bold;");
            row.getChildren().addAll(dot, nameLbl, spacer, valLbl);
            legendBox.getChildren().add(row);
        }
    }
}
