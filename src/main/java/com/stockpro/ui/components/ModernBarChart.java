package com.stockpro.ui.components;

import com.stockpro.service.AppSession;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Canvas bar chart in the StockPro editorial style: solid ink columns topped with a diagonal
 * hatched ("slashed") cap, an accent highlight plus inverse tooltip on hover, and a short
 * ease-out grow animation whenever data changes.
 *
 * <p>Two modes are supported: single values (the cap is a stylistic hatched crown on each
 * column) and stacked values via {@link BarPoint#solid()}, where the solid section shows one
 * measure (e.g. profit) and the hatched cap shows the remainder of the total (e.g. revenue).</p>
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public class ModernBarChart extends StackPane {

    /**
     * A single column.
     *
     * @param label x-axis label
     * @param value total column value
     * @param solid value drawn as the solid lower section; {@code NaN} for single-value mode
     */
    public record BarPoint(String label, double value, double solid) {
        /**
         * Creates a single-value column.
         *
         * @param label x-axis label
         * @param value column value
         */
        public BarPoint(String label, double value) {
            this(label, value, Double.NaN);
        }

        /**
         * Returns whether this column carries an explicit solid/hatched split.
         *
         * @return {@code true} for stacked columns
         */
        public boolean stacked() {
            return !Double.isNaN(solid);
        }
    }

    /** Fraction of a single-value column rendered as the hatched crown. */
    private static final double CROWN_FRACTION = 0.26;

    private final Canvas canvas = new Canvas();
    private final DoubleProperty growth = new SimpleDoubleProperty(1.0);
    private final Timeline growTimeline = new Timeline();
    private List<BarPoint> points = new ArrayList<>();
    private String chartTitle = "";
    private String solidSeriesName = "Profit";
    private String hatchSeriesName = "Revenue";
    private boolean currencyFormat = false;
    private boolean compactMiniMode = false;
    private boolean showValueLabels = true;
    private int hoverIndex = -1;

    /** Bar whose tooltip is shown (kept while the tooltip fades out after the pointer leaves). */
    private int tipIndex = -1;
    /** Tooltip/highlight opacity, animated for smooth appear and disappear. */
    private final DoubleProperty tipAlpha = new SimpleDoubleProperty(0.0);
    /** Fractional bar position of the tooltip, animated so it glides between bars. */
    private final DoubleProperty tipSlot = new SimpleDoubleProperty(0.0);
    private final Timeline tipTimeline = new Timeline();

    /**
     * Creates a bar chart with the requested preferred height.
     *
     * @param prefHeight preferred chart height in pixels
     */
    public ModernBarChart(double prefHeight) {
        setPrefHeight(prefHeight);
        setMinHeight(64);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        growth.addListener((o, a, b) -> redraw());
        tipAlpha.addListener((o, a, b) -> redraw());
        tipSlot.addListener((o, a, b) -> redraw());

        setOnMouseMoved(e -> updateHover(e.getX()));
        setOnMouseExited(e -> setHover(-1));

        getChildren().add(canvas);
    }

    /**
     * Changes the hovered bar and animates the tooltip: fade/lift in on enter, glide between
     * bars while visible, and fade out on exit.
     *
     * @param next hovered bar index, or {@code -1} for none
     */
    private void setHover(int next) {
        if (next == hoverIndex) {
            return;
        }
        hoverIndex = next;
        tipTimeline.stop();
        Interpolator ease = Interpolator.SPLINE(0.25, 0.1, 0.25, 1.0);
        if (next >= 0) {
            boolean visible = tipIndex >= 0 && tipAlpha.get() > 0.05;
            tipIndex = next;
            if (!visible) {
                tipSlot.set(next);
            }
            tipTimeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(visible ? 160 : 200),
                    new KeyValue(tipAlpha, 1.0, ease),
                    new KeyValue(tipSlot, next, ease)));
        } else {
            tipTimeline.getKeyFrames().setAll(new KeyFrame(Duration.millis(240),
                    new KeyValue(tipAlpha, 0.0, ease)));
        }
        tipTimeline.playFromStart();
        redraw();
    }

    /**
     * Reports a small minimum width so the bound canvas never pins the layout at its last size
     * (otherwise the chart could grow but never shrink, e.g. when the sidebar expands).
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
        return 240;
    }

    /**
     * Enables the compact sparkline-style rendering used inside small cards and drawers.
     *
     * @param compactMiniMode {@code true} for compact mode
     */
    public void setCompactMiniMode(boolean compactMiniMode) {
        this.compactMiniMode = compactMiniMode;
        redraw();
    }

    /**
     * Formats values as currency in labels and tooltips.
     *
     * @param currencyFormat {@code true} for currency formatting
     */
    public void setCurrencyFormat(boolean currencyFormat) {
        this.currencyFormat = currencyFormat;
        redraw();
    }

    /**
     * Toggles the value labels drawn above each column.
     *
     * @param show {@code true} to draw value labels
     */
    public void setShowValueLabels(boolean show) {
        this.showValueLabels = show;
        redraw();
    }

    /**
     * Names the two measures of a stacked chart for the hover tooltip.
     *
     * @param solidName measure drawn as the solid section
     * @param hatchName measure drawn as the total (solid + hatched cap)
     */
    public void setSeriesNames(String solidName, String hatchName) {
        this.solidSeriesName = solidName;
        this.hatchSeriesName = hatchName;
    }

    /**
     * Replaces the chart data and plays the grow-in animation.
     *
     * @param title optional title drawn inside the chart ({@code null} or blank for none)
     * @param barPoints columns to render
     */
    public void setData(String title, List<BarPoint> barPoints) {
        this.chartTitle = title != null ? title : "";
        this.points = barPoints != null ? new ArrayList<>(barPoints) : new ArrayList<>();
        playGrow();
    }

    private void playGrow() {
        growTimeline.stop();
        growTimeline.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(growth, 0.0)),
                new KeyFrame(Duration.millis(650), new KeyValue(growth, 1.0, Interpolator.SPLINE(0.2, 0.8, 0.2, 1.0))));
        growTimeline.playFromStart();
    }

    /**
     * Repaints the chart using the active theme palette.
     */
    public void redraw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (w <= 20 || h <= 20) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        Color ink = ThemeManager.ink();
        Color muted = ThemeManager.inkMuted();
        Color accent = ThemeManager.accent();
        Color grid = ThemeManager.grid();
        double t = growth.get();

        if (points.isEmpty()) {
            gc.setFill(muted);
            gc.setFont(ThemeManager.body(FontWeight.NORMAL, 12));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.setTextBaseline(VPos.CENTER);
            gc.fillText("No chart data", w / 2.0, h / 2.0);
            return;
        }

        double maxVal = points.stream().mapToDouble(BarPoint::value).max().orElse(10.0);
        maxVal = maxVal <= 0.0 ? 10.0 : maxVal * 1.12;

        if (compactMiniMode) {
            drawCompact(gc, w, h, maxVal, ink, accent, t);
            return;
        }

        double topPad = chartTitle.isBlank() ? 24 : 36;
        double bottomPad = 26;
        double sidePad = 6;
        double plotW = Math.max(30, w - sidePad * 2);
        double plotH = Math.max(30, h - topPad - bottomPad);
        double baseY = topPad + plotH;

        if (!chartTitle.isBlank()) {
            gc.setFill(ink);
            gc.setFont(ThemeManager.display(FontWeight.BOLD, 13));
            gc.setTextAlign(TextAlignment.LEFT);
            gc.setTextBaseline(VPos.TOP);
            gc.fillText(chartTitle, sidePad, 4);
        }

        gc.setStroke(grid);
        gc.setLineWidth(1.0);
        gc.strokeLine(sidePad, baseY + 0.5, sidePad + plotW, baseY + 0.5);

        int n = points.size();
        double slotW = plotW / n;
        double barW = Math.min(30, Math.max(6, slotW * 0.42));
        boolean dense = slotW < 26;
        int labelEvery = Math.max(1, (int) Math.ceil(34 / slotW));

        for (int i = 0; i < n; i++) {
            BarPoint pt = points.get(i);
            boolean active = i == hoverIndex;
            double cx = sidePad + i * slotW + slotW / 2.0;
            double x = cx - barW / 2.0;

            double total = Math.max(0.0, pt.value());
            double totalH = Math.max(3, (total / maxVal) * plotH) * t;
            double solidFrac = pt.stacked()
                    ? (total <= 0 ? 0 : Math.max(0.0, Math.min(1.0, pt.solid() / total)))
                    : 1.0 - CROWN_FRACTION;
            double solidH = totalH * solidFrac;
            double hatchH = totalH - solidH;

            // Highlight cross-fades from ink to accent with the tooltip opacity.
            double emphasis = i == tipIndex ? tipAlpha.get() : 0.0;
            Color barColor = emphasis > 0 ? ink.interpolate(accent, emphasis) : ink;

            // Hatched crown (ink stripes, with accent stripes blended over while highlighted)
            if (hatchH > 0.5) {
                double hy = baseY - totalH;
                gc.setFill(ThemeManager.hatch(ink, null));
                gc.fillRect(x, hy, barW, hatchH);
                if (emphasis > 0) {
                    gc.setGlobalAlpha(emphasis);
                    gc.setFill(ThemeManager.hatch(accent, null));
                    gc.fillRect(x, hy, barW, hatchH);
                    gc.setGlobalAlpha(1.0);
                }
                gc.setStroke(Color.color(barColor.getRed(), barColor.getGreen(), barColor.getBlue(), 0.55));
                gc.setLineWidth(1);
                gc.strokeRect(x + 0.5, hy + 0.5, barW - 1, hatchH);
            }
            // Solid body
            if (solidH > 0.5) {
                gc.setFill(barColor);
                gc.fillRect(x, baseY - solidH, barW, solidH);
            }

            if (showValueLabels && !dense && t > 0.98) {
                gc.setFill(active ? ink : muted);
                gc.setFont(ThemeManager.body(active ? FontWeight.BOLD : FontWeight.NORMAL, 10.5));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.BOTTOM);
                gc.fillText(compact(total), cx, baseY - totalH - 4);
            }

            if (i % labelEvery == 0 || active) {
                String lbl = pt.label().length() > 10 ? pt.label().substring(0, 9) + "…" : pt.label();
                gc.setFill(active ? ink : muted);
                gc.setFont(ThemeManager.body(active ? FontWeight.BOLD : FontWeight.NORMAL, 11));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.setTextBaseline(VPos.TOP);
                gc.fillText(lbl, cx, baseY + 8);
            }
        }

        if (tipIndex >= 0 && tipIndex < n && tipAlpha.get() > 0.01 && t > 0.98) {
            drawTooltip(gc, w, plotH, baseY, slotW, sidePad, maxVal);
        }
    }

    private void drawCompact(GraphicsContext gc, double w, double h, double maxVal, Color ink, Color accent, double t) {
        int n = points.size();
        double gap = 5;
        double barW = Math.max(5, (w - gap * (n + 1)) / n);
        for (int i = 0; i < n; i++) {
            double val = Math.max(0.0, points.get(i).value());
            double bh = Math.max(4, (val / maxVal) * (h - 6)) * t;
            double x = gap + i * (barW + gap);
            double y = h - bh;
            boolean last = i == n - 1;
            Color c = last ? accent : ink;
            double crown = bh * CROWN_FRACTION;
            gc.setFill(ThemeManager.hatch(c, null));
            gc.fillRect(x, y, barW, crown);
            gc.setFill(c);
            gc.fillRect(x, y + crown, barW, bh - crown);
        }
    }

    private void drawTooltip(GraphicsContext gc, double w, double plotH, double baseY,
                             double slotW, double sidePad, double maxVal) {
        BarPoint pt = points.get(tipIndex);
        List<String> lines = new ArrayList<>();
        if (pt.stacked()) {
            lines.add(hatchSeriesName + "  " + full(pt.value()));
            lines.add(solidSeriesName + "  " + full(pt.solid()));
        } else {
            lines.add(full(pt.value()));
        }

        var titleFont = ThemeManager.display(FontWeight.BOLD, 12);
        var lineFont = ThemeManager.body(FontWeight.NORMAL, 11.5);
        double textW = measure(pt.label(), titleFont);
        for (String l : lines) {
            textW = Math.max(textW, measure(l, lineFont) + 14);
        }
        double boxW = textW + 20;
        double boxH = 12 + 16 + lines.size() * 16 + 4;

        // Position follows the animated slot so the tooltip glides between bars.
        double slot = Math.max(0, Math.min(points.size() - 1, tipSlot.get()));
        int lo = (int) Math.floor(slot);
        int hi = Math.min(points.size() - 1, lo + 1);
        double frac = slot - lo;
        double topLo = columnTop(lo, baseY, plotH, maxVal);
        double topHi = columnTop(hi, baseY, plotH, maxVal);
        double colTop = topLo + (topHi - topLo) * frac;

        double cx = sidePad + slot * slotW + slotW / 2.0;
        double bx = cx + 12;
        if (bx + boxW > w - 2) {
            bx = cx - 12 - boxW;
        }
        double alpha = tipAlpha.get();
        double lift = (1.0 - alpha) * 6;
        double by = Math.max(2, Math.min(colTop - 6, baseY - boxH)) + lift;

        gc.save();
        gc.setGlobalAlpha(alpha);
        gc.setFill(ThemeManager.inverse());
        gc.fillRoundRect(bx, by, boxW, boxH, ThemeManager.RADIUS, ThemeManager.RADIUS);

        gc.setFill(ThemeManager.onInverse());
        gc.setFont(titleFont);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.TOP);
        gc.fillText(pt.label(), bx + 10, by + 8);

        gc.setFont(lineFont);
        for (int i = 0; i < lines.size(); i++) {
            double ly = by + 26 + i * 16;
            boolean accentDot = i == lines.size() - 1;
            gc.setFill(accentDot ? ThemeManager.accent() : ThemeManager.onInverse());
            gc.fillOval(bx + 10, ly + 4, 6, 6);
            gc.setFill(ThemeManager.onInverse());
            gc.fillText(lines.get(i), bx + 22, ly);
        }
        gc.restore();
    }

    private double columnTop(int index, double baseY, double plotH, double maxVal) {
        double v = Math.max(0.0, points.get(index).value());
        return baseY - Math.max(3, (v / maxVal) * plotH);
    }

    private static double measure(String s, javafx.scene.text.Font font) {
        Text probe = new Text(s);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    private void updateHover(double mouseX) {
        if (compactMiniMode || points.isEmpty()) {
            return;
        }
        double sidePad = 6;
        double plotW = Math.max(30, canvas.getWidth() - sidePad * 2);
        int idx = (int) Math.floor((mouseX - sidePad) / (plotW / points.size()));
        setHover(idx >= 0 && idx < points.size() ? idx : -1);
    }

    private String compact(double v) {
        String sym = currencyFormat ? AppSession.getInstance().getCurrencySymbol() : "";
        if (v >= 1_000_000) {
            return String.format("%s%.1fM", sym, v / 1_000_000.0);
        }
        if (v >= 1_000) {
            return String.format("%s%.1fk", sym, v / 1_000.0);
        }
        return String.format("%s%.0f", sym, v);
    }

    private String full(double v) {
        return currencyFormat ? AppSession.getInstance().formatMoney(v) : String.format("%,.0f", v);
    }
}
