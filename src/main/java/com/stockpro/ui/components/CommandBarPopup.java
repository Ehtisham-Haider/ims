package com.stockpro.ui.components;

import com.stockpro.domain.Product;
import com.stockpro.service.CommandParser;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.InventoryService;
import com.stockpro.ui.controllers.NavigationSection;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Spotlight-style floating {@code Ctrl+K} popup supporting instant search, screen jumps, and quick commands.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmad Khan
 */
public class CommandBarPopup {
    private final StackPane overlayHost;
    private final InventoryService inventoryService;
    private final Consumer<NavigationSection> onNavigate;
    private final Runnable onToggleTheme;
    private final Runnable onExportPdf;
    private final Consumer<Product> onSelectProduct;
    private final ToastNotificationManager toastManager;

    private StackPane backdrop;
    private TextField inputField;
    private ListView<CommandEntry> resultsList;

    /**
     * Internal list item model displayed inside the Spotlight suggestion list.
     *
     * @param title primary display text
     * @param subtitle secondary hint or category
     * @param action callback executed on selection
     */
    private record CommandEntry(String title, String subtitle, Runnable action) {
    }

    /**
     * Constructs the Spotlight command bar controller.
     *
     * @param overlayHost root stack pane hosting modal overlays
     * @param inventoryService inventory service for quick-add and catalog searches
     * @param onNavigate navigation callback for screen jumps
     * @param onToggleTheme theme switch callback
     * @param onExportPdf report export callback
     * @param onSelectProduct product inspection callback
     * @param toastManager toast notification manager
     */
    public CommandBarPopup(
            StackPane overlayHost,
            InventoryService inventoryService,
            Consumer<NavigationSection> onNavigate,
            Runnable onToggleTheme,
            Runnable onExportPdf,
            Consumer<Product> onSelectProduct,
            ToastNotificationManager toastManager) {
        this.overlayHost = overlayHost;
        this.inventoryService = inventoryService;
        this.onNavigate = onNavigate;
        this.onToggleTheme = onToggleTheme;
        this.onExportPdf = onExportPdf;
        this.onSelectProduct = onSelectProduct;
        this.toastManager = toastManager;
        buildUi();
    }

    /**
     * Builds the floating Spotlight modal scene graph once for instant display.
     */
    private void buildUi() {
        backdrop = new StackPane();
        backdrop.getStyleClass().add("modal-backdrop");
        backdrop.setAlignment(Pos.TOP_CENTER);
        backdrop.setPadding(new Insets(72, 24, 24, 24));

        VBox modal = new VBox(12);
        modal.getStyleClass().add("card");
        modal.setMaxWidth(620);
        modal.setMaxHeight(440);
        modal.setPadding(new Insets(16));

        HBox searchRow = new HBox(10);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("Ctrl+K");
        icon.getStyleClass().addAll("status-pill", "status-info-pill");

        inputField = new TextField();
        inputField.setPromptText("Search products, jump to screen, or type 'add 10 soap', 'toggle theme', 'export pdf'...");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        searchRow.getChildren().addAll(icon, inputField);

        resultsList = new ListView<>();
        resultsList.setPrefHeight(310);
        resultsList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(CommandEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    VBox box = new VBox(2);
                    Label t = new Label(item.title());
                    t.setStyle("-fx-font-weight: bold;");
                    Label sub = new Label(item.subtitle());
                    sub.getStyleClass().add("muted-text");
                    sub.setStyle("-fx-font-size: 11.5px;");
                    box.getChildren().addAll(t, sub);
                    setGraphic(box);
                }
            }
        });

        Label footerHint = new Label("Enter to run  •  Esc to close  •  Commands: add <qty> <item>, toggle theme, export pdf");
        footerHint.getStyleClass().add("muted-text");
        footerHint.setStyle("-fx-font-size: 11.5px;");

        modal.getChildren().addAll(searchRow, resultsList, footerHint);
        backdrop.getChildren().add(modal);

        backdrop.setOnMouseClicked(e -> {
            if (e.getTarget() == backdrop) {
                hide();
            }
        });

        inputField.textProperty().addListener((obs, oldVal, newVal) -> refreshSuggestions(newVal));
        inputField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            } else if (e.getCode() == KeyCode.DOWN && !resultsList.getItems().isEmpty()) {
                resultsList.requestFocus();
                resultsList.getSelectionModel().selectFirst();
            } else if (e.getCode() == KeyCode.ENTER) {
                executeFirstOrTyped();
            }
        });

        resultsList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                executeSelected();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hide();
            }
        });

        resultsList.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 1 && resultsList.getSelectionModel().getSelectedItem() != null) {
                executeSelected();
            }
        });
    }

    /**
     * Opens the Spotlight overlay and resets input focus.
     */
    public void show() {
        if (!overlayHost.getChildren().contains(backdrop)) {
            overlayHost.getChildren().add(backdrop);
            ToastNotificationManager.animateIn(backdrop, backdrop.getChildren().get(0));
        }
        inputField.clear();
        refreshSuggestions("");
        inputField.requestFocus();
    }

    /**
     * Closes the Spotlight overlay if visible.
     */
    public void hide() {
        overlayHost.getChildren().remove(backdrop);
    }

    /**
     * Updates the suggestion list dynamically from command syntax, screens, and matching products.
     *
     * @param query raw input text
     */
    private void refreshSuggestions(String query) {
        List<CommandEntry> entries = new ArrayList<>();
        CommandParser.ParsedCommand parsed = CommandParser.parse(query);

        switch (parsed.actionType()) {
            case QUICK_ADD -> entries.add(new CommandEntry(
                    "Quick Add: " + parsed.quantity() + " × " + parsed.argument(),
                    "Creates or adds item immediately into local SQLite inventory",
                    () -> runQuickAdd(parsed.argument(), parsed.quantity())
            ));
            case TOGGLE_THEME -> entries.add(new CommandEntry(
                    "Switch Dark / Light Theme",
                    "Applies updated CSS theme across all screens",
                    onToggleTheme
            ));
            case EXPORT_PDF -> entries.add(new CommandEntry(
                    "Export Business Report PDF",
                    "Navigates to Analytics & Reports and runs PDF export",
                    onExportPdf
            ));
            case NAVIGATE -> {
                NavigationSection target = NavigationSection.valueOf(parsed.targetScreen());
                entries.add(new CommandEntry(
                        "Go to " + target.getLabel(),
                        "Open " + target.getLabel() + " screen",
                        () -> onNavigate.accept(target)
                ));
            }
            case SEARCH_PRODUCTS -> {
                // Populate matching catalog products plus screen shortcuts below.
            }
        }

        String lower = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        for (Product p : inventoryService.getAllProducts()) {
            if (lower.isEmpty()
                    || p.getName().toLowerCase(Locale.ROOT).contains(lower)
                    || p.getSku().toLowerCase(Locale.ROOT).contains(lower)
                    || (p.getCategory() != null && p.getCategory().toLowerCase(Locale.ROOT).contains(lower))) {
                entries.add(new CommandEntry(
                        p.getName() + " (" + p.getSku() + ")",
                        "Qty: " + p.getQuantity() + " • " + p.getStatus().getDisplayName() + " • " + p.getCategory(),
                        () -> {
                            onNavigate.accept(NavigationSection.INVENTORY);
                            if (onSelectProduct != null) {
                                onSelectProduct.accept(p);
                            }
                        }
                ));
            }
            if (entries.size() >= 8) {
                break;
            }
        }

        for (NavigationSection sec : NavigationSection.values()) {
            if (lower.isEmpty() || sec.getLabel().toLowerCase(Locale.ROOT).contains(lower)) {
                entries.add(new CommandEntry(
                        "Jump to " + sec.getLabel(),
                        "Navigation shortcut",
                        () -> onNavigate.accept(sec)
                ));
            }
        }

        if (lower.isEmpty()) {
            entries.add(new CommandEntry(
                    "Toggle Dark / Light Theme",
                    "Quick theme action",
                    onToggleTheme
            ));
        }

        resultsList.setItems(FXCollections.observableArrayList(entries));
        if (!entries.isEmpty()) {
            resultsList.getSelectionModel().selectFirst();
        }
    }

    /**
     * Executes either the typed command directly or the first highlighted suggestion.
     */
    private void executeFirstOrTyped() {
        CommandEntry selected = resultsList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            hide();
            selected.action().run();
        }
    }

    /**
     * Executes the currently selected entry in the suggestion list.
     */
    private void executeSelected() {
        CommandEntry selected = resultsList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            hide();
            selected.action().run();
        }
    }

    /**
     * Executes the {@code add <qty> <item>} Spotlight command and surfaces an Undo toast banner.
     *
     * @param name item name
     * @param qty positive integer quantity
     */
    private void runQuickAdd(String name, int qty) {
        InventoryOperationResult<Product> res = inventoryService.addProductFromInputs(
                name, String.valueOf(qty), "General", 0.0, 0.0, null);
        if (res.isSuccess()) {
            toastManager.showToast(res.getMessage(), "Undo", () -> {
                inventoryService.undoLastAction();
                toastManager.showInfo("Undid quick add of " + name + ".");
            });
        } else {
            toastManager.showInfo(res.getMessage());
        }
    }
}
