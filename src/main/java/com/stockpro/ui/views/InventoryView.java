package com.stockpro.ui.views;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.InventoryFilterCriteria.StatusFilterChip;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.InventoryService;
import com.stockpro.service.ProductValidator;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * High-speed Inventory Catalog screen matching {@code Screens and popups/Inventory.png},
 * including status filter chips, category filter, empty-state welcome view, double-click inline quantity edits,
 * bulk multi-select actions, and animated slide-out Product Details drawer.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class InventoryView {
    private final ServiceContext services;
    private final InventoryService inventoryService;
    private final ToastNotificationManager toastManager;
    private final Runnable onCatalogChanged;

    private final ObservableList<Product> tableRows = FXCollections.observableArrayList();
    private final Set<Long> selectedIds = new HashSet<>();
    private final InventoryFilterCriteria criteria = new InventoryFilterCriteria();

    private BorderPane rootPane;
    private TableView<Product> tableView;
    private VBox emptyStateBox;
    private VBox tableCard;
    private HBox bulkBar;
    private Label bulkCountLabel;
    private Label countSummaryLabel;
    private TextField searchField;
    private ComboBox<String> categoryFilter;
    private Button chipAll;
    private Button chipIn;
    private Button chipLow;
    private Button chipOut;
    private Button chipExpiring;
    private ProductDetailsDrawer detailsDrawer;

    /**
     * Constructs an Inventory Catalog view controller.
     *
     * @param services initialized service registry
     * @param toastManager toast and alert banner manager
     * @param onCatalogChanged callback notifying shell badge counters after changes
     */
    public InventoryView(ServiceContext services, ToastNotificationManager toastManager, Runnable onCatalogChanged) {
        this.services = services;
        this.inventoryService = services.getInventoryService();
        this.toastManager = toastManager;
        this.onCatalogChanged = onCatalogChanged;
        InventoryEventBus.getInstance().subscribe(topic -> refreshAsync());
    }

    /**
     * Builds the Inventory view layout with table, empty-state card, bulk toolbar, and slide-out details drawer.
     *
     * @return JavaFX {@link Node}
     */
    public Node build() {
        rootPane = new BorderPane();

        VBox mainCol = new VBox(16);
        mainCol.setPadding(new Insets(0, 0, 8, 0));

        // Header Row
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleCol = new VBox(2);
        Label title = new Label("Inventory");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage your products and stock");
        subtitle.getStyleClass().add("page-subtitle");
        titleCol.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button scanBtn = new Button("📷 Scan Barcode");
        scanBtn.getStyleClass().add("btn-secondary");
        scanBtn.setOnAction(e -> promptQuickBarcodeLookup());

        Button addBtn = new Button("+ Add Product");
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openAddProductDialog());

        header.getChildren().addAll(titleCol, spacer, scanBtn, addBtn);

        // Filter Chips + Search + Category Dropdown
        HBox filterBar = buildFilterBar();

        // Main Table Card & Empty State Stack
        tableCard = buildTableCard();
        emptyStateBox = buildEmptyStateBox();
        VBox.setVgrow(tableCard, Priority.ALWAYS);
        VBox.setVgrow(emptyStateBox, Priority.ALWAYS);

        StackPane centerStack = new StackPane(tableCard, emptyStateBox);
        VBox.setVgrow(centerStack, Priority.ALWAYS);

        mainCol.getChildren().addAll(header, filterBar, centerStack);
        rootPane.setCenter(mainCol);

        detailsDrawer = new ProductDetailsDrawer(
                this::openEditProductDialog,
                this::confirmDeleteProduct,
                this::closeDetailsDrawer,
                p -> new BarcodeLabelDialog(services.getBarcodeLabelService(), toastManager).show(
                        rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                        p,
                        AppSession.getInstance().isDarkMode())
        );
        detailsDrawer.setVisible(false);
        detailsDrawer.setManaged(false);
        rootPane.setRight(detailsDrawer);

        refreshAsync();
        return rootPane;
    }

    /**
     * Builds the filter chip strip, search field, and category dropdown.
     *
     * @return horizontal filter bar
     */
    private HBox buildFilterBar() {
        chipAll = makeChip("All (0)", StatusFilterChip.ALL, true);
        chipIn = makeChip("In Stock", StatusFilterChip.IN_STOCK, false);
        chipLow = makeChip("Low Stock", StatusFilterChip.LOW_STOCK, false);
        chipOut = makeChip("Out of Stock", StatusFilterChip.OUT_OF_STOCK, false);
        chipExpiring = makeChip("Expiring", StatusFilterChip.EXPIRING, false);

        searchField = new TextField();
        searchField.setPromptText("Search products by name, SKU, or tags...");
        searchField.setPrefWidth(250);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            criteria.setSearchQuery(newVal);
            refreshAsync();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        categoryFilter = new ComboBox<>();
        categoryFilter.getItems().add("All Categories");
        categoryFilter.setValue("All Categories");
        categoryFilter.setOnAction(e -> {
            criteria.setCategory(categoryFilter.getValue());
            refreshAsync();
        });

        HBox row = new HBox(8, chipAll, chipIn, chipLow, chipOut, chipExpiring, spacer, searchField, categoryFilter);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Creates a single filter chip button wired to update active {@link StatusFilterChip}.
     *
     * @param text chip label
     * @param target chip filter mode
     * @param active initial active styling
     * @return styled {@link Button}
     */
    private Button makeChip(String text, StatusFilterChip target, boolean active) {
        Button btn = new Button(text);
        btn.getStyleClass().add("filter-chip");
        if (active) {
            btn.getStyleClass().add("filter-chip-active");
        }
        btn.setOnAction(e -> {
            criteria.setStatusChip(target);
            for (Button c : List.of(chipAll, chipIn, chipLow, chipOut, chipExpiring)) {
                c.getStyleClass().remove("filter-chip-active");
            }
            btn.getStyleClass().add("filter-chip-active");
            refreshAsync();
        });
        return btn;
    }

    /**
     * Builds the virtualized {@link TableView} card with inline quantity edit and bulk selection footer.
     *
     * @return styled table container
     */
    private VBox buildTableCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        tableView = new TableView<>(tableRows);
        tableView.setEditable(true);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 1. Multi-select Checkbox Column
        TableColumn<Product, Boolean> selectCol = new TableColumn<>("");
        selectCol.setMaxWidth(42);
        selectCol.setMinWidth(42);
        selectCol.setEditable(true);
        selectCol.setCellValueFactory(param -> {
            Product p = param.getValue();
            SimpleBooleanProperty prop = new SimpleBooleanProperty(selectedIds.contains(p.getId()));
            prop.addListener((obs, was, isNow) -> {
                if (isNow) {
                    selectedIds.add(p.getId());
                } else {
                    selectedIds.remove(p.getId());
                }
                updateBulkBarVisibility();
            });
            return prop;
        });
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));

        // 2. Thumbnail Column
        TableColumn<Product, String> imgCol = new TableColumn<>("Image");
        imgCol.setMaxWidth(64);
        imgCol.setMinWidth(58);
        imgCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPhotoPath()));
        imgCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String photoPath, boolean empty) {
                super.updateItem(photoPath, empty);
                if (empty) {
                    setGraphic(null);
                } else if (photoPath != null && new File(photoPath).exists()) {
                    ImageView iv = new ImageView(new Image(new File(photoPath).toURI().toString()));
                    iv.setFitWidth(30);
                    iv.setFitHeight(30);
                    iv.setPreserveRatio(true);
                    setGraphic(iv);
                } else {
                    Label icon = new Label("📦");
                    icon.setStyle("-fx-font-size: 16px;");
                    setGraphic(icon);
                }
            }
        });

        // 3. SKU Column
        TableColumn<Product, String> skuCol = new TableColumn<>("Product Code");
        skuCol.setMinWidth(95);
        skuCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSku()));

        // 4. Name Column
        TableColumn<Product, String> nameCol = new TableColumn<>("Product Name");
        nameCol.setMinWidth(150);
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));

        // 5. Category Column
        TableColumn<Product, String> catCol = new TableColumn<>("Category");
        catCol.setMinWidth(95);
        catCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCategory()));

        // 6. Quantity + Mini Stock Progress Bar (Supports double-click inline editing!)
        TableColumn<Product, String> qtyCol = new TableColumn<>("Qty");
        qtyCol.setMinWidth(115);
        qtyCol.setEditable(true);
        qtyCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        qtyCol.setCellFactory(col -> new InlineQtyTableCell());

        // 7. Buy Price Column
        TableColumn<Product, String> buyCol = new TableColumn<>("Buy Price");
        buyCol.setMinWidth(85);
        buyCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getBuyPrice())));

        // 8. Sell Price Column
        TableColumn<Product, String> sellCol = new TableColumn<>("Sell Price");
        sellCol.setMinWidth(85);
        sellCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getSellPrice())));

        // 9. Expiry Date Column
        TableColumn<Product, String> expCol = new TableColumn<>("Expiry Date");
        expCol.setMinWidth(95);
        expCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getExpiryDate() != null ? c.getValue().getExpiryDate().toString() : "—"));

        // 10. Status Pill Column
        TableColumn<Product, String> statusCol = new TableColumn<>("Status");
        statusCol.setMinWidth(115);
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStatus().getDisplayName()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String statusText, boolean empty) {
                super.updateItem(statusText, empty);
                if (empty || statusText == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label(statusText);
                StockStatus st = StockStatus.fromString(statusText);
                pill.getStyleClass().addAll("status-pill", switch (st) {
                    case IN_STOCK -> "status-in-stock";
                    case LOW_STOCK -> "status-low-stock";
                    case OUT_OF_STOCK -> "status-out-of-stock";
                });
                setGraphic(pill);
            }
        });

        // 11. Action Buttons Column
        TableColumn<Product, Void> actionCol = new TableColumn<>("Actions");
        actionCol.setMinWidth(115);
        actionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                Product product = getTableRow().getItem();
                Button editBtn = new Button("✎");
                editBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
                editBtn.setOnAction(e -> openEditProductDialog(product));

                Button delBtn = new Button("🗑");
                delBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #EF4444; -fx-cursor: hand;");
                delBtn.setOnAction(e -> confirmDeleteProduct(product));

                Button infoBtn = new Button("⋯");
                infoBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
                infoBtn.setOnAction(e -> openDetailsDrawer(product));

                HBox box = new HBox(4, editBtn, delBtn, infoBtn);
                box.setAlignment(Pos.CENTER);
                setGraphic(box);
            }
        });

        tableView.getColumns().addAll(List.of(
                selectCol, imgCol, skuCol, nameCol, catCol, qtyCol, buyCol, sellCol, expCol, statusCol, actionCol
        ));

        tableView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 1 && tableView.getSelectionModel().getSelectedItem() != null) {
                openDetailsDrawer(tableView.getSelectionModel().getSelectedItem());
            }
        });

        // Footer Summary + Bulk Selection Bar
        countSummaryLabel = new Label("Showing 0 items");
        countSummaryLabel.getStyleClass().add("muted-text");

        bulkCountLabel = new Label("0 items selected:");
        bulkCountLabel.setStyle("-fx-font-weight: bold;");

        ComboBox<StockStatus> bulkStatusBox = new ComboBox<>();
        bulkStatusBox.getItems().addAll(StockStatus.IN_STOCK, StockStatus.LOW_STOCK, StockStatus.OUT_OF_STOCK);
        bulkStatusBox.setPromptText("Update Status");
        bulkStatusBox.setOnAction(e -> {
            StockStatus target = bulkStatusBox.getValue();
            if (target != null && !selectedIds.isEmpty()) {
                InventoryOperationResult<Integer> res = inventoryService.bulkUpdateStatus(
                        new ArrayList<>(selectedIds), target, AppSession.getInstance().canManageInventory());
                toastManager.showInfo(res.getMessage());
                selectedIds.clear();
                bulkStatusBox.setValue(null);
                refreshAsync();
            }
        });

        Button bulkDeleteBtn = new Button("Delete Selected");
        bulkDeleteBtn.getStyleClass().add("btn-danger-outline");
        bulkDeleteBtn.setOnAction(e -> {
            InventoryOperationResult<Integer> res = inventoryService.bulkDelete(
                    new ArrayList<>(selectedIds), AppSession.getInstance().canDeleteOrApprove());
            toastManager.showInfo(res.getMessage());
            selectedIds.clear();
            refreshAsync();
        });

        Button bulkExportBtn = new Button("Export CSV");
        bulkExportBtn.getStyleClass().add("btn-secondary");
        bulkExportBtn.setOnAction(e -> exportSelectedToCsv());

        bulkBar = new HBox(10, bulkCountLabel, bulkStatusBox, bulkDeleteBtn, bulkExportBtn);
        bulkBar.setAlignment(Pos.CENTER_LEFT);
        bulkBar.setVisible(false);
        bulkBar.setManaged(false);

        HBox footer = new HBox(12, countSummaryLabel, new Region(), bulkBar);
        HBox.setHgrow(footer.getChildren().get(1), Priority.ALWAYS);
        footer.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(tableView, footer);
        return card;
    }

    /**
     * Builds the friendly Empty Store Welcome view displayed when inventory contains zero items.
     *
     * @return styled empty-state {@link VBox}
     */
    private VBox buildEmptyStateBox() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));

        Label icon = new Label("📦");
        icon.setStyle("-fx-font-size: 54px;");
        Label heading = new Label("Your Inventory is Empty");
        heading.getStyleClass().add("page-title");
        Label hint = new Label("Add your first item manually or load starter store items to explore StockPro right away.");
        hint.getStyleClass().add("page-subtitle");

        Button addFirst = new Button("+ Add Your First Item");
        addFirst.getStyleClass().add("btn-primary");
        addFirst.setOnAction(e -> openAddProductDialog());

        Button loadDemo = new Button("Load Demo Items");
        loadDemo.getStyleClass().add("btn-secondary");
        loadDemo.setOnAction(e -> AppExecutor.runAsync(
                inventoryService::loadStarterDemoItems,
                count -> {
                    toastManager.showInfo("Loaded " + count + " starter items into SQLite.");
                    refreshAsync();
                },
                ex -> toastManager.showInfo("Failed to load starter items: " + ex.getMessage())
        ));

        HBox buttons = new HBox(12, addFirst, loadDemo);
        buttons.setAlignment(Pos.CENTER);

        box.getChildren().addAll(icon, heading, hint, buttons);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    /**
     * Reloads filtered catalog rows and chip counts asynchronously on the background executor.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(() -> {
            List<Product> all = inventoryService.getAllProducts();
            List<Product> filtered = inventoryService.filterProducts(criteria);
            return new Object[]{all, filtered};
        }, result -> {
            @SuppressWarnings("unchecked")
            List<Product> all = (List<Product>) result[0];
            @SuppressWarnings("unchecked")
            List<Product> filtered = (List<Product>) result[1];

            boolean emptyStore = all.isEmpty();
            emptyStateBox.setVisible(emptyStore);
            emptyStateBox.setManaged(emptyStore);
            tableCard.setVisible(!emptyStore);
            tableCard.setManaged(!emptyStore);

            chipAll.setText("All (" + all.size() + ")");
            Set<String> cats = new LinkedHashSet<>();
            cats.add("All Categories");
            for (Product p : all) {
                if (p.getCategory() != null && !p.getCategory().isBlank()) {
                    cats.add(p.getCategory());
                }
            }
            String prevCat = categoryFilter.getValue();
            categoryFilter.setItems(FXCollections.observableArrayList(cats));
            categoryFilter.setValue(cats.contains(prevCat) ? prevCat : "All Categories");

            tableRows.setAll(filtered);
            countSummaryLabel.setText("Showing " + filtered.size() + " of " + all.size() + " product(s)");
            updateBulkBarVisibility();
            if (onCatalogChanged != null) {
                onCatalogChanged.run();
            }
        }, ex -> {
        });
    }

    /**
     * Opens the Add Product modal dialog and persists validated entries with Undo toast feedback.
     */
    public void openAddProductDialog() {
        ProductFormDialog dialog = new ProductFormDialog();
        Optional<Product> created = dialog.showAndWait(
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                null,
                AppSession.getInstance().isDarkMode()
        );
        created.ifPresent(product -> {
            InventoryOperationResult<Product> res = inventoryService.addProduct(product);
            if (res.isSuccess()) {
                toastManager.showToast(res.getMessage(), "Undo", () -> {
                    inventoryService.undoLastAction();
                    refreshAsync();
                });
                refreshAsync();
            } else {
                toastManager.showInfo(res.getMessage());
            }
        });
    }

    /**
     * Opens the Edit Product dialog for an existing product.
     *
     * @param product target product
     */
    private void openEditProductDialog(Product product) {
        if (product == null) {
            return;
        }
        ProductFormDialog dialog = new ProductFormDialog();
        Optional<Product> edited = dialog.showAndWait(
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                product,
                AppSession.getInstance().isDarkMode()
        );
        edited.ifPresent(updated -> {
            InventoryOperationResult<Product> res = inventoryService.updateProduct(updated);
            if (res.isSuccess()) {
                toastManager.showToast(res.getMessage(), "Undo", () -> {
                    inventoryService.undoLastAction();
                    refreshAsync();
                });
                openDetailsDrawer(updated);
                refreshAsync();
            } else {
                toastManager.showInfo(res.getMessage());
            }
        });
    }

    /**
     * Opens the Delete/Archive confirmation popup for a product.
     *
     * @param product target product
     */
    private void confirmDeleteProduct(Product product) {
        if (product == null) {
            return;
        }
        if (!AppSession.getInstance().canDeleteOrApprove()) {
            toastManager.showInfo("Only Owner or Manager may delete or archive items.");
            return;
        }
        DeleteConfirmDialog dialog = new DeleteConfirmDialog();
        DeleteConfirmDialog.Choice choice = dialog.showAndWait(
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                product,
                AppSession.getInstance().isDarkMode()
        );
        if (choice == DeleteConfirmDialog.Choice.DELETE) {
            InventoryOperationResult<Product> res = inventoryService.deleteProduct(product.getId());
            if (res.isSuccess()) {
                closeDetailsDrawer();
                toastManager.showToast(res.getMessage(), "Undo", () -> {
                    inventoryService.undoLastAction();
                    refreshAsync();
                });
                refreshAsync();
            } else {
                toastManager.showInfo(res.getMessage());
            }
        } else if (choice == DeleteConfirmDialog.Choice.ARCHIVE_INSTEAD) {
            InventoryOperationResult<Product> res = inventoryService.setArchived(product.getId(), true);
            toastManager.showInfo(res.getMessage());
            closeDetailsDrawer();
            refreshAsync();
        }
    }

    /**
     * Opens the animated slide-out Product Details drawer for the chosen item.
     *
     * @param product target product
     */
    private void openDetailsDrawer(Product product) {
        detailsDrawer.setVisible(true);
        detailsDrawer.setManaged(true);
        detailsDrawer.showProduct(product);
    }

    /**
     * Closes the slide-out Product Details drawer.
     */
    private void closeDetailsDrawer() {
        detailsDrawer.setVisible(false);
        detailsDrawer.setManaged(false);
    }

    /**
     * Focuses the catalog search input (bound to {@code Ctrl+F}).
     */
    public void focusSearch() {
        if (searchField != null) {
            searchField.requestFocus();
        }
    }

    /**
     * Deletes the currently focused table row when {@code Delete} is pressed.
     */
    public void deleteSelectedRow() {
        if (tableView != null && tableView.getSelectionModel().getSelectedItem() != null) {
            confirmDeleteProduct(tableView.getSelectionModel().getSelectedItem());
        }
    }

    /**
     * Updates the visibility and item counter of the Bulk Multi-Select toolbar.
     */
    private void updateBulkBarVisibility() {
        int count = selectedIds.size();
        bulkCountLabel.setText(count + " item(s) selected:");
        bulkBar.setVisible(count > 0);
        bulkBar.setManaged(count > 0);
    }

    /**
     * Prompts for quick SKU or barcode input to jump straight to a matching item.
     */
    private void promptQuickBarcodeLookup() {
        javafx.scene.control.TextInputDialog dlg = new javafx.scene.control.TextInputDialog();
        dlg.setTitle("Scan Barcode / SKU");
        dlg.setHeaderText("Enter or scan a product barcode or SKU (e.g. PRD-1001)");
        dlg.showAndWait().ifPresent(code -> {
            Optional<Product> found = inventoryService.getProductRepository().findByBarcode(code);
            if (found.isPresent()) {
                openDetailsDrawer(found.get());
                toastManager.showInfo("Found: " + found.get().getName());
            } else {
                toastManager.showInfo("No item matched barcode '" + code + "'.");
            }
        });
    }

    /**
     * Exports selected products (or entire filtered list) to a CSV file.
     */
    private void exportSelectedToCsv() {
        List<Product> targets = tableRows.stream()
                .filter(p -> selectedIds.isEmpty() || selectedIds.contains(p.getId()))
                .toList();
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("stockpro-inventory.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        File file = chooser.showSaveDialog(rootPane.getScene() != null ? rootPane.getScene().getWindow() : null);
        if (file == null) {
            return;
        }
        AppExecutor.runAsync(() -> {
            StringBuilder sb = new StringBuilder("SKU,Name,Category,Quantity,BuyPrice,SellPrice,Status,Shelf,ExpiryDate\n");
            for (Product p : targets) {
                sb.append(String.format("%s,\"%s\",\"%s\",%d,%.2f,%.2f,%s,%s,%s\n",
                        p.getSku(),
                        p.getName().replace("\"", "\"\""),
                        p.getCategory() != null ? p.getCategory() : "",
                        p.getQuantity(),
                        p.getBuyPrice(),
                        p.getSellPrice(),
                        p.getStatus().getDisplayName(),
                        p.getShelfLocation() != null ? p.getShelfLocation() : "",
                        p.getExpiryDate() != null ? p.getExpiryDate() : ""));
            }
            try {
                Files.writeString(file.toPath(), sb.toString());
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to export CSV", ex);
            }
            return targets.size();
        }, count -> toastManager.showInfo("Exported " + count + " item(s) to " + file.getName()),
                ex -> toastManager.showInfo("Export failed: " + ex.getMessage()));
    }

    /**
     * Custom TableCell showing quantity alongside a mini level bar and supporting double-click inline editing.
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    private final class InlineQtyTableCell extends TableCell<Product, String> {
        private TextField editor;

        @Override
        public void startEdit() {
            super.startEdit();
            Product item = getTableRow() != null ? getTableRow().getItem() : null;
            if (item == null) {
                return;
            }
            editor = new TextField(String.valueOf(item.getQuantity()));
            editor.setOnAction(e -> commitQtyEdit(item, editor.getText()));
            editor.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                }
            });
            setGraphic(editor);
            setText(null);
            editor.selectAll();
            editor.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            renderReadView(getTableRow() != null ? getTableRow().getItem() : null);
        }

        @Override
        protected void updateItem(String itemText, boolean empty) {
            super.updateItem(itemText, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                setText(null);
            } else if (!isEditing()) {
                renderReadView(getTableRow().getItem());
            }
        }

        /**
         * Renders the quantity number and miniature progress indicator bar.
         *
         * @param product row product
         */
        private void renderReadView(Product product) {
            if (product == null) {
                setGraphic(null);
                return;
            }
            Label qtyLbl = new Label(String.valueOf(product.getQuantity()));
            qtyLbl.setMinWidth(32);
            if (product.getQuantity() <= product.getLowStockThreshold()) {
                qtyLbl.setStyle("-fx-text-fill: #DC2626; -fx-font-weight: bold;");
            } else {
                qtyLbl.setStyle("-fx-font-weight: bold;");
            }
            double cap = Math.max(50.0, product.getLowStockThreshold() * 5.0);
            ProgressBar bar = new ProgressBar(Math.min(1.0, product.getQuantity() / cap));
            bar.setPrefWidth(54);
            bar.setPrefHeight(7);
            HBox row = new HBox(6, qtyLbl, bar);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
            setText(null);
        }

        /**
         * Validates and persists an inline quantity edit via {@link InventoryService}.
         *
         * @param product target product
         * @param rawQty new raw quantity string
         */
        private void commitQtyEdit(Product product, String rawQty) {
            try {
                int newQty = ProductValidator.parseQuantity(rawQty);
                Product copy = Product.copyOf(product);
                copy.setQuantity(newQty);
                InventoryOperationResult<Product> res = inventoryService.updateProduct(copy);
                if (res.isSuccess()) {
                    commitEdit(String.valueOf(newQty));
                    toastManager.showToast("Updated quantity of " + product.getName() + " to " + newQty,
                            "Undo", () -> {
                                inventoryService.undoLastAction();
                                refreshAsync();
                            });
                    refreshAsync();
                } else {
                    cancelEdit();
                    toastManager.showInfo(res.getMessage());
                }
            } catch (IllegalArgumentException ex) {
                cancelEdit();
                toastManager.showInfo(ex.getMessage());
            }
        }
    }
}
