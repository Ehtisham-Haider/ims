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
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom smooth cubic-bezier area/line chart rendered via high-DPI {@link Canvas}
 * with translucent area fills, inline legend, floating peak pill callout, interactive mouse crosshair,
 * and automatic Dark/Light theme adaptation.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ModernTrendChart extends StackPane {

    /**
     * Named numeric series rendered on the trend canvas.
     *
     * @param name display legend name
     * @param hexColor line and gradient accent hex color
     * @param values ordered y-axis values aligned to x-labels
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record SeriesData(String name, String hexColor, List<Double> values) {
    }

    private final Canvas canvas = new Canvas();
    private final HBox legendRow = new HBox(16);
    private List<String> labels = new ArrayList<>();
    private List<SeriesData> seriesList = new ArrayList<>();
    private boolean currencyFormat = true;
    private int hoverIndex = -1;

    /**
     * Creates a responsive modern smooth trend chart container.
     *
     * @param prefHeight preferred chart height in pixels
     */
    public ModernTrendChart(double prefHeight) {
        setPrefHeight(prefHeight);
        setMinHeight(180);

        legendRow.setAlignment(Pos.CENTER_RIGHT);
        legendRow.setPadding(new Insets(2, 6, 0, 6));
        legendRow.setPickOnBounds(false);

        VBox layout = new VBox(4);
        layout.setFillWidth(true);
        StackPane plotHost = new StackPane(canvas);
        VBox.setVgrow(plotHost, Priority.ALWAYS);
        canvas.widthProperty().bind(plotHost.widthProperty());
        canvas.heightProperty().bind(plotHost.heightProperty());

        canvas.widthProperty().addListener((o, a, b) -> redraw());
        canvas.heightProperty().addListener((o, a, b) -> redraw());

        plotHost.setOnMouseMoved(e -> updateHoverIndex(e.getX(), plotHost.getWidth()));
        plotHost.setOnMouseExited(e -> {
            if (hoverIndex != -1) {
                hoverIndex = -1;
                redraw();
            }
        });

        layout.getChildren().addAll(legendRow, plotHost);
        getChildren().add(layout);
    }

    /**
     * Controls whether y-axis ticks and pill callouts format values as store currency or plain units.
     *
     * @param currencyFormat {@code true} for currency formatting, {@code false} for numeric counts
     */
    public void setCurrencyFormat(boolean currencyFormat) {
        this.currencyFormat = currencyFormat;
        redraw();
    }

    /**
     * Updates series values and X-axis category labels and repaints the smooth curves.
     *
     * @param xLabels ordered x-axis labels
     * @param series ordered series definitions
     */
    public void setSeries(List<String> xLabels, List<SeriesData> series) {
        this.labels = xLabels != null ? new ArrayList<>(xLabels) : new ArrayList<>();
        this.seriesList = series != null ? new ArrayList<>(series) : new ArrayList<>();
        rebuildLegend();
        redraw();
    }

    /**
     * Repaints the canvas using active Light/Dark theme palette tokens.
     */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 40 || h <= 40) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        boolean dark = AppSession.getInstance().isDarkMode();
        Color gridColor = dark ? Color.web("#334155") : Color.web("#E2E8F0");
        Color axisTextColor = dark ? Color.web("#94A3B8") : Color.web("#64748B");
        Color nodeInnerColor = dark ? Color.web("#1E293B") : Color.WHITE;
        Color crosshairColor = dark ? Color.web("#475569") : Color.web("#CBD5E1");
        Color badgeBg = Color.web("#4F46E5");

        double leftPad = 56;
        double rightPad = 24;
        double topPad = 30;
        double bottomPad = 28;
        double plotW = Math.max(20, w - leftPad - rightPad);
        double plotH = Math.max(20, h - topPad - bottomPad);

        if (labels.isEmpty() || seriesList.isEmpty()) {
            gc.setFill(axisTextColor);
            gc.setFont(Font.font("Segoe UI", 12));
            gc.setTextAlign(TextAlignment.CENTER);
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
        if (maxVal <= 0.0) {
            maxVal = 100.0;
        } else {
            maxVal *= 1.16;
        }

        // Draw 4 horizontal gridlines & Y labels
        gc.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 11));
        gc.setTextAlign(TextAlignment.RIGHT);
        int ticks = 3;
        for (int i = 0; i <= ticks; i++) {
            double frac = i / (double) ticks;
            double y = topPad + plotH - (frac * plotH);
            gc.setStroke(gridColor);
            gc.setLineWidth(1.0);
            gc.setLineDashes(i == 0 ? null : new double[]{3, 4});
            gc.strokeLine(leftPad, y, leftPad + plotW, y);
            gc.setLineDashes(null);

            gc.setFill(axisTextColor);
            String tickText = formatCompact(maxVal * frac);
            gc.fillText(tickText, leftPad - 8, y + 4);
        }

        // Draw X-axis labels
        int n = labels.size();
        gc.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i < n; i++) {
            double x = pointX(i, n, leftPad, plotW);
            gc.setFill(i == hoverIndex ? (dark ? Color.WHITE : Color.web("#0F172A")) : axisTextColor);
            gc.setFont(Font.font("Segoe UI", i == hoverIndex ? FontWeight.BOLD : FontWeight.NORMAL, 11));
            gc.fillText(labels.get(i), x, topPad + plotH + 18);
        }

        // Optional hover vertical guide
        if (hoverIndex >= 0 && hoverIndex < n) {
            double hx = pointX(hoverIndex, n, leftPad, plotW);
            gc.setStroke(crosshairColor);
            gc.setLineWidth(1.2);
            gc.setLineDashes(4, 4);
            gc.strokeLine(hx, topPad, hx, topPad + plotH);
            gc.setLineDashes(null);
        }

        // Draw each series in reverse order so primary series renders on top
        for (int sIdx = seriesList.size() - 1; sIdx >= 0; sIdx--) {
            SeriesData s = seriesList.get(sIdx);
            Color strokeCol = Color.web(s.hexColor());
            int count = Math.min(n, s.values().size());
            if (count == 0) {
                continue;
            }
            double[] xs = new double[count];
            double[] ys = new double[count];
            for (int i = 0; i < count; i++) {
                double val = s.values().get(i) != null ? Math.max(0.0, s.values().get(i)) : 0.0;
                xs[i] = pointX(i, n, leftPad, plotW);
                ys[i] = topPad + plotH - ((val / maxVal) * plotH);
            }

            // Translucent area fill
            gc.beginPath();
            gc.moveTo(xs[0], topPad + plotH);
            gc.lineTo(xs[0], ys[0]);
            for (int i = 0; i < count - 1; i++) {
                double cx = (xs[i] + xs[i + 1]) / 2.0;
                gc.bezierCurveTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }
            gc.lineTo(xs[count - 1], topPad + plotH);
            gc.closePath();

            Color fillTop = Color.color(strokeCol.getRed(), strokeCol.getGreen(), strokeCol.getBlue(), sIdx == 0 ? 0.24 : 0.14);
            Color fillBottom = Color.color(strokeCol.getRed(), strokeCol.getGreen(), strokeCol.getBlue(), 0.01);
            gc.setFill(new LinearGradient(0, topPad, 0, topPad + plotH, false, CycleMethod.NO_CYCLE,
                    new Stop(0, fillTop), new Stop(1, fillBottom)));
            gc.fill();

            // Smooth stroke curve
            gc.beginPath();
            gc.moveTo(xs[0], ys[0]);
            for (int i = 0; i < count - 1; i++) {
                double cx = (xs[i] + xs[i + 1]) / 2.0;
                gc.bezierCurveTo(cx, ys[i], cx, ys[i + 1], xs[i + 1], ys[i + 1]);
            }
            gc.setStroke(strokeCol);
            gc.setLineWidth(sIdx == 0 ? 2.8 : 2.1);
            gc.setLineCap(StrokeLineCap.ROUND);
            gc.setLineJoin(StrokeLineJoin.ROUND);
            gc.stroke();

            // Data point circles
            for (int i = 0; i < count; i++) {
                boolean active = i == hoverIndex;
                double r = active ? 5.5 : 3.8;
                if (active) {
                    gc.setFill(Color.color(strokeCol.getRed(), strokeCol.getGreen(), strokeCol.getBlue(), 0.22));
                    gc.fillOval(xs[i] - 9, ys[i] - 9, 18, 18);
                }
                gc.setFill(nodeInnerColor);
                gc.fillOval(xs[i] - r, ys[i] - r, r * 2, r * 2);
                gc.setStroke(strokeCol);
                gc.setLineWidth(2.1);
                gc.strokeOval(xs[i] - r, ys[i] - r, r * 2, r * 2);
            }
        }

        // Draw floating pill callout for hovered point or latest primary point
        int focusIdx = hoverIndex >= 0 && hoverIndex < n ? hoverIndex : (n - 1);
        SeriesData primary = seriesList.get(0);
        if (focusIdx >= 0 && focusIdx < primary.values().size()) {
            double focusVal = primary.values().get(focusIdx);
            double fx = pointX(focusIdx, n, leftPad, plotW);
            double fy = topPad + plotH - ((Math.max(0.0, focusVal) / maxVal) * plotH);

            String labelText = hoverIndex >= 0 && seriesList.size() > 1
                    ? labels.get(focusIdx) + ": " + formatFull(focusVal) + " | "
                    + (focusIdx < seriesList.get(1).values().size() ? formatFull(seriesList.get(1).values().get(focusIdx)) : "")
                    : formatFull(focusVal);

            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            double pillW = Math.max(66, labelText.length() * 6.5 + 18);
            double pillH = 22;
            double px = Math.max(leftPad, Math.min(leftPad + plotW - pillW, fx - pillW / 2.0));
            double py = Math.max(4, fy - pillH - 10);

            gc.setFill(badgeBg);
            gc.fillRoundRect(px, py, pillW, pillH, 10, 10);
            gc.setFill(Color.WHITE);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(labelText, px + pillW / 2.0, py + 15);
        }
    }

    /**
     * Rebuilds the top-right series dot legend matching {@code Dasboard Optional.png}.
     */
    private void rebuildLegend() {
        legendRow.getChildren().clear();
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        legendRow.getChildren().add(spacer);

        for (SeriesData s : seriesList) {
            Circle dot = new Circle(4.5, Color.web(s.hexColor()));
            Label lbl = new Label(s.name());
            lbl.getStyleClass().add("muted-text");
            lbl.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600;");
            HBox item = new HBox(6, dot, lbl);
            item.setAlignment(Pos.CENTER_LEFT);
            legendRow.getChildren().add(item);
        }
    }

    /**
     * Tracks nearest X-axis bucket under mouse pointer for live hover inspection.
     *
     * @param mouseX pointer X in plot coordinates
     * @param totalWidth plot width
     */
    private void updateHoverIndex(double mouseX, double totalWidth) {
        if (labels.isEmpty()) {
            return;
        }
        double leftPad = 56;
        double rightPad = 24;
        double plotW = Math.max(20, totalWidth - leftPad - rightPad);
        int n = labels.size();
        int nearest = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double x = pointX(i, n, leftPad, plotW);
            double dist = Math.abs(mouseX - x);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = i;
            }
        }
        int next = bestDist <= 38 ? nearest : -1;
        if (next != hoverIndex) {
            hoverIndex = next;
            redraw();
        }
    }

    private double pointX(int index, int total, double leftPad, double plotW) {
        if (total <= 1) {
            return leftPad + plotW / 2.0;
        }
        return leftPad + (index * plotW) / (total - 1.0);
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
