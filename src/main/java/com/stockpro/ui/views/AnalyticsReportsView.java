package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.service.AppSession;
import com.stockpro.service.DashboardMetricsService;
import com.stockpro.service.ExecutivePdfReportGenerator;
import com.stockpro.service.FinanceService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.SpreadsheetImportExportService;
import com.stockpro.ui.components.KpiStatCard;
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
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
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

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Analytics &amp; Reports Hub matching {@code Screens and popups/Analytics.png}, {@code Analytics (2).png},
 * and {@code Generated Report.png}, offering live KPIs, category &amp; profit visualizations, dry-run Excel/CSV import,
 * Excel export, printable barcode sheets, and branded Executive PDF export.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AnalyticsReportsView {
    private final ServiceContext services;
    private final DashboardMetricsService metricsService;
    private final FinanceService financeService;
    private final SpreadsheetImportExportService spreadsheetService;
    private final ExecutivePdfReportGenerator pdfGenerator;
    private final ToastNotificationManager toastManager;
    private final Consumer<NavigationSection> onNavigate;

    private KpiStatCard totalSalesCard;
    private KpiStatCard revenueCard;
    private KpiStatCard profitCard;
    private KpiStatCard bestSellerCard;
    private KpiStatCard deadStockCard;

    private ComboBox<String> periodCombo;
    private ComboBox<String> categoryCombo;
    private AreaChart<String, Number> profitTrendChart;
    private StockDonutChart categoryDonut;
    private StockDonutChart healthDonut;
    private Label aiInsightText;
    private final ObservableList<Product> topProducts = FXCollections.observableArrayList();
    private Node rootNode;

    /**
     * Constructs the Analytics &amp; Reports screen.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     * @param onNavigate navigation callback
     */
    public AnalyticsReportsView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Consumer<NavigationSection> onNavigate) {
        this.services = services;
        this.metricsService = new DashboardMetricsService(services.getDatabaseManager(), services.getProductRepository());
        this.financeService = services.getFinanceService();
        this.spreadsheetService = services.getSpreadsheetImportExportService();
        this.pdfGenerator = services.getExecutivePdfReportGenerator();
        this.toastManager = toastManager;
        this.onNavigate = onNavigate;
        InventoryEventBus.getInstance().subscribe(t -> refreshAsync());
    }

    /**
     * Builds the scrollable Analytics &amp; Reports screen.
     *
     * @return JavaFX node
     */
    @SuppressWarnings("unchecked")
    public Node build() {
        VBox page = new VBox(18);
        page.setPadding(new Insets(0, 4, 16, 0));

        // Header + Export Hub Buttons
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleCol = new VBox(2);
        Label title = new Label("Business Report");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Live analytics, spreadsheet import/export, barcode sheets & executive PDF reports");
        subtitle.getStyleClass().add("page-subtitle");
        titleCol.getChildren().addAll(title, subtitle);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        periodCombo = new ComboBox<>();
        periodCombo.getItems().addAll("Last 7 Days", "Last 30 Days", "All Time");
        periodCombo.setValue("Last 30 Days");
        periodCombo.setOnAction(e -> refreshAsync());

        categoryCombo = new ComboBox<>();
        categoryCombo.getItems().add("All Categories");
        categoryCombo.setValue("All Categories");
        categoryCombo.setOnAction(e -> refreshAsync());

        Button importBtn = new Button("📥 Import Excel/CSV");
        importBtn.getStyleClass().add("btn-secondary");
        importBtn.setOnAction(e -> runDryRunImportWizard());

        Button excelBtn = new Button("📊 Export Excel");
        excelBtn.getStyleClass().add("btn-secondary");
        excelBtn.setOnAction(e -> exportExcelCatalog());

        Button stickerBtn = new Button("🏷 Sticker PDF");
        stickerBtn.getStyleClass().add("btn-secondary");
        stickerBtn.setOnAction(e -> exportBarcodeStickerSheet());

        Button exportPdfBtn = new Button("📄 Export Report");
        exportPdfBtn.getStyleClass().add("btn-primary");
        exportPdfBtn.setOnAction(e -> exportExecutiveReportPdf());

        header.getChildren().addAll(titleCol, sp, periodCombo, categoryCombo, importBtn, excelBtn, stickerBtn, exportPdfBtn);

        // 5 KPI Cards matching Analytics (2).png & Analytics.png
        totalSalesCard = new KpiStatCard("🛍", "#4F46E5", "kpi-card-blue", "Total Value", "$0.00", "Retail catalog");
        revenueCard = new KpiStatCard("💰", "#10B981", "kpi-card-green", "Cost Value", "$0.00", "Inventory asset");
        profitCard = new KpiStatCard("📈", "#F59E0B", "kpi-card-amber", "Est. Profit", "$0.00", "Gross potential");
        bestSellerCard = new KpiStatCard("🏆", "#8B5CF6", "kpi-card-purple", "Top Product", "—", "Highest value");
        deadStockCard = new KpiStatCard("⚠", "#EF4444", "kpi-card-rose", "Dead Stock", "0", "Stagnant 60d+");
        HBox kpiRow = new HBox(14, totalSalesCard, revenueCard, profitCard, bestSellerCard, deadStockCard);

        // Middle Row: Profit Trend + Top Categories Donut + Top Products Table
        VBox trendCard = new VBox(8);
        trendCard.getStyleClass().add("card");
        HBox.setHgrow(trendCard, Priority.ALWAYS);
        Label trTitle = new Label("Sales & Profit Trend");
        trTitle.getStyleClass().add("section-title");
        profitTrendChart = new AreaChart<>(new CategoryAxis(), new NumberAxis());
        profitTrendChart.setAnimated(false);
        profitTrendChart.setPrefHeight(240);
        trendCard.getChildren().addAll(trTitle, profitTrendChart);

        VBox catCard = new VBox(8);
        catCard.getStyleClass().add("card");
        catCard.setPrefWidth(340);
        Label cTitle = new Label("Top Categories");
        cTitle.getStyleClass().add("section-title");
        categoryDonut = new StockDonutChart();
        catCard.getChildren().addAll(cTitle, categoryDonut);

        VBox topProdCard = new VBox(8);
        topProdCard.getStyleClass().add("card");
        topProdCard.setPrefWidth(340);
        Label tpTitle = new Label("Top Products");
        tpTitle.getStyleClass().add("section-title");
        TableView<Product> topTable = new TableView<>(topProducts);
        topTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        topTable.setPrefHeight(220);
        TableColumn<Product, String> pName = new TableColumn<>("Product");
        pName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Product, String> pQty = new TableColumn<>("Qty");
        pQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<Product, String> pVal = new TableColumn<>("Value");
        pVal.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getRetailValuation())));
        topTable.getColumns().addAll(pName, pQty, pVal);
        topProdCard.getChildren().addAll(tpTitle, topTable);

        HBox midRow = new HBox(16, trendCard, catCard, topProdCard);

        // Bottom Row: Inventory Health Ring + AI Insights Card
        VBox healthCard = new VBox(8);
        healthCard.getStyleClass().add("card");
        healthCard.setPrefWidth(420);
        Label hTitle = new Label("Inventory Overview");
        hTitle.getStyleClass().add("section-title");
        healthDonut = new StockDonutChart();
        healthCard.getChildren().addAll(hTitle, healthDonut);

        VBox aiCard = new VBox(10);
        aiCard.getStyleClass().add("card");
        HBox.setHgrow(aiCard, Priority.ALWAYS);
        Label aiHead = new Label("✨ AI Insights (Powered by Groq / Offline ML)");
        aiHead.getStyleClass().add("section-title");
        aiInsightText = new Label("Loading inventory insights...");
        aiInsightText.setWrapText(true);
        Button viewAiBtn = new Button("View Suggestions →");
        viewAiBtn.getStyleClass().add("btn-primary");
        viewAiBtn.setOnAction(e -> onNavigate.accept(NavigationSection.AI_ASSISTANT));
        aiCard.getChildren().addAll(aiHead, aiInsightText, viewAiBtn);

        HBox bottomRow = new HBox(16, healthCard, aiCard);

        page.getChildren().addAll(header, kpiRow, midRow, bottomRow);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
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
        File out = chooser.showSaveDialog(rootNode != null && rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        String period = periodCombo != null ? periodCombo.getValue() : "Last 30 Days";
        AppExecutor.runAsync(
                () -> pdfGenerator.generateBusinessReport(period, out.toPath()),
                path -> toastManager.showInfo("Exported Business Report PDF: " + path.getFileName()),
                ex -> toastManager.showInfo("Report PDF failed: " + ex.getMessage())
        );
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
                ex -> toastManager.showInfo("Excel export failed: " + ex.getMessage())
        );
    }

    /**
     * Exports a printable A4 ZXing barcode &amp; QR sticker sheet PDF for active products.
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
                ex -> toastManager.showInfo("Sticker export failed: " + ex.getMessage())
        );
    }

    /**
     * Runs the dry-run validated spreadsheet import wizard for {@code .xlsx} or {@code .csv} files.
     */
    private void runDryRunImportWizard() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import Inventory Spreadsheet (.xlsx / .csv)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Spreadsheets", "*.xlsx", "*.csv"));
        File file = chooser.showOpenDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> spreadsheetService.dryRunImport(file.toPath()),
                report -> {
                    Alert preview = new Alert(Alert.AlertType.CONFIRMATION);
                    preview.setTitle("Spreadsheet Dry-Run Validation");
                    preview.setHeaderText(String.format("Scanned %d row(s): %d valid, %d error(s)",
                            report.totalRows(), report.validCount(), report.errorCount()));
                    String bodyText = report.rowErrors().isEmpty()
                            ? "All rows passed validation! Click OK to import " + report.validCount() + " product(s)."
                            : "Errors:\n" + String.join("\n", report.rowErrors().stream().limit(6).toList())
                              + "\n\nCommit the " + report.validCount() + " valid row(s)?";
                    preview.setContentText(bodyText);
                    preview.showAndWait().ifPresent(btn -> {
                        if (btn == ButtonType.OK && report.validCount() > 0) {
                            AppExecutor.runAsync(
                                    () -> spreadsheetService.commitImport(report),
                                    count -> {
                                        toastManager.showInfo("Imported " + count + " validated product(s).");
                                        refreshAsync();
                                    },
                                    ex -> toastManager.showInfo("Import failed: " + ex.getMessage())
                            );
                        }
                    });
                },
                ex -> toastManager.showInfo("Dry-run failed: " + ex.getMessage())
        );
    }

    /**
     * Refreshes all analytics KPIs, charts, category breakdowns, and insights in the background.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(() -> new Object[]{
                metricsService.loadSnapshot(),
                financeService.computeSummary(60, AppSession.getInstance().getTaxRatePercent()),
                services.getInventoryService().getAllProducts(),
                services.getAiAssistantService().generateMorningBriefing()
        }, data -> {
            DashboardMetricsService.DashboardSnapshot snap = (DashboardMetricsService.DashboardSnapshot) data[0];
            FinanceService.FinanceSummary fin = (FinanceService.FinanceSummary) data[1];
            @SuppressWarnings("unchecked")
            List<Product> all = (List<Product>) data[2];
            String briefing = (String) data[3];

            String chosenCat = categoryCombo != null && categoryCombo.getValue() != null
                    ? categoryCombo.getValue() : "All Categories";
            List<Product> scoped = "All Categories".equals(chosenCat)
                    ? all
                    : all.stream().filter(p -> chosenCat.equalsIgnoreCase(p.getCategory())).toList();

            AppSession session = AppSession.getInstance();
            totalSalesCard.updateMetric(session.formatMoney(fin.totalRetailValue()), snap.totalProducts() + " SKUs", "#4F46E5");
            revenueCard.updateMetric(session.formatMoney(fin.totalCostValue()), "Cost asset", "#15803D");
            profitCard.updateMetric(session.formatMoney(fin.totalPotentialProfit()),
                    String.format("%.1f%% margin", fin.averageMarginPercent()), "#B45309");

            String topName = scoped.stream()
                    .max(java.util.Comparator.comparingDouble(Product::getRetailValuation))
                    .map(Product::getName).orElse("—");
            bestSellerCard.updateMetric(topName, "Top valuation", "#6D28D9");
            deadStockCard.updateMetric(String.valueOf(fin.deadMoneyItems().size()),
                    session.formatMoney(fin.deadMoneyValue()) + " idle", "#B91C1C");

            XYChart.Series<String, Number> s1 = new XYChart.Series<>();
            s1.setName("Sales");
            XYChart.Series<String, Number> s2 = new XYChart.Series<>();
            s2.setName("Profit");
            for (DashboardMetricsService.TrendPoint pt : snap.trendPoints()) {
                s1.getData().add(new XYChart.Data<>(pt.dayLabel(), pt.salesRevenue()));
                s2.getData().add(new XYChart.Data<>(pt.dayLabel(), pt.profitAmount()));
            }
            profitTrendChart.getData().setAll(s1, s2);

            // Category donut
            Map<String, Double> catTotals = new LinkedHashMap<>();
            double grand = 0.0;
            for (Product p : all) {
                String c = p.getCategory() != null ? p.getCategory() : "General";
                double v = Math.max(1.0, p.getRetailValuation());
                catTotals.merge(c, v, Double::sum);
                grand += v;
            }
            String[] palette = {"#4F46E5", "#10B981", "#F59E0B", "#0EA5E9"};
            List<Map.Entry<String, Double>> topCats = catTotals.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(4).toList();
            StockDonutChart.Slice[] slices = new StockDonutChart.Slice[topCats.size()];
            for (int i = 0; i < topCats.size(); i++) {
                double frac = grand > 0 ? topCats.get(i).getValue() / grand : 0.0;
                slices[i] = new StockDonutChart.Slice(topCats.get(i).getKey(),
                        String.format("%.0f%%", frac * 100), frac, palette[i % palette.length]);
            }
            if (slices.length > 0) {
                categoryDonut.setData(String.format("%.0f%%", slices[0].fraction() * 100), slices[0].label(), slices);
            } else {
                categoryDonut.setData("0%", "Empty");
            }

            healthDonut.setData(
                    String.format("%.0f%%", snap.inStockPercent()),
                    "Stock Health",
                    new StockDonutChart.Slice("In Stock", String.valueOf(snap.inStockCount()), snap.inStockPercent() / 100.0, "#10B981"),
                    new StockDonutChart.Slice("Low Stock", String.valueOf(snap.lowStockCount()), snap.lowStockPercent() / 100.0, "#F59E0B"),
                    new StockDonutChart.Slice("Out of Stock", String.valueOf(snap.outOfStockCount()), snap.outOfStockPercent() / 100.0, "#EF4444")
            );

            topProducts.setAll(scoped.stream()
                    .sorted(java.util.Comparator.comparingDouble(Product::getRetailValuation).reversed())
                    .limit(6).toList());

            List<String> cats = new java.util.ArrayList<>();
            cats.add("All Categories");
            cats.addAll(catTotals.keySet());
            categoryCombo.setItems(FXCollections.observableArrayList(cats));
            categoryCombo.setValue(cats.contains(chosenCat) ? chosenCat : "All Categories");

            aiInsightText.setText(briefing);
        }, ex -> {
        });
    }
}
