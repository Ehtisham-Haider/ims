package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.service.AppSession;
import com.stockpro.service.DashboardMetricsService;
import com.stockpro.service.ExecutivePdfReportGenerator;
import com.stockpro.service.FinanceService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.SpreadsheetImportExportService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ThemeManager;
import com.stockpro.ui.components.KpiStatCard;
import com.stockpro.ui.components.KpiStatCard.DeltaTone;
import com.stockpro.ui.components.ModernTrendChart;
import com.stockpro.ui.components.StockDonutChart;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Analytics &amp; Reports Hub matching
 * {@code Screens and popups/Analytics (2).png} and
 * {@code Generated Report.png}, with balanced non-cramped header/export
 * toolbars,
 * real period filtering (7d / 30d / All Time), smooth custom trend &amp; donut
 * charts,
 * dry-run Excel/CSV import wizard, and branded A4 Executive PDF generation.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AnalyticsReportsView {
    private final ServiceContext services;
    private final DashboardMetricsService metricsService;
    private final FinanceService financeService;
    private final SpreadsheetImportExportService spreadsheetService;
    private final ExecutivePdfReportGenerator pdfGenerator;
    private final ToastNotificationManager toastManager;
    private final Consumer<NavigationSection> onNavigate;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private KpiStatCard totalSalesCard;
    private KpiStatCard revenueCard;
    private KpiStatCard profitCard;
    private KpiStatCard bestSellerCard;
    private KpiStatCard deadStockCard;

    private ComboBox<String> periodCombo;
    private ComboBox<String> categoryCombo;
    private boolean updatingFilters = false;

    private ModernTrendChart profitTrendChart;
    private StockDonutChart categoryDonut;
    private StockDonutChart healthDonut;
    private Label aiInsightText;
    private final ObservableList<Product> topProducts = FXCollections.observableArrayList();
    private Node rootNode;

    /**
     * Constructs the Analytics &amp; Reports screen.
     *
     * @param services     shared service registry
     * @param toastManager toast notification helper
     * @param onNavigate   navigation callback
     */
    public AnalyticsReportsView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Consumer<NavigationSection> onNavigate) {
        this.services = services;
        this.metricsService = new DashboardMetricsService(services.getDatabaseManager(),
                services.getProductRepository());
        this.financeService = services.getFinanceService();
        this.spreadsheetService = services.getSpreadsheetImportExportService();
        this.pdfGenerator = services.getExecutivePdfReportGenerator();
        this.toastManager = toastManager;
        this.onNavigate = onNavigate;
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when leaving this view.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Repaints custom Canvas charts cleanly when Light/Dark theme toggles.
     */
    public void onThemeChanged() {
        if (profitTrendChart != null) {
            profitTrendChart.redraw();
        }
        if (categoryDonut != null) {
            categoryDonut.redraw();
        }
        if (healthDonut != null) {
            healthDonut.redraw();
        }
    }

    /**
     * Builds the scrollable Analytics &amp; Reports screen with non-cramped header
     * &amp; export bar.
     *
     * @return JavaFX node
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(0, 4, 16, 0));

        // Row 1 Header: Title + Period & Category Filters
        HBox titleHeader = new HBox(12);
        titleHeader.setAlignment(Pos.CENTER_LEFT);
        VBox titleCol = new VBox(2);
        Label title = new Label("Business Report & Analytics");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(
                "Live analytics, spreadsheet import/export, sticker sheets & branded A4 PDF reports");
        subtitle.getStyleClass().add("page-subtitle");
        titleCol.getChildren().addAll(title, subtitle);

        Region sp1 = new Region();
        HBox.setHgrow(sp1, Priority.ALWAYS);

        periodCombo = new ComboBox<>();
        periodCombo.getItems().addAll("Last 7 Days", "Last 30 Days", "All Time");
        periodCombo.setValue("Last 30 Days");
        periodCombo.setOnAction(e -> refreshAsync());

        categoryCombo = new ComboBox<>();
        categoryCombo.getItems().add("All Categories");
        categoryCombo.setValue("All Categories");
        categoryCombo.setOnAction(e -> {
            if (!updatingFilters) {
                refreshAsync();
            }
        });

        titleHeader.getChildren().addAll(titleCol, sp1, periodCombo, categoryCombo);

        // Row 2 Export Action Strip (plenty of breathing room)
        Button importBtn = new Button("Import Excel / CSV");
        importBtn.setGraphic(AppIcons.of(Feather.UPLOAD, 14));
        importBtn.getStyleClass().add("btn-secondary");
        importBtn.setOnAction(e -> runDryRunImportWizard());

        Button excelBtn = new Button("Export Excel (.xlsx)");
        excelBtn.setGraphic(AppIcons.of(Feather.DOWNLOAD, 14));
        excelBtn.getStyleClass().add("btn-secondary");
        excelBtn.setOnAction(e -> exportExcelCatalog());

        Button stickerBtn = new Button("Barcode Stickers PDF");
        stickerBtn.setGraphic(AppIcons.of(Feather.TAG, 14));
        stickerBtn.getStyleClass().add("btn-secondary");
        stickerBtn.setOnAction(e -> exportBarcodeStickerSheet());

        Region sp2 = new Region();
        HBox.setHgrow(sp2, Priority.ALWAYS);

        Button exportPdfBtn = new Button("Export Executive Report PDF");
        exportPdfBtn.setGraphic(AppIcons.of(Feather.FILE_TEXT, 14));
        exportPdfBtn.getStyleClass().add("btn-primary");
        exportPdfBtn.setOnAction(e -> exportExecutiveReportPdf());

        HBox actionBar = new HBox(10, importBtn, excelBtn, stickerBtn, sp2, exportPdfBtn);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        // 5 KPI Cards matching Analytics (2).png
        totalSalesCard = new KpiStatCard(Feather.SHOPPING_BAG, "#4F46E5", KpiStatCard.INVERSE, "Retail Value", "$0.00",
                "Catalog total");
        revenueCard = new KpiStatCard(Feather.DOLLAR_SIGN, "#10B981", KpiStatCard.ACCENT, "Cost Asset", "$0.00",
                "On hand value");
        profitCard = new KpiStatCard(Feather.TRENDING_UP, "#F59E0B", KpiStatCard.MUTED, "Est. Profit", "$0.00",
                "Gross potential");
        bestSellerCard = new KpiStatCard(Feather.AWARD, "#8B5CF6", KpiStatCard.PLAIN, "Top Product", "—",
                "Highest value");
        deadStockCard = new KpiStatCard(Feather.ALERT_TRIANGLE, "#EF4444", KpiStatCard.PLAIN, "Dead Stock", "0",
                "Stagnant 60d+");
        HBox kpiRow = new HBox(12, totalSalesCard, revenueCard, profitCard, bestSellerCard, deadStockCard);

        // Middle Row: Smooth Trend + Top Categories Donut + Top Products Table
        VBox trendCard = new VBox(8);
        trendCard.getStyleClass().add("card");
        HBox.setHgrow(trendCard, Priority.ALWAYS);
        Label trTitle = new Label("Sales & Profit Trend");
        trTitle.getStyleClass().add("section-title");
        profitTrendChart = new ModernTrendChart(245);
        trendCard.getChildren().addAll(trTitle, profitTrendChart);

        VBox catCard = new VBox(8);
        catCard.getStyleClass().add("card");
        catCard.setPrefWidth(335);
        catCard.setMinWidth(300);
        Label cTitle = new Label("Top Categories");
        cTitle.getStyleClass().add("section-title");
        categoryDonut = new StockDonutChart();
        catCard.getChildren().addAll(cTitle, categoryDonut);

        VBox topProdCard = new VBox(8);
        topProdCard.getStyleClass().add("card");
        topProdCard.setPrefWidth(335);
        topProdCard.setMinWidth(290);
        Label tpTitle = new Label("Top Products");
        tpTitle.getStyleClass().add("section-title");
        TableView<Product> topTable = new TableView<>(topProducts);
        topTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        topTable.setPrefHeight(225);
        TableColumn<Product, String> pName = new TableColumn<>("Product");
        pName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Product, String> pQty = new TableColumn<>("Qty");
        pQty.setMaxWidth(64);
        pQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<Product, String> pVal = new TableColumn<>("Value");
        pVal.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getRetailValuation())));
        topTable.getColumns().addAll(List.of(pName, pQty, pVal));
        topProdCard.getChildren().addAll(tpTitle, topTable);

        HBox midRow = new HBox(14, trendCard, catCard, topProdCard);

        // Bottom Row: Inventory Health Ring + AI Insights Card
        VBox healthCard = new VBox(8);
        healthCard.getStyleClass().add("card");
        healthCard.setPrefWidth(410);
        Label hTitle = new Label("Inventory Health Overview");
        hTitle.getStyleClass().add("section-title");
        healthDonut = new StockDonutChart();
        healthCard.getChildren().addAll(hTitle, healthDonut);

        VBox aiCard = new VBox(10);
        aiCard.getStyleClass().add("card");
        HBox.setHgrow(aiCard, Priority.ALWAYS);

        HBox aiHeadRow = new HBox(8, AppIcons.of(Feather.CPU, 16, "#6366F1"));
        aiHeadRow.setAlignment(Pos.CENTER_LEFT);
        Label aiHead = new Label("AI Business Insights");
        aiHead.getStyleClass().add("section-title");
        Region aiSp = new Region();
        HBox.setHgrow(aiSp, Priority.ALWAYS);
        Button refreshAiBtn = new Button("Generate Fresh Insights");
        refreshAiBtn.setGraphic(AppIcons.of(Feather.REFRESH_CW, 13));
        refreshAiBtn.getStyleClass().add("btn-secondary");
        refreshAiBtn.setOnAction(e -> runOnDemandAiInsight());
        aiHeadRow.getChildren().addAll(aiHead, aiSp, refreshAiBtn);

        aiInsightText = new Label("Click 'Generate Fresh Insights' for live store analysis or open AI Assistant.");
        aiInsightText.setWrapText(true);

        Button viewAiBtn = new Button("Open AI Assistant");
        viewAiBtn.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13));
        viewAiBtn.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        viewAiBtn.getStyleClass().add("btn-primary");
        viewAiBtn.setOnAction(e -> onNavigate.accept(NavigationSection.AI_ASSISTANT));
        aiCard.getChildren().addAll(aiHeadRow, aiInsightText, viewAiBtn);

        HBox bottomRow = new HBox(14, healthCard, aiCard);

        page.getChildren().addAll(titleHeader, actionBar, kpiRow, midRow, bottomRow);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        this.rootNode = scroll;

        refreshAsync();
        return scroll;
    }

    /**
     * Triggers executive A4 PDF report export using a file save picker.
     */
    public void exportExecutiveReportPdf() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("StockPro-Business-Report.pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));
        File out = chooser.showSaveDialog(
                rootNode != null && rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        String period = periodCombo != null ? periodCombo.getValue() : "Last 30 Days";
        toastManager.showInfo("Generating Business Report PDF...");
        AppExecutor.runAsync(
                () -> pdfGenerator.generateBusinessReport(period, out.toPath()),
                path -> toastManager.showInfo("Exported Business Report PDF: " + path.getFileName()),
                ex -> toastManager.showInfo("Report PDF failed: " + ex.getMessage()));
    }

    /**
     * Exports the full store catalog as an Apache POI {@code .xlsx} workbook.
     */
    private void exportExcelCatalog() {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("stockpro-inventory.xlsx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel Workbook", "*.xlsx"));
        File out = chooser.showSaveDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> spreadsheetService.exportToExcel(services.getInventoryService().getAllProducts(), out.toPath()),
                path -> toastManager.showInfo("Exported Excel inventory: " + path.getFileName()),
                ex -> toastManager.showInfo("Excel export failed: " + ex.getMessage()));
    }

    /**
     * Exports a printable A4 ZXing barcode &amp; QR sticker sheet PDF for active
     * products.
     */
    private void exportBarcodeStickerSheet() {
        List<Product> items = services.getInventoryService().getAllProducts();
        if (items.isEmpty()) {
            toastManager.showInfo("Add items first before printing barcode labels.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("stockpro-barcode-stickers.pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));
        File out = chooser.showSaveDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> services.getBarcodeLabelService().exportStickerSheetPdf(items, out.toPath()),
                path -> toastManager.showInfo("Exported barcode & QR sticker sheet: " + path.getFileName()),
                ex -> toastManager.showInfo("Sticker export failed: " + ex.getMessage()));
    }

    /**
     * Runs the dry-run validated spreadsheet import wizard with a custom Light/Dark
     * themed confirmation modal.
     */
    private void runDryRunImportWizard() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Inventory Spreadsheet (.xlsx / .csv)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Spreadsheets", "*.xlsx", "*.csv"));
        File file = chooser.showOpenDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> spreadsheetService.dryRunImport(file.toPath()),
                this::showStyledImportConfirmModal,
                ex -> toastManager.showInfo("Dry-run failed: " + ex.getMessage()));
    }

    private void showStyledImportConfirmModal(SpreadsheetImportExportService.DryRunImportReport report) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (rootNode != null && rootNode.getScene() != null) {
            dialog.initOwner(rootNode.getScene().getWindow());
        }
        dialog.setTitle("Spreadsheet Dry-Run Validation");

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));

        HBox hdr = new HBox(10,
                AppIcons.of(Feather.CHECK_CIRCLE, 20, report.errorCount() == 0 ? "#10B981" : "#F59E0B"));
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label(String.format("Scanned %d row(s): %d valid, %d error(s)",
                report.totalRows(), report.validCount(), report.errorCount()));
        heading.getStyleClass().add("section-title");
        hdr.getChildren().add(heading);

        String bodyText = report.rowErrors().isEmpty()
                ? "All rows passed validation checks! Confirm below to import " + report.validCount()
                        + " product(s) into SQLite."
                : "Skipped invalid rows:\n" + String.join("\n", report.rowErrors().stream().limit(6).toList())
                        + "\n\nCommit the " + report.validCount() + " valid row(s)?";
        Label body = new Label(bodyText);
        body.setWrapText(true);
        body.getStyleClass().add("muted-text");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> dialog.close());

        Button commitBtn = new Button("Import " + report.validCount() + " Valid Row(s)");
        commitBtn.getStyleClass().add("btn-primary");
        commitBtn.setDisable(report.validCount() <= 0);
        commitBtn.setOnAction(e -> {
            dialog.close();
            AppExecutor.runAsync(
                    () -> spreadsheetService.commitImport(report),
                    count -> {
                        toastManager.showInfo("Imported " + count + " validated product(s).");
                        refreshAsync();
                    },
                    ex -> toastManager.showInfo("Import failed: " + ex.getMessage()));
        });

        HBox actions = new HBox(10, cancelBtn, commitBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(hdr, body, actions);

        Scene sc = new Scene(card, 470, 230);
        ThemeManager.apply(sc);
        dialog.setScene(sc);
        dialog.showAndWait();
    }

    private void runOnDemandAiInsight() {
        aiInsightText.setText("Analyzing live catalog with "
                + (services.getAiAssistantService().isCloudAiReady() ? "Groq Cloud..." : "Offline ML engine..."));
        AppExecutor.runAsync(
                () -> services.getAiAssistantService().generateMorningBriefingReply(),
                reply -> {
                    String prefix = reply.usedCloud() ? "[Groq Cloud • " + reply.modelUsed() + "]\n"
                            : "[Local Briefing]\n";
                    aiInsightText.setText(prefix + reply.message());
                },
                ex -> aiInsightText.setText("Unable to generate insights: " + ex.getMessage()));
    }

    /**
     * Refreshes all analytics KPIs, period-filtered trend charts, category donuts,
     * and insights off the FX thread.
     */
    public void refreshAsync() {
        String periodText = periodCombo != null && periodCombo.getValue() != null ? periodCombo.getValue()
                : "Last 30 Days";
        int periodDays = periodText.contains("7") ? 7 : (periodText.contains("All") ? 0 : 30);

        AppExecutor.runAsync(() -> new Object[] {
                metricsService.loadSnapshot(periodDays),
                financeService.computeSummary(60, AppSession.getInstance().getTaxRatePercent()),
                services.getInventoryService().getAllProducts()
        }, data -> {
            DashboardMetricsService.DashboardSnapshot snap = (DashboardMetricsService.DashboardSnapshot) data[0];
            FinanceService.FinanceSummary fin = (FinanceService.FinanceSummary) data[1];
            @SuppressWarnings("unchecked")
            List<Product> all = (List<Product>) data[2];

            String chosenCat = categoryCombo != null && categoryCombo.getValue() != null
                    ? categoryCombo.getValue()
                    : "All Categories";
            List<Product> scoped = "All Categories".equals(chosenCat)
                    ? all
                    : all.stream().filter(p -> chosenCat.equalsIgnoreCase(p.getCategory())).toList();

            AppSession session = AppSession.getInstance();
            double scopedRetail = scoped.stream().mapToDouble(Product::getRetailValuation).sum();
            double scopedCost = scoped.stream().mapToDouble(Product::getCostValuation).sum();
            double scopedProfit = Math.max(0.0, scopedRetail - scopedCost);
            double marginPct = scopedRetail > 0 ? (scopedProfit * 100.0) / scopedRetail : 0.0;

            totalSalesCard.updateMetric(session.formatMoney(scopedRetail), scoped.size() + " SKUs", DeltaTone.ACCENT);
            revenueCard.updateMetric(session.formatMoney(scopedCost), "Cost asset", DeltaTone.POSITIVE);
            profitCard.updateMetric(session.formatMoney(scopedProfit), String.format("%.1f%% margin", marginPct),
                    DeltaTone.WARNING);

            String topName = scoped.stream()
                    .max(java.util.Comparator.comparingDouble(Product::getRetailValuation))
                    .map(Product::getName).orElse("—");
            bestSellerCard.updateMetric(topName, "Top valuation", DeltaTone.ACCENT);
            deadStockCard.updateMetric(String.valueOf(fin.deadMoneyItems().size()),
                    session.formatMoney(fin.deadMoneyValue()) + " idle",
                    fin.deadMoneyItems().isEmpty() ? DeltaTone.POSITIVE : DeltaTone.DANGER);

            // Smooth Trend Chart
            List<String> xLabels = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::dayLabel)
                    .toList();
            List<Double> salesVals = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::salesRevenue)
                    .toList();
            List<Double> profitVals = snap.trendPoints().stream().map(DashboardMetricsService.TrendPoint::profitAmount)
                    .toList();
            profitTrendChart.setSeries(xLabels, List.of(
                    new ModernTrendChart.SeriesData("Sales", "ink", salesVals),
                    new ModernTrendChart.SeriesData("Profit", "accent", profitVals)));

            // Category donut
            Map<String, Double> catTotals = new LinkedHashMap<>();
            double grand = 0.0;
            for (Product p : all) {
                String c = p.getCategory() != null ? p.getCategory() : "General";
                double v = Math.max(1.0, p.getRetailValuation());
                catTotals.merge(c, v, Double::sum);
                grand += v;
            }
            String[] palette = { "ink", "accent", "hatch", "neutral" };
            List<Map.Entry<String, Double>> topCats = catTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(4)
                    .toList();
            StockDonutChart.Slice[] slices = new StockDonutChart.Slice[topCats.size()];
            for (int i = 0; i < topCats.size(); i++) {
                double frac = grand > 0 ? topCats.get(i).getValue() / grand : 0.0;
                slices[i] = new StockDonutChart.Slice(topCats.get(i).getKey(),
                        String.format("%.0f%%", frac * 100), frac, palette[i % palette.length]);
            }
            if (slices.length > 0) {
                categoryDonut.setData(String.format("%.0f%%", slices[0].fraction() * 100),
                        slices[0].label(), slices);
            } else {
                categoryDonut.setData("0%", "Empty");
            }

            healthDonut.setData(
                    String.format("%.0f%%", snap.inStockPercent()),
                    "Stock Health",
                    new StockDonutChart.Slice("In Stock", String.valueOf(snap.inStockCount()),
                            snap.inStockPercent() / 100.0, "ink"),
                    new StockDonutChart.Slice("Low Stock", String.valueOf(snap.lowStockCount()),
                            snap.lowStockPercent() / 100.0, "hatch"),
                    new StockDonutChart.Slice("Out of Stock", String.valueOf(snap.outOfStockCount()),
                            snap.outOfStockPercent() / 100.0, "accent"));

            topProducts.setAll(scoped.stream()
                    .sorted(java.util.Comparator.comparingDouble(Product::getRetailValuation).reversed())
                    .limit(6).toList());

            List<String> cats = new java.util.ArrayList<>();
            cats.add("All Categories");
            cats.addAll(catTotals.keySet());

            // Guard programmatic ComboBox update so it never retriggers refreshAsync
            // recursively!
            updatingFilters = true;
            try {
                categoryCombo.setItems(FXCollections.observableArrayList(cats));
                categoryCombo.setValue(cats.contains(chosenCat) ? chosenCat : "All Categories");
            } finally {
                updatingFilters = false;
            }

            if (!snap.briefingLines().isEmpty()) {
                aiInsightText.setText(String.join("\n", snap.briefingLines().stream().map(s -> "• " + s).toList()));
            }
        }, ex -> toastManager.showInfo("Analytics refresh failed: " + ex.getMessage()));
    }
}
