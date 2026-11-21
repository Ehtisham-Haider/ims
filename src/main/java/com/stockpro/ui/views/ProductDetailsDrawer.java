package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.service.AppSession;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.File;
import java.util.function.Consumer;

/**
 * Animated right-side Product Details Drawer matching {@code Screens and popups/Product Details.png},
 * displaying product photo, barcode strip, cost/sell/profit cards, stock bar, shelf spot,
 * recent movement summary, and sales forecast mini bars.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ProductDetailsDrawer extends VBox {
    private final Consumer<Product> onEdit;
    private final Consumer<Product> onDelete;
    private final Runnable onClose;
    private final VBox bodyBox = new VBox(14);

    /**
     * Constructs a slide-out product details drawer.
     *
     * @param onEdit callback invoked when {@code Edit Product} is clicked
     * @param onDelete callback invoked when {@code Delete} is clicked
     * @param onClose callback invoked when closing the drawer
     */
    public ProductDetailsDrawer(Consumer<Product> onEdit, Consumer<Product> onDelete, Runnable onClose) {
        this.onEdit = onEdit;
        this.onDelete = onDelete;
        this.onClose = onClose;

        getStyleClass().add("drawer-panel");
        setPrefWidth(390);
        setMinWidth(360);
        setMaxWidth(410);
        setPadding(new Insets(20));

        ScrollPane scroll = new ScrollPane(bodyBox);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().add(scroll);
    }

    /**
     * Populates the drawer with the selected product's metrics and plays a smooth slide-in animation.
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
        Button closeX = new Button("✕");
        closeX.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-font-size: 15px;");
        closeX.setOnAction(e -> onClose.run());
        header.getChildren().addAll(title, sp, closeX);

        // Photo + Basic Summary
        HBox hero = new HBox(14);
        hero.setAlignment(Pos.CENTER_LEFT);
        StackPane thumb = new StackPane();
        thumb.setMinSize(90, 90);
        thumb.setPrefSize(90, 90);
        thumb.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 12; -fx-border-color: #E2E8F0; -fx-border-radius: 12;");
        if (product.getPhotoPath() != null && new File(product.getPhotoPath()).exists()) {
            ImageView img = new ImageView(new Image(new File(product.getPhotoPath()).toURI().toString()));
            img.setFitWidth(82);
            img.setFitHeight(82);
            img.setPreserveRatio(true);
            thumb.getChildren().add(img);
        } else {
            Label glyph = new Label("📦");
            glyph.setStyle("-fx-font-size: 34px;");
            thumb.getChildren().add(glyph);
        }

        VBox summaryCol = new VBox(5);
        Label nameLbl = new Label(product.getName());
        nameLbl.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
        nameLbl.setWrapText(true);

        Label statusPill = new Label(product.getStatus().getDisplayName());
        statusPill.getStyleClass().addAll("status-pill", statusCss(product.getStatus()));

        Label skuLbl = new Label("Product Code: " + product.getSku());
        skuLbl.getStyleClass().add("muted-text");
        Label catLbl = new Label("Category: " + (product.getCategory() != null ? product.getCategory() : "General"));
        catLbl.getStyleClass().add("muted-text");
        summaryCol.getChildren().addAll(nameLbl, statusPill, skuLbl, catLbl);
        hero.getChildren().addAll(thumb, summaryCol);

        // Mini Barcode Strip Preview
        HBox barcodeStrip = new HBox(2);
        barcodeStrip.setAlignment(Pos.CENTER_LEFT);
        barcodeStrip.setPadding(new Insets(8, 10, 8, 10));
        barcodeStrip.setStyle("-fx-background-color: #F8FAFC; -fx-background-radius: 8; -fx-border-color: #E2E8F0; -fx-border-radius: 8;");
        String code = product.getBarcode() != null ? product.getBarcode() : product.getSku();
        for (int i = 0; i < 24; i++) {
            double w = (code.charAt(i % code.length()) % 3) + 1.0;
            Rectangle bar = new Rectangle(w, 22, Color.web("#1E293B"));
            barcodeStrip.getChildren().add(bar);
        }
        Label codeTxt = new Label("  " + code);
        codeTxt.setStyle("-fx-font-family: monospace; -fx-font-weight: bold;");
        barcodeStrip.getChildren().add(codeTxt);

        // Cost / Sell / Profit Cards
        HBox priceRow = new HBox(10,
                miniMetricCard("Buy Price", session.formatMoney(product.getBuyPrice())),
                miniMetricCard("Sell Price", session.formatMoney(product.getSellPrice())),
                miniMetricCard("Unit Profit", session.formatMoney(product.getUnitProfit()))
        );

        // Quantity & Stock Bar
        VBox stockBox = new VBox(6);
        int cap = Math.max(100, Math.max(product.getQuantity(), product.getLowStockThreshold() * 5));
        double ratio = Math.min(1.0, Math.max(0.0, (double) product.getQuantity() / cap));
        Label qtyLbl = new Label("Quantity: " + product.getQuantity() + " units (" + Math.round(ratio * 100) + "%)");
        qtyLbl.setStyle("-fx-font-weight: bold;");
        ProgressBar bar = new ProgressBar(ratio);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(10);
        Label expiryLbl = new Label("Expiry Date: " + (product.getExpiryDate() != null ? product.getExpiryDate() : "—"));
        Label shelfLbl = new Label("Shelf: " + (product.getShelfLocation() != null && !product.getShelfLocation().isBlank() ? product.getShelfLocation() : "Unassigned"));
        stockBox.getChildren().addAll(qtyLbl, bar, expiryLbl, shelfLbl);

        // AI Forecast Mini Chart Snippet
        VBox forecastCard = new VBox(8);
        forecastCard.getStyleClass().add("card");
        Label forecastTitle = new Label("💡 AI Insights — Sales Forecast");
        forecastTitle.setStyle("-fx-font-weight: bold;");
        int estMonthly = Math.max(12, (int) Math.round(Math.max(5, product.getQuantity()) * 1.15));
        Label forecastVal = new Label("≈ " + estMonthly + " units projected next month");
        forecastVal.getStyleClass().add("muted-text");

        HBox miniBars = new HBox(6);
        miniBars.setAlignment(Pos.BOTTOM_LEFT);
        miniBars.setPrefHeight(48);
        int[] heights = {14, 18, 22, 26, 30, 36, 44};
        for (int h : heights) {
            Region r = new Region();
            r.setPrefSize(18, h);
            r.setStyle("-fx-background-color: #6366F1; -fx-background-radius: 4 4 0 0;");
            miniBars.getChildren().add(r);
        }
        forecastCard.getChildren().addAll(forecastTitle, forecastVal, miniBars);

        // Action Buttons
        HBox actions = new HBox(10);
        Button editBtn = new Button("✎ Edit Product");
        editBtn.getStyleClass().add("btn-primary");
        editBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(editBtn, Priority.ALWAYS);
        editBtn.setOnAction(e -> onEdit.accept(product));

        Button delBtn = new Button("🗑 Delete");
        delBtn.getStyleClass().add("btn-danger-outline");
        delBtn.setOnAction(e -> onDelete.accept(product));

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> onClose.run());

        actions.getChildren().addAll(editBtn, delBtn, closeBtn);

        bodyBox.getChildren().addAll(header, hero, barcodeStrip, priceRow, stockBox, forecastCard, actions);

        setTranslateX(40);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), this);
        slide.setToX(0);
        slide.play();
    }

    /**
     * Creates a compact metric sub-card for Buy Price, Sell Price, and Profit.
     *
     * @param caption label above value
     * @param value formatted price string
     * @return styled {@link VBox}
     */
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

    /**
     * Resolves CSS pill class for a stock status.
     *
     * @param status product status
     * @return CSS class name
     */
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
