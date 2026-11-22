package com.stockpro.ui.views;

import com.stockpro.domain.BundleComponent;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProductAttachment;
import com.stockpro.domain.ProductVariant;
import com.stockpro.domain.SerialNumberRecord;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.UomConversion;
import com.stockpro.ml.OfflineMlEngine;
import com.stockpro.service.AppSession;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ModernBarChart;
import com.stockpro.util.AppExecutor;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

import java.awt.Desktop;
import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Animated right-side Product Details Drawer matching {@code Screens and popups/Product Details.png},
 * displaying product photo, barcode strip, cost/sell/profit cards, stock progress bar, shelf spot,
 * Enterprise catalog details (variants, UoM, BOM, serials, spec sheet attachments, custom fields),
 * real SQLite stock movement history, offline ML forecast bars via {@link ModernBarChart},
 * and Light/Dark contrast.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class ProductDetailsDrawer extends VBox {
    private static final DateTimeFormatter MOVE_TIME_FMT = DateTimeFormatter.ofPattern("MMM dd, hh:mm a");

    private final ServiceContext services;
    private final Consumer<Product> onEdit;
    private final Consumer<Product> onDelete;
    private final Runnable onClose;
    private final Consumer<Product> onPrintLabel;
    private final VBox bodyBox = new VBox(14);
    private ModernBarChart forecastMiniChart;

    /**
     * Constructs a slide-out product details drawer.
     *
     * @param onEdit   callback invoked when {@code Edit Product} is clicked
     * @param onDelete callback invoked when {@code Delete} is clicked
     * @param onClose  callback invoked when closing the drawer
     */
    public ProductDetailsDrawer(Consumer<Product> onEdit, Consumer<Product> onDelete, Runnable onClose) {
        this(null, onEdit, onDelete, onClose, null);
    }

    /**
     * Constructs a slide-out product details drawer with optional barcode/QR
     * sticker callback.
     *
     * @param onEdit       callback invoked when {@code Edit Product} is clicked
     * @param onDelete     callback invoked when {@code Delete} is clicked
     * @param onClose      callback invoked when closing the drawer
     * @param onPrintLabel optional callback opening the ZXing Barcode &amp; QR
     *                     label modal
     */
    public ProductDetailsDrawer(
            Consumer<Product> onEdit,
            Consumer<Product> onDelete,
            Runnable onClose,
            Consumer<Product> onPrintLabel) {
        this(null, onEdit, onDelete, onClose, onPrintLabel);
    }

    /**
     * Constructs a slide-out product details drawer wired to live SQLite movements
     * and offline ML predictions.
     *
     * @param services     optional {@link ServiceContext} for live movements &amp;
     *                     ML forecasts
     * @param onEdit       callback invoked when {@code Edit Product} is clicked
     * @param onDelete     callback invoked when {@code Delete} is clicked
     * @param onClose      callback invoked when closing the drawer
     * @param onPrintLabel optional callback opening the ZXing Barcode &amp; QR
     *                     label modal
     */
    public ProductDetailsDrawer(
            ServiceContext services,
            Consumer<Product> onEdit,
            Consumer<Product> onDelete,
            Runnable onClose,
            Consumer<Product> onPrintLabel) {
        this.services = services;
        this.onEdit = onEdit;
        this.onDelete = onDelete;
        this.onClose = onClose;
        this.onPrintLabel = onPrintLabel;

        getStyleClass().add("drawer-panel");
        setPrefWidth(395);
        setMinWidth(355);
        setMaxWidth(420);
        setPadding(new Insets(18));

        ScrollPane scroll = new ScrollPane(bodyBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    /**
     * Repaints embedded forecast bars cleanly after Light/Dark theme toggles.
     */
    public void onThemeChanged() {
        if (forecastMiniChart != null) {
            forecastMiniChart.redraw();
        }
    }

    /**
     * Populates the drawer with the selected product's metrics, loads real recent
     * movements and ML projections,
     * and plays a smooth slide-in animation.
     *
     * @param product target product to inspect
     */
    public void showProduct(Product product) {
        if (product == null) {
            return;
        }
        AppSession session = AppSession.getInstance();
        bodyBox.getChildren().clear();

        // Header
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Product Details");
        title.getStyleClass().add("section-title");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button closeX = new Button();
        closeX.getStyleClass().add("icon-btn");
        closeX.setGraphic(AppIcons.of(Feather.X, 16));
        closeX.setOnAction(e -> onClose.run());
        header.getChildren().addAll(title, sp, closeX);

        // Photo + Basic Summary
        HBox hero = new HBox(14);
        hero.setAlignment(Pos.CENTER_LEFT);
        StackPane thumb = new StackPane();
        thumb.setMinSize(86, 86);
        thumb.setPrefSize(86, 86);
        thumb.setMaxSize(86, 86);
        thumb.getStyleClass().add("surface-subtle");
        Rectangle thumbClip = new Rectangle(86, 86);
        thumbClip.setArcWidth(16);
        thumbClip.setArcHeight(16);
        thumb.setClip(thumbClip);
        applyHeroThumbnail(thumb, product.getPhotoPath());
        if (services != null && services.getProductImageService().needsImageResolution(product.getPhotoPath())) {
            AppExecutor.runAsync(
                    () -> services.getProductImageService().ensureProductImageAndPersist(product),
                    resolvedPath -> applyHeroThumbnail(thumb, resolvedPath),
                    ex -> {
                    });
        }

        VBox summaryCol = new VBox(5);
        Label nameLbl = new Label(product.getName());
        nameLbl.setStyle("-fx-font-size: 16.5px; -fx-font-weight: bold;");
        nameLbl.setWrapText(true);

        Label statusPill = new Label(product.getStatus().getDisplayName());
        statusPill.getStyleClass().addAll("status-pill", statusCss(product.getStatus()));

        Label skuLbl = new Label("SKU: " + product.getSku());
        skuLbl.getStyleClass().add("muted-text");
        Label catLbl = new Label("Category: " + (product.getCategory() != null ? product.getCategory() : "General"));
        catLbl.getStyleClass().add("muted-text");
        summaryCol.getChildren().addAll(nameLbl, statusPill, skuLbl, catLbl);
        hero.getChildren().addAll(thumb, summaryCol);

        // Mini Barcode Strip Preview (crisp white paper card so barcode stays
        // black-on-white even in Dark Mode!)
        HBox barcodeStrip = new HBox(2);
        barcodeStrip.getStyleClass().add("barcode-strip");
        barcodeStrip.setAlignment(Pos.CENTER_LEFT);
        barcodeStrip.setPadding(new Insets(8, 12, 8, 12));
        String code = product.getBarcode() != null && !product.getBarcode().isBlank()
                ? product.getBarcode()
                : product.getSku();
        for (int i = 0; i < 24; i++) {
            double w = (code.charAt(i % code.length()) % 3) + 1.0;
            Rectangle bar = new Rectangle(w, 22, Color.web("#0F172A"));
            barcodeStrip.getChildren().add(bar);
        }
        Label codeTxt = new Label("  " + code);
        codeTxt.getStyleClass().add("barcode-code-label");
        barcodeStrip.getChildren().add(codeTxt);

        // Cost / Sell / Profit Cards
        HBox priceRow = new HBox(10,
                miniMetricCard("Buy Price", session.formatMoney(product.getBuyPrice())),
                miniMetricCard("Sell Price", session.formatMoney(product.getSellPrice())),
                miniMetricCard("Unit Profit", session.formatMoney(product.getUnitProfit())));

        // Quantity & Stock Bar
        VBox stockBox = new VBox(6);
        int cap = Math.max(100, Math.max(product.getQuantity(), product.getLowStockThreshold() * 5));
        double ratio = Math.min(1.0, Math.max(0.0, (double) product.getQuantity() / cap));
        Label qtyLbl = new Label("Quantity: " + product.getQuantity() + " units (" + Math.round(ratio * 100) + "%)");
        qtyLbl.setStyle("-fx-font-weight: bold;");
        ProgressBar bar = new ProgressBar(ratio);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(9);
        Label expiryLbl = new Label(
                "Expiry Date: " + (product.getExpiryDate() != null ? product.getExpiryDate() : "—"));
        expiryLbl.getStyleClass().add("muted-text");
        Label shelfLbl = new Label(
                "Shelf Spot: " + (product.getShelfLocation() != null && !product.getShelfLocation().isBlank()
                        ? product.getShelfLocation()
                        : "Unassigned"));
        shelfLbl.getStyleClass().add("muted-text");
        stockBox.getChildren().addAll(qtyLbl, bar, expiryLbl, shelfLbl);

        // Recent Stock Movement History
        VBox historyCard = new VBox(6);
        historyCard.getStyleClass().add("surface-subtle");
        historyCard.setPadding(new Insets(10, 12, 10, 12));
        Label histTitle = new Label("Stock Movement History");
        histTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px;");
        VBox historyRowsBox = new VBox(5);
        Label loadingHist = new Label("Loading movements...");
        loadingHist.getStyleClass().add("muted-text");
        loadingHist.setStyle("-fx-font-size: 11.5px;");
        historyRowsBox.getChildren().add(loadingHist);
        historyCard.getChildren().addAll(histTitle, historyRowsBox);

        // AI Forecast Mini Bar Chart Card
        VBox forecastCard = new VBox(8);
        forecastCard.getStyleClass().add("card");
        HBox fHead = new HBox(6, AppIcons.of(Feather.TRENDING_UP, 14, "#6366F1"));
        fHead.setAlignment(Pos.CENTER_LEFT);
        Label forecastTitle = new Label("Offline ML — Sales Forecast");
        forecastTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px;");
        fHead.getChildren().add(forecastTitle);

        Label forecastVal = new Label("Calculating forecast...");
        forecastVal.getStyleClass().add("muted-text");
        forecastVal.setStyle("-fx-font-size: 12px;");

        forecastMiniChart = new ModernBarChart(68);
        forecastMiniChart.setCompactMiniMode(true);
        forecastCard.getChildren().addAll(fHead, forecastVal, forecastMiniChart);

        // Enterprise V2 Catalog Card (Variants, UoM, BOM, Serials, Attachments, Custom Fields)
        VBox enterpriseCard = new VBox(8);
        enterpriseCard.getStyleClass().add("surface-subtle");
        enterpriseCard.setPadding(new Insets(10, 12, 10, 12));
        HBox entHead = new HBox(8, AppIcons.of(Feather.LAYERS, 14, "#6366F1"));
        entHead.setAlignment(Pos.CENTER_LEFT);
        Label entTitle = new Label("Catalog Specs, Variants & Serials");
        entTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12.5px;");
        entHead.getChildren().add(entTitle);

        VBox entBody = new VBox(5);
        entBody.getChildren().add(mutedSmall("Loading variants, BOM, serials & spec sheets..."));
        enterpriseCard.getChildren().addAll(entHead, entBody);

        // Action Buttons
        HBox actions = new HBox(8);
        Button editBtn = new Button("Edit");
        editBtn.setGraphic(AppIcons.of(Feather.EDIT_2, 14));
        editBtn.getStyleClass().add("btn-primary");
        editBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(editBtn, Priority.ALWAYS);
        editBtn.setOnAction(e -> onEdit.accept(product));

        Button delBtn = new Button("Delete");
        delBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 14));
        delBtn.getStyleClass().add("btn-danger-outline");
        delBtn.setOnAction(e -> onDelete.accept(product));

        actions.getChildren().addAll(editBtn, delBtn);
        if (onPrintLabel != null) {
            Button qrBtn = new Button("QR");
            qrBtn.setGraphic(AppIcons.of(Feather.TAG, 14));
            qrBtn.getStyleClass().add("btn-secondary");
            qrBtn.setOnAction(e -> onPrintLabel.accept(product));
            actions.getChildren().add(qrBtn);
        }

        bodyBox.getChildren().addAll(header, hero, barcodeStrip, priceRow, stockBox, enterpriseCard,
                historyCard, forecastCard, actions);

        setTranslateX(36);
        TranslateTransition slide = new TranslateTransition(Duration.millis(170), this);
        slide.setToX(0);
        slide.play();

        loadDrawerInsightsAsync(product, historyRowsBox, forecastVal, entBody);
    }

    private void loadDrawerInsightsAsync(
            Product product,
            VBox historyRowsBox,
            Label forecastVal,
            VBox entBody) {
        if (services == null) {
            historyRowsBox.getChildren().setAll(mutedSmall("Movement log ready in Stock Operations."));
            entBody.getChildren().setAll(mutedSmall("Variants, BOM, serials & PDF spec sheets require active service session."));
            int estMonthly = Math.max(12, (int) Math.round(Math.max(5, product.getQuantity()) * 1.15));
            forecastVal.setText("Projected: ≈ " + estMonthly + " units / 30 days");
            forecastMiniChart.setData("", buildSampleCurve(estMonthly));
            return;
        }
        AppExecutor.runAsync(() -> {
            List<StockMovement> moves = services.getStockMovementRepository().findByProductId(product.getId(), 4);
            OfflineMlEngine.ProductForecast forecast = services.getOfflineMlEngine().analyzeCatalog().stream()
                    .filter(f -> f.product().getId() == product.getId())
                    .findFirst().orElse(null);
            var catSvc = services.getCatalogVariantBomService();
            List<ProductVariant> variants = catSvc.getVariantsForParent(product.getId());
            List<UomConversion> uoms = catSvc.listUomRules(product.getId());
            List<BundleComponent> bom = catSvc.getBundleComponents(product.getId());
            List<SerialNumberRecord> serials = catSvc.getSerialsForProduct(product.getId());
            List<ProductAttachment> attachments = catSvc.listAttachments(product.getId());
            Map<String, String> customFields = catSvc.getCustomFields(product.getId());
            return new Object[] { moves, forecast, variants, uoms, bom, serials, attachments, customFields };
        }, data -> {
            @SuppressWarnings("unchecked")
            List<StockMovement> moves = (List<StockMovement>) data[0];
            OfflineMlEngine.ProductForecast forecast = (OfflineMlEngine.ProductForecast) data[1];
            @SuppressWarnings("unchecked")
            List<ProductVariant> variants = (List<ProductVariant>) data[2];
            @SuppressWarnings("unchecked")
            List<UomConversion> uoms = (List<UomConversion>) data[3];
            @SuppressWarnings("unchecked")
            List<BundleComponent> bom = (List<BundleComponent>) data[4];
            @SuppressWarnings("unchecked")
            List<SerialNumberRecord> serials = (List<SerialNumberRecord>) data[5];
            @SuppressWarnings("unchecked")
            List<ProductAttachment> attachments = (List<ProductAttachment>) data[6];
            @SuppressWarnings("unchecked")
            Map<String, String> customFields = (Map<String, String>) data[7];

            entBody.getChildren().clear();
            entBody.getChildren().add(mutedSmall(String.format(
                    "Variants: %d • UoM Rules: %d • BOM Components: %d • Serials: %d",
                    variants.size(), uoms.size(), bom.size(), serials.size())));
            if (!customFields.isEmpty()) {
                List<String> kv = new ArrayList<>();
                customFields.forEach((k, v) -> kv.add(k + ": " + v));
                Label cfLbl = mutedSmall("Attributes: " + String.join(" | ", kv));
                cfLbl.setWrapText(true);
                entBody.getChildren().add(cfLbl);
            }
            for (ProductAttachment att : attachments) {
                Button openAtt = new Button("Open " + att.fileName());
                openAtt.setGraphic(AppIcons.of(Feather.FILE_TEXT, 12));
                openAtt.getStyleClass().add("btn-secondary");
                openAtt.setStyle("-fx-font-size: 11px; -fx-padding: 3 8 3 8;");
                openAtt.setOnAction(e -> openAttachmentFile(att.filePath()));
                entBody.getChildren().add(openAtt);
            }

            HBox v2Actions = new HBox(6);
            Button addVarBtn = new Button("+ Variant");
            addVarBtn.getStyleClass().add("btn-secondary");
            addVarBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
            addVarBtn.setOnAction(e -> promptQuickVariant(product, entBody));

            Button attachBtn = new Button("Attach Spec PDF");
            attachBtn.setGraphic(AppIcons.of(Feather.PAPERCLIP, 11));
            attachBtn.getStyleClass().add("btn-secondary");
            attachBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 8 4 8;");
            attachBtn.setOnAction(e -> promptAttachSpecSheet(product));
            v2Actions.getChildren().addAll(addVarBtn, attachBtn);
            entBody.getChildren().add(v2Actions);

            historyRowsBox.getChildren().clear();
            if (moves.isEmpty()) {
                historyRowsBox.getChildren().add(mutedSmall("No recorded movements yet for this item."));
            } else {
                for (StockMovement m : moves) {
                    HBox r = new HBox(8);
                    r.setAlignment(Pos.CENTER_LEFT);
                    Label typePill = new Label(m.getMovementType().getDisplayName());
                    typePill.getStyleClass().addAll("status-pill",
                            m.getMovementType() == MovementType.RESTOCK || m.getMovementType() == MovementType.RETURNED
                                    ? "status-in-stock"
                                    : "status-low-stock");
                    Label delta = new Label((m.getQuantityDelta() >= 0 ? "+" : "") + m.getQuantityDelta());
                    delta.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                    Region sp = new Region();
                    HBox.setHgrow(sp, Priority.ALWAYS);
                    Label ts = new Label(m.getCreatedAt() != null ? m.getCreatedAt().format(MOVE_TIME_FMT) : "");
                    ts.getStyleClass().add("muted-text");
                    ts.setStyle("-fx-font-size: 11px;");
                    r.getChildren().addAll(typePill, delta, sp, ts);
                    historyRowsBox.getChildren().add(r);
                }
            }

            if (forecast != null) {
                forecastVal.setText(String.format("30d Forecast: %.0f units • Tier %s • %s",
                        forecast.forecast30Days(),
                        forecast.abcGrade(),
                        forecast.daysUntilEmpty() >= 999 ? "90+ days cover"
                                : String.format("%.1f days until empty", forecast.daysUntilEmpty())));
                forecastMiniChart.setData("", buildSampleCurve(Math.max(7.0, forecast.forecast30Days())));
            } else {
                forecastVal.setText("≈ 15 units projected next 30 days");
                forecastMiniChart.setData("", buildSampleCurve(15));
            }
        }, ex -> historyRowsBox.getChildren().setAll(mutedSmall("Unable to load movements.")));
    }

    private void promptQuickVariant(Product product, VBox entBody) {
        TextInputDialog dlg = new TextInputDialog("Standard / M");
        dlg.setTitle("Create Matrix Variant");
        dlg.setHeaderText("Add size/color/flavor option for " + product.getName());
        dlg.setContentText("Variant label:");
        dlg.showAndWait().ifPresent(label -> AppExecutor.runAsync(
                () -> services.getCatalogVariantBomService().createMatrixVariant(
                        product.getId(), label, null, label, null, 0.0, 5),
                res -> {
                    if (res.isSuccess()) {
                        showProduct(product);
                    } else {
                        entBody.getChildren().add(mutedSmall(res.getMessage()));
                    }
                },
                ex -> {
                }));
    }

    private void promptAttachSpecSheet(Product product) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach PDF Spec Sheet / Manual");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Documents (*.pdf, *.txt)", "*.pdf", "*.txt"));
        File chosen = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (chosen != null) {
            AppExecutor.runAsync(
                    () -> services.getCatalogVariantBomService().attachSpecSheet(product.getId(), chosen.toPath(), "SPEC_SHEET"),
                    res -> {
                        if (res.isSuccess()) {
                            showProduct(product);
                        }
                    },
                    ex -> {
                    });
        }
    }

    private void openAttachmentFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        AppExecutor.runAsync(() -> {
            File file = new File(filePath);
            if (file.exists() && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file);
            }
            return null;
        }, ok -> {
        }, ex -> {
        });
    }

    private List<ModernBarChart.BarPoint> buildSampleCurve(double targetMonthly) {
        double base = Math.max(4.0, targetMonthly / 7.0);
        double[] mult = { 0.55, 0.68, 0.75, 0.88, 0.96, 1.08, 1.22 };
        List<ModernBarChart.BarPoint> pts = new ArrayList<>();
        for (int i = 0; i < mult.length; i++) {
            pts.add(new ModernBarChart.BarPoint("D" + (i + 1), Math.round(base * mult[i])));
        }
        return pts;
    }

    private Label mutedSmall(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted-text");
        l.setStyle("-fx-font-size: 11.5px;");
        return l;
    }

    private VBox miniMetricCard(String caption, String value) {
        VBox box = new VBox(2);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(10));
        HBox.setHgrow(box, Priority.ALWAYS);
        Label c = new Label(caption);
        c.getStyleClass().add("muted-text");
        c.setStyle("-fx-font-size: 11px;");
        Label v = new Label(value);
        v.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        box.getChildren().addAll(c, v);
        return box;
    }

    private void applyHeroThumbnail(StackPane thumb, String photoPath) {
        thumb.getChildren().clear();
        if (photoPath != null && new File(photoPath).exists()) {
            ImageView img = new ImageView(new Image(new File(photoPath).toURI().toString(), 172, 172, true, true, true));
            img.setFitWidth(78);
            img.setFitHeight(78);
            img.setPreserveRatio(true);
            Rectangle imgClip = new Rectangle(78, 78);
            imgClip.setArcWidth(14);
            imgClip.setArcHeight(14);
            img.setClip(imgClip);
            thumb.getChildren().add(img);
        } else {
            thumb.getChildren().add(AppIcons.of(Feather.PACKAGE, 32, "#6366F1"));
        }
    }

    private String statusCss(StockStatus status) {
        if (status == null) {
            return "status-in-stock";
        }
        return switch (status) {
            case IN_STOCK -> "status-in-stock";
            case LOW_STOCK -> "status-low-stock";
            case OUT_OF_STOCK -> "status-out-of-stock";
        };
    }
}
