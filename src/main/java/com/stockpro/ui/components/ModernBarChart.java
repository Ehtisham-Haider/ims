package com.stockpro.ui.components;

import com.stockpro.service.AppSession;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Sleek responsive gradient Bar Chart component rendered via {@link Canvas}
 * with rounded bar caps, hover highlighting, value pills, and Light/Dark mode responsiveness.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ModernBarChart extends StackPane {

    /**
     * Immutable labeled value point displayed as a vertical bar.
     *
     * @param label category or day label
     * @param value positive bar magnitude
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record BarPoint(String label, double value) {
    }

    private static final String[] PALETTE = {
            "#4F46E5", "#6366F1", "#8B5CF6", "#10B981", "#0EA5E9", "#F59E0B"
    };

    private final Canvas canvas = new Canvas();
    private List<BarPoint> points = new ArrayList<>();
    private String chartTitle = "";
    private boolean currencyFormat = false;
    private boolean compactMiniMode = false;
    private int hoverIndex = -1;

    /**
     * Creates a modern bar chart widget at the specified preferred height.
     *
     * @param prefHeight preferred component height in pixels
     */
    public ModernBarChart(double prefHeight) {
        setPrefHeight(prefHeight);
        setMinHeight(64);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());

        setOnMouseMoved(e -> updateHover(e.getX()));
        setOnMouseExited(e -> {
            if (hoverIndex != -1) {
                hoverIndex = -1;
                redraw();
            }
        });

        getChildren().add(canvas);
    }

    /**
     * Configures whether bars render compact mini-mode (no axis labels, ideal for drawer thumbnails).
     *
     * @param compactMiniMode {@code true} to render compact mini-bars
     */
    public void setCompactMiniMode(boolean compactMiniMode) {
        this.compactMiniMode = compactMiniMode;
        redraw();
    }

    /**
     * Configures whether bar tooltips format values as store currency.
     *
     * @param currencyFormat {@code true} for currency formatting
     */
    public void setCurrencyFormat(boolean currencyFormat) {
        this.currencyFormat = currencyFormat;
        redraw();
    }

    /**
     * Populates bars and repaints immediately.
     *
     * @param title optional title rendered above bars
     * @param barPoints ordered list of {@link BarPoint} values
     */
    public void setData(String title, List<BarPoint> barPoints) {
        this.chartTitle = title != null ? title : "";
        this.points = barPoints != null ? new ArrayList<>(barPoints) : new ArrayList<>();
        redraw();
    }

    /**
     * Repaints bars using active Light/Dark theme palette tokens.
     */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 20 || h <= 20) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        boolean dark = AppSession.getInstance().isDarkMode();
        Color gridColor = dark ? Color.web("#334155") : Color.web("#E2E8F0");
        Color textMuted = dark ? Color.web("#94A3B8") : Color.web("#64748B");
        Color textPrimary = dark ? Color.web("#F8FAFC") : Color.web("#0F172A");

        if (points.isEmpty()) {
            gc.setFill(textMuted);
            gc.setFont(Font.font("Segoe UI", 12));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("No chart data", w / 2.0, h / 2.0);
            return;
        }

        double maxVal = points.stream().mapToDouble(BarPoint::value).max().orElse(10.0);
        if (maxVal <= 0.0) {
            maxVal = 10.0;
        } else {
            maxVal *= 1.15;
        }

        if (compactMiniMode) {
            double gap = 6;
            double barW = Math.max(8, (w - (gap * (points.size() + 1))) / points.size());
            for (int i = 0; i < points.size(); i++) {
                double val = Math.max(0.0, points.get(i).value());
                double bh = Math.max(6, (val / maxVal) * (h - 10));
                double x = gap + i * (barW + gap);
                double y = h - bh - 4;
                Color base = Color.web(i == points.size() - 1 ? "#4F46E5" : "#6366F1");
                gc.setFill(new LinearGradient(0, y, 0, h - 4, false, CycleMethod.NO_CYCLE,
                        new Stop(0, base), new Stop(1, Color.color(base.getRed(), base.getGreen(), base.getBlue(), 0.45))));
                gc.fillRoundRect(x, y, barW, bh, 6, 6);
            }
            return;
        }

        double topPad = chartTitle.isBlank() ? 22 : 34;
        double bottomPad = 32;
        double leftPad = 14;
        double rightPad = 14;
        double plotW = Math.max(30, w - leftPad - rightPad);
        double plotH = Math.max(30, h - topPad - bottomPad);

        if (!chartTitle.isBlank()) {
            gc.setFill(textPrimary);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.fillText(chartTitle, leftPad, 16);
        }

        for (int g = 0; g <= 3; g++) {
            double gy = topPad + plotH - (g / 3.0) * plotH;
            gc.setStroke(gridColor);
            gc.setLineWidth(1.0);
            gc.setLineDashes(g == 0 ? null : new double[]{3, 4});
            gc.strokeLine(leftPad, gy, leftPad + plotW, gy);
            gc.setLineDashes(null);
        }

        int n = points.size();
        double slotW = plotW / n;
        double barW = Math.min(46, Math.max(18, slotW * 0.56));

        for (int i = 0; i < n; i++) {
            BarPoint pt = points.get(i);
            double bh = Math.max(4, (Math.max(0.0, pt.value()) / maxVal) * plotH);
            double x = leftPad + i * slotW + (slotW - barW) / 2.0;
            double y = topPad + plotH - bh;
            Color accent = Color.web(PALETTE[i % PALETTE.length]);
            boolean active = i == hoverIndex;

            gc.setFill(new LinearGradient(0, y, 0, topPad + plotH, false, CycleMethod.NO_CYCLE,
                    new Stop(0, active ? accent.brighter() : accent),
                    new Stop(1, Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.72))));
            gc.fillRoundRect(x, y, barW, bh, 8, 8);

            // Value text above bar
            String valStr = currencyFormat
                    ? AppSession.getInstance().formatMoney(pt.value())
                    : (pt.value() >= 1000 ? String.format("%.1fk", pt.value() / 1000.0) : String.format("%.0f", pt.value()));
            gc.setFill(active ? accent : textMuted);
            gc.setFont(Font.font("Segoe UI", active ? FontWeight.BOLD : FontWeight.NORMAL, 10.5));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(valStr, x + barW / 2.0, Math.max(topPad - 4, y - 5));

            // Trimmed X label below bar
            String lbl = pt.label().length() > 11 ? pt.label().substring(0, 10) + "…" : pt.label();
            gc.setFill(active ? textPrimary : textMuted);
            gc.setFont(Font.font("Segoe UI", active ? FontWeight.BOLD : FontWeight.NORMAL, 11));
            gc.fillText(lbl, x + barW / 2.0, topPad + plotH + 17);
        }
    }

    private void updateHover(double mouseX) {
        if (compactMiniMode || points.isEmpty()) {
            return;
        }
        double leftPad = 14;
        double plotW = Math.max(30, canvas.getWidth() - 28);
        int idx = (int) Math.floor((mouseX - leftPad) / (plotW / points.size()));
        int next = idx >= 0 && idx < points.size() ? idx : -1;
        if (next != hoverIndex) {
            hoverIndex = next;
            redraw();
        }
    }
}
