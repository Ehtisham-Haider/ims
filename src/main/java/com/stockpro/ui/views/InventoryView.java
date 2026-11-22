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
import com.stockpro.service.UsbBarcodeScannerListener;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ThemeManager;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleBooleanProperty;
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
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
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
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * High-speed Inventory Catalog screen matching {@code Screens and popups/Inventory.png},
 * with Feather vector action icons, guarded category filtering (zero infinite refresh loops),
 * double-click inline quantity edits, clean bulk selection without drawer collision,
 * custom Barcode Scanner Lookup/Restock/Sale dialog, and live Product Details drawer.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class InventoryView {
    private final ServiceContext services;
    private final InventoryService inventoryService;
    private final ToastNotificationManager toastManager;
    private final Runnable onCatalogChanged;

    private final ObservableList<Product> tableRows = FXCollections.observableArrayList();
    private final Set<Long> selectedIds = new HashSet<>();
    private final Map<String, Image> thumbnailCache = new ConcurrentHashMap<>();
    private final Set<Long> pendingImageFetches = ConcurrentHashMap.newKeySet();
    private final Consumer<String> eventListener = topic -> {
        if ("CATALOG_IMAGES_UPDATED".equals(topic) || "DEMO_DATA_LOADED".equals(topic) || "STORE_CLEARED".equals(topic)) {
            pendingImageFetches.clear();
            thumbnailCache.clear();
        }
        refreshAsync();
    };
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
    private boolean updatingFilters = false;
    private Product pendingInspectProduct;

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
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when leaving Inventory.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Updates embedded drawer forecast bars when Light/Dark theme toggles.
     */
    public void onThemeChanged() {
        if (detailsDrawer != null) {
            detailsDrawer.onThemeChanged();
        }
    }

    /**
     * Builds the Inventory view layout with table, empty-state card, bulk toolbar, and slide-out details drawer.
     *
     * @return JavaFX {@link Node}
     */
    public Node build() {
        rootPane = new BorderPane();

        VBox mainCol = new VBox(14);
        mainCol.setPadding(new Insets(0, 0, 8, 0));

        // Header Row
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleCol = new VBox(2);
        Label title = new Label("Inventory");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage your products, inline stock levels, barcodes, and details");
        subtitle.getStyleClass().add("page-subtitle");
        titleCol.getChildren().addAll(title, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button scanBtn = new Button("Scan Barcode");
        scanBtn.setGraphic(AppIcons.of(Feather.CAMERA, 14));
        scanBtn.getStyleClass().add("btn-secondary");
        scanBtn.setOnAction(e -> promptQuickBarcodeLookup());

        Button addBtn = new Button("Add Product");
        addBtn.setGraphic(AppIcons.of(Feather.PLUS, 14));
        addBtn.getStyleClass().add("btn-primary");
        addBtn.setOnAction(e -> openAddProductDialog());

        header.getChildren().addAll(titleCol, spacer, scanBtn, addBtn);

        HBox filterBar = buildFilterBar();

        tableCard = buildTableCard();
        emptyStateBox = buildEmptyStateBox();
        VBox.setVgrow(tableCard, Priority.ALWAYS);
        VBox.setVgrow(emptyStateBox, Priority.ALWAYS);

        StackPane centerStack = new StackPane(tableCard, emptyStateBox);
        VBox.setVgrow(centerStack, Priority.ALWAYS);

        mainCol.getChildren().addAll(header, filterBar, centerStack);
        rootPane.setCenter(mainCol);

        detailsDrawer = new ProductDetailsDrawer(
                services,
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
     * Selects a product in the catalog table and opens its Details Drawer immediately.
     *
     * @param product target product to inspect
     */
    public void selectAndInspectProduct(Product product) {
        if (product == null) {
            return;
        }
        this.pendingInspectProduct = product;
        if (detailsDrawer != null) {
            openDetailsDrawer(product);
        }
    }

    /**
     * Applies pre-configured {@link InventoryFilterCriteria} (from Smart Natural Search or Dashboard links)
     * and refreshes the table cleanly.
     *
     * @param external filter criteria to mirror
     */
    public void applyExternalCriteria(InventoryFilterCriteria external) {
        if (external == null) {
            return;
        }
        criteria.setStatusChip(external.getStatusChip());
        criteria.setCategory(external.getCategory());
        criteria.setSearchQuery(external.getSearchQuery());
        criteria.setMaxSellPrice(external.getMaxSellPrice());

        updatingFilters = true;
        try {
            if (searchField != null) {
                searchField.setText(external.getSearchQuery());
            }
            if (categoryFilter != null && external.getCategory() != null) {
                if (!categoryFilter.getItems().contains(external.getCategory())) {
                    categoryFilter.getItems().add(external.getCategory());
                }
                categoryFilter.setValue(external.getCategory());
            }
            updateActiveChipStyles(external.getStatusChip());
        } finally {
            updatingFilters = false;
        }
        refreshAsync();
    }

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
            if (!updatingFilters) {
                criteria.setSearchQuery(newVal);
                refreshAsync();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        categoryFilter = new ComboBox<>();
        categoryFilter.getItems().add("All Categories");
        categoryFilter.setValue("All Categories");
        categoryFilter.setOnAction(e -> {
            if (!updatingFilters) {
                criteria.setCategory(categoryFilter.getValue());
                refreshAsync();
            }
        });

        HBox row = new HBox(8, chipAll, chipIn, chipLow, chipOut, chipExpiring, spacer, searchField, categoryFilter);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button makeChip(String text, StatusFilterChip target, boolean active) {
        Button btn = new Button(text);
        btn.getStyleClass().add("filter-chip");
        if (active) {
            btn.getStyleClass().add("filter-chip-active");
        }
        btn.setOnAction(e -> {
            criteria.setStatusChip(target);
            criteria.setMaxSellPrice(null);
            updateActiveChipStyles(target);
            refreshAsync();
        });
        return btn;
    }

    private void updateActiveChipStyles(StatusFilterChip activeChip) {
        for (Button c : List.of(chipAll, chipIn, chipLow, chipOut, chipExpiring)) {
            if (c != null) {
                c.getStyleClass().remove("filter-chip-active");
            }
        }
        Button targetBtn = switch (activeChip) {
            case ALL -> chipAll;
            case IN_STOCK -> chipIn;
            case LOW_STOCK -> chipLow;
            case OUT_OF_STOCK -> chipOut;
            case EXPIRING -> chipExpiring;
        };
        if (targetBtn != null) {
            targetBtn.getStyleClass().add("filter-chip-active");
        }
    }

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
        imgCol.setMaxWidth(58);
        imgCol.setMinWidth(52);
        imgCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPhotoPath()));
        imgCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String photoPath, boolean empty) {
                super.updateItem(photoPath, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                Product rowProduct = getTableRow().getItem();
                String currentPath = rowProduct.getPhotoPath();
                if (currentPath != null && new File(currentPath).exists()) {
                    Image img = thumbnailCache.computeIfAbsent(
                            currentPath,
                            path -> new Image(new File(path).toURI().toString(), 68, 68, true, true, true));
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(34);
                    iv.setFitHeight(34);
                    iv.setPreserveRatio(true);
                    Rectangle clip = new Rectangle(34, 34);
                    clip.setArcWidth(8);
                    clip.setArcHeight(8);
                    iv.setClip(clip);
                    setGraphic(iv);
                } else {
                    setGraphic(AppIcons.of(Feather.PACKAGE, 18, "#6366F1"));
                }

                var imgService = services.getProductImageService();
                if (imgService.needsImageResolution(currentPath) && pendingImageFetches.add(rowProduct.getId())) {
                    AppExecutor.runAsync(
                            () -> imgService.ensureProductImageAndPersist(rowProduct),
                            resolvedPath -> {
                                if (resolvedPath != null && !resolvedPath.equals(currentPath)) {
                                    thumbnailCache.remove(resolvedPath);
                                    rowProduct.setPhotoPath(resolvedPath);
                                    if (tableView != null) {
                                        tableView.refresh();
                                    }
                                }
                            },
                            ex -> {
                            });
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
        catCol.setMinWidth(90);
        catCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCategory()));

        // 6. Quantity + Mini Stock Progress Bar (double-click to edit inline)
        TableColumn<Product, String> qtyCol = new TableColumn<>("Qty (2× Click)");
        qtyCol.setMinWidth(115);
        qtyCol.setEditable(true);
        qtyCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        qtyCol.setCellFactory(col -> new InlineQtyTableCell());

        // 7. Buy Price Column
        TableColumn<Product, String> buyCol = new TableColumn<>("Buy Price");
        buyCol.setMinWidth(80);
        buyCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getBuyPrice())));

        // 8. Sell Price Column
        TableColumn<Product, String> sellCol = new TableColumn<>("Sell Price");
        sellCol.setMinWidth(80);
        sellCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getSellPrice())));

        // 9. Expiry Date Column
        TableColumn<Product, String> expCol = new TableColumn<>("Expiry Date");
        expCol.setMinWidth(92);
        expCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getExpiryDate() != null ? c.getValue().getExpiryDate().toString() : "—"));

        // 10. Status Pill Column
        TableColumn<Product, String> statusCol = new TableColumn<>("Status");
        statusCol.setMinWidth(108);
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

        // 11. Feather Action Buttons Column
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

                Button editBtn = new Button();
                editBtn.setGraphic(AppIcons.of(Feather.EDIT_2, 14));
                editBtn.getStyleClass().add("icon-btn");
                editBtn.setTooltip(new Tooltip("Edit Product"));
                editBtn.setOnAction(e -> openEditProductDialog(product));

                Button delBtn = new Button();
                delBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 14));
                delBtn.getStyleClass().add("icon-btn-danger");
                delBtn.setTooltip(new Tooltip("Delete or Archive"));
                delBtn.setOnAction(e -> confirmDeleteProduct(product));

                Button infoBtn = new Button();
                infoBtn.setGraphic(AppIcons.of(Feather.EYE, 14));
                infoBtn.getStyleClass().add("icon-btn");
                infoBtn.setTooltip(new Tooltip("Open Product Details"));
                infoBtn.setOnAction(e -> openDetailsDrawer(product));

                HBox box = new HBox(2, editBtn, delBtn, infoBtn);
                box.setAlignment(Pos.CENTER);
                setGraphic(box);
            }
        });

        tableView.getColumns().addAll(List.of(
                selectCol, imgCol, skuCol, nameCol, catCol, qtyCol, buyCol, sellCol, expCol, statusCol, actionCol
        ));

        // Open details drawer ONLY when clicking non-interactive row content (never stealing checkbox or action clicks)
        tableView.setRowFactory(tv -> {
            TableRow<Product> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (row.isEmpty() || row.getItem() == null) {
                    return;
                }
                Node target = e.getTarget() instanceof Node n ? n : null;
                while (target != null && target != row) {
                    if (target instanceof Button || target instanceof javafx.scene.control.CheckBox || target instanceof TextField) {
                        return;
                    }
                    target = target.getParent();
                }
                if (e.getClickCount() == 1) {
                    openDetailsDrawer(row.getItem());
                }
            });
            return row;
        });

        // Footer Summary + Bulk Selection Bar
        countSummaryLabel = new Label("Showing 0 items");
        countSummaryLabel.getStyleClass().add("muted-text");

        bulkCountLabel = new Label("0 item(s) selected:");
        bulkCountLabel.setStyle("-fx-font-weight: bold;");

        ComboBox<StockStatus> bulkStatusBox = new ComboBox<>();
        bulkStatusBox.getItems().addAll(StockStatus.IN_STOCK, StockStatus.LOW_STOCK, StockStatus.OUT_OF_STOCK);
        bulkStatusBox.setPromptText("Update Status");
        bulkStatusBox.setOnAction(e -> {
            if (updatingFilters) {
                return;
            }
            StockStatus target = bulkStatusBox.getValue();
            if (target != null && !selectedIds.isEmpty()) {
                InventoryOperationResult<Integer> res = inventoryService.bulkUpdateStatus(
                        new ArrayList<>(selectedIds), target, AppSession.getInstance().canManageInventory());
                toastManager.showInfo(res.getMessage());
                selectedIds.clear();
                updatingFilters = true;
                bulkStatusBox.setValue(null);
                updatingFilters = false;
                refreshAsync();
            }
        });

        Button bulkDeleteBtn = new Button("Delete Selected");
        bulkDeleteBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 13));
        bulkDeleteBtn.getStyleClass().add("btn-danger-outline");
        bulkDeleteBtn.setOnAction(e -> {
            InventoryOperationResult<Integer> res = inventoryService.bulkDelete(
                    new ArrayList<>(selectedIds), AppSession.getInstance().canDeleteOrApprove());
            toastManager.showInfo(res.getMessage());
            selectedIds.clear();
            refreshAsync();
        });

        Button bulkExportBtn = new Button("Export CSV");
        bulkExportBtn.setGraphic(AppIcons.of(Feather.DOWNLOAD, 13));
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

    private VBox buildEmptyStateBox() {
        VBox box = new VBox(14);
        box.getStyleClass().add("card");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));

        StackPane iconWrap = new StackPane(AppIcons.of(Feather.PACKAGE, 46, "#6366F1"));
        iconWrap.setMinSize(84, 84);
        iconWrap.setPrefSize(84, 84);
        iconWrap.setMaxSize(84, 84);
        iconWrap.getStyleClass().add("surface-subtle");

        Label heading = new Label("Your Inventory is Empty");
        heading.getStyleClass().add("page-title");
        Label hint = new Label("Add your first item manually or load starter store items to explore StockPro right away.");
        hint.getStyleClass().add("page-subtitle");

        Button addFirst = new Button("Add Your First Item");
        addFirst.setGraphic(AppIcons.of(Feather.PLUS, 14));
        addFirst.getStyleClass().add("btn-primary");
        addFirst.setOnAction(e -> openAddProductDialog());

        Button loadDemo = new Button("Load Demo Items");
        loadDemo.setGraphic(AppIcons.of(Feather.PLAY, 14));
        loadDemo.getStyleClass().add("btn-secondary");
        loadDemo.setOnAction(e -> AppExecutor.runAsync(
                () -> {
                    int loaded = inventoryService.loadStarterDemoItems();
                    if (services.getProductImageService().isApiKeyConfigured()) {
                        services.getProductImageService().enrichMissingCatalogImages();
                    }
                    return loaded;
                },
                count -> {
                    toastManager.showInfo("Loaded " + count + " starter items into SQLite.");
                    pendingImageFetches.clear();
                    refreshAsync();
                },
                ex -> toastManager.showInfo("Failed to load starter items: " + ex.getMessage())
        ));

        HBox buttons = new HBox(12, addFirst, loadDemo);
        buttons.setAlignment(Pos.CENTER);

        box.getChildren().addAll(iconWrap, heading, hint, buttons);
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
            updatingFilters = true;
            try {
                categoryFilter.setItems(FXCollections.observableArrayList(cats));
                categoryFilter.setValue(cats.contains(prevCat) ? prevCat : "All Categories");
            } finally {
                updatingFilters = false;
            }

            tableRows.setAll(filtered);
            countSummaryLabel.setText("Showing " + filtered.size() + " of " + all.size() + " product(s)");
            updateBulkBarVisibility();
            if (pendingInspectProduct != null) {
                Product match = filtered.stream()
                        .filter(p -> p.getId() == pendingInspectProduct.getId())
                        .findFirst().orElse(pendingInspectProduct);
                tableView.getSelectionModel().select(match);
                openDetailsDrawer(match);
                pendingInspectProduct = null;
            }
            if (onCatalogChanged != null) {
                onCatalogChanged.run();
            }
        }, ex -> toastManager.showInfo("Failed to refresh inventory: " + ex.getMessage()));
    }

    /**
     * Opens the Add Product modal dialog and persists validated entries with Undo toast feedback.
     */
    public void openAddProductDialog() {
        ProductFormDialog dialog = new ProductFormDialog();
        Optional<Product> created = dialog.showAndWait(
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                null,
                AppSession.getInstance().isDarkMode(),
                services.getProductImageService()
        );
        created.ifPresent(product -> {
            InventoryOperationResult<Product> res = inventoryService.addProduct(product);
            if (res.isSuccess()) {
                Product saved = res.getData();
                if (saved != null && !dialog.getCustomFieldsResult().isEmpty()) {
                    services.getCatalogVariantBomService().saveCustomFields(
                            saved.getId(), dialog.getCustomFieldsResult());
                }
                toastManager.showToast(res.getMessage(), "Undo", () -> {
                    inventoryService.undoLastAction();
                    refreshAsync();
                });
                if (saved != null && services.getProductImageService().needsImageResolution(saved.getPhotoPath())) {
                    AppExecutor.runAsync(
                            () -> services.getProductImageService().ensureProductImageAndPersist(saved),
                            p -> refreshAsync(),
                            ex -> {
                            });
                }
                refreshAsync();
            } else {
                toastManager.showInfo(res.getMessage());
            }
        });
    }

    private void openEditProductDialog(Product product) {
        if (product == null) {
            return;
        }
        ProductFormDialog dialog = new ProductFormDialog();
        Optional<Product> edited = dialog.showAndWait(
                rootPane.getScene() != null ? rootPane.getScene().getWindow() : null,
                product,
                AppSession.getInstance().isDarkMode(),
                services.getProductImageService()
        );
        edited.ifPresent(updated -> {
            InventoryOperationResult<Product> res = inventoryService.updateProduct(updated);
            if (res.isSuccess()) {
                if (!dialog.getCustomFieldsResult().isEmpty()) {
                    services.getCatalogVariantBomService().saveCustomFields(
                            updated.getId(), dialog.getCustomFieldsResult());
                }
                thumbnailCache.clear();
                pendingImageFetches.remove(updated.getId());
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

    private void openDetailsDrawer(Product product) {
        detailsDrawer.setVisible(true);
        detailsDrawer.setManaged(true);
        detailsDrawer.showProduct(product);
    }

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

    private void updateBulkBarVisibility() {
        int count = selectedIds.size();
        bulkCountLabel.setText(count + " item(s) selected:");
        bulkBar.setVisible(count > 0);
        bulkBar.setManaged(count > 0);
    }

    /**
     * Custom Light/Dark themed Barcode Scanner dialog allowing SKU lookup, +1 Restock, or -1 Sale.
     */
    private void promptQuickBarcodeLookup() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (rootPane != null && rootPane.getScene() != null) {
            dialog.initOwner(rootPane.getScene().getWindow());
        }
        dialog.setTitle("Scan Barcode / SKU");

        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));

        HBox hdr = new HBox(10, AppIcons.of(Feather.CAMERA, 18, "#6366F1"));
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Barcode / SKU Scanner");
        heading.getStyleClass().add("section-title");
        hdr.getChildren().add(heading);

        TextField codeField = new TextField();
        codeField.setPromptText("Enter or scan barcode / SKU (e.g. PRD-1001)...");

        ComboBox<UsbBarcodeScannerListener.ScanMode> modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll(
                UsbBarcodeScannerListener.ScanMode.LOOKUP,
                UsbBarcodeScannerListener.ScanMode.STOCK_IN,
                UsbBarcodeScannerListener.ScanMode.SALE_DEDUCT);
        modeCombo.setValue(services.getUsbBarcodeScannerListener().getActiveMode());
        modeCombo.setMaxWidth(Double.MAX_VALUE);

        Runnable submitScan = () -> {
            String code = codeField.getText() != null ? codeField.getText().trim() : "";
            if (code.isBlank()) {
                toastManager.showInfo("Enter a barcode or SKU first.");
                return;
            }
            var mode = modeCombo.getValue();
            services.getUsbBarcodeScannerListener().setActiveMode(mode);
            var outcome = services.getUsbBarcodeScannerListener().handleScannedCode(
                    code, mode, AppSession.getInstance().getActorName());
            toastManager.showInfo(outcome.message());
            if (outcome.matched() && outcome.product() != null) {
                dialog.close();
                openDetailsDrawer(outcome.product());
                refreshAsync();
            }
        };

        codeField.setOnAction(e -> submitScan.run());

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> dialog.close());

        Button goBtn = new Button("Scan / Apply");
        goBtn.getStyleClass().add("btn-primary");
        goBtn.setOnAction(e -> submitScan.run());

        HBox footer = new HBox(10, cancelBtn, goBtn);
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(
                hdr,
                new Label("Barcode / SKU"), codeField,
                new Label("Scanner Action Mode"), modeCombo,
                footer
        );

        Scene sc = new Scene(card, 400, 240);
        ThemeManager.apply(sc);
        dialog.setScene(sc);
        dialog.showAndWait();
    }

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
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
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

        private void renderReadView(Product product) {
            if (product == null) {
                setGraphic(null);
                return;
            }
            Label qtyLbl = new Label(String.valueOf(product.getQuantity()));
            qtyLbl.setMinWidth(32);
            if (product.getQuantity() <= product.getLowStockThreshold()) {
                qtyLbl.getStyleClass().add("kpi-delta-danger");
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
