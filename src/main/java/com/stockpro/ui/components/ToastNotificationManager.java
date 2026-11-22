package com.stockpro.ui.components;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages bottom-right floating Undo toast banners and urgent low/out-of-stock alert popups.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ToastNotificationManager {
    private final StackPane overlayHost;
    private final VBox toastStack;

    /**
     * Attaches a toast and modal alert host onto the root shell {@link StackPane}.
     *
     * @param overlayHost root container supporting floating overlays
     */
    public ToastNotificationManager(StackPane overlayHost) {
        this.overlayHost = overlayHost;
        this.toastStack = new VBox(10);
        this.toastStack.setPickOnBounds(false);
        this.toastStack.setAlignment(Pos.BOTTOM_RIGHT);
        this.toastStack.setPadding(new Insets(0, 24, 24, 0));
        StackPane.setAlignment(toastStack, Pos.BOTTOM_RIGHT);
        overlayHost.getChildren().add(toastStack);
    }

    /**
     * Displays a bottom-right floating notification banner without an action button.
     *
     * @param message text to display
     */
    public void showInfo(String message) {
        showToast(message, null, null);
    }

    /**
     * Displays a bottom-right floating notification banner with an optional clickable action (such as Undo).
     *
     * @param message notification message text
     * @param actionLabel optional button text (e.g. {@code "Undo"}), or {@code null}
     * @param onAction callback executed when the action button is clicked
     */
    public void showToast(String message, String actionLabel, Runnable onAction) {
        HBox banner = new HBox(12);
        banner.getStyleClass().add("toast-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setMaxWidth(440);

        Label msgLabel = new Label(message);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-weight: 600;");
        HBox.setHgrow(msgLabel, Priority.ALWAYS);
        banner.getChildren().add(msgLabel);

        if (actionLabel != null && !actionLabel.isBlank() && onAction != null) {
            Button actionBtn = new Button(actionLabel);
            actionBtn.getStyleClass().add("btn-primary");
            actionBtn.setStyle("-fx-padding: 5 12 5 12; -fx-background-radius: 7;");
            actionBtn.setOnAction(e -> {
                toastStack.getChildren().remove(banner);
                onAction.run();
            });
            banner.getChildren().add(actionBtn);
        }

        Button closeBtn = new Button();
        closeBtn.setGraphic(AppIcons.of(Feather.X, 14, "#94A3B8"));
        closeBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 2 4 2 4;");
        closeBtn.setOnAction(e -> toastStack.getChildren().remove(banner));
        banner.getChildren().add(closeBtn);

        banner.setOpacity(0);
        toastStack.getChildren().add(banner);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(160), banner);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        PauseTransition delay = new PauseTransition(Duration.seconds(5));
        delay.setOnFinished(e -> toastStack.getChildren().remove(banner));
        delay.play();
    }

    /**
     * Displays the centered urgent Low/Out-of-Stock Alert modal matching the reference mockup design.
     *
     * @param product critical inventory item
     * @param onViewProduct callback triggered when {@code View Product} is clicked
     */
    public void showLowStockAlertModal(Product product, Consumer<Product> onViewProduct) {
        if (product == null) {
            return;
        }
        StackPane backdrop = new StackPane();
        backdrop.setStyle("-fx-background-color: rgba(15, 23, 42, 0.52);");
        backdrop.setAlignment(Pos.CENTER);

        VBox card = new VBox(16);
        card.getStyleClass().add("card");
        card.setMaxWidth(410);
        card.setPrefWidth(410);
        card.setPadding(new Insets(24));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        boolean isOut = product.getQuantity() <= 0 || product.getStatus() == StockStatus.OUT_OF_STOCK;
        StackPane badge = new StackPane(AppIcons.of(
                Feather.ALERT_TRIANGLE, 18, isOut ? "#DC2626" : "#D97706"));
        badge.setMinSize(36, 36);
        badge.setStyle("-fx-background-color: " + (isOut ? "#FEE2E2" : "#FEF3C7") + "; -fx-background-radius: 10;");

        Label title = new Label(isOut ? "Out of Stock Alert" : "Low Stock Alert");
        title.getStyleClass().add("section-title");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-border-color: "
                + (isOut ? "#EF4444" : "#F59E0B") + "; -fx-border-width: 0 0 3 0; -fx-padding: 0 0 3 0;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeX = new Button();
        closeX.getStyleClass().add("icon-btn");
        closeX.setGraphic(AppIcons.of(Feather.X, 16));
        closeX.setOnAction(e -> overlayHost.getChildren().remove(backdrop));
        header.getChildren().addAll(badge, title, spacer, closeX);

        Label body = new Label(isOut
                ? product.getName() + " is completely out of stock! Immediate restock recommended."
                : product.getName() + " is running low! Only " + product.getQuantity() + " items left in stock.");
        body.setWrapText(true);
        body.setStyle("-fx-font-size: 14px;");

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button viewBtn = new Button("View Product");
        viewBtn.getStyleClass().add("btn-primary");
        viewBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(viewBtn, Priority.ALWAYS);
        viewBtn.setOnAction(e -> {
            overlayHost.getChildren().remove(backdrop);
            if (onViewProduct != null) {
                onViewProduct.accept(product);
            }
        });

        Button dismissBtn = new Button("Dismiss");
        dismissBtn.getStyleClass().add("btn-secondary");
        dismissBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(dismissBtn, Priority.ALWAYS);
        dismissBtn.setOnAction(e -> overlayHost.getChildren().remove(backdrop));

        actions.getChildren().addAll(viewBtn, dismissBtn);
        card.getChildren().addAll(header, body, actions);
        backdrop.getChildren().add(card);
        backdrop.setOnMouseClicked(e -> {
            if (e.getTarget() == backdrop) {
                overlayHost.getChildren().remove(backdrop);
            }
        });

        overlayHost.getChildren().add(backdrop);
    }

    /**
     * Opens a clean scrollable urgent-alerts popover listing ALL low-stock and out-of-stock products
     * so every critical alert can be inspected individually.
     *
     * @param alerts list of low or out-of-stock products
     * @param onSelectProduct callback when user selects an item to inspect
     */
    public void showAlertsBannerList(List<Product> alerts, Consumer<Product> onSelectProduct) {
        if (alerts == null || alerts.isEmpty()) {
            showInfo("All products are currently healthy and above stock thresholds.");
            return;
        }
        if (alerts.size() == 1) {
            showLowStockAlertModal(alerts.get(0), onSelectProduct);
            return;
        }

        StackPane backdrop = new StackPane();
        backdrop.setStyle("-fx-background-color: rgba(15, 23, 42, 0.52);");
        backdrop.setAlignment(Pos.CENTER);

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setMaxWidth(490);
        card.setMaxHeight(460);
        card.setPadding(new Insets(22));

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane badge = new StackPane(AppIcons.of(Feather.BELL, 17, "#D97706"));
        badge.setMinSize(34, 34);
        badge.setStyle("-fx-background-color: #FEF3C7; -fx-background-radius: 10;");

        Label title = new Label("Urgent Stock Alerts (" + alerts.size() + ")");
        title.getStyleClass().add("section-title");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button closeX = new Button();
        closeX.getStyleClass().add("icon-btn");
        closeX.setGraphic(AppIcons.of(Feather.X, 16));
        closeX.setOnAction(e -> overlayHost.getChildren().remove(backdrop));
        header.getChildren().addAll(badge, title, spacer, closeX);

        VBox listRows = new VBox(8);
        for (Product p : alerts) {
            boolean out = p.getQuantity() <= 0 || p.getStatus() == StockStatus.OUT_OF_STOCK;
            HBox row = new HBox(10);
            row.getStyleClass().add("surface-subtle");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 12, 10, 12));

            Label pill = new Label(out ? "Out of Stock" : p.getQuantity() + " left");
            pill.getStyleClass().addAll("status-pill", out ? "status-out-of-stock" : "status-low-stock");

            VBox info = new VBox(2);
            HBox.setHgrow(info, Priority.ALWAYS);
            Label name = new Label(p.getName());
            name.setStyle("-fx-font-weight: bold;");
            Label meta = new Label(p.getSku() + " • " + (p.getCategory() != null ? p.getCategory() : "General"));
            meta.getStyleClass().add("muted-text");
            meta.setStyle("-fx-font-size: 11px;");
            info.getChildren().addAll(name, meta);

            Button viewBtn = new Button("Inspect");
            viewBtn.getStyleClass().add("btn-secondary");
            viewBtn.setStyle("-fx-padding: 5 11 5 11;");
            viewBtn.setOnAction(e -> {
                overlayHost.getChildren().remove(backdrop);
                if (onSelectProduct != null) {
                    onSelectProduct.accept(p);
                }
            });

            row.getChildren().addAll(pill, info, viewBtn);
            listRows.getChildren().add(row);
        }

        ScrollPane scroll = new ScrollPane(listRows);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(290);

        Button dismissAll = new Button("Dismiss");
        dismissAll.getStyleClass().add("btn-secondary");
        dismissAll.setOnAction(e -> overlayHost.getChildren().remove(backdrop));
        HBox footer = new HBox(dismissAll);
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(header, scroll, footer);
        backdrop.getChildren().add(card);
        backdrop.setOnMouseClicked(e -> {
            if (e.getTarget() == backdrop) {
                overlayHost.getChildren().remove(backdrop);
            }
        });
        overlayHost.getChildren().add(backdrop);
    }
}
