package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockStatus;
import com.stockpro.service.AppSession;
import com.stockpro.service.DashboardMetricsService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.KpiStatCard;
import com.stockpro.ui.components.StockDonutChart;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Executive Dashboard view aligned with {@code Dashboard.png} and {@code Dasboard Optional.png},
 * rendering 5 live KPI cards, a dual-series Sales &amp; Profit trend chart, Stock Status donut breakdown,
 * Urgent Alerts, Recent Activity feed, and AI Business Briefing card.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class DashboardView {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private final DashboardMetricsService metricsService;
    private final Consumer<NavigationSection> onNavigate;

    private KpiStatCard totalProductsCard;
    private KpiStatCard inventoryValueCard;
    private KpiStatCard estProfitCard;
    private KpiStatCard lowStockCard;
    private KpiStatCard outOfStockCard;

    private AreaChart<String, Number> trendChart;
    private StockDonutChart donutChart;
    private VBox urgentAlertsBox;
    private VBox recentActivityBox;
    private VBox briefingBodyBox;

    /**
     * Constructs the dashboard view controller.
     *
     * @param services active application service registry
     * @param onNavigate navigation callback to switch sidebar screens
     */
    public DashboardView(ServiceContext services, Consumer<NavigationSection> onNavigate) {
        this.metricsService = new DashboardMetricsService(services.getDatabaseManager(), services.getProductRepository());
        this.onNavigate = onNavigate;
        InventoryEventBus.getInstance().subscribe(topic -> refreshAsync());
    }

    /**
     * Builds the scrollable Dashboard node and triggers an asynchronous initial data load.
     *
     * @return JavaFX dashboard {@link Node}
     */
    public Node build() {
        VBox page = new VBox(18);
        page.setPadding(new Insets(0, 4, 16, 0));

        // Page Header
        HBox headerRow = new HBox(12);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        VBox titleGroup = new VBox(2);
        Label title = new Label("Dashboard");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Welcome back, " + AppSession.getInstance().getActorName() + "!");
        subtitle.getStyleClass().add("page-subtitle");
        titleGroup.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button("↻ Refresh");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshAsync());

        headerRow.getChildren().addAll(titleGroup, spacer, refreshBtn);

        // Row 1: 5 Pastel KPI Stat Cards
        totalProductsCard = new KpiStatCard("📦", "#4F46E5", "kpi-card-blue", "Total Products", "0", "Active SKUs");
        inventoryValueCard = new KpiStatCard("💼", "#10B981", "kpi-card-green", "Inventory Value", "$0.00", "Cost valuation");
        estProfitCard = new KpiStatCard("📈", "#8B5CF6", "kpi-card-purple", "Est. Profit", "$0.00", "Projected margin");
        lowStockCard = new KpiStatCard("⚠", "#F59E0B", "kpi-card-amber", "Low Stock", "0", "Needs restock");
        outOfStockCard = new KpiStatCard("✕", "#EF4444", "kpi-card-rose", "Out of Stock", "0", "Zero units");

        HBox kpiRow = new HBox(14, totalProductsCard, inventoryValueCard, estProfitCard, lowStockCard, outOfStockCard);

        // Row 2: Dual-Series Sales & Profit Trend + Stock Status Donut & Urgent Alerts
        VBox trendCard = buildTrendChartCard();
        HBox.setHgrow(trendCard, Priority.ALWAYS);

        VBox rightMiddleCol = new VBox(14, buildDonutCard(), buildUrgentAlertsCard());
        rightMiddleCol.setPrefWidth(390);
        rightMiddleCol.setMinWidth(350);

        HBox middleRow = new HBox(16, trendCard, rightMiddleCol);

        // Row 3: Recent Activity + AI Business Briefing Card
        VBox recentCard = buildRecentActivityCard();
        HBox.setHgrow(recentCard, Priority.ALWAYS);

        VBox briefingCard = buildAiBriefingCard();
        briefingCard.setPrefWidth(390);
        briefingCard.setMinWidth(350);

        HBox bottomRow = new HBox(16, recentCard, briefingCard);

        page.getChildren().addAll(headerRow, kpiRow, middleRow, bottomRow);

        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        refreshAsync();
        return scrollPane;
    }

    /**
     * Creates the interactive dual-series Sales &amp; Profit Trend chart card.
     *
     * @return styled chart card
     */
    private VBox buildTrendChartCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        HBox top = new HBox(10);
        top.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Sales & Profit Trend");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label badge = new Label("Last 7 Days");
        badge.getStyleClass().add("muted-text");
        top.getChildren().addAll(title, spacer, badge);

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        yAxis.setForceZeroInRange(true);

        trendChart = new AreaChart<>(xAxis, yAxis);
        trendChart.setAnimated(false);
        trendChart.setLegendVisible(true);
        trendChart.setPrefHeight(285);
        trendChart.setStyle("-fx-background-color: transparent;");

        card.getChildren().addAll(top, trendChart);
        return card;
    }

    /**
     * Builds the Stock Status Donut breakdown card.
     *
     * @return donut chart card
     */
    private VBox buildDonutCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label title = new Label("Stock Status");
        title.getStyleClass().add("section-title");
        donutChart = new StockDonutChart();
        card.getChildren().addAll(title, donutChart);
        return card;
    }

    /**
     * Builds the Urgent Alerts card summarizing expiring, out-of-stock, and low-stock SKUs.
     *
     * @return urgent alerts card
     */
    private VBox buildUrgentAlertsCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Urgent Alerts");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button viewAll = new Button("View All →");
        viewAll.setStyle("-fx-background-color: transparent; -fx-text-fill: #4F46E5; -fx-font-weight: bold; -fx-cursor: hand;");
        viewAll.setOnAction(e -> onNavigate.accept(NavigationSection.INVENTORY));
        header.getChildren().addAll(title, spacer, viewAll);

        urgentAlertsBox = new VBox(8);
        card.getChildren().addAll(header, urgentAlertsBox);
        return card;
    }

    /**
     * Builds the Recent Activity feed card showing latest stock changes.
     *
     * @return recent activity card
     */
    private VBox buildRecentActivityCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Recent Activity");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button viewAll = new Button("View All →");
        viewAll.setStyle("-fx-background-color: transparent; -fx-text-fill: #4F46E5; -fx-font-weight: bold; -fx-cursor: hand;");
        viewAll.setOnAction(e -> onNavigate.accept(NavigationSection.STOCK_OPERATIONS));
        header.getChildren().addAll(title, spacer, viewAll);

        recentActivityBox = new VBox(8);
        card.getChildren().addAll(header, recentActivityBox);
        return card;
    }

    /**
     * Builds the gradient AI Business Briefing card linking to the AI Assistant workspace.
     *
     * @return styled briefing card
     */
    private VBox buildAiBriefingCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("ai-briefing-card");

        Label heading = new Label("🤖  AI Business Briefing ✨");
        heading.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold;");

        briefingBodyBox = new VBox(6);

        Button detailsBtn = new Button("View Details →");
        detailsBtn.setStyle("-fx-background-color: rgba(255,255,255,0.22); -fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-background-radius: 9; -fx-padding: 8 14 8 14; -fx-cursor: hand;");
        detailsBtn.setOnAction(e -> onNavigate.accept(NavigationSection.AI_ASSISTANT));

        card.getChildren().addAll(heading, briefingBodyBox, detailsBtn);
        return card;
    }

    /**
     * Reloads dashboard metrics in a background worker thread and applies updates on the JavaFX thread.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(
                metricsService::loadSnapshot,
                this::applySnapshot,
                ex -> {
                }
        );
    }

    /**
     * Binds a loaded {@link DashboardMetricsService.DashboardSnapshot} into all UI widgets.
     *
     * @param snap aggregated metrics snapshot
     */
    private void applySnapshot(DashboardMetricsService.DashboardSnapshot snap) {
        if (totalProductsCard == null) {
            return;
        }
        AppSession session = AppSession.getInstance();

        totalProductsCard.updateMetric(
                String.valueOf(snap.totalProducts()),
                snap.inStockCount() + " healthy items",
                "#15803D"
        );
        inventoryValueCard.updateMetric(
                session.formatMoney(snap.inventoryValue()),
                "Total cost on hand",
                "#15803D"
        );
        estProfitCard.updateMetric(
                session.formatMoney(snap.estimatedProfit()),
                "Potential gross profit",
                "#4F46E5"
        );
        lowStockCard.updateMetric(
                String.valueOf(snap.lowStockCount()),
                snap.lowStockCount() > 0 ? "Action recommended" : "All clear",
                snap.lowStockCount() > 0 ? "#B45309" : "#15803D"
        );
        outOfStockCard.updateMetric(
                String.valueOf(snap.outOfStockCount()),
                snap.outOfStockCount() > 0 ? "Immediate restock" : "Zero stockouts",
                snap.outOfStockCount() > 0 ? "#B91C1C" : "#15803D"
        );

        // Update Trend Chart
        XYChart.Series<String, Number> salesSeries = new XYChart.Series<>();
        salesSeries.setName("Sales");
        XYChart.Series<String, Number> profitSeries = new XYChart.Series<>();
        profitSeries.setName("Profit");

        for (DashboardMetricsService.TrendPoint pt : snap.trendPoints()) {
            salesSeries.getData().add(new XYChart.Data<>(pt.dayLabel(), pt.salesRevenue()));
            profitSeries.getData().add(new XYChart.Data<>(pt.dayLabel(), pt.profitAmount()));
        }
        trendChart.getData().setAll(salesSeries, profitSeries);

        for (XYChart.Series<String, Number> series : trendChart.getData()) {
            for (XYChart.Data<String, Number> data : series.getData()) {
                if (data.getNode() != null) {
                    Tooltip.install(data.getNode(),
                            new Tooltip(series.getName() + " (" + data.getXValue() + "): "
                                    + session.formatMoney(data.getYValue().doubleValue())));
                }
            }
        }

        // Update Donut Chart
        donutChart.setData(
                String.valueOf(snap.totalProducts()),
                "Total",
                new StockDonutChart.Slice("In Stock", String.format("%.0f%%", snap.inStockPercent()),
                        snap.inStockPercent() / 100.0, "#10B981"),
                new StockDonutChart.Slice("Low Stock", String.format("%.0f%%", snap.lowStockPercent()),
                        snap.lowStockPercent() / 100.0, "#F59E0B"),
                new StockDonutChart.Slice("Out of Stock", String.format("%.0f%%", snap.outOfStockPercent()),
                        snap.outOfStockPercent() / 100.0, "#EF4444")
        );

        // Update Urgent Alerts
        urgentAlertsBox.getChildren().clear();
        if (snap.expiringSoonCount() > 0) {
            urgentAlertsBox.getChildren().add(alertLine("⚠", "#FEF3C7", "#B45309",
                    snap.expiringSoonCount() + " item(s) will expire in next 7 days"));
        }
        if (snap.outOfStockCount() > 0) {
            urgentAlertsBox.getChildren().add(alertLine("✕", "#FEE2E2", "#B91C1C",
                    snap.outOfStockCount() + " item(s) out of stock"));
        }
        for (Product p : snap.urgentProducts().stream().limit(2).toList()) {
            boolean out = p.getStatus() == StockStatus.OUT_OF_STOCK || p.getQuantity() <= 0;
            urgentAlertsBox.getChildren().add(alertLine(
                    out ? "✕" : "⚠",
                    out ? "#FEE2E2" : "#FEF3C7",
                    out ? "#B91C1C" : "#B45309",
                    (out ? "Out of stock: " : "Low stock: ") + p.getName() + " (" + p.getQuantity() + " left)"
            ));
        }
        if (urgentAlertsBox.getChildren().isEmpty()) {
            Label healthy = new Label("All inventory levels and expiry dates are healthy.");
            healthy.getStyleClass().add("muted-text");
            urgentAlertsBox.getChildren().add(healthy);
        }

        // Update Recent Activity
        recentActivityBox.getChildren().clear();
        if (snap.recentMovements().isEmpty()) {
            Label emptyMsg = new Label("No stock movements recorded yet. Record a Restock or Sale in Stock Operations.");
            emptyMsg.getStyleClass().add("muted-text");
            emptyMsg.setWrapText(true);
            recentActivityBox.getChildren().add(emptyMsg);
        } else {
            for (StockMovement m : snap.recentMovements()) {
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);
                String deltaStr = (m.getQuantityDelta() >= 0 ? "+" : "") + m.getQuantityDelta();
                Label badge = new Label(m.getMovementType().getDisplayName());
                badge.getStyleClass().addAll("status-pill",
                        m.getQuantityDelta() >= 0 ? "status-in-stock" : "status-low-stock");
                Label text = new Label(m.getProductName() + " (" + deltaStr + ") by " + m.getPerformedBy());
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                Label time = new Label(m.getCreatedAt() != null ? m.getCreatedAt().format(TIME_FMT) : "");
                time.getStyleClass().add("muted-text");
                row.getChildren().addAll(badge, text, sp, time);
                recentActivityBox.getChildren().add(row);
            }
        }

        // Update AI Briefing Card
        briefingBodyBox.getChildren().clear();
        for (String line : snap.briefingLines()) {
            Label lbl = new Label("• " + line);
            lbl.setWrapText(true);
            lbl.setStyle("-fx-text-fill: #EEF2FF; -fx-font-size: 13px;");
            briefingBodyBox.getChildren().add(lbl);
        }
    }

    /**
     * Renders a compact alert row inside the Urgent Alerts card.
     *
     * @param symbol icon symbol
     * @param bgHex badge background color
     * @param fgHex badge text color
     * @param message alert text
     * @return formatted row {@link HBox}
     */
    private HBox alertLine(String symbol, String bgHex, String fgHex, String message) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label(symbol);
        icon.setMinSize(26, 26);
        icon.setAlignment(Pos.CENTER);
        icon.setStyle("-fx-background-color: " + bgHex + "; -fx-text-fill: " + fgHex + "; -fx-font-weight: bold; -fx-background-radius: 999;");
        Label text = new Label(message);
        text.setWrapText(true);
        row.getChildren().addAll(icon, text);
        return row;
    }
}
