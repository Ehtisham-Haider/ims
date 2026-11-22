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
import com.stockpro.ui.components.ModernBarChart;
import com.stockpro.ui.components.StockDonutChart;
import com.stockpro.ui.components.ThemeManager;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
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
 * Executive Dashboard in the StockPro editorial layout: a full-width row of KPI cards
 * (black / accent / grey / white), a hatched-top Sales &amp; Profit bar chart with a 7/30-day
 * segmented switch, a Recent Activity list, and a right column holding the segmented Stock Status
 * pie, clickable Urgent Alerts and the inverse AI Business Briefing card.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class DashboardView {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM d, hh:mm a");
    private static final double SIDE_COLUMN_WIDTH = 360;

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

    private ModernBarChart salesChart;
    private StockDonutChart donutChart;
    private Canvas legendHatch;
    private Canvas legendSolid;
    private VBox urgentAlertsBox;
    private VBox recentActivityBox;
    private VBox briefingBodyBox;
    private HBox briefingActionsRow;
    private int trendDays = 7;

    /**
     * Creates the dashboard with navigation support only.
     *
     * @param services shared service registry
     * @param onNavigate navigation callback
     */
    public DashboardView(ServiceContext services, Consumer<NavigationSection> onNavigate) {
        this(services, onNavigate, null, null, null);
    }

    /**
     * Creates the dashboard with cross-screen deep-link callbacks.
     *
     * @param services shared service registry
     * @param onNavigate navigation callback
     * @param onInspectProduct opens a product in the Inventory details drawer
     * @param onFilterInventory opens Inventory with filter criteria applied
     * @param onOpenAiSettings opens the AI settings tab
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
     * Unsubscribes the live-refresh EventBus listener.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Repaints canvas-rendered charts after a Light/Dark switch.
     */
    public void onThemeChanged() {
        if (salesChart != null) {
            salesChart.redraw();
        }
        if (donutChart != null) {
            donutChart.redraw();
        }
        paintLegendSwatches();
    }

    /**
     * Builds the dashboard layout and kicks off the first asynchronous data load.
     *
     * @return scrollable dashboard root node
     */
    public Node build() {
        VBox page = new VBox(12);
        page.setPadding(new Insets(0, 8, 4, 0));

        totalProductsCard = new KpiStatCard(Feather.PACKAGE, null, KpiStatCard.INVERSE, "Total Products", "0", "Active SKUs");
        inventoryValueCard = new KpiStatCard(Feather.DOLLAR_SIGN, null, KpiStatCard.ACCENT, "Inventory Value", "$0.00", "Cost on hand");
        estProfitCard = new KpiStatCard(Feather.TRENDING_UP, null, KpiStatCard.MUTED, "Est. Profit", "$0.00", "Projected margin");
        lowStockCard = new KpiStatCard(Feather.ALERT_TRIANGLE, null, KpiStatCard.PLAIN, "Low Stock", "0", "Needs restock");
        outOfStockCard = new KpiStatCard(Feather.X_CIRCLE, null, KpiStatCard.PLAIN, "Out of Stock", "0", "Zero units");
        HBox kpiRow = new HBox(12, totalProductsCard, inventoryValueCard, estProfitCard, lowStockCard, outOfStockCard);

        VBox chartCard = buildSalesChartCard();
        VBox activityCard = buildRecentActivityCard();
        VBox leftCol = new VBox(12, chartCard, activityCard);
        HBox.setHgrow(leftCol, Priority.ALWAYS);

        VBox rightCol = new VBox(12, buildDonutCard(), buildUrgentAlertsCard(), buildAiBriefingCard());
        rightCol.setPrefWidth(SIDE_COLUMN_WIDTH);
        rightCol.setMinWidth(SIDE_COLUMN_WIDTH);
        rightCol.setMaxWidth(SIDE_COLUMN_WIDTH);

        HBox body = new HBox(12, leftCol, rightCol);
        page.getChildren().addAll(kpiRow, body);

        ScrollPane scrollPane = new ScrollPane(page);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        refreshAsync();
        return scrollPane;
    }

    private HBox cardHeader(String title, Node... trailing) {
        Label t = new Label(title);
        t.getStyleClass().add("section-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, t, spacer);
        header.getChildren().addAll(trailing);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Button arrowLink(Runnable action) {
        Button b = new Button();
        b.setGraphic(AppIcons.of(Feather.ARROW_UP_RIGHT, 16));
        b.getStyleClass().add("icon-btn");
        b.setOnAction(e -> action.run());
        return b;
    }

    private VBox buildSalesChartCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");

        legendHatch = new Canvas(12, 12);
        legendSolid = new Canvas(12, 12);
        Label revenueLbl = new Label("Revenue");
        revenueLbl.getStyleClass().add("legend-label");
        Label profitLbl = new Label("Profit");
        profitLbl.getStyleClass().add("legend-label");
        HBox legend = new HBox(6, legendHatch, revenueLbl, new Region(), legendSolid, profitLbl);
        ((Region) legend.getChildren().get(2)).setMinWidth(8);
        legend.setAlignment(Pos.CENTER_LEFT);
        paintLegendSwatches();

        Button p7Btn = new Button("7 Days");
        p7Btn.getStyleClass().addAll("filter-chip", "filter-chip-active");
        Button p30Btn = new Button("30 Days");
        p30Btn.getStyleClass().add("filter-chip");
        p7Btn.setOnAction(e -> switchPeriod(7, p7Btn, p30Btn));
        p30Btn.setOnAction(e -> switchPeriod(30, p30Btn, p7Btn));
        HBox segmented = new HBox(p7Btn, p30Btn);
        segmented.getStyleClass().add("segmented");

        salesChart = new ModernBarChart(236);
        salesChart.setCurrencyFormat(true);
        salesChart.setSeriesNames("Profit", "Revenue");
        VBox.setVgrow(salesChart, Priority.ALWAYS);

        card.getChildren().addAll(cardHeader("Sales & Profit", legend, segmented), salesChart);
        return card;
    }

    private void switchPeriod(int days, Button activate, Button deactivate) {
        if (trendDays == days) {
            return;
        }
        trendDays = days;
        activate.getStyleClass().add("filter-chip-active");
        deactivate.getStyleClass().remove("filter-chip-active");
        refreshAsync();
    }

    private void paintLegendSwatches() {
        if (legendHatch == null) {
            return;
        }
        GraphicsContext h = legendHatch.getGraphicsContext2D();
        h.clearRect(0, 0, 12, 12);
        h.setFill(ThemeManager.hatch(ThemeManager.ink(), null));
        h.fillRect(0, 0, 12, 12);
        h.setStroke(ThemeManager.ink());
        h.strokeRect(0.5, 0.5, 11, 11);
        GraphicsContext s = legendSolid.getGraphicsContext2D();
        s.clearRect(0, 0, 12, 12);
        s.setFill(ThemeManager.ink());
        s.fillRect(0, 0, 12, 12);
    }

    private VBox buildDonutCard() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        donutChart = new StockDonutChart();
        card.getChildren().addAll(cardHeader("Stock Status", arrowLink(() -> onNavigate.accept(NavigationSection.INVENTORY))),
                donutChart);
        return card;
    }

    private VBox buildUrgentAlertsCard() {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        Button viewAll = arrowLink(() -> {
            if (onFilterInventory != null) {
                InventoryFilterCriteria c = new InventoryFilterCriteria();
                c.setStatusChip(StatusFilterChip.LOW_STOCK);
                onFilterInventory.accept(c);
            } else {
                onNavigate.accept(NavigationSection.INVENTORY);
            }
        });
        urgentAlertsBox = new VBox(2);
        card.getChildren().addAll(cardHeader("Urgent Alerts", viewAll), urgentAlertsBox);
        return card;
    }

    private VBox buildRecentActivityCard() {
        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        recentActivityBox = new VBox(0);
        card.getChildren().addAll(
                cardHeader("Recent Activity", arrowLink(() -> onNavigate.accept(NavigationSection.STOCK_OPERATIONS))),
                recentActivityBox);
        return card;
    }

    private VBox buildAiBriefingCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("ai-briefing-card");

        Label heading = new Label("AI Business Briefing");
        heading.getStyleClass().add("briefing-title");
        HBox headingRow = new HBox(8, AppIcons.of(Feather.CPU, 16), heading);
        headingRow.setAlignment(Pos.CENTER_LEFT);

        briefingBodyBox = new VBox(6);
        briefingActionsRow = new HBox(8);
        briefingActionsRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(headingRow, briefingBodyBox, briefingActionsRow);
        return card;
    }

    /**
     * Reloads the dashboard snapshot on a background thread and applies it on the FX thread.
     */
    public void refreshAsync() {
        int horizon = trendDays;
        AppExecutor.runAsync(
                () -> metricsService.loadSnapshot(horizon),
                this::applySnapshot,
                ex -> {}
        );
    }

    private void applySnapshot(DashboardMetricsService.DashboardSnapshot snap) {
        if (totalProductsCard == null) {
            return;
        }
        AppSession session = AppSession.getInstance();
        double total = Math.max(1, snap.totalProducts());

        totalProductsCard.updateMetric(String.valueOf(snap.totalProducts()),
                snap.inStockCount() + " healthy  ·  " + Math.round(snap.inStockPercent()) + "%", DeltaTone.MUTED);
        totalProductsCard.setMeter(snap.inStockCount() / total);

        inventoryValueCard.updateMetric(session.formatMoney(snap.inventoryValue()),
                "Total cost on hand", DeltaTone.MUTED);

        double margin = snap.inventoryValue() + snap.estimatedProfit() <= 0 ? 0
                : snap.estimatedProfit() / (snap.inventoryValue() + snap.estimatedProfit());
        estProfitCard.updateMetric(session.formatMoney(snap.estimatedProfit()),
                Math.round(margin * 100) + "% potential margin", DeltaTone.MUTED);
        estProfitCard.setMeter(margin);

        lowStockCard.updateMetric(String.valueOf(snap.lowStockCount()),
                snap.lowStockCount() > 0 ? "Restock recommended" : "All clear",
                snap.lowStockCount() > 0 ? DeltaTone.WARNING : DeltaTone.POSITIVE);
        lowStockCard.setMeter(snap.lowStockCount() / total);

        outOfStockCard.updateMetric(String.valueOf(snap.outOfStockCount()),
                snap.outOfStockCount() > 0 ? "Immediate restock" : "Zero stockouts",
                snap.outOfStockCount() > 0 ? DeltaTone.DANGER : DeltaTone.POSITIVE);
        outOfStockCard.setMeter(snap.outOfStockCount() / total);

        salesChart.setData(null, snap.trendPoints().stream()
                .map(p -> new ModernBarChart.BarPoint(p.dayLabel(), p.salesRevenue(),
                        Math.max(0, Math.min(p.salesRevenue(), p.profitAmount()))))
                .toList());

        donutChart.setData(
                String.valueOf(snap.totalProducts()),
                "Products by status",
                new StockDonutChart.Slice("In Stock", String.valueOf(snap.inStockCount()),
                        snap.inStockPercent() / 100.0, "ink"),
                new StockDonutChart.Slice("Low Stock", String.valueOf(snap.lowStockCount()),
                        snap.lowStockPercent() / 100.0, "hatch"),
                new StockDonutChart.Slice("Out of Stock", String.valueOf(snap.outOfStockCount()),
                        snap.outOfStockPercent() / 100.0, "accent"));

        renderAlerts(snap);
        renderActivity(snap.recentMovements());
        renderBriefing(snap.briefingLines());
    }

    private void renderAlerts(DashboardMetricsService.DashboardSnapshot snap) {
        urgentAlertsBox.getChildren().clear();
        if (snap.expiringSoonCount() > 0) {
            urgentAlertsBox.getChildren().add(listRow(Feather.CLOCK, "row-icon-warning",
                    snap.expiringSoonCount() + " item(s) expire within 7 days", "Expiry tracking", null));
        }
        if (snap.outOfStockCount() > 0) {
            urgentAlertsBox.getChildren().add(listRow(Feather.X_CIRCLE, "row-icon-danger",
                    snap.outOfStockCount() + " item(s) out of stock", "Immediate restock", null));
        }
        int room = Math.max(1, 3 - urgentAlertsBox.getChildren().size());
        for (Product p : snap.urgentProducts().stream().limit(room).toList()) {
            boolean out = p.getStatus() == StockStatus.OUT_OF_STOCK || p.getQuantity() <= 0;
            urgentAlertsBox.getChildren().add(listRow(
                    out ? Feather.X_CIRCLE : Feather.ALERT_TRIANGLE,
                    out ? "row-icon-danger" : "row-icon-warning",
                    p.getName(),
                    (out ? "Out of stock" : "Low stock") + "  ·  " + p.getQuantity() + " left",
                    p));
        }
        if (urgentAlertsBox.getChildren().isEmpty()) {
            urgentAlertsBox.getChildren().add(listRow(Feather.CHECK, "row-icon-success",
                    "All levels healthy", "No low stock or expiring items", null));
        }
    }

    private void renderActivity(List<StockMovement> movements) {
        recentActivityBox.getChildren().clear();
        if (movements.isEmpty()) {
            Label emptyMsg = new Label("No stock movements yet. Record a Restock or Sale in Stock Operations.");
            emptyMsg.getStyleClass().add("muted-text");
            emptyMsg.setWrapText(true);
            recentActivityBox.getChildren().add(emptyMsg);
            return;
        }
        boolean first = true;
        for (StockMovement m : movements) {
            if (!first) {
                Region div = new Region();
                div.getStyleClass().add("divider");
                recentActivityBox.getChildren().add(div);
            }
            first = false;
            boolean inbound = m.getQuantityDelta() >= 0;

            StackPane icon = new StackPane(AppIcons.of(inbound ? Feather.ARROW_DOWN_LEFT : Feather.ARROW_UP_RIGHT, 15));
            icon.getStyleClass().addAll("row-icon", "row-icon-neutral");

            Label name = new Label(m.getProductName());
            name.getStyleClass().add("row-title");
            Label meta = new Label(m.getMovementType().getDisplayName() + "  ·  by " + m.getPerformedBy());
            meta.getStyleClass().add("row-meta");
            VBox text = new VBox(1, name, meta);
            HBox.setHgrow(text, Priority.ALWAYS);

            Label delta = new Label((inbound ? "+" : "") + m.getQuantityDelta());
            delta.getStyleClass().addAll("legend-value", inbound ? "kpi-delta-positive" : "kpi-delta-danger");
            delta.setMinWidth(54);
            delta.setAlignment(Pos.CENTER_RIGHT);

            Label time = new Label(m.getCreatedAt() != null ? m.getCreatedAt().format(TIME_FMT) : "");
            time.getStyleClass().add("row-meta");
            time.setMinWidth(118);
            time.setAlignment(Pos.CENTER_RIGHT);

            HBox row = new HBox(12, icon, text, delta, time);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            row.setPadding(new Insets(8, 4, 8, 0));
            recentActivityBox.getChildren().add(row);
        }
    }

    private void renderBriefing(List<String> lines) {
        briefingBodyBox.getChildren().clear();
        for (String line : lines) {
            Label lbl = new Label(line);
            lbl.setWrapText(true);
            lbl.getStyleClass().add("briefing-line");
            Region bullet = new Region();
            bullet.getStyleClass().add("kpi-meter-fill");
            bullet.setMinSize(6, 6);
            bullet.setMaxSize(6, 6);
            HBox row = new HBox(8, bullet, lbl);
            row.setAlignment(Pos.TOP_LEFT);
            HBox.setMargin(bullet, new Insets(6, 0, 0, 0));
            briefingBodyBox.getChildren().add(row);
        }

        briefingActionsRow.getChildren().clear();
        Button detailsBtn = new Button("Open AI Assistant");
        detailsBtn.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 14));
        detailsBtn.setContentDisplay(ContentDisplay.RIGHT);
        detailsBtn.getStyleClass().add("btn-on-inverse");
        detailsBtn.setOnAction(e -> onNavigate.accept(NavigationSection.AI_ASSISTANT));
        briefingActionsRow.getChildren().add(detailsBtn);

        if (!services.getGroqApiClient().isApiKeyConfigured() && onOpenAiSettings != null) {
            Button keyBtn = new Button("Add API Key");
            keyBtn.setGraphic(AppIcons.of(Feather.KEY, 14));
            keyBtn.getStyleClass().add("btn-ghost-inverse");
            keyBtn.setOnAction(e -> onOpenAiSettings.run());
            briefingActionsRow.getChildren().add(keyBtn);
        }
    }

    private HBox listRow(Feather feather, String toneClass, String title, String meta, Product targetProduct) {
        StackPane iconBadge = new StackPane(AppIcons.of(feather, 15));
        iconBadge.getStyleClass().addAll("row-icon", toneClass);

        Label titleLbl = new Label(title);
        titleLbl.getStyleClass().add("row-title");
        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("row-meta");
        VBox text = new VBox(1, titleLbl, metaLbl);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(10, iconBadge, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("list-row");

        if (targetProduct != null && onInspectProduct != null) {
            row.getStyleClass().add("list-row-clickable");
            row.getChildren().add(AppIcons.of(Feather.CHEVRON_RIGHT, 15, "#94A3B8"));
            row.setOnMouseClicked(e -> onInspectProduct.accept(targetProduct));
        }
        return row;
    }
}
