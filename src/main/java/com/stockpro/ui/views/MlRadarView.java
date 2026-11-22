package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.List;
import java.util.function.Consumer;

/**
 * Offline Machine Learning Radar &amp; DS&amp;A Search Sandbox UI displaying Days-Until-Empty bars,
 * Smart Restock Plan, Auto-Tuned Thresholds, ABC grades, Holt-Winters 95% confidence bounds,
 * Supplier OTIF scores, Apriori rules, and Theft/Typo anomaly alerts with Light/Dark fidelity.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class MlRadarView {
    private final CatalogSearchEngine searchEngine;
    private final OfflineMlEngine mlEngine;
    private final com.stockpro.ml.AdvancedOfflineMlService advancedMlService;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private final ObservableList<OfflineMlEngine.ProductForecast> forecastRows = FXCollections.observableArrayList();
    private VBox anomalyCardsBox;
    private VBox advancedMlBox;
    private Label searchStatsLabel;

    /**
     * Constructs the ML Radar view.
     *
     * @param services shared service registry
     */
    public MlRadarView(ServiceContext services) {
        this.searchEngine = services.getCatalogSearchEngine();
        this.mlEngine = services.getOfflineMlEngine();
        this.advancedMlService = services.getAdvancedOfflineMlService();
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when navigating away.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Builds the ML Radar and DS&amp;A inspection UI panel.
     *
     * @return scrollable JavaFX node
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(12, 4, 16, 0));

        // DS&A Live Trie / BK-Tree Search Sandbox Card
        VBox searchCard = new VBox(8);
        searchCard.getStyleClass().add("card");
        HBox sHdr = new HBox(8, AppIcons.of(Feather.ZAP, 16, "#6366F1"));
        sHdr.setAlignment(Pos.CENTER_LEFT);
        Label sTitle = new Label("DS&A Instant Search Sandbox (HashMap + Compressed Trie + BK-Tree Typo Tolerance)");
        sTitle.getStyleClass().add("section-title");
        sHdr.getChildren().add(sTitle);

        TextField queryBox = new TextField();
        queryBox.setPromptText("Try exact SKU (PRD-1001), prefix (wir), or typo (keybord / wirless)...");
        searchStatsLabel = new Label("Type to benchmark real-time Trie & BK-Tree search latency.");
        searchStatsLabel.getStyleClass().add("muted-text");

        queryBox.textProperty().addListener((obs, oldVal, newVal) -> {
            CatalogSearchEngine.SearchResult res = searchEngine.search(newVal, 5);
            String names = res.results().stream().map(Product::getName).reduce((a, b) -> a + ", " + b).orElse("None");
            searchStatsLabel.setText(String.format("Strategy: %s | Latency: %.3f ms | Matches (%d): %s",
                    res.strategyUsed(), res.latencyMs(), res.results().size(), names));
        });
        searchCard.getChildren().addAll(sHdr, queryBox, searchStatsLabel);

        // Forecast + Smart Restock + Days-Until-Empty + ABC Table
        VBox tableCard = new VBox(10);
        tableCard.getStyleClass().add("card");
        HBox fHdr = new HBox(8, AppIcons.of(Feather.ACTIVITY, 16, "#10B981"));
        fHdr.setAlignment(Pos.CENTER_LEFT);
        Label fTitle = new Label("Offline ML Radar — Stockout Countdown, Smart Restock Plan & ABC Velocity Grades");
        fTitle.getStyleClass().add("section-title");
        fHdr.getChildren().add(fTitle);

        TableView<OfflineMlEngine.ProductForecast> table = new TableView<>(forecastRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(290);

        TableColumn<OfflineMlEngine.ProductForecast, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().product().getSku()));

        TableColumn<OfflineMlEngine.ProductForecast, String> nameCol = new TableColumn<>("Product");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().product().getName()));

        TableColumn<OfflineMlEngine.ProductForecast, String> abcCol = new TableColumn<>("ABC");
        abcCol.setMaxWidth(76);
        abcCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().abcGrade()));
        abcCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String grade, boolean empty) {
                super.updateItem(grade, empty);
                if (empty || grade == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label("Tier " + grade);
                pill.getStyleClass().addAll("status-pill", switch (grade) {
                    case "A" -> "status-in-stock";
                    case "B" -> "status-low-stock";
                    default -> "status-out-of-stock";
                });
                setGraphic(pill);
            }
        });

        TableColumn<OfflineMlEngine.ProductForecast, String> countdownCol = new TableColumn<>("Days Until Empty");
        countdownCol.setMinWidth(160);
        countdownCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().daysUntilEmpty() >= 999.0 ? "90+ days" : String.format("%.1f days", c.getValue().daysUntilEmpty())));
        countdownCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                OfflineMlEngine.ProductForecast f = getTableRow().getItem();
                double days = f.daysUntilEmpty();
                ProgressBar bar = new ProgressBar(Math.min(1.0, days / 30.0));
                bar.setPrefWidth(72);
                Label lbl = new Label(text);
                if (days <= 7.0) {
                    lbl.getStyleClass().add("kpi-delta-danger");
                } else {
                    lbl.setStyle("-fx-font-weight: bold;");
                }
                HBox box = new HBox(8, bar, lbl);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });

        TableColumn<OfflineMlEngine.ProductForecast, String> f7Col = new TableColumn<>("7d / 30d Sales");
        f7Col.setCellValueFactory(c -> new SimpleStringProperty(
                String.format("%.0f / %.0f", c.getValue().forecast7Days(), c.getValue().forecast30Days())));

        TableColumn<OfflineMlEngine.ProductForecast, String> restockCol = new TableColumn<>("Smart Restock Qty");
        restockCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().recommendedReorderQty() > 0
                        ? "+" + c.getValue().recommendedReorderQty() + " (Safety: " + c.getValue().safetyStock() + ")"
                        : "Stocked"));

        TableColumn<OfflineMlEngine.ProductForecast, String> tunedCol = new TableColumn<>("Auto Low Limit");
        tunedCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().tunedLowStockThreshold() + " (now " + c.getValue().product().getLowStockThreshold() + ")"));

        table.getColumns().addAll(List.of(skuCol, nameCol, abcCol, countdownCol, f7Col, restockCol, tunedCol));
        tableCard.getChildren().addAll(fHdr, table);

        // Theft, Anomaly & Typo Guard Cards
        VBox anomalyCard = new VBox(10);
        anomalyCard.getStyleClass().add("card");
        HBox aHdr = new HBox(8, AppIcons.of(Feather.SHIELD, 16, "#F59E0B"));
        aHdr.setAlignment(Pos.CENTER_LEFT);
        Label aTitle = new Label("Theft, Shrinkage & Fat-Finger Typo Guard (Z-Score / IQR)");
        aTitle.getStyleClass().add("section-title");
        aHdr.getChildren().add(aTitle);
        anomalyCardsBox = new VBox(8);
        anomalyCard.getChildren().addAll(aHdr, anomalyCardsBox);

        VBox advancedCard = new VBox(10);
        advancedCard.getStyleClass().add("card");
        HBox advHdr = new HBox(8, AppIcons.of(Feather.TRENDING_UP, 16, "#4F46E5"));
        advHdr.setAlignment(Pos.CENTER_LEFT);
        Label advTitle = new Label("Enterprise ML Suite (Holt-Winters 95% CI • Supplier OTIF • Dynamic Pricing • Expiry • Apriori)");
        advTitle.getStyleClass().add("section-title");
        advHdr.getChildren().add(advTitle);
        advancedMlBox = new VBox(8);
        advancedCard.getChildren().addAll(advHdr, advancedMlBox);

        page.getChildren().addAll(searchCard, tableCard, anomalyCard, advancedCard);

        refreshAsync();

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        return scroll;
    }

    /**
     * Recomputes offline ML forecasts and statistical anomaly alerts on a background worker thread.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(() -> new Object[]{
                mlEngine.analyzeCatalog(),
                mlEngine.detectAnomalies(),
                advancedMlService.forecastCatalogHoltWinters(),
                advancedMlService.scoreSuppliersOtif(),
                advancedMlService.suggestExpiryMarkdowns(),
                advancedMlService.mineBoughtTogetherRules(1)
        }, data -> {
            @SuppressWarnings("unchecked")
            List<OfflineMlEngine.ProductForecast> forecasts = (List<OfflineMlEngine.ProductForecast>) data[0];
            @SuppressWarnings("unchecked")
            List<OfflineMlEngine.AnomalyAlert> alerts = (List<OfflineMlEngine.AnomalyAlert>) data[1];
            @SuppressWarnings("unchecked")
            List<com.stockpro.domain.MlSuiteModels.HoltWintersPrediction> hwList = (List<com.stockpro.domain.MlSuiteModels.HoltWintersPrediction>) data[2];
            @SuppressWarnings("unchecked")
            List<com.stockpro.domain.MlSuiteModels.SupplierOtifScore> otifs = (List<com.stockpro.domain.MlSuiteModels.SupplierOtifScore>) data[3];
            @SuppressWarnings("unchecked")
            List<com.stockpro.domain.MlSuiteModels.ExpiryMarkdownSuggestion> expiries = (List<com.stockpro.domain.MlSuiteModels.ExpiryMarkdownSuggestion>) data[4];
            @SuppressWarnings("unchecked")
            List<com.stockpro.domain.MlSuiteModels.BasketAssociationRule> basket = (List<com.stockpro.domain.MlSuiteModels.BasketAssociationRule>) data[5];

            forecastRows.setAll(forecasts);
            anomalyCardsBox.getChildren().clear();
            if (alerts.isEmpty()) {
                Label clean = new Label("No statistical quantity typos or shrinkage anomalies detected across inventory.");
                clean.getStyleClass().add("muted-text");
                anomalyCardsBox.getChildren().add(clean);
            } else {
                for (OfflineMlEngine.AnomalyAlert alert : alerts) {
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    Label badge = new Label(alert.severity() + " • Z=" + alert.zScore());
                    badge.getStyleClass().addAll("status-pill",
                            "HIGH".equals(alert.severity()) ? "status-out-of-stock" : "status-low-stock");
                    Label desc = new Label(alert.description());
                    desc.setWrapText(true);
                    HBox.setHgrow(desc, Priority.ALWAYS);
                    row.getChildren().addAll(badge, desc);
                    anomalyCardsBox.getChildren().add(row);
                }
            }

            if (advancedMlBox != null) {
                advancedMlBox.getChildren().clear();
                if (!hwList.isEmpty()) {
                    var topHw = hwList.get(0);
                    advancedMlBox.getChildren().add(new Label(String.format(
                            "Holt-Winters Leader: %s -> Next 7d %.1f units (95%% CI: %.1f..%.1f) • %s",
                            topHw.productName(), topHw.next7DaysForecast(), topHw.lower95Bound(), topHw.upper95Bound(), topHw.seasonalityNote())));
                }
                if (!otifs.isEmpty()) {
                    var topSup = otifs.get(0);
                    advancedMlBox.getChildren().add(new Label(String.format(
                            "Top Supplier OTIF: %s (%.1f%% • %s • Est. Lead %.1fd)",
                            topSup.supplierName(), topSup.otifScorePercent(), topSup.tier(), topSup.predictedDeliveryDays())));
                }
                if (!expiries.isEmpty()) {
                    var ex0 = expiries.get(0);
                    advancedMlBox.getChildren().add(new Label(String.format(
                            "Expiry Rescue Markdown: %s expires in %dd -> Suggest -%d%% ($%.2f) to recover $%.2f",
                            ex0.productName(), ex0.daysUntilExpiry(), ex0.recommendedDiscountPercent(), ex0.clearanceUnitPrice(), ex0.recoverableRevenue())));
                }
                if (!basket.isEmpty()) {
                    advancedMlBox.getChildren().add(new Label("Apriori Basket Tip: " + basket.get(0).pitch()));
                }
                if (advancedMlBox.getChildren().isEmpty()) {
                    Label none = new Label("Add inventory and sales movements to unlock seasonal, OTIF, and Apriori rules.");
                    none.getStyleClass().add("muted-text");
                    advancedMlBox.getChildren().add(none);
                }
            }
        }, ex -> {});
    }
}
