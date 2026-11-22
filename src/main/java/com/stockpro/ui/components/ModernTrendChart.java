package com.stockpro.ui.components;

import com.stockpro.service.AppSession;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas line chart in the StockPro editorial style: thin smooth ink/accent lines, dotted
 * vertical guides, a hover marker with an inverse callout, and a left-to-right reveal animation.
 * Series colors are semantic tokens ({@code ink}, {@code accent}, {@code muted}) resolved at draw
 * time, so the chart follows Light/Dark switches without rebuilding its data.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ModernTrendChart extends StackPane {

    /**
     * One plotted series.
     *
     * @param name legend name
     * @param hexColor semantic color token ({@code ink}, {@code accent}, {@code muted}) or color literal
     * @param values y-values aligned with the chart labels
     */
    public record SeriesData(String name, String hexColor, List<Double> values) {
    }

    private static final double LEFT_PAD = 48;
    private static final double RIGHT_PAD = 16;

    private final Canvas canvas = new Canvas();
    private final HBox legendRow = new HBox(14);
    private final List<Circle> legendDots = new ArrayList<>();
    private final DoubleProperty reveal = new SimpleDoubleProperty(1.0);
    private final Timeline revealTimeline = new Timeline();
    private List<String> labels = new ArrayList<>();
    private List<SeriesData> seriesList = new ArrayList<>();
    private boolean currencyFormat = true;
    private int hoverIndex = -1;
    /** Point whose values the hover callout shows (kept while it fades out). */
    private int shownIndex = -1;
    private final DoubleProperty hoverAlpha = new SimpleDoubleProperty(0.0);
    private final DoubleProperty hoverSlot = new SimpleDoubleProperty(0.0);
    private final Timeline hoverTimeline = new Timeline();

    /**
     * Creates a trend chart with the requested preferred height.
     *
     * @param prefHeight preferred height in pixels
     */
    public ModernTrendChart(double prefHeight) {
        setPrefHeight(prefHeight);
        setMinHeight(160);

        legendRow.setAlignment(Pos.CENTER_RIGHT);
        legendRow.setPadding(new Insets(0, 4, 0, 4));
        legendRow.setPickOnBounds(false);

        VBox layout = new VBox(4);
        layout.setFillWidth(true);
        // A plain Pane does not derive its min size from the bound canvas, so the chart can shrink.
        Pane plotHost = new Pane(canvas);
        plotHost.setMinSize(60, 60);
        VBox.setVgrow(plotHost, Priority.ALWAYS);
        canvas.widthProperty().bind(plotHost.widthProperty());
        canvas.heightProperty().bind(plotHost.heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());
        reveal.addListener((o, a, b) -> redraw());
        hoverAlpha.addListener((o, a, b) -> redraw());
        hoverSlot.addListener((o, a, b) -> redraw());

        plotHost.setOnMouseMoved(e -> updateHoverIndex(e.getX(), plotHost.getWidth()));
        plotHost.setOnMouseExited(e -> setHover(-1));

        layout.getChildren().addAll(legendRow, plotHost);
        getChildren().add(layout);
    }

    /**
     * Reports a small minimum width so the chart can shrink with its container.
     *
     * @param height available height
     * @return minimum width in pixels
     */
    @Override
    protected double computeMinWidth(double height) {
        return 60;
    }

    /**
     * Reports a modest preferred width; parents stretch the chart to the space available.
     *
     * @param height available height
     * @return preferred width in pixels
     */
    @Override
    protected double computePrefWidth(double height) {
        return 260;
    }

    /**
     * Formats axis and callout values as currency.
     *
     * @param currencyFormat {@code true} for currency formatting
     */
    public void setCurrencyFormat(boolean currencyFormat) {
        this.currencyFormat = currencyFormat;
        redraw();
    }

    /**
     * Replaces the plotted data and replays the reveal animation.
     *
     * @param xLabels x-axis labels
     * @param series series to plot (first series is the primary one)
     */
    public void setSeries(List<String> xLabels, List<SeriesData> series) {
        this.labels = xLabels != null ? new ArrayList<>(xLabels) : new ArrayList<>();
        this.seriesList = series != null ? new ArrayList<>(series) : new ArrayList<>();
        rebuildLegend();
        revealTimeline.stop();
        revealTimeline.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(reveal, 0.0)),
                new KeyFrame(Duration.millis(750), new KeyValue(reveal, 1.0, Interpolator.SPLINE(0.25, 0.8, 0.25, 1.0))));
        revealTimeline.playFromStart();
    }

    /**
     * Repaints the chart with the active theme palette.
     */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        for (int i = 0; i < legendDots.size() && i < seriesList.size(); i++) {
            legendDots.get(i).setFill(ThemeManager.resolve(seriesList.get(i).hexColor()));
        }
        if (w <= 40 || h <= 40) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        Color grid = ThemeManager.grid();
        Color axisText = ThemeManager.inkMuted();
        Color surface = ThemeManager.surface();

        double topPad = 24;
        double bottomPad = 26;
        double plotW = Math.max(20, w - LEFT_PAD - RIGHT_PAD);
        double plotH = Math.max(20, h - topPad - bottomPad);
        double baseY = topPad + plotH;

        if (labels.isEmpty() || seriesList.isEmpty()) {
            gc.setFill(axisText);
            gc.setFont(ThemeManager.body(FontWeight.NORMAL, 12));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("No trend data available yet", w / 2.0, h / 2.0);
            return;
        }

        double maxVal = 0.0;
        for (SeriesData s : seriesList) {
            for (Double v : s.values()) {
                if (v != null && v > maxVal) {
                    maxVal = v;
                }
            }
        }
        maxVal = maxVal <= 0.0 ? 100.0 : maxVal * 1.15;

        // Horizontal reference lines + Y labels
        gc.setFont(ThemeManager.body(FontWeight.NORMAL, 10.5));
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.setTextBaseline(VPos.CENTER);
        for (int i = 0; i <= 3; i++) {
            double frac = i / 3.0;
            double y = baseY - frac * plotH;
            gc.setStroke(grid);
            gc.setLineWidth(1.0);
            gc.strokeLine(LEFT_PAD, Math.round(y) + 0.5, LEFT_PAD + plotW, Math.round(y) + 0.5);
            gc.setFill(axisText);
            gc.fillText(formatCompact(maxVal * frac), LEFT_PAD - 8, y);
        }

        // Dotted vertical guides and X labels (reference: dotted column texture)
        int n = labels.size();
        int labelEvery = Math.max(1, (int) Math.ceil(n / Math.max(1.0, plotW / 52.0)));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.TOP);
        for (int i = 0; i < n; i++) {
            double x = pointX(i, n, plotW);
            gc.setStroke(grid);
            gc.setLineWidth(1.0);
            gc.setLineDashes(1.5, 3.5);
            gc.strokeLine(Math.round(x) + 0.5, topPad, Math.round(x) + 0.5, baseY);
            gc.setLineDashes(null);
            if (i % labelEvery == 0 || i == hoverIndex) {
                gc.setFill(i == hoverIndex ? ThemeManager.ink() : axisText);
                gc.setFont(ThemeManager.body(i == hoverIndex ? FontWeight.BOLD : FontWeight.NORMAL, 10.5));
                gc.fillText(labels.get(i), x, baseY + 8);
            }
        }

        // Reveal clip
        double revealX = LEFT_PAD + plotW * reveal.get() + 2;
        gc.save();
        gc.beginPath();
        gc.rect(0, 0, revealX, h);
        gc.clip();

        for (int sIdx = seriesList.size() - 1; sIdx >= 0; sIdx--) {
            SeriesData s = seriesList.get(sIdx);
            Color stroke = ThemeManager.resolve(s.hexColor());
            int count = Math.min(n, s.values().size());
            if (count == 0) {
                continue;
            }
            double[] xs = new double[count];
            double[] ys = new double[count];
            for (int i = 0; i < count; i++) {
                Double raw = s.values().get(i);
                double val = raw != null ? Math.max(0.0, raw) : 0.0;
                xs[i] = pointX(i, n, plotW);
                ys[i] = baseY - (val / maxVal) * plotH;
            }

            gc.beginPath();
            gc.moveTo(xs[0], ys[0]);
            for (int i = 0; i < count - 1; i++) {
                double cx = (xs[i] + xs[i + 1]) / 2.0;
                gc.bezierCurveTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }
            gc.setStroke(stroke);
            gc.setLineWidth(sIdx == 0 ? 2.4 : 1.8);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.stroke();

            double alpha = hoverAlpha.get();
            if (alpha > 0.01 && count > 0) {
                // Marker glides along the line between points as the hover slot animates.
                double mx = pointAtSlot(xs, hoverSlot.get());
                double my = pointAtSlot(ys, hoverSlot.get());
                double r = 4.5;
                gc.setGlobalAlpha(alpha);
                gc.setFill(surface);
                gc.fillOval(mx - r, my - r, r * 2, r * 2);
                gc.setStroke(stroke);
                gc.setLineWidth(2);
                gc.strokeOval(mx - r, my - r, r * 2, r * 2);
                gc.setGlobalAlpha(1.0);
            }
        }
        gc.restore();

        if (reveal.get() < 0.98) {
            return;
        }

        SeriesData primary = seriesList.get(0);
        int count = Math.min(n, primary.values().size());
        if (count == 0) {
            return;
        }
        double[] pxs = new double[count];
        double[] pys = new double[count];
        for (int i = 0; i < count; i++) {
            Double raw = primary.values().get(i);
            pxs[i] = pointX(i, n, plotW);
            pys[i] = baseY - (Math.max(0.0, raw != null ? raw : 0.0) / maxVal) * plotH;
        }
        double alpha = hoverAlpha.get();

        // Idle callout (latest value) cross-fades with the hover callout.
        if (alpha < 0.99) {
            Double last = primary.values().get(count - 1);
            if (last != null) {
                drawCallout(gc, formatFull(last), pxs[count - 1], pys[count - 1], plotW, 1.0 - alpha);
            }
        }
        if (alpha > 0.01 && shownIndex >= 0 && shownIndex < count) {
            double fx = pointAtSlot(pxs, hoverSlot.get());
            double fy = pointAtSlot(pys, hoverSlot.get());
            gc.setGlobalAlpha(alpha);
            gc.setStroke(ThemeManager.ink());
            gc.setLineWidth(1);
            gc.strokeLine(fx, fy, fx, baseY);
            gc.setGlobalAlpha(1.0);

            Double value = primary.values().get(shownIndex);
            String text = formatFull(value != null ? value : 0.0);
            if (seriesList.size() > 1 && shownIndex < seriesList.get(1).values().size()) {
                Double second = seriesList.get(1).values().get(shownIndex);
                text = labels.get(shownIndex) + "  ·  " + text + (second != null ? "  /  " + formatFull(second) : "");
            }
            drawCallout(gc, text, fx, fy, plotW, alpha);
        }
    }

    private void drawCallout(GraphicsContext gc, String text, double fx, double fy, double plotW, double alpha) {
        var font = ThemeManager.body(FontWeight.BOLD, 11);
        Text probe = new Text(text);
        probe.setFont(font);
        double pillW = probe.getLayoutBounds().getWidth() + 18;
        double pillH = 22;
        double px = Math.max(LEFT_PAD, Math.min(LEFT_PAD + plotW - pillW, fx - pillW / 2.0));
        double py = Math.max(0, fy - pillH - 10) + (1.0 - alpha) * 4;

        gc.setGlobalAlpha(alpha);
        gc.setFill(ThemeManager.inverse());
        gc.fillRoundRect(px, py, pillW, pillH, ThemeManager.RADIUS, ThemeManager.RADIUS);
        gc.setFill(ThemeManager.onInverse());
        gc.setFont(font);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(text, px + pillW / 2.0, py + pillH / 2.0);
        gc.setGlobalAlpha(1.0);
    }

    private static double pointAtSlot(double[] values, double slot) {
        double s = Math.max(0, Math.min(values.length - 1, slot));
        int lo = (int) Math.floor(s);
        int hi = Math.min(values.length - 1, lo + 1);
        return values[lo] + (values[hi] - values[lo]) * (s - lo);
    }

    private void setHover(int next) {
        if (next == hoverIndex) {
            return;
        }
        hoverIndex = next;
        hoverTimeline.stop();
        if (next >= 0) {
            boolean visible = shownIndex >= 0 && hoverAlpha.get() > 0.05;
            shownIndex = next;
            if (!visible) {
                hoverSlot.set(next);
            }
            hoverTimeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(visible ? 170 : 200),
                    new KeyValue(hoverAlpha, 1.0, Motion.EASE),
                    new KeyValue(hoverSlot, next, Motion.EASE)));
        } else {
            hoverTimeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(240),
                    new KeyValue(hoverAlpha, 0.0, Motion.EASE)));
        }
        hoverTimeline.playFromStart();
        redraw();
    }

    private void rebuildLegend() {
        legendRow.getChildren().clear();
        legendDots.clear();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        legendRow.getChildren().add(spacer);
        for (SeriesData s : seriesList) {
            Circle dot = new Circle(4, ThemeManager.resolve(s.hexColor()));
            legendDots.add(dot);
            Label lbl = new Label(s.name());
            lbl.getStyleClass().add("legend-label");
            HBox item = new HBox(6, dot, lbl);
            item.setAlignment(Pos.CENTER_LEFT);
            legendRow.getChildren().add(item);
        }
    }

    private void updateHoverIndex(double mouseX, double totalWidth) {
        if (labels.isEmpty()) {
            return;
        }
        double plotW = Math.max(20, totalWidth - LEFT_PAD - RIGHT_PAD);
        int n = labels.size();
        int nearest = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double dist = Math.abs(mouseX - pointX(i, n, plotW));
            if (dist < bestDist) {
                bestDist = dist;
                nearest = i;
            }
        }
        setHover(bestDist <= Math.max(14, plotW / Math.max(1, n)) ? nearest : -1);
    }

    private double pointX(int index, int total, double plotW) {
        if (total <= 1) {
            return LEFT_PAD + plotW / 2.0;
        }
        return LEFT_PAD + (index * plotW) / (total - 1.0);
    }

    private String formatCompact(double value) {
        String sym = currencyFormat ? AppSession.getInstance().getCurrencySymbol() : "";
        if (value >= 1_000_000) {
            return String.format("%s%.1fM", sym, value / 1_000_000.0);
        }
        if (value >= 1_000) {
            return String.format("%s%.1fk", sym, value / 1_000.0);
        }
        return String.format("%s%.0f", sym, value);
    }

    private String formatFull(double value) {
        if (currencyFormat) {
            return AppSession.getInstance().formatMoney(value);
        }
        return String.format("%,.0f", value);
    }
}
