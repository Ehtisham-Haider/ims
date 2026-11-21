package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.dsa.CatalogSearchEngine;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
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

import java.util.List;

/**
 * Offline Machine Learning Radar &amp; DS&amp;A Search Sandbox UI displaying Days-Until-Empty bars,
 * Smart Restock Plan, Auto-Tuned Thresholds, ABC grades, and Theft/Typo anomaly alerts.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class MlRadarView {
    private final CatalogSearchEngine searchEngine;
    private final OfflineMlEngine mlEngine;

    private final ObservableList<OfflineMlEngine.ProductForecast> forecastRows = FXCollections.observableArrayList();
    private VBox anomalyCardsBox;
    private Label searchStatsLabel;

    /**
     * Constructs the ML Radar view.
     *
     * @param services shared service registry
     */
    public MlRadarView(ServiceContext services) {
        this.searchEngine = services.getCatalogSearchEngine();
        this.mlEngine = services.getOfflineMlEngine();
        InventoryEventBus.getInstance().subscribe(t -> refreshAsync());
    }

    /**
     * Builds the ML Radar and DS&amp;A inspection UI panel.
     *
     * @return scrollable JavaFX node
     */
    @SuppressWarnings("unchecked")
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(12, 4, 16, 0));

        // DS&A Live Trie / BK-Tree Search Sandbox Card
        VBox searchCard = new VBox(8);
        searchCard.getStyleClass().add("card");
        Label sTitle = new Label("⚡ DS&A Instant Search Sandbox (HashMap + Compressed Trie + BK-Tree Typo Tolerance)");
        sTitle.getStyleClass().add("section-title");

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
        searchCard.getChildren().addAll(sTitle, queryBox, searchStatsLabel);

        // Forecast + Smart Restock + Days-Until-Empty + ABC Table
        VBox tableCard = new VBox(10);
        tableCard.getStyleClass().add("card");
        Label fTitle = new Label("📡 Offline ML Radar — Stockout Countdown, Smart Restock Plan & ABC Velocity Grades");
        fTitle.getStyleClass().add("section-title");

        TableView<OfflineMlEngine.ProductForecast> table = new TableView<>(forecastRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(290);

        TableColumn<OfflineMlEngine.ProductForecast, String> skuCol = new TableColumn<>("SKU");
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().product().getSku()));

        TableColumn<OfflineMlEngine.ProductForecast, String> nameCol = new TableColumn<>("Product");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().product().getName()));

        TableColumn<OfflineMlEngine.ProductForecast, String> abcCol = new TableColumn<>("ABC");
        abcCol.setMaxWidth(68);
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
        countdownCol.setMinWidth(155);
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
                lbl.setStyle(days <= 7.0 ? "-fx-text-fill: #DC2626; -fx-font-weight: bold;" : "-fx-font-weight: bold;");
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

        table.getColumns().addAll(skuCol, nameCol, abcCol, countdownCol, f7Col, restockCol, tunedCol);
        tableCard.getChildren().addAll(fTitle, table);

        // Theft, Anomaly & Typo Guard Cards
        VBox anomalyCard = new VBox(10);
        anomalyCard.getStyleClass().add("card");
        Label aTitle = new Label("🛡 Theft, Shrinkage & Fat-Finger Typo Guard (Z-Score / IQR)");
        aTitle.getStyleClass().add("section-title");
        anomalyCardsBox = new VBox(8);
        anomalyCard.getChildren().addAll(aTitle, anomalyCardsBox);

        page.getChildren().addAll(searchCard, tableCard, anomalyCard);

        refreshAsync();

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /**
     * Recomputes offline ML forecasts and statistical anomaly alerts on a background worker thread.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(() -> new Object[]{
                mlEngine.analyzeCatalog(),
                mlEngine.detectAnomalies()
        }, data -> {
            @SuppressWarnings("unchecked")
            List<OfflineMlEngine.ProductForecast> forecasts = (List<OfflineMlEngine.ProductForecast>) data[0];
            @SuppressWarnings("unchecked")
            List<OfflineMlEngine.AnomalyAlert> alerts = (List<OfflineMlEngine.AnomalyAlert>) data[1];

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
        }, ex -> {
        });
    }
}
