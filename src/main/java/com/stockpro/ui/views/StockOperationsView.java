package com.stockpro.ui.views;

import com.stockpro.domain.AuditLogEntry;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockMovement;
import com.stockpro.service.AppSession;
import com.stockpro.service.AuditService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.StockOperationService;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stock Operations screen matching {@code Screens and popups/Stock.png}, featuring 6 operation tabs,
 * live stock change preview, mandatory reason codes, damaged stock quarantine, FEFO expiry tracking,
 * and SHA-256 tamper-proof audit verification.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockOperationsView {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MMM dd, hh:mm a");

    private final ServiceContext services;
    private final StockOperationService stockService;
    private final AuditService auditService;
    private final ToastNotificationManager toastManager;

    private final Map<MovementType, Button> modeTabs = new LinkedHashMap<>();
    private MovementType activeMode = MovementType.RESTOCK;

    private ComboBox<Product> productPicker;
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
        InventoryEventBus.getInstance().subscribe(topic -> refreshAsync());
    }

    /**
     * Builds the Stock Operations view node.
     *
     * @return scrollable JavaFX node
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(0, 4, 16, 0));

        // Header
        Label title = new Label("Stock Operations");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage stock movements, damaged quarantine, physical shelf counts, FEFO expiry, and SHA-256 audit seal");
        subtitle.getStyleClass().add("page-subtitle");

        // 6 Operation Tabs
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
            Button tabBtn = new Button(iconFor(m) + " " + (m == MovementType.SALE ? "Record Sale" : m.getDisplayName()));
            tabBtn.getStyleClass().add("filter-chip");
            tabBtn.setOnAction(e -> switchMode(m));
            modeTabs.put(m, tabBtn);
            tabStrip.getChildren().add(tabBtn);
        }

        // Two-Column Body
        VBox formCard = buildOperationFormCard();
        formCard.setPrefWidth(390);
        formCard.setMinWidth(350);

        VBox rightCol = buildRightLedgerPanel();
        HBox.setHgrow(rightCol, Priority.ALWAYS);

        HBox bodyRow = new HBox(18, formCard, rightCol);
        bodyRow.setAlignment(Pos.TOP_LEFT);

        page.getChildren().addAll(title, subtitle, tabStrip, bodyRow);

        switchMode(MovementType.RESTOCK);
        refreshAsync();

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        return scroll;
    }

    /**
     * Builds the left operation form card with live stock change preview and mandatory reason selector.
     *
     * @return styled form card
     */
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
        productPicker.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getSku() + " • Qty: " + item.getQuantity() + ")");
            }
        });
        productPicker.setButtonCell(new javafx.scene.control.ListCell<>() {
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
        previewLabel.setStyle("-fx-background-color: #EEF2FF; -fx-text-fill: #3730A3; -fx-font-weight: bold; -fx-padding: 8 10 8 10; -fx-background-radius: 8;");

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

        productPicker.setOnAction(e -> updateLivePreview());
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

    /**
     * Builds the right column containing Recent Movements, FEFO Expiry Tracker, Damaged Stock Quarantine,
     * and the SHA-256 Tamper-Proof Security Seal verification panel.
     *
     * @return styled right ledger panel
     */
    @SuppressWarnings("unchecked")
    private VBox buildRightLedgerPanel() {
        VBox wrapper = new VBox(14);

        // SHA-256 Security Seal Bar
        HBox sealBar = new HBox(12);
        sealBar.getStyleClass().add("card");
        sealBar.setAlignment(Pos.CENTER_LEFT);
        sealStatusBadge = new Label("🔒 SHA-256 Audit Seal Ready");
        sealStatusBadge.setStyle("-fx-font-weight: bold; -fx-text-fill: #15803D;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button verifyBtn = new Button("Verify Seal");
        verifyBtn.getStyleClass().add("btn-primary");
        verifyBtn.setOnAction(e -> runSealVerification());
        sealBar.getChildren().addAll(sealStatusBadge, sp, verifyBtn);

        // Tabbed Sub-Views
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        // Tab 1: Recent Stock Movements
        TableView<StockMovement> moveTable = new TableView<>(recentMovements);
        moveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        moveTable.setPrefHeight(340);

        TableColumn<StockMovement, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getCreatedAt() != null ? c.getValue().getCreatedAt().format(TIME_FMT) : ""));
        TableColumn<StockMovement, String> prodCol = new TableColumn<>("Product");
        prodCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProductName()));
        TableColumn<StockMovement, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getMovementType().getDisplayName()));
        TableColumn<StockMovement, String> deltaCol = new TableColumn<>("Qty");
        deltaCol.setCellValueFactory(c -> new SimpleStringProperty(
                (c.getValue().getQuantityDelta() >= 0 ? "+" : "") + c.getValue().getQuantityDelta()));
        TableColumn<StockMovement, String> userCol = new TableColumn<>("User");
        userCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPerformedBy()));
        TableColumn<StockMovement, String> reasonCol = new TableColumn<>("Reason");
        reasonCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getReason()));

        moveTable.getColumns().addAll(timeCol, prodCol, typeCol, deltaCol, userCol, reasonCol);

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
        fefoTable.getColumns().addAll(fSku, fName, fQty, fExp, fUrgency);

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
        dmgTable.getColumns().addAll(dSku, dName, dSellable, dDamaged);

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
        auditTable.getColumns().addAll(aAct, aActor, aRef, aDetails, aHash);

        tabs.getTabs().addAll(
                new Tab("Recent Movements", moveTable),
                new Tab("Expiry Tracker (FEFO)", fefoTable),
                new Tab("Damaged Quarantine", dmgTable),
                new Tab("SHA-256 Audit Ledger", auditTable)
        );

        VBox tabCard = new VBox(tabs);
        tabCard.getStyleClass().add("card");
        VBox.setVgrow(tabCard, Priority.ALWAYS);

        wrapper.getChildren().addAll(sealBar, tabCard);
        return wrapper;
    }

    /**
     * Switches the active operation tab and updates form labels and preset reasons.
     *
     * @param mode selected {@link MovementType}
     */
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

    /**
     * Recomputes the live preview pill ({@code Current -> New (Status)}) without touching SQLite.
     */
    private void updateLivePreview() {
        Product selected = productPicker.getValue();
        if (selected == null) {
            previewLabel.setText("Select a product to preview stock change.");
            previewLabel.setStyle("-fx-background-color: #EEF2FF; -fx-text-fill: #3730A3; -fx-font-weight: bold; -fx-padding: 8 10 8 10; -fx-background-radius: 8;");
            return;
        }
        try {
            int qty = com.stockpro.service.ProductValidator.parseQuantity(qtyField.getText());
            StockOperationService.StockPreview preview = stockService.previewOperation(selected, activeMode, qty);
            previewLabel.setText(preview.note());
            if (preview.valid()) {
                previewLabel.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #15803D; -fx-font-weight: bold; -fx-padding: 8 10 8 10; -fx-background-radius: 8;");
            } else {
                previewLabel.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-font-weight: bold; -fx-padding: 8 10 8 10; -fx-background-radius: 8;");
            }
        } catch (IllegalArgumentException ex) {
            previewLabel.setText(ex.getMessage());
            previewLabel.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #B91C1C; -fx-font-weight: bold; -fx-padding: 8 10 8 10; -fx-background-radius: 8;");
        }
    }

    /**
     * Persists the selected stock operation on a background thread and updates all views.
     */
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

    /**
     * Runs SHA-256 ledger integrity verification in the background and updates the status badge.
     */
    private void runSealVerification() {
        AppExecutor.runAsync(
                auditService::verifyIntegrity,
                result -> {
                    sealStatusBadge.setText((result.intact() ? "🔒 " : "⚠ ") + result.message());
                    sealStatusBadge.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                            + (result.intact() ? "#15803D;" : "#DC2626;"));
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
            productPicker.setItems(FXCollections.observableArrayList(catalog));
            if (prev != null) {
                catalog.stream().filter(p -> p.getId() == prev.getId()).findFirst().ifPresent(productPicker::setValue);
            } else if (!catalog.isEmpty()) {
                productPicker.getSelectionModel().selectFirst();
            }

            recentMovements.setAll(movements);
            expiringProducts.setAll(fefo);
            damagedProducts.setAll(damaged);
            auditEntries.setAll(audits);

            sealStatusBadge.setText((seal.intact() ? "🔒 " : "⚠ ") + seal.message());
            sealStatusBadge.setStyle("-fx-font-weight: bold; -fx-text-fill: "
                    + (seal.intact() ? "#15803D;" : "#DC2626;"));
            updateLivePreview();
        }, ex -> {
        });
    }

    /**
     * Maps a movement mode to its icon glyph.
     *
     * @param type movement type
     * @return icon text
     */
    private String iconFor(MovementType type) {
        return switch (type) {
            case RESTOCK -> "📥";
            case SALE -> "🛒";
            case DAMAGED -> "⚠";
            case RETURNED -> "↩";
            case LOST -> "❓";
            case PHYSICAL_COUNT -> "✔";
            default -> "⚙";
        };
    }
}
