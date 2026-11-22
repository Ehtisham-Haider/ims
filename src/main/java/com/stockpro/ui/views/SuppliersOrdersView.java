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
import com.stockpro.ui.components.ThemeManager;
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
        storeValueCard = new KpiStatCard(Feather.DOLLAR_SIGN, "#4F46E5", KpiStatCard.INVERSE, "Total Store Value", "$0.00", "Cost valuation");
        storeProfitCard = new KpiStatCard(Feather.TRENDING_UP, "#10B981", KpiStatCard.ACCENT, "Est. Total Profit", "$0.00", "0% avg margin");
        taxEstimateCard = new KpiStatCard(Feather.PERCENT, "#8B5CF6", KpiStatCard.MUTED, "Tax / GST Estimate", "$0.00", "15% store rate");
        deadMoneyCard = new KpiStatCard(Feather.CLOCK, "#F59E0B", KpiStatCard.PLAIN, "Dead Money Stock", "$0.00", "0 stagnant SKUs");
        HBox financeKpiRow = new HBox(12, storeValueCard, storeProfitCard, taxEstimateCard, deadMoneyCard);

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

        page.getChildren().addAll(title, subtitle, financeKpiRow, splitRow, bottomRow);

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
        actCol.setMinWidth(210);
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

                HBox box = new HBox(6, pdfBtn);
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
        ThemeManager.apply(sc);
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
        ThemeManager.apply(sc);
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
}
