package com.stockpro.ui.views;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.InventoryFilterCriteria.StatusFilterChip;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockStatus;
import com.stockpro.service.AppSession;
import com.stockpro.service.DashboardMetricsService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.KpiStatCard;
import com.stockpro.ui.components.KpiStatCard.DeltaTone;
import com.stockpro.ui.components.ModernTrendChart;
import com.stockpro.ui.components.StockDonutChart;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * Executive Dashboard view aligned with {@code Dashboard.png} and {@code Dasboard Optional.png},
 * rendering 5 Feather-badged KPI cards, interactive smooth cubic-bezier Sales &amp; Profit trend chart
 * with 7d/30d period pills, high-DPI Stock Status donut breakdown, clickable Urgent Alerts, Recent Activity feed,
 * and AI Business Briefing card with Groq key quick-connect action.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class DashboardView {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("hh:mm a");

    private final ServiceContext services;
    private final DashboardMetricsService metricsService;
    private final Consumer<NavigationSection> onNavigate;
    private final Consumer<Product> onInspectProduct;
    private final Consumer<InventoryFilterCriteria> onFilterInventory;
    private final Runnable onOpenAiSettings;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private KpiStatCard totalProductsCard;
    private KpiStatCard inventoryValueCard;
    private KpiStatCard estProfitCard;
    private KpiStatCard lowStockCard;
    private KpiStatCard outOfStockCard;

    private ModernTrendChart trendChart;
    private StockDonutChart donutChart;
    private VBox urgentAlertsBox;
    private VBox recentActivityBox;
    private VBox briefingBodyBox;
    private HBox briefingActionsRow;
    private int trendDays = 7;

    /**
     * Constructs a dashboard view controller with core section navigation.
     *
     * @param services active application service registry
     * @param onNavigate navigation callback to switch sidebar screens
     */
    public DashboardView(ServiceContext services, Consumer<NavigationSection> onNavigate) {
        this(services, onNavigate, null, null, null);
    }

    /**
     * Constructs a dashboard view controller wired to full cross-screen drill-down actions.
     *
     * @param services active application service registry
     * @param onNavigate navigation callback to switch sidebar screens
     * @param onInspectProduct callback opening a product in the Inventory details drawer
     * @param onFilterInventory callback opening Inventory with a pre-applied filter
     * @param onOpenAiSettings callback jumping directly to Settings -&gt; AI &amp; Groq API
     */
    public DashboardView(
            ServiceContext services,
            Consumer<NavigationSection> onNavigate,
            Consumer<Product> onInspectProduct,
            Consumer<InventoryFilterCriteria> onFilterInventory,
            Runnable onOpenAiSettings) {
        this.services = services;
        this.metricsService = new DashboardMetricsService(services.getDatabaseManager(),
                services.getProductRepository());
        this.onNavigate = onNavigate;
        this.onInspectProduct = onInspectProduct;
        this.onFilterInventory = onFilterInventory;
        this.onOpenAiSettings = onOpenAiSettings;
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when navigating away from the dashboard.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Repaints custom vector Canvas charts cleanly whenever the user switches Light/Dark mode.
     */
    public void onThemeChanged() {
        if (trendChart != null) {
            trendChart.redraw();
        }
        if (donutChart != null) {
            donutChart.redraw();
        }
    }

    /**
     * Builds the scrollable Dashboard node and triggers an asynchronous initial data load.
     *
     * @return JavaFX dashboard {@link Node}
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(0, 4, 16, 0));

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

        Button refreshBtn = new Button("Refresh");
        refreshBtn.setGraphic(AppIcons.of(Feather.REFRESH_CW, 14));
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshAsync());

        headerRow.getChildren().addAll(titleGroup, spacer, refreshBtn);

        // Row 1: 5 Pastel KPI Stat Cards with Feather icons & micro-sparklines
        totalProductsCard = new KpiStatCard(Feather.PACKAGE, "#4F46E5", "kpi-card-blue", "Total Products", "0", "Active SKUs");
        inventoryValueCard = new KpiStatCard(Feather.DOLLAR_SIGN, "#10B981", "kpi-card-green", "Inventory Value", "$0.00", "Cost valuation");
        estProfitCard = new KpiStatCard(Feather.TRENDING_UP, "#8B5CF6", "kpi-card-purple", "Est. Profit", "$0.00", "Projected margin");
        lowStockCard = new KpiStatCard(Feather.ALERT_TRIANGLE, "#F59E0B", "kpi-card-amber", "Low Stock", "0", "Needs restock");
        outOfStockCard = new KpiStatCard(Feather.X_CIRCLE, "#EF4444", "kpi-card-rose", "Out of Stock", "0", "Zero units");

        HBox kpiRow = new HBox(12, totalProductsCard, inventoryValueCard, estProfitCard, lowStockCard, outOfStockCard);

        // Row 2: Dual-Series Sales & Profit Trend + Stock Status Donut & Urgent Alerts
        VBox trendCard = buildTrendChartCard();
        HBox.setHgrow(trendCard, Priority.ALWAYS);

        VBox rightMiddleCol = new VBox(14, buildDonutCard(), buildUrgentAlertsCard());
        rightMiddleCol.setPrefWidth(390);
        rightMiddleCol.setMinWidth(340);

        HBox middleRow = new HBox(16, trendCard, rightMiddleCol);

        // Row 3: Recent Activity + AI Business Briefing Card
        VBox recentCard = buildRecentActivityCard();
        HBox.setHgrow(recentCard, Priority.ALWAYS);

        VBox briefingCard = buildAiBriefingCard();
        briefingCard.setPrefWidth(390);
        briefingCard.setMinWidth(340);

        HBox bottomRow = new HBox(16, recentCard, briefingCard);

        page.getChildren().addAll(headerRow, kpiRow, middleRow, bottomRow);

        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);

        refreshAsync();
        return scrollPane;
    }

    /**
     * Creates the interactive dual-series smooth Sales &amp; Profit Trend card with period switcher pills.
     *
     * @return styled chart card
     */
    private VBox buildTrendChartCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Sales & Profit Trend");
        title.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button p7Btn = new Button("7 Days");
        p7Btn.getStyleClass().addAll("filter-chip", "filter-chip-active");
        Button p30Btn = new Button("30 Days");
        p30Btn.getStyleClass().add("filter-chip");

        p7Btn.setOnAction(e -> {
            trendDays = 7;
            p7Btn.getStyleClass().add("filter-chip-active");
            p30Btn.getStyleClass().remove("filter-chip-active");
            refreshAsync();
        });
        p30Btn.setOnAction(e -> {
            trendDays = 30;
            p30Btn.getStyleClass().add("filter-chip-active");
            p7Btn.getStyleClass().remove("filter-chip-active");
            refreshAsync();
        });

        top.getChildren().addAll(title, spacer, p7Btn, p30Btn);

        trendChart = new ModernTrendChart(295);
        VBox.setVgrow(trendChart, Priority.ALWAYS);

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

        Button viewAll = new Button("View All");
        viewAll.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13));
        viewAll.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        viewAll.getStyleClass().add("link-btn");
        viewAll.setOnAction(e -> {
            if (onFilterInventory != null) {
                InventoryFilterCriteria c = new InventoryFilterCriteria();
                c.setStatusChip(StatusFilterChip.LOW_STOCK);
                onFilterInventory.accept(c);
            } else {
                onNavigate.accept(NavigationSection.INVENTORY);
            }
        });
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

        Button viewAll = new Button("View All");
        viewAll.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13));
        viewAll.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        viewAll.getStyleClass().add("link-btn");
        viewAll.setOnAction(e -> onNavigate.accept(NavigationSection.STOCK_OPERATIONS));
        header.getChildren().addAll(title, spacer, viewAll);

        recentActivityBox = new VBox(8);
        card.getChildren().addAll(header, recentActivityBox);
        return card;
    }

    /**
     * Builds the gradient AI Business Briefing card linking to AI Assistant or Groq Settings setup.
     *
     * @return styled briefing card
     */
    private VBox buildAiBriefingCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("ai-briefing-card");

        HBox headingRow = new HBox(8, AppIcons.of(Feather.CPU, 18, "#FFFFFF"));
        headingRow.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("AI Business Briefing");
        heading.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold;");
        headingRow.getChildren().add(heading);

        briefingBodyBox = new VBox(6);
        briefingActionsRow = new HBox(8);
        briefingActionsRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(headingRow, briefingBodyBox, briefingActionsRow);
        return card;
    }

    /**
     * Reloads dashboard metrics in a background worker thread and applies updates on the JavaFX thread.
     */
    public void refreshAsync() {
        int horizon = trendDays;
        AppExecutor.runAsync(
                () -> metricsService.loadSnapshot(horizon),
                this::applySnapshot,
                ex -> {}
        );
    }

    /**
     * Binds a loaded {@link DashboardMetricsService.DashboardSnapshot} into all KPI, chart, and alert widgets.
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
                DeltaTone.POSITIVE);
        inventoryValueCard.updateMetric(
                session.formatMoney(snap.inventoryValue()),
                "Total cost on hand",
                DeltaTone.POSITIVE);
        estProfitCard.updateMetric(
                session.formatMoney(snap.estimatedProfit()),
                "Potential gross profit",
                DeltaTone.ACCENT);
        lowStockCard.updateMetric(
                String.valueOf(snap.lowStockCount()),
                snap.lowStockCount() > 0 ? "Action recommended" : "All clear",
                snap.lowStockCount() > 0 ? DeltaTone.WARNING : DeltaTone.POSITIVE);
        outOfStockCard.updateMetric(
                String.valueOf(snap.outOfStockCount()),
                snap.outOfStockCount() > 0 ? "Immediate restock" : "Zero stockouts",
                snap.outOfStockCount() > 0 ? DeltaTone.DANGER : DeltaTone.POSITIVE);

        // Update custom Canvas Trend Chart
        List<String> xLabels = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::dayLabel).toList();
        List<Double> salesVals = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::salesRevenue).toList();
        List<Double> profitVals = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::profitAmount).toList();
        trendChart.setSeries(xLabels, List.of(
                new ModernTrendChart.SeriesData("Sales", "#4F46E5", salesVals),
                new ModernTrendChart.SeriesData("Profit", "#8B5CF6", profitVals)
        ));

        // Update Donut Chart
        donutChart.setData(
                String.valueOf(snap.totalProducts()),
                "Total",
                new StockDonutChart.Slice("In Stock", String.format("%.0f%%", snap.inStockPercent()),
                        snap.inStockPercent() / 100.0, "#10B981"),
                new StockDonutChart.Slice("Low Stock", String.format("%.0f%%", snap.lowStockPercent()),
                        snap.lowStockPercent() / 100.0, "#F59E0B"),
                new StockDonutChart.Slice("Out of Stock", String.format("%.0f%%", snap.outOfStockPercent()),
                        snap.outOfStockPercent() / 100.0, "#EF4444"));

        // Update Urgent Alerts (clickable to inspect directly)
        urgentAlertsBox.getChildren().clear();
        if (snap.expiringSoonCount() > 0) {
            urgentAlertsBox.getChildren().add(alertLine(Feather.CLOCK, "#FEF3C7", "#B45309",
                    snap.expiringSoonCount() + " item(s) expire within 7 days", null));
        }
        if (snap.outOfStockCount() > 0) {
            urgentAlertsBox.getChildren().add(alertLine(Feather.X_CIRCLE, "#FEE2E2", "#B91C1C",
                    snap.outOfStockCount() + " item(s) out of stock", null));
        }
        for (Product p : snap.urgentProducts().stream().limit(2).toList()) {
            boolean out = p.getStatus() == StockStatus.OUT_OF_STOCK || p.getQuantity() <= 0;
            urgentAlertsBox.getChildren().add(alertLine(
                    out ? Feather.X_CIRCLE : Feather.ALERT_TRIANGLE,
                    out ? "#FEE2E2" : "#FEF3C7",
                    out ? "#B91C1C" : "#B45309",
                    (out ? "Out of stock: " : "Low stock: ") + p.getName() + " (" + p.getQuantity() + " left)",
                    p));
        }
        if (urgentAlertsBox.getChildren().isEmpty()) {
            Label healthy = new Label("All inventory levels and expiry dates are healthy.");
            healthy.getStyleClass().add("muted-text");
            urgentAlertsBox.getChildren().add(healthy);
        }

        // Update Recent Activity
        recentActivityBox.getChildren().clear();
        if (snap.recentMovements().isEmpty()) {
            Label emptyMsg = new Label(
                    "No stock movements recorded yet. Record a Restock or Sale in Stock Operations.");
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

        briefingActionsRow.getChildren().clear();
        Button detailsBtn = new Button("Open AI Assistant");
        detailsBtn.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13, "#FFFFFF"));
        detailsBtn.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        detailsBtn.setStyle("-fx-background-color: rgba(255,255,255,0.22); -fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-background-radius: 9; -fx-padding: 8 14 8 14; -fx-cursor: hand;");
        detailsBtn.setOnAction(e -> onNavigate.accept(NavigationSection.AI_ASSISTANT));
        briefingActionsRow.getChildren().add(detailsBtn);

        if (!services.getGroqApiClient().isApiKeyConfigured() && onOpenAiSettings != null) {
            Button keyBtn = new Button("Add Groq API Key");
            keyBtn.setGraphic(AppIcons.of(Feather.KEY, 13, "#FFFFFF"));
            keyBtn.setStyle("-fx-background-color: rgba(255,255,255,0.14); -fx-text-fill: #FFFFFF; -fx-font-weight: 600; -fx-background-radius: 9; -fx-padding: 8 12 8 12; -fx-cursor: hand;");
            keyBtn.setOnAction(e -> onOpenAiSettings.run());
            briefingActionsRow.getChildren().add(keyBtn);
        }
    }

    private HBox alertLine(Feather feather, String bgHex, String fgHex, String message, Product targetProduct) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        StackPane iconBadge = new StackPane(AppIcons.of(feather, 13, fgHex));
        iconBadge.setMinSize(26, 26);
        iconBadge.setPrefSize(26, 26);
        iconBadge.setStyle("-fx-background-color: " + bgHex + "; -fx-background-radius: 999;");

        Label text = new Label(message);
        text.setWrapText(true);
        HBox.setHgrow(text, Priority.ALWAYS);
        row.getChildren().addAll(iconBadge, text);

        if (targetProduct != null && onInspectProduct != null) {
            row.setStyle("-fx-cursor: hand;");
            row.setOnMouseClicked(e -> onInspectProduct.accept(targetProduct));
        }
        return row;
    }
}
