package com.stockpro.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Reusable soft-pastel KPI summary card matching the Dashboard and Business Report mockups.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class KpiStatCard extends VBox {
    private final Label valueLabel;
    private final Label deltaLabel;

    /**
     * Creates a styled KPI card.
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
        getStyleClass().addAll("card", pastelStyleClass);
        setSpacing(8);
        setPadding(new Insets(16));
        setMinWidth(165);
        HBox.setHgrow(this, Priority.ALWAYS);

        HBox topRow = new HBox(12);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label iconBadge = new Label(iconText);
        iconBadge.setMinSize(38, 38);
        iconBadge.setAlignment(Pos.CENTER);
        iconBadge.setStyle("-fx-background-color: " + iconColor + "; -fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 10;");

        VBox textCol = new VBox(2);
        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("muted-text");
        titleLbl.setStyle("-fx-font-size: 12px; -fx-font-weight: 600;");

        valueLabel = new Label(initialValue);
        valueLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

        textCol.getChildren().addAll(titleLbl, valueLabel);
        topRow.getChildren().addAll(iconBadge, textCol);

        deltaLabel = new Label(deltaText);
        deltaLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: #15803D;");

        getChildren().addAll(topRow, deltaLabel);
    }

    /**
     * Updates the primary metric text and secondary delta label.
     *
     * @param value formatted KPI headline
     * @param delta secondary status or trend description
     * @param deltaColor hex color for the delta label
     */
    public void updateMetric(String value, String delta, String deltaColor) {
        valueLabel.setText(value);
        deltaLabel.setText(delta);
        if (deltaColor != null && !deltaColor.isBlank()) {
            deltaLabel.setStyle("-fx-font-size: 11.5px; -fx-font-weight: 600; -fx-text-fill: " + deltaColor + ";");
        }
    }
}
