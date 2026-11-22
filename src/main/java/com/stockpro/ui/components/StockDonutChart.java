package com.stockpro.ui.components;

import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Segmented pie chart in the StockPro editorial style: separated slices around a small center
 * hole, percentage tags on each slice, hatched/ink/accent/neutral fills resolved from the active
 * theme, a pop-out on hover and a short sweep-in animation.
 *
 * <p>Slice colors are semantic tokens: {@code ink}, {@code accent}, {@code neutral},
 * {@code hatch} (ink stripes) or any CSS color literal.</p>
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class StockDonutChart extends HBox {
    private static final double SIZE = 156;
    private static final double OUTER_R = 74;
    private static final double INNER_R = 30;
    private static final double GAP_DEG = 3.2;
    private static final double POP = 5;

    private final Canvas ringCanvas;
    private final Label centerPrimaryLabel;
    private final Label centerSecondaryLabel;
    private final VBox legendBox;
    private final List<Canvas> legendSwatches = new ArrayList<>();
    private final DoubleProperty sweep = new SimpleDoubleProperty(1.0);
    private final Timeline sweepTimeline = new Timeline();

    private List<Slice> lastSlices = List.of();
    private int hoverIndex = -1;

    /** Per-slice pop-out progress (0..1), eased toward its target every frame. */
    private double[] pop = new double[0];
    /** Overall focus amount (0..1) used to gently dim the non-hovered slices and legend rows. */
    private double focus;
    private long lastFrameNanos;
    private boolean easing;
    private final AnimationTimer easer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            double dt = lastFrameNanos == 0 ? 1.0 / 60 : Math.min(0.05, (now - lastFrameNanos) / 1e9);
            lastFrameNanos = now;
            // Critically-damped style exponential easing (~90 ms time constant).
            double k = 1 - Math.exp(-dt / 0.09);
            boolean settled = true;
            for (int i = 0; i < pop.length; i++) {
                double target = i == hoverIndex ? 1 : 0;
                pop[i] += (target - pop[i]) * k;
                if (Math.abs(target - pop[i]) > 0.002) {
                    settled = false;
                } else {
                    pop[i] = target;
                }
            }
            double focusTarget = hoverIndex >= 0 ? 1 : 0;
            focus += (focusTarget - focus) * k;
            if (Math.abs(focusTarget - focus) > 0.002) {
                settled = false;
            } else {
                focus = focusTarget;
            }
            redraw();
            updateLegendFocus();
            if (settled) {
                stop();
                easing = false;
            }
        }
    };

    /**
     * One pie slice.
     *
     * @param label legend label
     * @param valueDisplay formatted value shown in the legend
     * @param fraction share of the whole between {@code 0} and {@code 1}
     * @param hexColor semantic color token ({@code ink}, {@code accent}, {@code neutral}, {@code hatch}) or color literal
     */
    public record Slice(String label, String valueDisplay, double fraction, String hexColor) {
    }

    /**
     * Creates an empty segmented pie chart with a side legend.
     */
    public StockDonutChart() {
        setSpacing(18);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(2, 0, 2, 0));

        ringCanvas = new Canvas(SIZE, SIZE);
        ringCanvas.setOnMouseMoved(e -> updateHover(e.getX(), e.getY()));
        ringCanvas.setOnMouseExited(e -> setHover(-1));
        sweep.addListener((o, a, b) -> redraw());

        centerPrimaryLabel = new Label("0");
        centerPrimaryLabel.getStyleClass().add("donut-center-value");
        centerPrimaryLabel.setMouseTransparent(true);
        centerSecondaryLabel = new Label("Total");
        centerSecondaryLabel.getStyleClass().add("legend-label");

        StackPane donutStack = new StackPane(ringCanvas, centerPrimaryLabel);
        donutStack.setMinSize(SIZE, SIZE);
        donutStack.setPrefSize(SIZE, SIZE);
        donutStack.setMaxSize(SIZE, SIZE);

        legendBox = new VBox(10);
        legendBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(legendBox, Priority.ALWAYS);

        getChildren().addAll(donutStack, legendBox);
    }

    /**
     * Replaces the slices, rebuilds the legend and replays the sweep animation.
     *
     * @param centerValue text drawn in the center hole
     * @param centerSub caption shown at the top of the legend
     * @param slices slices in drawing order (clockwise from 12 o'clock)
     */
    public void setData(String centerValue, String centerSub, Slice... slices) {
        centerPrimaryLabel.setText(centerValue);
        centerSecondaryLabel.setText(centerSub);
        this.lastSlices = slices != null ? Arrays.asList(slices) : List.of();
        this.pop = new double[lastSlices.size()];
        this.hoverIndex = -1;
        this.focus = 0;
        rebuildLegend();
        sweepTimeline.stop();
        sweepTimeline.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(sweep, 0.0)),
                new KeyFrame(Duration.millis(700), new KeyValue(sweep, 1.0, Interpolator.SPLINE(0.25, 0.8, 0.25, 1.0))));
        sweepTimeline.playFromStart();
    }

    private void rebuildLegend() {
        legendBox.getChildren().clear();
        legendSwatches.clear();
        legendBox.getChildren().add(centerSecondaryLabel);
        for (Slice slice : lastSlices) {
            Canvas swatch = new Canvas(12, 12);
            legendSwatches.add(swatch);
            Label nameLbl = new Label(slice.label());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label valLbl = new Label(slice.valueDisplay());
            valLbl.getStyleClass().add("legend-value");
            HBox row = new HBox(8, swatch, nameLbl, spacer, valLbl);
            row.setAlignment(Pos.CENTER_LEFT);
            legendBox.getChildren().add(row);
        }
    }

    /**
     * Repaints the pie and legend swatches with the active theme palette.
     */
    public void redraw() {
        GraphicsContext gc = ringCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);
        double c = SIZE / 2.0;

        for (int i = 0; i < legendSwatches.size() && i < lastSlices.size(); i++) {
            Canvas sw = legendSwatches.get(i);
            GraphicsContext sg = sw.getGraphicsContext2D();
            sg.clearRect(0, 0, 12, 12);
            sg.setFill(paintFor(lastSlices.get(i).hexColor()));
            sg.fillRoundRect(0, 0, 12, 12, 4, 4);
            if ("hatch".equalsIgnoreCase(lastSlices.get(i).hexColor())) {
                sg.setStroke(ThemeManager.ink());
                sg.setLineWidth(1);
                sg.strokeRoundRect(0.5, 0.5, 11, 11, 4, 4);
            }
        }

        double total = lastSlices.stream().mapToDouble(s -> Math.max(0, s.fraction())).sum();
        if (total <= 0.0001) {
            gc.setFill(ThemeManager.neutral());
            annularSector(gc, c, c, OUTER_R, INNER_R, 90, -360 + 0.01);
            gc.fill();
            return;
        }

        long visible = lastSlices.stream().filter(s -> s.fraction() > 0.004).count();
        double gap = visible > 1 ? GAP_DEG : 0;
        double sweepAll = 360.0 * sweep.get();
        double start = 90.0;

        List<double[]> labelSpots = new ArrayList<>();
        for (int i = 0; i < lastSlices.size(); i++) {
            Slice s = lastSlices.get(i);
            double frac = Math.max(0, s.fraction()) / total;
            if (frac <= 0.004) {
                labelSpots.add(null);
                continue;
            }
            double full = 360.0 * frac;
            double drawn = Math.min(full, Math.max(0, sweepAll - (90.0 - start)));
            if (drawn <= gap) {
                labelSpots.add(null);
                start -= full;
                continue;
            }
            double mid = start - full / 2.0;
            double p = i < pop.length ? pop[i] : 0;
            double off = POP * p;
            double ox = c + off * Math.cos(Math.toRadians(mid));
            double oy = c - off * Math.sin(Math.toRadians(mid));

            // Non-hovered slices recede slightly while another slice is focused.
            gc.setGlobalAlpha(1.0 - 0.28 * focus * (1.0 - p));
            annularSector(gc, ox, oy, OUTER_R - POP, INNER_R, start - gap / 2.0, -(drawn - gap));
            gc.setFill(paintFor(s.hexColor()));
            gc.fill();
            if ("hatch".equalsIgnoreCase(s.hexColor())) {
                gc.setStroke(ThemeManager.ink());
                gc.setLineWidth(1);
                gc.stroke();
            }
            gc.setGlobalAlpha(1.0);

            double labelR = (OUTER_R - POP + INNER_R) / 2.0 + 6;
            labelSpots.add(new double[]{
                    ox + labelR * Math.cos(Math.toRadians(mid)),
                    oy - labelR * Math.sin(Math.toRadians(mid)),
                    frac});
            start -= full;
        }

        if (sweep.get() < 0.98) {
            return;
        }
        var font = ThemeManager.body(FontWeight.BOLD, 10.5);
        gc.setFont(font);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        for (double[] spot : labelSpots) {
            if (spot == null || spot[2] < 0.05) {
                continue;
            }
            String pct = Math.round(spot[2] * 100) + "%";
            Text probe = new Text(pct);
            probe.setFont(font);
            double pw = probe.getLayoutBounds().getWidth() + 10;
            double ph = 17;
            gc.setFill(ThemeManager.surface());
            gc.fillRoundRect(spot[0] - pw / 2, spot[1] - ph / 2, pw, ph, 8, 8);
            gc.setStroke(ThemeManager.border());
            gc.setLineWidth(0.8);
            gc.strokeRoundRect(spot[0] - pw / 2 + 0.5, spot[1] - ph / 2 + 0.5, pw - 1, ph - 1, 8, 8);
            gc.setFill(ThemeManager.ink());
            gc.fillText(pct, spot[0], spot[1] + 0.5);
        }
    }

    private static void annularSector(GraphicsContext gc, double cx, double cy, double rOuter, double rInner,
                                      double startDeg, double lengthDeg) {
        double a0 = Math.toRadians(startDeg);
        double a1 = Math.toRadians(startDeg + lengthDeg);
        gc.beginPath();
        gc.moveTo(cx + rOuter * Math.cos(a0), cy - rOuter * Math.sin(a0));
        gc.arc(cx, cy, rOuter, rOuter, startDeg, lengthDeg);
        gc.lineTo(cx + rInner * Math.cos(a1), cy - rInner * Math.sin(a1));
        gc.arc(cx, cy, rInner, rInner, startDeg + lengthDeg, -lengthDeg);
        gc.closePath();
    }

    private static Paint paintFor(String token) {
        if ("hatch".equalsIgnoreCase(token)) {
            return ThemeManager.hatch(ThemeManager.ink(), ThemeManager.surface());
        }
        return ThemeManager.resolve(token);
    }

    private void updateHover(double x, double y) {
        double c = SIZE / 2.0;
        double dx = x - c;
        double dy = c - y;
        double r = Math.hypot(dx, dy);
        int next = -1;
        double total = lastSlices.stream().mapToDouble(s -> Math.max(0, s.fraction())).sum();
        if (r >= INNER_R && r <= OUTER_R && total > 0) {
            double ang = Math.toDegrees(Math.atan2(dy, dx));
            double clockwiseFromTop = ((90.0 - ang) % 360 + 360) % 360;
            double acc = 0;
            for (int i = 0; i < lastSlices.size(); i++) {
                acc += 360.0 * Math.max(0, lastSlices.get(i).fraction()) / total;
                if (clockwiseFromTop <= acc) {
                    next = i;
                    break;
                }
            }
        }
        setHover(next);
    }

    private void setHover(int next) {
        if (next == hoverIndex) {
            return;
        }
        hoverIndex = next;
        if (!easing) {
            easing = true;
            lastFrameNanos = 0;
            easer.start();
        }
    }

    private void updateLegendFocus() {
        // Legend rows follow the same easing: the hovered row stays solid, the others fade back.
        for (int i = 0; i < lastSlices.size(); i++) {
            int childIndex = i + 1; // index 0 is the caption label
            if (childIndex < legendBox.getChildren().size()) {
                double p = i < pop.length ? pop[i] : 0;
                legendBox.getChildren().get(childIndex).setOpacity(1.0 - 0.45 * focus * (1.0 - p));
            }
        }
    }
}
