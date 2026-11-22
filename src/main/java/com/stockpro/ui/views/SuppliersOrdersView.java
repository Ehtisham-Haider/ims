package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;
import com.stockpro.domain.Supplier;
import com.stockpro.service.AppSession;
import com.stockpro.service.FinanceService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ProductValidator;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.SupplierOrderService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.KpiStatCard;
import com.stockpro.ui.components.KpiStatCard.DeltaTone;
import com.stockpro.ui.components.ToastNotificationManager;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Suppliers, Purchase Orders, and Financial Engine workspace matching
 * {@code Screens and popups/Suppliers and Orders.png}, with Feather vector icons,
 * editable PO draft line removal, supplier delete confirmation, status pills, and OpenPDF export.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class SuppliersOrdersView {
    private final ServiceContext services;
    private final SupplierOrderService supplierService;
    private final FinanceService financeService;
    private final ToastNotificationManager toastManager;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private final ObservableList<Supplier> supplierRows = FXCollections.observableArrayList();
    private final ObservableList<PurchaseOrder> poRows = FXCollections.observableArrayList();
    private final ObservableList<PurchaseOrderItem> draftItems = FXCollections.observableArrayList();
    private final ObservableList<Product> deadMoneyRows = FXCollections.observableArrayList();

    private ComboBox<Supplier> poSupplierCombo;
    private ComboBox<Product> poProductCombo;
    private TextField poQtyField;
    private Label poTotalLabel;

    private KpiStatCard storeValueCard;
    private KpiStatCard storeProfitCard;
    private KpiStatCard taxEstimateCard;
    private KpiStatCard deadMoneyCard;
    private ComboBox<Integer> deadWindowCombo;

    private Node rootNode;

    /**
     * Constructs the Suppliers, Purchase Orders &amp; Finance screen.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     */
    public SuppliersOrdersView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.supplierService = services.getSupplierOrderService();
        this.financeService = services.getFinanceService();
        this.toastManager = toastManager;
        InventoryEventBus.getInstance().subscribe(eventListener);
    }

    /**
     * Unsubscribes from {@link InventoryEventBus} when navigating away.
     */
    public void dispose() {
        InventoryEventBus.getInstance().unsubscribe(eventListener);
    }

    /**
     * Builds the Suppliers &amp; Orders view layout.
     *
     * @return scrollable JavaFX {@link Node}
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(0, 4, 16, 0));

        Label title = new Label("Suppliers & Orders");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage vendor contacts, build purchase orders, export PDFs, and analyze store valuation & dead stock");
        subtitle.getStyleClass().add("page-subtitle");

        // Top Financial Engine KPI Strip with Feather vector icons
        storeValueCard = new KpiStatCard(Feather.DOLLAR_SIGN, "#4F46E5", "kpi-card-blue", "Total Store Value", "$0.00", "Cost valuation");
        storeProfitCard = new KpiStatCard(Feather.TRENDING_UP, "#10B981", "kpi-card-green", "Est. Total Profit", "$0.00", "0% avg margin");
        taxEstimateCard = new KpiStatCard(Feather.PERCENT, "#8B5CF6", "kpi-card-purple", "Tax / GST Estimate", "$0.00", "15% store rate");
        deadMoneyCard = new KpiStatCard(Feather.CLOCK, "#F59E0B", "kpi-card-amber", "Dead Money Stock", "$0.00", "0 stagnant SKUs");
        HBox financeKpiRow = new HBox(12, storeValueCard, storeProfitCard, taxEstimateCard, deadMoneyCard);
        HBox treasuryStrip = buildTreasuryToolbar();

        // Two-Column Suppliers + PO Builder
        VBox suppliersCard = buildSuppliersCard();
        suppliersCard.setPrefWidth(430);
        VBox poCard = buildPurchaseOrderCard();
        HBox.setHgrow(poCard, Priority.ALWAYS);
        HBox splitRow = new HBox(16, suppliersCard, poCard);

        // Bottom Row: Order History + Dead Money Finder
        VBox poHistoryCard = buildOrderHistoryCard();
        HBox.setHgrow(poHistoryCard, Priority.ALWAYS);
        VBox deadMoneyCardBox = buildDeadMoneyCard();
        deadMoneyCardBox.setPrefWidth(430);
        HBox bottomRow = new HBox(16, poHistoryCard, deadMoneyCardBox);

        page.getChildren().addAll(title, subtitle, financeKpiRow, treasuryStrip, splitRow, bottomRow);

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        this.rootNode = scroll;

        AppExecutor.runAsync(() -> {
            supplierService.ensureSampleSuppliersIfEmpty();
            return true;
        }, ok -> refreshAsync(), ex -> toastManager.showInfo("Failed to load suppliers: " + ex.getMessage()));

        return scroll;
    }

    private VBox buildSuppliersCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox hdr = new HBox(8);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Suppliers");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button addBtn = new Button("Add Supplier");
        addBtn.setGraphic(AppIcons.of(Feather.PLUS, 13));
        addBtn.getStyleClass().add("btn-secondary");
        addBtn.setOnAction(e -> openSupplierDialog(null));
        hdr.getChildren().addAll(heading, sp, addBtn);

        TableView<Supplier> table = new TableView<>(supplierRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(250);

        TableColumn<Supplier, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Supplier, String> phoneCol = new TableColumn<>("Phone");
        phoneCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPhone()));
        TableColumn<Supplier, String> leadCol = new TableColumn<>("Lead / Rel.");
        leadCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getDeliveryDays() + "d • " + String.format("%.0f%%", c.getValue().getReliabilityScore())));
        TableColumn<Supplier, Void> actCol = new TableColumn<>("Actions");
        actCol.setMinWidth(82);
        actCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                Supplier s = getTableRow().getItem();
                Button edit = new Button();
                edit.setGraphic(AppIcons.of(Feather.EDIT_2, 13));
                edit.getStyleClass().add("icon-btn");
                edit.setTooltip(new Tooltip("Edit Supplier"));
                edit.setOnAction(e -> openSupplierDialog(s));

                Button del = new Button();
                del.setGraphic(AppIcons.of(Feather.TRASH_2, 13));
                del.getStyleClass().add("icon-btn-danger");
                del.setTooltip(new Tooltip("Remove Supplier"));
                del.setOnAction(e -> confirmDeleteSupplier(s));

                setGraphic(new HBox(2, edit, del));
            }
        });
        table.getColumns().addAll(List.of(nameCol, phoneCol, leadCol, actCol));

        card.getChildren().addAll(hdr, table);
        return card;
    }

    private VBox buildPurchaseOrderCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox topRow = new HBox(8);
        topRow.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Quick Purchase Order Builder");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button clearDraftBtn = new Button("Clear Draft");
        clearDraftBtn.getStyleClass().add("icon-btn");
        clearDraftBtn.setOnAction(e -> {
            draftItems.clear();
            updateDraftPoTotal();
        });
        topRow.getChildren().addAll(heading, sp, clearDraftBtn);

        poSupplierCombo = new ComboBox<>();
        poSupplierCombo.setMaxWidth(Double.MAX_VALUE);
        poSupplierCombo.setPromptText("Select Supplier");
        poSupplierCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Supplier item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getDeliveryDays() + "d delivery)");
            }
        });
        poSupplierCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Supplier item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select Supplier" : item.getName());
            }
        });

        poProductCombo = new ComboBox<>();
        poProductCombo.setPrefWidth(220);
        poProductCombo.setPromptText("Select Product");
        poProductCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + AppSession.getInstance().formatMoney(item.getBuyPrice()) + ")");
            }
        });
        poProductCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select Product" : item.getName());
            }
        });

        poQtyField = new TextField("25");
        poQtyField.setPrefWidth(76);
        poQtyField.setPromptText("Qty");

        Button addLineBtn = new Button("Add Line");
        addLineBtn.setGraphic(AppIcons.of(Feather.PLUS, 13));
        addLineBtn.getStyleClass().add("btn-secondary");
        addLineBtn.setOnAction(e -> addDraftLineItem());

        HBox addLineRow = new HBox(8, poProductCombo, poQtyField, addLineBtn);
        HBox.setHgrow(poProductCombo, Priority.ALWAYS);

        TableView<PurchaseOrderItem> draftTable = new TableView<>(draftItems);
        draftTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        draftTable.setPrefHeight(130);
        TableColumn<PurchaseOrderItem, String> pName = new TableColumn<>("Product");
        pName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getProductName()));
        TableColumn<PurchaseOrderItem, String> pQty = new TableColumn<>("Qty");
        pQty.setMaxWidth(64);
        pQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<PurchaseOrderItem, String> pTotal = new TableColumn<>("Est. Cost");
        pTotal.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getLineTotal())));
        TableColumn<PurchaseOrderItem, Void> removeCol = new TableColumn<>("");
        removeCol.setMaxWidth(44);
        removeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                Button rmBtn = new Button();
                rmBtn.setGraphic(AppIcons.of(Feather.X, 13));
                rmBtn.getStyleClass().add("icon-btn-danger");
                rmBtn.setTooltip(new Tooltip("Remove line"));
                rmBtn.setOnAction(e -> {
                    draftItems.remove(getTableRow().getItem());
                    updateDraftPoTotal();
                });
                setGraphic(rmBtn);
            }
        });
        draftTable.getColumns().addAll(List.of(pName, pQty, pTotal, removeCol));

        poTotalLabel = new Label("Estimated Total (incl. Tax): $0.00");
        poTotalLabel.setStyle("-fx-font-weight: bold;");

        Button generatePoBtn = new Button("Generate PO");
        generatePoBtn.setGraphic(AppIcons.of(Feather.FILE_TEXT, 14));
        generatePoBtn.getStyleClass().add("btn-primary");
        generatePoBtn.setMaxWidth(Double.MAX_VALUE);
        generatePoBtn.setOnAction(e -> createPurchaseOrder());

        card.getChildren().addAll(topRow, poSupplierCombo, addLineRow, draftTable, poTotalLabel, generatePoBtn);
        return card;
    }

    private VBox buildOrderHistoryCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        Label heading = new Label("Purchase Orders");
        heading.getStyleClass().add("section-title");

        TableView<PurchaseOrder> table = new TableView<>(poRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);

        TableColumn<PurchaseOrder, String> numCol = new TableColumn<>("PO #");
        numCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPoNumber()));
        TableColumn<PurchaseOrder, String> supCol = new TableColumn<>("Supplier");
        supCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getSupplierName()));
        TableColumn<PurchaseOrder, String> costCol = new TableColumn<>("Total");
        costCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getTotalCost())));
        TableColumn<PurchaseOrder, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getStatus()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label(status);
                pill.getStyleClass().addAll("status-pill", switch (status.toUpperCase()) {
                    case "DELIVERED" -> "status-in-stock";
                    case "SENT" -> "status-info-pill";
                    default -> "status-low-stock";
                });
                setGraphic(pill);
            }
        });
        TableColumn<PurchaseOrder, Void> actCol = new TableColumn<>("Actions");
        actCol.setMinWidth(265);
        actCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                PurchaseOrder po = getTableRow().getItem();
                Button pdfBtn = new Button("PDF");
                pdfBtn.getStyleClass().add("btn-secondary");
                pdfBtn.setStyle("-fx-padding: 4 9 4 9;");
                pdfBtn.setOnAction(e -> exportPoPdf(po));

                Button verifyBtn = new Button("3-Way Check");
                verifyBtn.getStyleClass().add("btn-secondary");
                verifyBtn.setStyle("-fx-padding: 4 9 4 9;");
                verifyBtn.setOnAction(e -> openBlindAndThreeWayDialog(po));

                HBox box = new HBox(6, pdfBtn, verifyBtn);
                if ("Draft".equalsIgnoreCase(po.getStatus())) {
                    Button sendBtn = new Button("Send");
                    sendBtn.getStyleClass().add("btn-secondary");
                    sendBtn.setStyle("-fx-padding: 4 9 4 9;");
                    sendBtn.setOnAction(e -> transitionPo(po.getId(), "Sent"));
                    box.getChildren().add(sendBtn);
                }
                if (!"Delivered".equalsIgnoreCase(po.getStatus())) {
                    Button deliverBtn = new Button("Deliver");
                    deliverBtn.getStyleClass().add("btn-success");
                    deliverBtn.setStyle("-fx-padding: 4 9 4 9;");
                    deliverBtn.setOnAction(e -> transitionPo(po.getId(), "Delivered"));
                    box.getChildren().add(deliverBtn);
                }
                setGraphic(box);
            }
        });
        table.getColumns().addAll(List.of(numCol, supCol, costCol, statusCol, actCol));

        card.getChildren().addAll(heading, table);
        return card;
    }

    private void openBlindAndThreeWayDialog(PurchaseOrder po) {
        if (po == null || po.getItems().isEmpty()) {
            toastManager.showInfo("Purchase order has no line items.");
            return;
        }
        java.util.Map<String, TextField> countFields = new java.util.LinkedHashMap<>();
        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));

        Label title = new Label("Blind Dock Receiving & 3-Way Match — " + po.getPoNumber());
        title.getStyleClass().add("section-title");
        Label hint = new Label("Enter physical dock counts (unbiased) and vendor invoice amount.");
        hint.getStyleClass().add("muted-text");
        box.getChildren().addAll(title, hint);

        for (PurchaseOrderItem item : po.getItems()) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            Label skuLbl = new Label(item.getProductSku() + " — " + item.getProductName());
            HBox.setHgrow(skuLbl, Priority.ALWAYS);
            TextField qtyInput = new TextField("0");
            qtyInput.setPrefWidth(90);
            qtyInput.setPromptText("Dock Qty");
            countFields.put(item.getProductSku(), qtyInput);
            row.getChildren().addAll(skuLbl, qtyInput);
            box.getChildren().add(row);
        }

        TextField invNumField = new TextField("INV-" + po.getPoNumber());
        TextField invTotalField = new TextField(String.format("%.2f", po.getTotalCost()));
        HBox invRow = new HBox(10, invNumField, invTotalField);
        Label resultLbl = new Label();
        resultLbl.setWrapText(true);

        Button runBtn = new Button("Run Blind Check & 3-Way Match");
        runBtn.getStyleClass().add("btn-primary");
        Stage dlg = new Stage();
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("3-Way Invoice Match");
        runBtn.setOnAction(e -> {
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            try {
                for (var entry : countFields.entrySet()) {
                    counts.put(entry.getKey(), Integer.parseInt(entry.getValue().getText().trim()));
                }
                double invTotal = Double.parseDouble(invTotalField.getText().trim());
                AppExecutor.runAsync(() -> {
                    var blind = services.getMultiBranchTransferRmaService().recordBlindDelivery(
                            po.getId(), counts, AppSession.getInstance().getActorName());
                    var match = services.getMultiBranchTransferRmaService().performThreeWayMatch(
                            po.getId(), invNumField.getText().trim(), invTotal, counts, AppSession.getInstance().getActorName());
                    return blind.getMessage() + " | " + match.getMessage();
                }, msg -> {
                    resultLbl.setText(msg);
                    toastManager.showInfo(msg);
                }, ex -> resultLbl.setText("Error: " + ex.getMessage()));
            } catch (NumberFormatException ex) {
                resultLbl.setText("Enter valid numeric dock counts and invoice amount.");
            }
        });

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> dlg.close());

        box.getChildren().addAll(new Label("Invoice # & Billed Total:"), invRow, resultLbl, new HBox(10, runBtn, closeBtn));
        Scene scene = new Scene(box, 520, 420);
        var css = getClass().getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        dlg.setScene(scene);
        dlg.showAndWait();
    }

    private VBox buildDeadMoneyCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox hdr = new HBox(8);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Dead Money Finder");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        deadWindowCombo = new ComboBox<>();
        deadWindowCombo.getItems().addAll(30, 60, 90);
        deadWindowCombo.setValue(60);
        deadWindowCombo.setOnAction(e -> refreshAsync());

        hdr.getChildren().addAll(heading, sp, new Label("Days:"), deadWindowCombo);

        TableView<Product> table = new TableView<>(deadMoneyRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);

        TableColumn<Product, String> nCol = new TableColumn<>("Stagnant Item");
        nCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        TableColumn<Product, String> qCol = new TableColumn<>("Qty");
        qCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<Product, String> vCol = new TableColumn<>("Tied Capital");
        vCol.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getCostValuation())));
        table.getColumns().addAll(List.of(nCol, qCol, vCol));

        card.getChildren().addAll(hdr, table);
        return card;
    }

    private void addDraftLineItem() {
        Product p = poProductCombo.getValue();
        if (p == null) {
            toastManager.showInfo("Select a product to add.");
            return;
        }
        try {
            int qty = ProductValidator.parseQuantity(poQtyField.getText());
            if (qty <= 0) {
                toastManager.showInfo("Quantity must be greater than zero.");
                return;
            }
            draftItems.add(new PurchaseOrderItem(p.getId(), p.getSku(), p.getName(), qty, p.getBuyPrice()));
            updateDraftPoTotal();
        } catch (IllegalArgumentException ex) {
            toastManager.showInfo(ex.getMessage());
        }
    }

    private void updateDraftPoTotal() {
        double subtotal = draftItems.stream().mapToDouble(PurchaseOrderItem::getLineTotal).sum();
        double taxRate = AppSession.getInstance().getTaxRatePercent();
        double withTax = financeService.calculateTotalWithTax(subtotal, taxRate);
        poTotalLabel.setText(String.format("Subtotal: %s | Est. Total (incl. %.1f%% tax): %s",
                AppSession.getInstance().formatMoney(subtotal),
                taxRate,
                AppSession.getInstance().formatMoney(withTax)));
    }

    private void createPurchaseOrder() {
        Supplier sup = poSupplierCombo.getValue();
        if (sup == null) {
            toastManager.showInfo("Select a supplier first.");
            return;
        }
        List<PurchaseOrderItem> items = new ArrayList<>(draftItems);
        AppExecutor.runAsync(
                () -> supplierService.createPurchaseOrder(sup.getId(), items),
                res -> {
                    toastManager.showInfo(res.getMessage());
                    if (res.isSuccess()) {
                        draftItems.clear();
                        updateDraftPoTotal();
                        refreshAsync();
                    }
                },
                ex -> toastManager.showInfo("Failed to create PO: " + ex.getMessage())
        );
    }

    private void transitionPo(long poId, String nextStatus) {
        String actor = AppSession.getInstance().getActorName();
        AppExecutor.runAsync(
                () -> supplierService.updateOrderStatus(poId, nextStatus, actor),
                res -> {
                    toastManager.showInfo(res.getMessage());
                    refreshAsync();
                },
                ex -> toastManager.showInfo("Status update failed: " + ex.getMessage())
        );
    }

    private void exportPoPdf(PurchaseOrder po) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(po.getPoNumber() + ".pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));
        File out = chooser.showSaveDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> supplierService.exportPurchaseOrderPdf(po, out.toPath()),
                path -> toastManager.showInfo("Exported Purchase Order PDF to " + path.getFileName()),
                ex -> toastManager.showInfo("PDF export failed: " + ex.getMessage())
        );
    }

    private void confirmDeleteSupplier(Supplier supplier) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (rootNode != null && rootNode.getScene() != null) {
            dialog.initOwner(rootNode.getScene().getWindow());
        }
        dialog.setTitle("Remove Supplier");

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        Label heading = new Label("Remove '" + supplier.getName() + "'?");
        heading.getStyleClass().add("section-title");
        Label hint = new Label("Suppliers linked to existing purchase orders are safely preserved.");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("btn-secondary");
        cancelBtn.setOnAction(e -> dialog.close());
        Button delBtn = new Button("Remove Supplier");
        delBtn.getStyleClass().add("btn-danger-outline");
        delBtn.setOnAction(e -> {
            dialog.close();
            InventoryOperationResult<Boolean> res = supplierService.deleteSupplier(supplier.getId());
            toastManager.showInfo(res.getMessage());
            refreshAsync();
        });
        HBox actions = new HBox(10, cancelBtn, delBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(heading, hint, actions);

        Scene sc = new Scene(card, 400, 175);
        var css = getClass().getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        dialog.setScene(sc);
        dialog.showAndWait();
    }

    private void openSupplierDialog(Supplier existing) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (rootNode != null && rootNode.getScene() != null) {
            dialog.initOwner(rootNode.getScene().getWindow());
        }
        dialog.setTitle(existing == null ? "Add Supplier" : "Edit Supplier");

        VBox box = new VBox(10);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(20));

        TextField nameF = new TextField(existing != null ? existing.getName() : "");
        nameF.setPromptText("Supplier Name *");
        TextField phoneF = new TextField(existing != null && existing.getPhone() != null ? existing.getPhone() : "");
        phoneF.setPromptText("Phone");
        TextField emailF = new TextField(existing != null && existing.getEmail() != null ? existing.getEmail() : "");
        emailF.setPromptText("Email");
        TextField leadF = new TextField(existing != null ? String.valueOf(existing.getDeliveryDays()) : "3");
        TextField relF = new TextField(existing != null ? String.valueOf(existing.getReliabilityScore()) : "95.0");
        TextField catsF = new TextField(existing != null && existing.getLinkedCategories() != null ? existing.getLinkedCategories() : "");
        catsF.setPromptText("Linked Categories (comma-separated)");

        Button saveBtn = new Button("Save Supplier");
        saveBtn.setGraphic(AppIcons.of(Feather.SAVE, 14));
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            try {
                Supplier s = existing != null ? existing : new Supplier();
                s.setName(nameF.getText());
                s.setPhone(phoneF.getText());
                s.setEmail(emailF.getText());
                s.setDeliveryDays(ProductValidator.parseQuantity(leadF.getText()));
                s.setReliabilityScore(Double.parseDouble(relF.getText().trim()));
                s.setLinkedCategories(catsF.getText());
                InventoryOperationResult<Supplier> res = supplierService.saveSupplier(s);
                toastManager.showInfo(res.getMessage());
                if (res.isSuccess()) {
                    dialog.close();
                    refreshAsync();
                }
            } catch (Exception ex) {
                toastManager.showInfo("Check supplier input fields: " + ex.getMessage());
            }
        });

        box.getChildren().addAll(
                new Label("Name *"), nameF,
                new Label("Phone"), phoneF,
                new Label("Email"), emailF,
                new Label("Delivery Days"), leadF,
                new Label("Reliability (%)"), relF,
                new Label("Categories"), catsF,
                saveBtn
        );

        Scene sc = new Scene(box, 390, 460);
        var css = getClass().getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        dialog.setScene(sc);
        dialog.showAndWait();
    }

    /**
     * Reloads suppliers, purchase orders, catalog picker, and financial KPIs off the FX thread.
     */
    public void refreshAsync() {
        int windowDays = deadWindowCombo != null && deadWindowCombo.getValue() != null ? deadWindowCombo.getValue() : 60;
        double taxRate = AppSession.getInstance().getTaxRatePercent();

        AppExecutor.runAsync(() -> new Object[]{
                supplierService.getAllSuppliers(),
                supplierService.getAllOrders(),
                services.getInventoryService().getAllProducts(),
                financeService.computeSummary(windowDays, taxRate)
        }, data -> {
            @SuppressWarnings("unchecked")
            List<Supplier> suppliers = (List<Supplier>) data[0];
            @SuppressWarnings("unchecked")
            List<PurchaseOrder> orders = (List<PurchaseOrder>) data[1];
            @SuppressWarnings("unchecked")
            List<Product> products = (List<Product>) data[2];
            FinanceService.FinanceSummary summary = (FinanceService.FinanceSummary) data[3];

            supplierRows.setAll(suppliers);
            poRows.setAll(orders);
            poSupplierCombo.setItems(FXCollections.observableArrayList(suppliers));
            if (poSupplierCombo.getValue() == null && !suppliers.isEmpty()) {
                poSupplierCombo.getSelectionModel().selectFirst();
            }
            poProductCombo.setItems(FXCollections.observableArrayList(products));
            if (poProductCombo.getValue() == null && !products.isEmpty()) {
                poProductCombo.getSelectionModel().selectFirst();
            }

            AppSession session = AppSession.getInstance();
            storeValueCard.updateMetric(session.formatMoney(summary.totalCostValue()),
                    "Retail: " + session.formatMoney(summary.totalRetailValue()), DeltaTone.ACCENT);
            storeProfitCard.updateMetric(session.formatMoney(summary.totalPotentialProfit()),
                    String.format("%.1f%% avg margin", summary.averageMarginPercent()), DeltaTone.POSITIVE);
            taxEstimateCard.updateMetric(session.formatMoney(summary.estimatedTaxAmount()),
                    String.format("%.1f%% GST • Incl: %s", summary.taxRatePercent(),
                            session.formatMoney(summary.retailValueWithTax())), DeltaTone.ACCENT);
            deadMoneyCard.updateMetric(session.formatMoney(summary.deadMoneyValue()),
                    summary.deadMoneyItems().size() + " stagnant SKU(s)",
                    summary.deadMoneyItems().isEmpty() ? DeltaTone.POSITIVE : DeltaTone.WARNING);
            deadMoneyRows.setAll(summary.deadMoneyItems());
        }, ex -> toastManager.showInfo("Failed to refresh supplier finances: " + ex.getMessage()));
    }

    private HBox buildTreasuryToolbar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("card");
        bar.setPadding(new Insets(10, 14, 10, 14));

        Label lbl = new Label("Global Finance & Purchasing Tools:");
        lbl.getStyleClass().add("section-title");

        Button fxBtn = new Button("FX Currency Converter");
        fxBtn.setGraphic(AppIcons.of(Feather.GLOBE, 14));
        fxBtn.getStyleClass().add("btn-secondary");
        fxBtn.setOnAction(e -> openFxCurrencyDialog());

        Button landedBtn = new Button("Landed Cost Allocator");
        landedBtn.setGraphic(AppIcons.of(Feather.ANCHOR, 14));
        landedBtn.getStyleClass().add("btn-secondary");
        landedBtn.setOnAction(e -> openLandedCostDialog());

        Button tiersBtn = new Button("Volume Tiers & MOQ");
        tiersBtn.setGraphic(AppIcons.of(Feather.LAYERS, 14));
        tiersBtn.getStyleClass().add("btn-secondary");
        tiersBtn.setOnAction(e -> openVolumeTiersDialog());

        Button cashBtn = new Button("30/60/90d Cash Flow");
        cashBtn.setGraphic(AppIcons.of(Feather.ACTIVITY, 14));
        cashBtn.getStyleClass().add("btn-secondary");
        cashBtn.setOnAction(e -> openCashFlowDialog());

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        bar.getChildren().addAll(lbl, sp, fxBtn, landedBtn, tiersBtn, cashBtn);
        return bar;
    }

    private void openFxCurrencyDialog() {
        Stage dlg = createModalStage("Multi-Currency FX Converter & Cache", 540, 440);
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));

        TextField amtField = new TextField("100.00");
        ComboBox<String> fromBox = new ComboBox<>();
        ComboBox<String> toBox = new ComboBox<>();
        ObservableList<com.stockpro.domain.FxRateQuote> rateRows = FXCollections.observableArrayList();
        Label convertedLbl = new Label("Converted: --");
        convertedLbl.getStyleClass().add("section-title");

        Runnable recalc = () -> {
            try {
                double val = Double.parseDouble(amtField.getText().trim());
                double out = services.getMultiCurrencyFxService().convert(val, fromBox.getValue(), toBox.getValue());
                convertedLbl.setText(String.format("%.2f %s = %.2f %s", val, fromBox.getValue(), out, toBox.getValue()));
            } catch (Exception ex) {
                convertedLbl.setText("Enter valid numeric amount.");
            }
        };

        Button convertBtn = new Button("Convert");
        convertBtn.getStyleClass().add("btn-primary");
        convertBtn.setOnAction(e -> recalc.run());

        Button refreshBtn = new Button("Sync Live Rates (Frankfurter)");
        refreshBtn.getStyleClass().add("btn-secondary");

        TableView<com.stockpro.domain.FxRateQuote> table = new TableView<>(rateRows);
        table.setPrefHeight(220);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<com.stockpro.domain.FxRateQuote, String> cCol = new TableColumn<>("Currency");
        cCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().currencyCode()));
        TableColumn<com.stockpro.domain.FxRateQuote, String> rCol = new TableColumn<>("1 USD =");
        rCol.setCellValueFactory(c -> new SimpleStringProperty(String.format("%.4f", c.getValue().rateFromUsd())));
        TableColumn<com.stockpro.domain.FxRateQuote, String> sCol = new TableColumn<>("Source");
        sCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().source()));
        table.getColumns().addAll(List.of(cCol, rCol, sCol));

        Runnable loadCached = () -> AppExecutor.runAsync(
                () -> services.getMultiCurrencyFxService().listCachedRates(),
                quotes -> {
                    rateRows.setAll(quotes);
                    List<String> codes = quotes.stream().map(com.stockpro.domain.FxRateQuote::currencyCode).toList();
                    fromBox.setItems(FXCollections.observableArrayList(codes));
                    toBox.setItems(FXCollections.observableArrayList(codes));
                    if (fromBox.getValue() == null) {
                        fromBox.setValue("USD");
                    }
                    if (toBox.getValue() == null) {
                        toBox.setValue(codes.contains("PKR") ? "PKR" : codes.get(0));
                    }
                    recalc.run();
                },
                ex -> toastManager.showInfo("FX load error: " + ex.getMessage())
        );

        refreshBtn.setOnAction(e -> AppExecutor.runAsync(
                () -> services.getMultiCurrencyFxService().refreshRatesFromNetwork(),
                res -> {
                    toastManager.showInfo(res.getMessage());
                    loadCached.run();
                },
                ex -> toastManager.showInfo("FX sync failed: " + ex.getMessage())
        ));

        loadCached.run();
        HBox controls = new HBox(8, amtField, fromBox, new Label("->"), toBox, convertBtn);
        controls.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(controls, convertedLbl, refreshBtn, table);
        showModalScene(dlg, box);
    }

    private void openLandedCostDialog() {
        Stage dlg = createModalStage("Landed Cost Allocator (Freight, Customs & Handling)", 640, 480);
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));

        ComboBox<PurchaseOrder> poCombo = new ComboBox<>(poRows);
        poCombo.setMaxWidth(Double.MAX_VALUE);
        if (!poRows.isEmpty()) {
            poCombo.getSelectionModel().selectFirst();
        }
        ComboBox<com.stockpro.domain.LandedCostAllocation.AllocationStrategy> stratCombo = new ComboBox<>();
        stratCombo.getItems().addAll(com.stockpro.domain.LandedCostAllocation.AllocationStrategy.values());
        stratCombo.setValue(com.stockpro.domain.LandedCostAllocation.AllocationStrategy.BY_VALUE);

        TextField freightF = new TextField("120.00");
        TextField customsF = new TextField("45.00");
        TextField handlingF = new TextField("15.00");
        javafx.scene.control.CheckBox updateCatalogBox = new javafx.scene.control.CheckBox("Update SKU catalog buy price with landed unit cost");

        ObservableList<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown> linesModel = FXCollections.observableArrayList();
        TableView<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown> lineTable = new TableView<>(linesModel);
        lineTable.setPrefHeight(210);
        lineTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown, String> sCol = new TableColumn<>("SKU");
        sCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().sku()));
        TableColumn<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown, String> bCol = new TableColumn<>("Base Unit");
        bCol.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().baseUnitCost())));
        TableColumn<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown, String> exCol = new TableColumn<>("Overhead Share");
        exCol.setCellValueFactory(c -> new SimpleStringProperty(String.format("+$%.2f", c.getValue().allocatedExtraTotal())));
        TableColumn<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown, String> finCol = new TableColumn<>("Effective Landed");
        finCol.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().effectiveLandedUnitCost())));
        lineTable.getColumns().addAll(List.of(sCol, bCol, exCol, finCol));

        Button previewBtn = new Button("Preview Split");
        previewBtn.getStyleClass().add("btn-secondary");
        Button saveBtn = new Button("Save Landed Allocation");
        saveBtn.getStyleClass().add("btn-primary");

        previewBtn.setOnAction(e -> runLandedCostAction(poCombo.getValue(), freightF, customsF, handlingF,
                stratCombo.getValue(), false, updateCatalogBox.isSelected(), linesModel));
        saveBtn.setOnAction(e -> runLandedCostAction(poCombo.getValue(), freightF, customsF, handlingF,
                stratCombo.getValue(), true, updateCatalogBox.isSelected(), linesModel));

        HBox fees = new HBox(8, new Label("Freight:"), freightF, new Label("Customs:"), customsF, new Label("Handling:"), handlingF);
        fees.setAlignment(Pos.CENTER_LEFT);
        HBox btns = new HBox(10, previewBtn, saveBtn);
        box.getChildren().addAll(new Label("Purchase Order & Allocation Strategy:"), poCombo, stratCombo, fees, updateCatalogBox, btns, lineTable);
        showModalScene(dlg, box);
    }

    private void runLandedCostAction(
            PurchaseOrder po,
            TextField freightF,
            TextField customsF,
            TextField handlingF,
            com.stockpro.domain.LandedCostAllocation.AllocationStrategy strategy,
            boolean persist,
            boolean updateCatalog,
            ObservableList<com.stockpro.domain.LandedCostAllocation.LandedCostLineBreakdown> targetModel) {
        if (po == null) {
            toastManager.showInfo("Create or select a Purchase Order first.");
            return;
        }
        try {
            double fr = Double.parseDouble(freightF.getText().trim());
            double du = Double.parseDouble(customsF.getText().trim());
            double ha = Double.parseDouble(handlingF.getText().trim());
            String actor = AppSession.getInstance().getActorName();
            AppExecutor.runAsync(
                    () -> persist
                            ? services.getLandedCostEngine().allocateAndPersist(po.getId(), fr, du, ha, strategy, updateCatalog, actor)
                            : services.getLandedCostEngine().calculatePreview(po.getId(), fr, du, ha, strategy),
                    res -> {
                        toastManager.showInfo(res.getMessage());
                        if (res.isSuccess() && res.getData() != null) {
                            targetModel.setAll(res.getData().lines());
                            if (persist && updateCatalog) {
                                refreshAsync();
                            }
                        }
                    },
                    ex -> toastManager.showInfo("Landed cost failed: " + ex.getMessage())
            );
        } catch (NumberFormatException ex) {
            toastManager.showInfo("Enter numeric freight, customs, and handling amounts.");
        }
    }

    private void openVolumeTiersDialog() {
        Stage dlg = createModalStage("Vendor Bulk Pricing Tiers & MOQ Advisor", 580, 470);
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));

        ComboBox<Supplier> supPick = new ComboBox<>(supplierRows);
        ComboBox<Product> prodPick = new ComboBox<>(FXCollections.observableArrayList(services.getInventoryService().getAllProducts()));
        if (!supplierRows.isEmpty()) {
            supPick.getSelectionModel().selectFirst();
        }
        if (!prodPick.getItems().isEmpty()) {
            prodPick.getSelectionModel().selectFirst();
        }

        TextField minQtyF = new TextField("50");
        TextField moqF = new TextField("10");
        TextField unitPriceF = new TextField("4.20");
        TextField testReqF = new TextField("35");
        Label adviceLbl = new Label("Add a bulk price tier or evaluate a candidate order quantity.");
        adviceLbl.setWrapText(true);
        adviceLbl.getStyleClass().add("section-title");

        ObservableList<com.stockpro.domain.VendorPriceTier> tierRows = FXCollections.observableArrayList();
        TableView<com.stockpro.domain.VendorPriceTier> table = new TableView<>(tierRows);
        table.setPrefHeight(175);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<com.stockpro.domain.VendorPriceTier, String> mCol = new TableColumn<>("Min Qty");
        mCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().minQty())));
        TableColumn<com.stockpro.domain.VendorPriceTier, String> qCol = new TableColumn<>("MOQ");
        qCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().moq())));
        TableColumn<com.stockpro.domain.VendorPriceTier, String> pCol = new TableColumn<>("Tier Unit Cost");
        pCol.setCellValueFactory(c -> new SimpleStringProperty(String.format("$%.2f", c.getValue().unitPrice())));
        table.getColumns().addAll(List.of(mCol, qCol, pCol));

        Runnable reloadTiers = () -> {
            Supplier s = supPick.getValue();
            Product p = prodPick.getValue();
            if (s != null && p != null) {
                AppExecutor.runAsync(
                        () -> services.getVolumePricingTierService().listTiersFor(s.getId(), p.getId()),
                        tierRows::setAll,
                        ex -> {}
                );
            }
        };
        supPick.setOnAction(e -> reloadTiers.run());
        prodPick.setOnAction(e -> reloadTiers.run());
        reloadTiers.run();

        Button addTierBtn = new Button("Save Tier");
        addTierBtn.getStyleClass().add("btn-primary");
        addTierBtn.setOnAction(e -> {
            Supplier s = supPick.getValue();
            Product p = prodPick.getValue();
            if (s == null || p == null) {
                return;
            }
            try {
                int minQ = Integer.parseInt(minQtyF.getText().trim());
                int moq = Integer.parseInt(moqF.getText().trim());
                double up = Double.parseDouble(unitPriceF.getText().trim());
                AppExecutor.runAsync(
                        () -> services.getVolumePricingTierService().upsertTier(s.getId(), p.getId(), minQ, moq, up),
                        res -> {
                            toastManager.showInfo(res.getMessage());
                            reloadTiers.run();
                        },
                        ex -> toastManager.showInfo("Failed to save tier: " + ex.getMessage())
                );
            } catch (NumberFormatException ex) {
                toastManager.showInfo("Enter numeric Min Qty, MOQ, and Price.");
            }
        });

        Button checkQtyBtn = new Button("Run MOQ / Step-Up Advisor");
        checkQtyBtn.getStyleClass().add("btn-secondary");
        checkQtyBtn.setOnAction(e -> {
            Supplier s = supPick.getValue();
            Product p = prodPick.getValue();
            if (s == null || p == null) {
                return;
            }
            try {
                int reqQ = Integer.parseInt(testReqF.getText().trim());
                AppExecutor.runAsync(
                        () -> services.getVolumePricingTierService().evaluateOrderQuantity(s.getId(), p.getId(), reqQ, p.getBuyPrice()),
                        adv -> adviceLbl.setText(adv.advisorMessage()),
                        ex -> adviceLbl.setText("Error: " + ex.getMessage())
                );
            } catch (NumberFormatException ex) {
                adviceLbl.setText("Enter valid integer quantity.");
            }
        });

        HBox addRow = new HBox(8, new Label("Min:"), minQtyF, new Label("MOQ:"), moqF, new Label("Price:"), unitPriceF, addTierBtn);
        HBox advRow = new HBox(8, new Label("Order Qty:"), testReqF, checkQtyBtn);
        box.getChildren().addAll(supPick, prodPick, addRow, table, advRow, adviceLbl);
        showModalScene(dlg, box);
    }

    private void openCashFlowDialog() {
        Stage dlg = createModalStage("30 / 60 / 90-Day Cash-Flow Forecast", 600, 360);
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        box.setPadding(new Insets(18));

        ObservableList<com.stockpro.domain.CashFlowHorizon> rows = FXCollections.observableArrayList();
        TableView<com.stockpro.domain.CashFlowHorizon> table = new TableView<>(rows);
        table.setPrefHeight(220);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<com.stockpro.domain.CashFlowHorizon, String> hCol = new TableColumn<>("Horizon");
        hCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().horizonDays() + " Days"));
        TableColumn<com.stockpro.domain.CashFlowHorizon, String> inCol = new TableColumn<>("Est. Inflow");
        inCol.setCellValueFactory(c -> new SimpleStringProperty(AppSession.getInstance().formatMoney(c.getValue().projectedSalesInflow())));
        TableColumn<com.stockpro.domain.CashFlowHorizon, String> outCol = new TableColumn<>("PO + Restock Out");
        outCol.setCellValueFactory(c -> new SimpleStringProperty(AppSession.getInstance().formatMoney(
                c.getValue().committedPoOutflow() + c.getValue().projectedRestockOutflow())));
        TableColumn<com.stockpro.domain.CashFlowHorizon, String> netCol = new TableColumn<>("Net Cash");
        netCol.setCellValueFactory(c -> new SimpleStringProperty(AppSession.getInstance().formatMoney(c.getValue().netCashFlow())));
        TableColumn<com.stockpro.domain.CashFlowHorizon, String> verCol = new TableColumn<>("Outlook");
        verCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().summary()));
        table.getColumns().addAll(List.of(hCol, inCol, outCol, netCol, verCol));

        AppExecutor.runAsync(
                () -> services.getCashFlowForecastService().forecastHorizons(),
                rows::setAll,
                ex -> toastManager.showInfo("Forecast failed: " + ex.getMessage())
        );

        box.getChildren().addAll(new Label("Projected Store Liquidity Horizon (Open POs, Restock Needs & Sales Velocity)"), table);
        showModalScene(dlg, box);
    }

    private Stage createModalStage(String title, int w, int h) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.APPLICATION_MODAL);
        if (rootNode != null && rootNode.getScene() != null) {
            dlg.initOwner(rootNode.getScene().getWindow());
        }
        dlg.setTitle(title);
        dlg.setWidth(w);
        dlg.setHeight(h);
        return dlg;
    }

    private void showModalScene(Stage dlg, VBox content) {
        Scene sc = new Scene(content);
        var css = getClass().getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        dlg.setScene(sc);
        dlg.showAndWait();
    }
}
