package com.stockpro.ui.views;

import com.stockpro.domain.WhatIfSimulationModels.SimulationResultSummary;
import com.stockpro.domain.WhatIfSimulationModels.SimulationScenarioInput;
import com.stockpro.domain.WhatIfSimulationModels.SkuSimulationRow;
import com.stockpro.ml.MonteCarloSimulator;
import com.stockpro.service.AppSession;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ModernBarChart;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interactive What-If Time Machine studio enabling managers to stress-test demand spikes,
 * supplier delays, monthly capital limits, and safety stock buffers across 1,000 Monte Carlo
 * simulations with Baseline vs Simulated charts and one-click threshold hardening.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class WhatIfTimeMachineView extends VBox {

    private final ServiceContext services;
    private final ToastNotificationManager toastManager;
    private final MonteCarloSimulator simulator;

    private Slider demandSlider;
    private Label demandValLabel;
    private Slider delaySlider;
    private Label delayValLabel;
    private Slider budgetSlider;
    private Label budgetValLabel;
    private Slider bufferSlider;
    private Label bufferValLabel;

    private Label baselineRevVal;
    private Label p50RevVal;
    private Label rangeQuantileVal;
    private Label stockoutRiskVal;
    private Label recBufferVal;
    private Label holdingCostVal;

    private ModernBarChart comparisonChart;
    private final ObservableList<SkuSimulationRow> skuRowsModel = FXCollections.observableArrayList();
    private TableView<SkuSimulationRow> riskTable;
    private Button runBtn;
    private Button applyBtn;
    private SimulationResultSummary latestSummary;

    /**
     * Constructs the interactive Monte Carlo What-If Studio view.
     *
     * @param services application service registry
     * @param toastManager toast notification feedback manager
     */
    public WhatIfTimeMachineView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.toastManager = toastManager;
        this.simulator = services.getMonteCarloSimulator();

        setSpacing(18);
        setPadding(new Insets(20, 24, 24, 24));
        getChildren().add(buildScrollableContent());
        runSimulationAsync();
    }

    private ScrollPane buildScrollableContent() {
        VBox rootContent = new VBox(18);
        rootContent.getChildren().addAll(
                buildHeader(),
                buildSliderControlCard(),
                buildKpiStrip(),
                buildChartsAndTableSplit()
        );

        ScrollPane scroll = new ScrollPane(rootContent);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("edge-to-edge");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private HBox buildHeader() {
        Label title = new Label("What-If Time Machine (Monte Carlo Studio)");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Run 1,000 stochastic trials on demand surges, supplier delays, budget caps & safety buffers.");
        subtitle.getStyleClass().add("page-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button resetBtn = new Button("Reset Baseline");
        resetBtn.getStyleClass().add("btn-secondary");
        resetBtn.setGraphic(AppIcons.of(Feather.ROTATE_CCW, 14));
        resetBtn.setOnAction(e -> resetSlidersToBaseline());

        runBtn = new Button("Run 1,000 Simulations");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setGraphic(AppIcons.of(Feather.PLAY, 14));
        runBtn.setOnAction(e -> runSimulationAsync());

        applyBtn = new Button("Apply Recommended Buffer");
        applyBtn.getStyleClass().add("btn-success");
        applyBtn.setGraphic(AppIcons.of(Feather.SHIELD, 14));
        applyBtn.setOnAction(e -> applyRecommendedBufferAsync());

        HBox bar = new HBox(10, titleBox, spacer, resetBtn, runBtn, applyBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox buildSliderControlCard() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        Label heading = new Label("Scenario Stress Levers");
        heading.getStyleClass().add("section-title");

        double capDefault = 5000.0;
        try {
            capDefault = Math.max(500.0, Double.parseDouble(
                    services.getSettingsRepository().get("ai.budget.monthly_cap", "5000.0")));
        } catch (NumberFormatException ignored) {
            capDefault = 5000.0;
        }

        demandValLabel = new Label("+20%");
        demandSlider = createSlider(-50, 150, 20, 10);
        demandSlider.valueProperty().addListener((o, a, b) -> {
            int val = (int) Math.round(b.doubleValue());
            demandValLabel.setText((val >= 0 ? "+" : "") + val + "%");
        });

        delayValLabel = new Label("4 days");
        delaySlider = createSlider(0, 21, 4, 1);
        delaySlider.valueProperty().addListener((o, a, b) ->
                delayValLabel.setText(Math.round(b.doubleValue()) + " days"));

        budgetValLabel = new Label(AppSession.getInstance().formatMoney(capDefault));
        budgetSlider = createSlider(500, 50000, Math.min(50000, capDefault), 500);
        budgetSlider.valueProperty().addListener((o, a, b) ->
                budgetValLabel.setText(AppSession.getInstance().formatMoney(Math.round(b.doubleValue()))));

        bufferValLabel = new Label("15%");
        bufferSlider = createSlider(0, 75, 15, 5);
        bufferSlider.valueProperty().addListener((o, a, b) ->
                bufferValLabel.setText(Math.round(b.doubleValue()) + "%"));

        HBox row = new HBox(16,
                sliderBox("Demand Surge (%)", demandValLabel, demandSlider),
                sliderBox("Supplier Delay (Days)", delayValLabel, delaySlider),
                sliderBox("Monthly Budget Cap", budgetValLabel, budgetSlider),
                sliderBox("Safety Stock Buffer", bufferValLabel, bufferSlider)
        );
        for (var node : row.getChildren()) {
            HBox.setHgrow(node, Priority.ALWAYS);
        }
        card.getChildren().addAll(heading, row);
        return card;
    }

    private Slider createSlider(double min, double max, double value, double blockIncrement) {
        Slider slider = new Slider(min, max, value);
        slider.setBlockIncrement(blockIncrement);
        slider.setShowTickMarks(true);
        return slider;
    }

    private VBox sliderBox(String title, Label valueLabel, Slider slider) {
        Label lbl = new Label(title);
        lbl.getStyleClass().add("muted-text");
        valueLabel.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, lbl, spacer, valueLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, top, slider);
    }

    private HBox buildKpiStrip() {
        baselineRevVal = new Label("...");
        p50RevVal = new Label("...");
        rangeQuantileVal = new Label("...");
        stockoutRiskVal = new Label("...");
        recBufferVal = new Label("...");
        holdingCostVal = new Label("...");

        HBox strip = new HBox(12,
                kpiTile("Baseline 30d Revenue", baselineRevVal, "No-shock projection"),
                kpiTile("Simulated Median (P50)", p50RevVal, "Mean fulfilled horizon"),
                kpiTile("P10 .. P90 Outcome Band", rangeQuantileVal, "80% confidence interval"),
                kpiTile("Stockout Risk %", stockoutRiskVal, "Across 1,000 trials"),
                kpiTile("Recommended Buffer", recBufferVal, "Click Apply to harden"),
                kpiTile("Holding Cost Est.", holdingCostVal, "Capital carrying impact")
        );
        for (var child : strip.getChildren()) {
            HBox.setHgrow(child, Priority.ALWAYS);
        }
        return strip;
    }

    private VBox kpiTile(String heading, Label valueLabel, String caption) {
        VBox tile = new VBox(4);
        tile.getStyleClass().add("card");
        tile.setPadding(new Insets(14));
        Label h = new Label(heading);
        h.getStyleClass().add("muted-text");
        valueLabel.setStyle("-fx-font-size: 17px; -fx-font-weight: 700;");
        Label c = new Label(caption);
        c.getStyleClass().add("muted-text");
        tile.getChildren().addAll(h, valueLabel, c);
        return tile;
    }

    private HBox buildChartsAndTableSplit() {
        VBox chartCard = new VBox(10);
        chartCard.getStyleClass().add("card");
        chartCard.setPadding(new Insets(16));
        Label chartTitle = new Label("Baseline vs Monte Carlo Percentile Outcomes");
        chartTitle.getStyleClass().add("section-title");
        comparisonChart = new ModernBarChart(280);
        comparisonChart.setCurrencyFormat(true);
        chartCard.getChildren().addAll(chartTitle, comparisonChart);
        HBox.setHgrow(chartCard, Priority.ALWAYS);

        VBox tableCard = new VBox(10);
        tableCard.getStyleClass().add("card");
        tableCard.setPadding(new Insets(16));
        Label tableTitle = new Label("Highest Stockout-Risk SKUs Under Scenario");
        tableTitle.getStyleClass().add("section-title");

        riskTable = new TableView<>(skuRowsModel);
        riskTable.setPrefHeight(280);
        riskTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SkuSimulationRow, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sku()));

        TableColumn<SkuSimulationRow, String> nameCol = new TableColumn<>("Product");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));

        TableColumn<SkuSimulationRow, String> demandCol = new TableColumn<>("30d Demand");
        demandCol.setCellValueFactory(d -> new SimpleStringProperty(
                String.format(Locale.US, "%.1f", d.getValue().projectedDemandUnits())));

        TableColumn<SkuSimulationRow, String> riskCol = new TableColumn<>("Stockout Risk");
        riskCol.setCellValueFactory(d -> new SimpleStringProperty(
                String.format(Locale.US, "%.1f%%", d.getValue().stockoutProbabilityPercent())));

        TableColumn<SkuSimulationRow, String> bufCol = new TableColumn<>("Threshold (Now -> Rec)");
        bufCol.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().currentThreshold() + " -> " + d.getValue().recommendedThreshold()));

        riskTable.getColumns().addAll(List.of(skuCol, nameCol, demandCol, riskCol, bufCol));
        tableCard.getChildren().addAll(tableTitle, riskTable);
        HBox.setHgrow(tableCard, Priority.ALWAYS);

        return new HBox(16, chartCard, tableCard);
    }

    private void resetSlidersToBaseline() {
        demandSlider.setValue(0);
        delaySlider.setValue(0);
        bufferSlider.setValue(15);
        runSimulationAsync();
    }

    private void runSimulationAsync() {
        runBtn.setDisable(true);
        SimulationScenarioInput input = SimulationScenarioInput.builder()
                .demandSurgePercent(demandSlider.getValue())
                .supplierDelayDays((int) Math.round(delaySlider.getValue()))
                .monthlyBudgetCap(budgetSlider.getValue())
                .safetyBufferPercent(bufferSlider.getValue())
                .horizonDays(30)
                .trialCount(1000)
                .build();

        AppExecutor.runAsync(
                () -> simulator.runSimulation(input),
                summary -> {
                    latestSummary = summary;
                    renderSummary(summary);
                    runBtn.setDisable(false);
                },
                ex -> {
                    runBtn.setDisable(false);
                    toastManager.showInfo("Simulation error: " + ex.getMessage());
                }
        );
    }

    private void applyRecommendedBufferAsync() {
        if (latestSummary == null) {
            toastManager.showInfo("Run a simulation first.");
            return;
        }
        applyBtn.setDisable(true);
        String actor = AppSession.getInstance().getActorName();
        AppExecutor.runAsync(
                () -> simulator.applyRecommendedBuffer(latestSummary, actor),
                res -> {
                    applyBtn.setDisable(false);
                    toastManager.showInfo(res.getMessage());
                    runSimulationAsync();
                },
                ex -> {
                    applyBtn.setDisable(false);
                    toastManager.showInfo("Failed to apply buffer: " + ex.getMessage());
                }
        );
    }

    private void renderSummary(SimulationResultSummary summary) {
        AppSession session = AppSession.getInstance();
        baselineRevVal.setText(session.formatMoney(summary.baselineRevenue()));
        p50RevVal.setText(session.formatMoney(summary.revenueP50()));
        rangeQuantileVal.setText(session.formatMoney(summary.revenueP10())
                + " .. " + session.formatMoney(summary.revenueP90()));
        stockoutRiskVal.setText(String.format(Locale.US, "%.1f%% (%d SKUs)",
                summary.stockoutRiskPercent(), summary.atRiskSkuCount()));
        recBufferVal.setText(summary.recommendedSafetyBufferPercent() + "%");
        holdingCostVal.setText(session.formatMoney(summary.holdingCostEstimate()));

        List<ModernBarChart.BarPoint> points = new ArrayList<>();
        points.add(new ModernBarChart.BarPoint("Baseline", summary.baselineRevenue()));
        points.add(new ModernBarChart.BarPoint("Sim P10", summary.revenueP10()));
        points.add(new ModernBarChart.BarPoint("Sim P50", summary.revenueP50()));
        points.add(new ModernBarChart.BarPoint("Sim P90", summary.revenueP90()));
        points.add(new ModernBarChart.BarPoint("Profit P50", Math.max(0.0, summary.profitP50())));
        comparisonChart.setData("30-Day Revenue & Profit Scenarios (" + summary.trialCount() + " Trials)", points);

        skuRowsModel.setAll(summary.skuRows().stream().limit(25).toList());
    }

    /**
     * Repaints theme-sensitive charts when Light or Dark mode toggles.
     */
    public void refreshTheme() {
        if (latestSummary != null) {
            renderSummary(latestSummary);
        }
    }
}
