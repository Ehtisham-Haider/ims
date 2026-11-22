package com.stockpro.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import org.kordamp.ikonli.feather.Feather;

import java.util.List;

/**
 * Reusable soft-pastel KPI summary card matching the Dashboard and Business Report mockups
 * with crisp Feather vector icons, subtle micro-sparkline accents, and theme-adaptive contrast tokens.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class KpiStatCard extends VBox {

    /**
     * Semantic color tone applied to the KPI delta badge for crisp Light and Dark contrast.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public enum DeltaTone {
        /** Healthy positive growth state (emerald). */
        POSITIVE("kpi-delta-positive"),
        /** Caution/attention state (amber). */
        WARNING("kpi-delta-warning"),
        /** Critical alert state (rose/red). */
        DANGER("kpi-delta-danger"),
        /** Primary indigo accent state. */
        ACCENT("kpi-delta-accent"),
        /** Neutral muted caption. */
        MUTED("muted-text");

        private final String cssClass;

        DeltaTone(String cssClass) {
            this.cssClass = cssClass;
        }

        /**
         * Returns the stylesheet class backing this delta tone.
         *
         * @return CSS class name
         */
        public String getCssClass() {
            return cssClass;
        }
    }

    private static final List<String> ALL_DELTA_CLASSES = List.of(
            "kpi-delta-positive", "kpi-delta-warning", "kpi-delta-danger", "kpi-delta-accent", "muted-text"
    );

    private final Label valueLabel;
    private final Label deltaLabel;

    /**
     * Creates a styled KPI card with a legacy text glyph fallback.
     *
     * @param iconText compact icon glyph
     * @param iconColor hex accent color for the circular icon pill
     * @param pastelStyleClass CSS class such as {@code kpi-card-blue} or {@code kpi-card-green}
     * @param title metric label
     * @param initialValue initial formatted value
     * @param deltaText secondary trend or subtext badge
     */
    public KpiStatCard(
            String iconText,
            String iconColor,
            String pastelStyleClass,
            String title,
            String initialValue,
            String deltaText) {
        this(buildTextIconBadge(iconText, iconColor), iconColor, pastelStyleClass, title, initialValue, deltaText);
    }

    /**
     * Creates a styled KPI card powered by a crisp Feather vector icon and micro-sparkline accent.
     *
     * @param feather Feather vector glyph
     * @param iconColor hex accent color for the badge and sparkline
     * @param pastelStyleClass CSS class such as {@code kpi-card-blue} or {@code kpi-card-green}
     * @param title metric label
     * @param initialValue initial formatted value
     * @param deltaText secondary trend or subtext badge
     */
    public KpiStatCard(
            Feather feather,
            String iconColor,
            String pastelStyleClass,
            String title,
            String initialValue,
            String deltaText) {
        this(buildFeatherBadge(feather, iconColor), iconColor, pastelStyleClass, title, initialValue, deltaText);
    }

    /**
     * Internal shared constructor building the card body and decorative micro-sparkline.
     *
     * @param iconBadge badge node
     * @param iconColor accent hex
     * @param pastelStyleClass card tone CSS class
     * @param title metric title
     * @param initialValue headline value
     * @param deltaText subtext delta
     */
    private KpiStatCard(
            Node iconBadge,
            String iconColor,
            String pastelStyleClass,
            String title,
            String initialValue,
            String deltaText) {
        getStyleClass().addAll("card", pastelStyleClass);
        setSpacing(8);
        setPadding(new Insets(14, 16, 12, 16));
        setMinWidth(160);
        HBox.setHgrow(this, Priority.ALWAYS);

        HBox topRow = new HBox(12);
        topRow.setAlignment(Pos.CENTER_LEFT);

        VBox textCol = new VBox(2);
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("muted-text");
        titleLbl.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600;");

        valueLabel = new Label(initialValue);
        valueLabel.getStyleClass().add("kpi-value");

        textCol.getChildren().addAll(titleLbl, valueLabel);
        topRow.getChildren().addAll(iconBadge, textCol);

        deltaLabel = new Label(deltaText);
        deltaLabel.getStyleClass().add("kpi-delta-positive");

        Polyline sparkline = new Polyline(0, 12, 22, 9, 44, 11, 66, 6, 88, 8, 112, 3, 134, 4);
        Color accent = Color.web(iconColor != null && !iconColor.isBlank() ? iconColor : "#4F46E5");
        sparkline.setStroke(Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.42));
        sparkline.setStrokeWidth(1.9);
        sparkline.setStrokeLineCap(StrokeLineCap.ROUND);
        sparkline.setStrokeLineJoin(StrokeLineJoin.ROUND);

        HBox bottomRow = new HBox(8, deltaLabel);
        bottomRow.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(topRow, bottomRow, sparkline);
    }

    /**
     * Updates the primary metric text and secondary delta label using an explicit semantic {@link DeltaTone}.
     *
     * @param value formatted KPI headline
     * @param delta secondary status or trend description
     * @param tone semantic CSS contrast tone
     */
    public void updateMetric(String value, String delta, DeltaTone tone) {
        valueLabel.setText(value);
        deltaLabel.setText(delta);
        deltaLabel.getStyleClass().removeAll(ALL_DELTA_CLASSES);
        deltaLabel.getStyleClass().add((tone != null ? tone : DeltaTone.MUTED).getCssClass());
    }

    /**
     * Updates the primary metric text and maps legacy hex hints to theme-safe semantic {@link DeltaTone} classes.
     *
     * @param value formatted KPI headline
     * @param delta secondary status or trend description
     * @param deltaColor optional hex hint mapped to Light/Dark readable semantic classes
     */
    public void updateMetric(String value, String delta, String deltaColor) {
        updateMetric(value, delta, inferTone(deltaColor));
    }

    private DeltaTone inferTone(String hex) {
        if (hex == null) {
            return DeltaTone.MUTED;
        }
        String upper = hex.toUpperCase();
        if (upper.contains("B91C") || upper.contains("DC26") || upper.contains("EF44")) {
            return DeltaTone.DANGER;
        }
        if (upper.contains("B453") || upper.contains("D977") || upper.contains("F59E")) {
            return DeltaTone.WARNING;
        }
        if (upper.contains("1580") || upper.contains("10B9") || upper.contains("0596")) {
            return DeltaTone.POSITIVE;
        }
        return DeltaTone.ACCENT;
    }

    private static Node buildFeatherBadge(Feather feather, String iconColor) {
        StackPane badge = new StackPane(AppIcons.of(feather, 18, "#FFFFFF"));
        badge.setMinSize(38, 38);
        badge.setPrefSize(38, 38);
        badge.setMaxSize(38, 38);
        badge.setStyle("-fx-background-color: " + iconColor + "; -fx-background-radius: 11;");
        return badge;
    }

    private static Node buildTextIconBadge(String iconText, String iconColor) {
        Label badge = new Label(iconText);
        badge.setMinSize(38, 38);
        badge.setAlignment(Pos.CENTER);
        badge.setStyle("-fx-background-color: " + iconColor
                + "; -fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 11;");
        return badge;
    }
}
