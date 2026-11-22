package com.stockpro.ui.views;

import com.stockpro.domain.AuditLogEntry;
import com.stockpro.domain.BranchStockRecord;
import com.stockpro.domain.CustomerBackorder;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.StoreBranch;
import com.stockpro.service.AppSession;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ProductValidator;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.StockOperationService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Stock Operations screen matching {@code Screens and popups/Stock.png}, featuring Feather icon tabs,
 * live stock change preview using theme-safe CSS badges, mandatory reason codes, colored movement type pills,
 * damaged stock quarantine, FEFO expiry tracking, SHA-256 tamper-proof audit verification, and V2 Multi-Branch/RMA controls.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class StockOperationsView {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM dd, hh:mm a");
    private static final List<String> PREVIEW_CLASSES = List.of("preview-banner-info", "preview-banner-ok", "preview-banner-err");

    private final ServiceContext services;
    private final StockOperationService stockService;
    private final AuditService auditService;
    private final ToastNotificationManager toastManager;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private final Map<MovementType, Button> modeTabs = new LinkedHashMap<>();
    private MovementType activeMode = MovementType.RESTOCK;

    private ComboBox<Product> productPicker;
    private boolean updatingUi = false;
    private TextField qtyField;
    private Label qtyCaption;
    private Label previewLabel;
    private ComboBox<String> reasonBox;
    private TextArea notesArea;
    private Button submitBtn;
    private Label formHeading;

    private final ObservableList<StockMovement> recentMovements = FXCollections.observableArrayList();
    private final ObservableList<Product> expiringProducts = FXCollections.observableArrayList();
    private final ObservableList<Product> damagedProducts = FXCollections.observableArrayList();
    private final ObservableList<AuditLogEntry> auditEntries = FXCollections.observableArrayList();
    private Label sealStatusBadge;

    /**
     * Constructs the Stock Operations workspace view.
     *
     * @param services shared application services
     * @param toastManager notification banner helper
     */
    public StockOperationsView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.stockService = services.getStockOperationService();
        this.auditService = services.getAuditService();
        this.toastManager = toastManager;
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when navigating away from Stock Operations.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Builds the Stock Operations view node.
     *
     * @return scrollable JavaFX node
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(0, 4, 16, 0));

        Label title = new Label("Stock Operations");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage stock movements, damaged quarantine, physical shelf counts, FEFO expiry, and SHA-256 audit seal");
        subtitle.getStyleClass().add("page-subtitle");

        // 6 Operation Tabs with Feather vector icons
        HBox tabStrip = new HBox(10);
        tabStrip.setAlignment(Pos.CENTER_LEFT);
        MovementType[] modes = {
                MovementType.RESTOCK,
                MovementType.SALE,
                MovementType.DAMAGED,
                MovementType.RETURNED,
                MovementType.LOST,
                MovementType.PHYSICAL_COUNT
        };
        for (MovementType m : modes) {
            Button tabBtn = new Button(m == MovementType.SALE ? "Record Sale" : m.getDisplayName());
            tabBtn.setGraphic(AppIcons.of(featherFor(m), 14));
            tabBtn.setGraphicTextGap(7);
            tabBtn.getStyleClass().add("filter-chip");
            tabBtn.setOnAction(e -> switchMode(m));
            modeTabs.put(m, tabBtn);
            tabStrip.getChildren().add(tabBtn);
        }

        VBox formCard = buildOperationFormCard();
        formCard.setPrefWidth(390);
        formCard.setMinWidth(345);

        VBox rightCol = buildRightLedgerPanel();
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox bodyRow = new HBox(18, formCard, rightCol);
        bodyRow.setAlignment(Pos.TOP_LEFT);

        page.getChildren().addAll(title, subtitle, tabStrip, bodyRow);

        switchMode(MovementType.RESTOCK);
        refreshAsync();

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private VBox buildOperationFormCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        formHeading = new Label("Restock Product");
        formHeading.getStyleClass().add("section-title");
        formHeading.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label prodLbl = new Label("Product *");
        prodLbl.setStyle("-fx-font-weight: bold;");
        productPicker = new ComboBox<>();
        productPicker.setMaxWidth(Double.MAX_VALUE);
        productPicker.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getSku() + " • Qty: " + item.getQuantity() + ")");
            }
        });
        productPicker.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select a product..." : item.getName() + " (" + item.getSku() + ")");
            }
        });

        qtyCaption = new Label("Quantity *");
        qtyCaption.setStyle("-fx-font-weight: bold;");
        qtyField = new TextField("10");

        previewLabel = new Label("Select a product to preview change");
        previewLabel.setWrapText(true);
        previewLabel.getStyleClass().add("preview-banner-info");

        Label reasonLbl = new Label("Reason * (Mandatory)");
        reasonLbl.setStyle("-fx-font-weight: bold;");
        reasonBox = new ComboBox<>();
        reasonBox.setEditable(true);
        reasonBox.setMaxWidth(Double.MAX_VALUE);

        Label notesLbl = new Label("Notes (Optional)");
        notesLbl.setStyle("-fx-font-weight: bold;");
        notesArea = new TextArea();
        notesArea.setPromptText("Invoice #, batch note, or audit comment...");
        notesArea.setPrefRowCount(3);

        submitBtn = new Button("Add Restock Entry");
        submitBtn.getStyleClass().add("btn-primary");
        submitBtn.setMaxWidth(Double.MAX_VALUE);
        submitBtn.setOnAction(e -> submitOperation());

        productPicker.setOnAction(e -> {
            if (!updatingUi) {
                updateLivePreview();
            }
        });
        qtyField.textProperty().addListener((o, a, b) -> updateLivePreview());

        card.getChildren().addAll(
                formHeading,
                prodLbl, productPicker,
                qtyCaption, qtyField,
                previewLabel,
                reasonLbl, reasonBox,
                notesLbl, notesArea,
                submitBtn
        );
        return card;
    }

    private VBox buildRightLedgerPanel() {
        VBox wrapper = new VBox(14);

        HBox sealBar = new HBox(12);
        sealBar.getStyleClass().add("card");
        sealBar.setAlignment(Pos.CENTER_LEFT);
        sealStatusBadge = new Label("SHA-256 Audit Seal Ready");
        sealStatusBadge.setGraphic(AppIcons.of(Feather.SHIELD, 16, "#10B981"));
        sealStatusBadge.setGraphicTextGap(8);
        sealStatusBadge.getStyleClass().add("kpi-delta-positive");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button verifyBtn = new Button("Verify Seal");
        verifyBtn.setGraphic(AppIcons.of(Feather.CHECK_CIRCLE, 14));
        verifyBtn.getStyleClass().add("btn-primary");
        verifyBtn.setOnAction(e -> runSealVerification());
        sealBar.getChildren().addAll(sealStatusBadge, sp, verifyBtn);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // Tab 1: Recent Stock Movements with colored action pills matching Stock.png
        TableView<StockMovement> moveTable = new TableView<>(recentMovements);
        moveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        moveTable.setPrefHeight(340);

        TableColumn<StockMovement, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCreatedAt() != null ? c.getValue().getCreatedAt().format(TIME_FMT) : ""));
        TableColumn<StockMovement, String> prodCol = new TableColumn<>("Product");
        prodCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProductName()));
        TableColumn<StockMovement, String> typeCol = new TableColumn<>("Type");
        typeCol.setMinWidth(105);
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getMovementType().getDisplayName()));
        typeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty || text == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                MovementType mt = getTableRow().getItem().getMovementType();
                Label pill = new Label(text);
                pill.getStyleClass().addAll("status-pill", switch (mt) {
                    case RESTOCK -> "status-in-stock";
                    case SALE -> "status-info-pill";
                    case RETURNED, PHYSICAL_COUNT -> "status-low-stock";
                    default -> "status-out-of-stock";
                });
                setGraphic(pill);
            }
        });
        TableColumn<StockMovement, String> deltaCol = new TableColumn<>("Qty");
        deltaCol.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().getQuantityDelta() >= 0 ? "+" : "") + c.getValue().getQuantityDelta()));
        TableColumn<StockMovement, String> userCol = new TableColumn<>("User");
        userCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPerformedBy()));
        TableColumn<StockMovement, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getReason()));

        moveTable.getColumns().addAll(List.of(timeCol, prodCol, typeCol, deltaCol, userCol, reasonCol));

        // Tab 2: FEFO Expiry Tracker
        TableView<Product> fefoTable = new TableView<>(expiringProducts);
        fefoTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Product, String> fSku = new TableColumn<>("SKU");
        fSku.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSku()));
        TableColumn<Product, String> fName = new TableColumn<>("Product (FEFO Priority)");
        fName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Product, String> fQty = new TableColumn<>("Qty");
        fQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<Product, String> fExp = new TableColumn<>("Expiry Date");
        fExp.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getExpiryDate() != null ? c.getValue().getExpiryDate().toString() : "—"));
        TableColumn<Product, String> fUrgency = new TableColumn<>("FEFO Alert");
        fUrgency.setCellValueFactory(c -> {
            LocalDate exp = c.getValue().getExpiryDate();
            if (exp == null) {
                return new SimpleStringProperty("—");
            }
            long days = ChronoUnit.DAYS.between(LocalDate.now(), exp);
            if (days < 0) {
                return new SimpleStringProperty("EXPIRED (" + Math.abs(days) + "d ago)");
            }
            if (days <= 7) {
                return new SimpleStringProperty("SELL FIRST (" + days + "d left)");
            }
            return new SimpleStringProperty("Expiring in " + days + "d");
        });
        fUrgency.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                if (empty || text == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label(text);
                pill.getStyleClass().addAll("status-pill",
                        text.contains("EXPIRED") || text.contains("FIRST") ? "status-out-of-stock" : "status-low-stock");
                setGraphic(pill);
            }
        });
        fefoTable.getColumns().addAll(List.of(fSku, fName, fQty, fExp, fUrgency));

        // Tab 3: Damaged Goods Quarantine
        TableView<Product> dmgTable = new TableView<>(damagedProducts);
        dmgTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<Product, String> dSku = new TableColumn<>("SKU");
        dSku.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSku()));
        TableColumn<Product, String> dName = new TableColumn<>("Product");
        dName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Product, String> dSellable = new TableColumn<>("Sellable Qty");
        dSellable.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<Product, String> dDamaged = new TableColumn<>("Damaged / Quarantine Qty");
        dDamaged.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getDamagedQty())));
        dmgTable.getColumns().addAll(List.of(dSku, dName, dSellable, dDamaged));

        // Tab 4: SHA-256 Audit Log
        TableView<AuditLogEntry> auditTable = new TableView<>(auditEntries);
        auditTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<AuditLogEntry, String> aAct = new TableColumn<>("Action");
        aAct.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getActionType()));
        TableColumn<AuditLogEntry, String> aActor = new TableColumn<>("Actor");
        aActor.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getActor()));
        TableColumn<AuditLogEntry, String> aRef = new TableColumn<>("Ref");
        aRef.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEntityRef()));
        TableColumn<AuditLogEntry, String> aDetails = new TableColumn<>("Details");
        aDetails.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDetails()));
        TableColumn<AuditLogEntry, String> aHash = new TableColumn<>("SHA-256 Seal");
        aHash.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCurrHash() != null ? c.getValue().getCurrHash().substring(0, 14) + "…" : ""));
        auditTable.getColumns().addAll(List.of(aAct, aActor, aRef, aDetails, aHash));

        tabs.getTabs().addAll(
                new Tab("Recent Movements", moveTable),
                new Tab("Expiry Tracker (FEFO)", fefoTable),
                new Tab("Damaged Quarantine", dmgTable),
                new Tab("SHA-256 Audit Ledger", auditTable),
                new Tab("Transfers, RMA & ATP", buildEnterpriseOpsPane())
        );

        VBox tabCard = new VBox(tabs);
        tabCard.getStyleClass().add("card");
        VBox.setVgrow(tabCard, Priority.ALWAYS);

        wrapper.getChildren().addAll(sealBar, tabCard);
        return wrapper;
    }

    private Node buildEnterpriseOpsPane() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(12, 4, 8, 4));

        // 1. Inter-branch Transfer + RMA + Backorder Quick Controls
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);

        ComboBox<Product> skuBox = new ComboBox<>();
        skuBox.setPrefWidth(190);
        skuBox.setPromptText("Product");
        TextField qtyInput = new TextField("1");
        qtyInput.setPrefWidth(65);
        ComboBox<String> rmaDispBox = new ComboBox<>();
        rmaDispBox.getItems().addAll("RESTOCK_SELLABLE", "MARK_DAMAGED", "SCRAP");
        rmaDispBox.setValue("RESTOCK_SELLABLE");

        Button rmaBtn = new Button("Process Customer RMA");
        rmaBtn.getStyleClass().add("btn-primary");
        Label statusLbl = new Label("Branch & ATP live");
        statusLbl.getStyleClass().add("muted-text");

        Button backorderBtn = new Button("Log Backorder");
        backorderBtn.getStyleClass().add("btn-secondary");

        ComboBox<StoreBranch> toBranchBox = new ComboBox<>();
        toBranchBox.setPrefWidth(145);
        Button trfBtn = new Button("Transfer to Branch");
        trfBtn.getStyleClass().add("btn-secondary");

        controls.getChildren().addAll(skuBox, qtyInput, rmaDispBox, rmaBtn, backorderBtn, toBranchBox, trfBtn);

        TableView<BranchStockRecord> atpTable = new TableView<>();
        atpTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        atpTable.setPrefHeight(220);
        TableColumn<BranchStockRecord, String> bSku = new TableColumn<>("SKU");
        bSku.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        TableColumn<BranchStockRecord, String> bName = new TableColumn<>("Product");
        bName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productName()));
        TableColumn<BranchStockRecord, String> bOnHand = new TableColumn<>("On-Hand");
        bOnHand.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().onHandQty())));
        TableColumn<BranchStockRecord, String> bRes = new TableColumn<>("Reserved");
        bRes.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().reservedQty())));
        TableColumn<BranchStockRecord, String> bAtp = new TableColumn<>("ATP");
        bAtp.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().availableToPromise())));
        atpTable.getColumns().addAll(List.of(bSku, bName, bOnHand, bRes, bAtp));

        Runnable reloadPane = () -> AppExecutor.runAsync(() -> {
            long bid = AppSession.getInstance().getActiveBranchId();
            List<Product> all = services.getInventoryService().getAllProducts();
            List<BranchStockRecord> stock = services.getMultiBranchTransferRmaService().listBranchStock(bid);
            List<StoreBranch> branches = services.getMultiBranchTransferRmaService().listBranches();
            List<CustomerBackorder> wait = services.getMultiBranchTransferRmaService().listBackorders();
            double val = services.getMultiBranchTransferRmaService().computeCompanyValuationIncludingInTransit();
            return new Object[]{all, stock, branches, wait, val};
        }, res -> {
            @SuppressWarnings("unchecked")
            List<Product> all = (List<Product>) res[0];
            @SuppressWarnings("unchecked")
            List<BranchStockRecord> stock = (List<BranchStockRecord>) res[1];
            @SuppressWarnings("unchecked")
            List<StoreBranch> branches = (List<StoreBranch>) res[2];
            @SuppressWarnings("unchecked")
            List<CustomerBackorder> wait = (List<CustomerBackorder>) res[3];
            double val = (Double) res[4];
            skuBox.getItems().setAll(all.stream().limit(100).toList());
            if (skuBox.getValue() == null && !all.isEmpty()) {
                skuBox.setValue(all.get(0));
            }
            toBranchBox.getItems().setAll(branches);
            if (toBranchBox.getValue() == null && branches.size() > 1) {
                toBranchBox.setValue(branches.get(1));
            }
            atpTable.getItems().setAll(stock);
            long readyCount = wait.stream().filter(w -> "READY_NOTIFY".equals(w.status())).count();
            statusLbl.setText(String.format("Enterprise Valuation (incl. In-Transit): %s | Waiting Backorders: %d (%d Ready)",
                    AppSession.getInstance().formatMoney(val), wait.size(), readyCount));
        }, ex -> {
        });

        rmaBtn.setOnAction(e -> {
            Product p = skuBox.getValue();
            if (p == null) {
                return;
            }
            int q;
            try {
                q = Integer.parseInt(qtyInput.getText().trim());
            } catch (NumberFormatException ex) {
                toastManager.showInfo("Enter valid numeric quantity.");
                return;
            }
            AppExecutor.runAsync(
                    () -> services.getMultiBranchTransferRmaService().processReturnRma(
                            p.getId(), AppSession.getInstance().getActiveBranchId(), q,
                            rmaDispBox.getValue(), "Customer RMA desk", AppSession.getInstance().getActorName()),
                    r -> {
                        toastManager.showInfo(r.getMessage());
                        reloadPane.run();
                    },
                    ex -> toastManager.showInfo(ex.getMessage()));
        });

        backorderBtn.setOnAction(e -> {
            Product p = skuBox.getValue();
            if (p == null) {
                return;
            }
            AppExecutor.runAsync(
                    () -> services.getMultiBranchTransferRmaService().recordBackorder(
                            p.getId(), "Walk-in Customer", "counter@store.local", 1),
                    r -> {
                        toastManager.showInfo(r.getMessage());
                        reloadPane.run();
                    },
                    ex -> {
                    });
        });

        trfBtn.setOnAction(e -> {
            Product p = skuBox.getValue();
            StoreBranch target = toBranchBox.getValue();
            if (p == null || target == null) {
                return;
            }
            int q;
            try {
                q = Integer.parseInt(qtyInput.getText().trim());
            } catch (NumberFormatException ex) {
                toastManager.showInfo("Enter valid numeric quantity.");
                return;
            }
            AppExecutor.runAsync(() -> {
                InventoryOperationResult<com.stockpro.domain.StockTransfer> tr =
                        services.getMultiBranchTransferRmaService().createTransfer(
                                AppSession.getInstance().getActiveBranchId(), target.id(),
                                p.getId(), q, "Branch rebalance", AppSession.getInstance().getActorName());
                if (tr.isSuccess()) {
                    services.getMultiBranchTransferRmaService().dispatchTransfer(
                            tr.getData().id(), AppSession.getInstance().getActorName());
                }
                return tr;
            }, r -> {
                toastManager.showInfo(r.getMessage());
                reloadPane.run();
            }, ex -> toastManager.showInfo(ex.getMessage()));
        });

        reloadPane.run();
        box.getChildren().addAll(statusLbl, controls, atpTable);
        return box;
    }

    private void switchMode(MovementType mode) {
        this.activeMode = mode;
        modeTabs.forEach((m, btn) -> {
            btn.getStyleClass().remove("filter-chip-active");
            if (m == mode) {
                btn.getStyleClass().add("filter-chip-active");
            }
        });

        formHeading.setText(switch (mode) {
            case RESTOCK -> "Restock Product";
            case SALE -> "Record Sale";
            case DAMAGED -> "Log Damaged Goods";
            case RETURNED -> "Customer Return";
            case LOST -> "Record Lost / Shrinkage";
            case PHYSICAL_COUNT -> "Live Physical Shelf Count";
            default -> "Stock Adjustment";
        });
        qtyCaption.setText(mode == MovementType.PHYSICAL_COUNT ? "Counted Shelf Quantity *" : "Quantity *");
        submitBtn.setText("Confirm " + (mode == MovementType.SALE ? "Sale" : mode.getDisplayName()));

        reasonBox.getItems().setAll(switch (mode) {
            case RESTOCK -> List.of("Restock from Supplier", "Warehouse Transfer", "Opening Balance");
            case SALE -> List.of("POS Customer Sale", "Bulk Order Dispatch", "Online Fulfillment");
            case DAMAGED -> List.of("Broken Packaging", "Expired Shelf Batch", "Water/Transit Damage");
            case RETURNED -> List.of("Customer Return (Resellable)", "Unused Exchange");
            case LOST -> List.of("Inventory Shrinkage", "Misplaced During Audit", "Theft Suspected");
            case PHYSICAL_COUNT -> List.of("Weekly Shelf Audit", "Cycle Count Reconciliation");
            default -> List.of("Manual Adjustment");
        });
        reasonBox.setValue(reasonBox.getItems().get(0));
        updateLivePreview();
    }

    private void updateLivePreview() {
        Product selected = productPicker.getValue();
        previewLabel.getStyleClass().removeAll(PREVIEW_CLASSES);
        if (selected == null) {
            previewLabel.setText("Select a product to preview stock change.");
            previewLabel.getStyleClass().add("preview-banner-info");
            return;
        }
        try {
            int qty = ProductValidator.parseQuantity(qtyField.getText());
            StockOperationService.StockPreview preview = stockService.previewOperation(selected, activeMode, qty);
            previewLabel.setText(preview.note());
            previewLabel.getStyleClass().add(preview.valid() ? "preview-banner-ok" : "preview-banner-err");
        } catch (IllegalArgumentException ex) {
            previewLabel.setText(ex.getMessage());
            previewLabel.getStyleClass().add("preview-banner-err");
        }
    }

    private void submitOperation() {
        Product selected = productPicker.getValue();
        if (selected == null) {
            toastManager.showInfo("Please select a product first.");
            return;
        }
        String reason = reasonBox.getEditor().getText();
        String rawQty = qtyField.getText();
        String notes = notesArea.getText();
        String actor = AppSession.getInstance().getActorName();

        AppExecutor.runAsync(
                () -> stockService.recordOperation(selected.getId(), activeMode, rawQty, reason, notes, actor),
                res -> {
                    toastManager.showInfo(res.getMessage());
                    if (res.isSuccess()) {
                        notesArea.clear();
                        refreshAsync();
                    }
                },
                ex -> toastManager.showInfo("Stock operation failed: " + ex.getMessage())
        );
    }

    private void runSealVerification() {
        AppExecutor.runAsync(
                auditService::verifyIntegrity,
                result -> {
                    sealStatusBadge.setText(result.message());
                    sealStatusBadge.setGraphic(AppIcons.of(
                            result.intact() ? Feather.SHIELD : Feather.ALERT_TRIANGLE,
                            16,
                            result.intact() ? "#10B981" : "#EF4444"));
                    sealStatusBadge.getStyleClass().removeAll("kpi-delta-positive", "kpi-delta-danger");
                    sealStatusBadge.getStyleClass().add(result.intact() ? "kpi-delta-positive" : "kpi-delta-danger");
                    toastManager.showInfo(result.message());
                },
                ex -> toastManager.showInfo("Audit verification error: " + ex.getMessage())
        );
    }

    /**
     * Reloads product dropdown, recent movements, FEFO items, damaged stock, and audit ledger asynchronously.
     */
    public void refreshAsync() {
        AppExecutor.runAsync(() -> new Object[]{
                services.getInventoryService().getAllProducts(),
                stockService.getRecentMovements(50),
                stockService.getFefoExpiringProducts(30),
                stockService.getDamagedGoods(),
                auditService.getRecentEntries(50),
                auditService.verifyIntegrity()
        }, data -> {
            @SuppressWarnings("unchecked")
            List<Product> catalog = (List<Product>) data[0];
            @SuppressWarnings("unchecked")
            List<StockMovement> movements = (List<StockMovement>) data[1];
            @SuppressWarnings("unchecked")
            List<Product> fefo = (List<Product>) data[2];
            @SuppressWarnings("unchecked")
            List<Product> damaged = (List<Product>) data[3];
            @SuppressWarnings("unchecked")
            List<AuditLogEntry> audits = (List<AuditLogEntry>) data[4];
            AuditService.SealVerificationResult seal = (AuditService.SealVerificationResult) data[5];

            Product prev = productPicker.getValue();
            updatingUi = true;
            try {
                productPicker.setItems(FXCollections.observableArrayList(catalog));
                if (prev != null) {
                    catalog.stream().filter(p -> p.getId() == prev.getId()).findFirst().ifPresent(productPicker::setValue);
                } else if (!catalog.isEmpty()) {
                    productPicker.getSelectionModel().selectFirst();
                }
            } finally {
                updatingUi = false;
            }

            recentMovements.setAll(movements);
            expiringProducts.setAll(fefo);
            damagedProducts.setAll(damaged);
            auditEntries.setAll(audits);

            sealStatusBadge.setText(seal.message());
            sealStatusBadge.setGraphic(AppIcons.of(
                    seal.intact() ? Feather.SHIELD : Feather.ALERT_TRIANGLE,
                    16,
                    seal.intact() ? "#10B981" : "#EF4444"));
            sealStatusBadge.getStyleClass().removeAll("kpi-delta-positive", "kpi-delta-danger");
            sealStatusBadge.getStyleClass().add(seal.intact() ? "kpi-delta-positive" : "kpi-delta-danger");
            updateLivePreview();
        }, ex -> toastManager.showInfo("Unable to load stock operations data: " + ex.getMessage()));
    }

    private Feather featherFor(MovementType type) {
        return switch (type) {
            case RESTOCK -> Feather.DOWNLOAD;
            case SALE -> Feather.SHOPPING_CART;
            case DAMAGED -> Feather.ALERT_TRIANGLE;
            case RETURNED -> Feather.CORNER_UP_LEFT;
            case LOST -> Feather.HELP_CIRCLE;
            case PHYSICAL_COUNT -> Feather.CHECK_CIRCLE;
            default -> Feather.SLIDERS;
        };
    }
}
