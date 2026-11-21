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
import com.stockpro.ui.components.KpiStatCard;
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
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Suppliers, Purchase Orders, and Financial Engine workspace matching
 * {@code Screens and popups/Suppliers and Orders.png}.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SuppliersOrdersView {
    private final ServiceContext services;
    private final SupplierOrderService supplierService;
    private final FinanceService financeService;
    private final ToastNotificationManager toastManager;

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
        InventoryEventBus.getInstance().subscribe(t -> refreshAsync());
    }

    /**
     * Builds the Suppliers &amp; Orders view layout.
     *
     * @return scrollable JavaFX {@link Node}
     */
    public Node build() {
        VBox page = new VBox(18);
        page.setPadding(new Insets(0, 4, 16, 0));

        Label title = new Label("Suppliers & Orders");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage vendor contacts, build purchase orders, export PDFs, and analyze store valuation & dead stock");
        subtitle.getStyleClass().add("page-subtitle");

        // Top Financial Engine KPI Strip
        storeValueCard = new KpiStatCard("🏦", "#4F46E5", "kpi-card-blue", "Total Store Value", "$0.00", "Cost valuation");
        storeProfitCard = new KpiStatCard("💹", "#10B981", "kpi-card-green", "Est. Total Profit", "$0.00", "0% avg margin");
        taxEstimateCard = new KpiStatCard("🧾", "#8B5CF6", "kpi-card-purple", "Tax / GST Estimate", "$0.00", "15% store rate");
        deadMoneyCard = new KpiStatCard("🐢", "#F59E0B", "kpi-card-amber", "Dead Money Stock", "$0.00", "0 stagnant SKUs");
        HBox financeKpiRow = new HBox(14, storeValueCard, storeProfitCard, taxEstimateCard, deadMoneyCard);

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
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        this.rootNode = scroll;

        AppExecutor.runAsync(() -> {
            supplierService.ensureSampleSuppliersIfEmpty();
            return true;
        }, ok -> refreshAsync(), ex -> {});

        return scroll;
    }

    /**
     * Builds the Supplier Address Book card with Add/Edit/Delete actions.
     *
     * @return styled supplier directory card
     */
    @SuppressWarnings("unchecked")
    private VBox buildSuppliersCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox hdr = new HBox(8);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Suppliers");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button addBtn = new Button("+ Add Supplier");
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
        actCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                Supplier s = getTableRow().getItem();
                Button edit = new Button("✎");
                edit.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
                edit.setOnAction(e -> openSupplierDialog(s));
                Button del = new Button("🗑");
                del.setStyle("-fx-background-color: transparent; -fx-text-fill: #EF4444; -fx-cursor: hand;");
                del.setOnAction(e -> {
                    InventoryOperationResult<Boolean> res = supplierService.deleteSupplier(s.getId());
                    toastManager.showInfo(res.getMessage());
                    refreshAsync();
                });
                setGraphic(new HBox(4, edit, del));
            }
        });
        table.getColumns().addAll(nameCol, phoneCol, leadCol, actCol);

        card.getChildren().addAll(hdr, table);
        return card;
    }

    /**
     * Builds the Quick multi-item Purchase Order Builder card.
     *
     * @return styled PO builder card
     */
    @SuppressWarnings("unchecked")
    private VBox buildPurchaseOrderCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        Label heading = new Label("Quick Purchase Order Builder");
        heading.getStyleClass().add("section-title");

        poSupplierCombo = new ComboBox<>();
        poSupplierCombo.setMaxWidth(Double.MAX_VALUE);
        poSupplierCombo.setPromptText("Select Supplier");

        poProductCombo = new ComboBox<>();
        poProductCombo.setPrefWidth(230);
        poProductCombo.setPromptText("Select Product");
        poProductCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + AppSession.getInstance().formatMoney(item.getBuyPrice()) + ")");
            }
        });
        poProductCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Select Product" : item.getName());
            }
        });

        poQtyField = new TextField("25");
        poQtyField.setPrefWidth(80);
        poQtyField.setPromptText("Qty");

        Button addLineBtn = new Button("+ Add Line");
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
        pQty.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<PurchaseOrderItem, String> pTotal = new TableColumn<>("Est. Cost");
        pTotal.setCellValueFactory(c -> new SimpleStringProperty(
                AppSession.getInstance().formatMoney(c.getValue().getLineTotal())));
        draftTable.getColumns().addAll(pName, pQty, pTotal);

        poTotalLabel = new Label("Estimated Total (incl. Tax): $0.00");
        poTotalLabel.setStyle("-fx-font-weight: bold;");

        Button generatePoBtn = new Button("Generate PO");
        generatePoBtn.getStyleClass().add("btn-primary");
        generatePoBtn.setMaxWidth(Double.MAX_VALUE);
        generatePoBtn.setOnAction(e -> createPurchaseOrder());

        card.getChildren().addAll(heading, poSupplierCombo, addLineRow, draftTable, poTotalLabel, generatePoBtn);
        return card;
    }

    /**
     * Builds the Purchase Order History table with lifecycle transitions and OpenPDF export button.
     *
     * @return styled PO history card
     */
    @SuppressWarnings("unchecked")
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
        table.getColumns().addAll(numCol, supCol, costCol, statusCol, actCol);

        card.getChildren().addAll(heading, table);
        return card;
    }

    /**
     * Builds the Dead Money Finder card displaying slow/zero-sale items over a configurable lookback window.
     *
     * @return styled dead money card
     */
    @SuppressWarnings("unchecked")
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
        table.getColumns().addAll(nCol, qCol, vCol);

        card.getChildren().addAll(hdr, table);
        return card;
    }

    /**
     * Adds a selected product and validated quantity to the draft Purchase Order list.
     */
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

    /**
     * Updates the draft Purchase Order subtotal and tax-inclusive total label.
     */
    private void updateDraftPoTotal() {
        double subtotal = draftItems.stream().mapToDouble(PurchaseOrderItem::getLineTotal).sum();
        double taxRate = AppSession.getInstance().getTaxRatePercent();
        double withTax = financeService.calculateTotalWithTax(subtotal, taxRate);
        poTotalLabel.setText(String.format("Subtotal: %s | Est. Total (incl. %.1f%% tax): %s",
                AppSession.getInstance().formatMoney(subtotal),
                taxRate,
                AppSession.getInstance().formatMoney(withTax)));
    }

    /**
     * Persists the drafted Purchase Order asynchronously.
     */
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

    /**
     * Transitions a Purchase Order status in a background task.
     *
     * @param poId order id
     * @param nextStatus target status
     */
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

    /**
     * Prompts for a file path and exports the selected Purchase Order as an OpenPDF sheet.
     *
     * @param po purchase order to export
     */
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

    /**
     * Opens a clean modal dialog to add or edit a supplier contact.
     *
     * @param existing optional supplier to edit, or {@code null} for new supplier
     */
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

        Scene sc = new Scene(box, 380, 440);
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
                    "Retail: " + session.formatMoney(summary.totalRetailValue()), "#4F46E5");
            storeProfitCard.updateMetric(session.formatMoney(summary.totalPotentialProfit()),
                    String.format("%.1f%% avg margin", summary.averageMarginPercent()), "#15803D");
            taxEstimateCard.updateMetric(session.formatMoney(summary.estimatedTaxAmount()),
                    String.format("%.1f%% GST • Incl: %s", summary.taxRatePercent(),
                            session.formatMoney(summary.retailValueWithTax())), "#6D28D9");
            deadMoneyCard.updateMetric(session.formatMoney(summary.deadMoneyValue()),
                    summary.deadMoneyItems().size() + " stagnant SKU(s)", "#B45309");
            deadMoneyRows.setAll(summary.deadMoneyItems());
        }, ex -> {
        });
    }
}
