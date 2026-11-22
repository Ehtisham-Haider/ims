package com.stockpro.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

import java.util.List;

/**
 * KPI summary card in the StockPro editorial style: a title with a small icon at the top right,
 * a large Red Hat Display figure, a caption line and an optional real-data meter.
 *
 * <p>Cards come in four surface variants that mirror the reference layout:
 * {@code kpi-inverse} (black card), {@code kpi-accent} (orange / neon-green card),
 * {@code kpi-muted} (grey card) and {@code kpi-plain} (white card). Values fade/slide in
 * subtly whenever they change.</p>
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public class KpiStatCard extends VBox {

    /**
     * Semantic color tone applied to the KPI caption line.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public enum DeltaTone {
        /** Healthy positive state. */
        POSITIVE("kpi-delta-positive"),
        /** Caution/attention state. */
        WARNING("kpi-delta-warning"),
        /** Critical alert state. */
        DANGER("kpi-delta-danger"),
        /** Brand accent state. */
        ACCENT("kpi-delta-accent"),
        /** Neutral secondary text. */
        MUTED("kpi-caption");

        private final String cssClass;

        /**
         * Creates a tone bound to a CSS class.
         *
         * @param cssClass style class applied to the caption label
         */
        DeltaTone(String cssClass) {
            this.cssClass = cssClass;
        }

        /**
         * Returns the CSS class for this tone.
         *
         * @return style class name
         */
        public String getCssClass() {
            return cssClass;
        }
    }

    /** Black (inverse) card variant. */
    public static final String INVERSE = "kpi-inverse";
    /** Accent card variant (orange in light mode, neon green in dark mode). */
    public static final String ACCENT = "kpi-accent";
    /** Grey card variant. */
    public static final String MUTED = "kpi-muted";
    /** Plain surface card variant. */
    public static final String PLAIN = "kpi-plain";

    private static final List<String> ALL_DELTA_CLASSES = List.of(
            "kpi-delta-positive", "kpi-delta-warning", "kpi-delta-danger", "kpi-delta-accent", "kpi-caption"
    );

    private final Label valueLabel;
    private final Label deltaLabel;
    private final StackPane meterTrack;
    private final Region meterFill;
    private final DoubleProperty meterLevel = new SimpleDoubleProperty(0);

    /**
     * Creates a KPI card with a text glyph icon.
     *
     * @param iconText short glyph text shown as the icon
     * @param iconColor legacy color hint (ignored: colors come from the variant)
     * @param variant surface variant ({@link #INVERSE}, {@link #ACCENT}, {@link #MUTED}, {@link #PLAIN})
     * @param title card title
     * @param initialValue initial figure
     * @param deltaText initial caption
     */
    public KpiStatCard(String iconText, String iconColor, String variant, String title,
                       String initialValue, String deltaText) {
        this(textGlyph(iconText), variant, title, initialValue, deltaText);
    }

    /**
     * Creates a KPI card with a Feather icon.
     *
     * @param feather icon glyph
     * @param iconColor legacy color hint (ignored: colors come from the variant)
     * @param variant surface variant ({@link #INVERSE}, {@link #ACCENT}, {@link #MUTED}, {@link #PLAIN})
     * @param title card title
     * @param initialValue initial figure
     * @param deltaText initial caption
     */
    public KpiStatCard(Feather feather, String iconColor, String variant, String title,
                       String initialValue, String deltaText) {
        this(AppIcons.of(feather, 16), variant, title, initialValue, deltaText);
    }

    private KpiStatCard(Node icon, String variant, String title, String initialValue, String deltaText) {
        getStyleClass().addAll("card", "kpi-card", normalizeVariant(variant));
        setSpacing(4);
        setMinWidth(150);
        setPrefWidth(170);
        setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(this, Priority.ALWAYS);

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("kpi-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topRow = new HBox(8, titleLbl, spacer, icon);
        topRow.setAlignment(Pos.CENTER_LEFT);

        valueLabel = new Label(initialValue);
        valueLabel.getStyleClass().add("kpi-value");

        deltaLabel = new Label(deltaText);
        deltaLabel.getStyleClass().add("kpi-caption");

        meterFill = new Region();
        meterFill.getStyleClass().add("kpi-meter-fill");
        meterFill.setMaxWidth(0);
        meterTrack = new StackPane(meterFill);
        meterTrack.getStyleClass().add("kpi-meter-track");
        meterTrack.setAlignment(Pos.CENTER_LEFT);
        meterTrack.setMinHeight(6);
        meterTrack.setPrefHeight(6);
        meterTrack.setMaxHeight(6);
        meterTrack.setVisible(false);
        meterTrack.setManaged(false);
        VBox.setMargin(meterTrack, new javafx.geometry.Insets(6, 0, 0, 0));

        getChildren().addAll(topRow, valueLabel, deltaLabel, meterTrack);
    }

    private static String normalizeVariant(String variant) {
        if (variant == null) {
            return PLAIN;
        }
        return switch (variant) {
            case INVERSE, ACCENT, MUTED, PLAIN -> variant;
            case "kpi-card-blue" -> INVERSE;
            case "kpi-card-green" -> ACCENT;
            case "kpi-card-purple" -> MUTED;
            default -> PLAIN;
        };
    }

    private static Node textGlyph(String text) {
        Label glyph = new Label(text);
        glyph.getStyleClass().add("kpi-title");
        return glyph;
    }

    /**
     * Updates the figure and caption with a subtle fade/slide when the value changes.
     *
     * @param value new figure
     * @param delta new caption
     * @param tone caption tone
     */
    public void updateMetric(String value, String delta, DeltaTone tone) {
        boolean changed = value != null && !value.equals(valueLabel.getText());
        valueLabel.setText(value);
        deltaLabel.setText(delta);
        deltaLabel.getStyleClass().removeAll(ALL_DELTA_CLASSES);
        deltaLabel.getStyleClass().add((tone != null ? tone : DeltaTone.MUTED).getCssClass());
        if (changed) {
            animateValue();
        }
    }

    /**
     * Updates the figure and caption, inferring the tone from a legacy hex color hint.
     *
     * @param value new figure
     * @param delta new caption
     * @param deltaColor legacy hex color hint
     */
    public void updateMetric(String value, String delta, String deltaColor) {
        updateMetric(value, delta, inferTone(deltaColor));
    }

    /**
     * Shows a thin meter under the caption filled to the given real-data ratio.
     *
     * @param fraction ratio between {@code 0} and {@code 1}, or {@code null}/negative to hide the meter
     */
    public void setMeter(Double fraction) {
        boolean show = fraction != null && fraction >= 0;
        meterTrack.setVisible(show);
        meterTrack.setManaged(show);
        if (show) {
            double f = Math.max(0.0, Math.min(1.0, fraction));
            if (!meterFill.maxWidthProperty().isBound()) {
                meterFill.maxWidthProperty().bind(meterTrack.widthProperty().multiply(meterLevel));
            }
            // Ease the meter from its current level to the new one.
            Timeline grow = new Timeline(new KeyFrame(Duration.millis(650),
                    new KeyValue(meterLevel, f, Motion.EASE)));
            grow.play();
        }
    }

    private void animateValue() {
        FadeTransition fade = new FadeTransition(Duration.millis(260), valueLabel);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        TranslateTransition slide = new TranslateTransition(Duration.millis(260), valueLabel);
        slide.setFromY(6);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        new ParallelTransition(fade, slide).play();
    }

    private DeltaTone inferTone(String hex) {
        if (hex == null) {
            return DeltaTone.MUTED;
        }
        String tone = AppIcons.toneFor(hex);
        if (tone == null || "icon-muted".equals(tone)) {
            return DeltaTone.MUTED;
        }
        return switch (tone) {
            case "icon-danger" -> DeltaTone.DANGER;
            case "icon-warning" -> DeltaTone.WARNING;
            case "icon-success" -> DeltaTone.POSITIVE;
            default -> DeltaTone.ACCENT;
        };
    }
}
