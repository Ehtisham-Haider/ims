package com.stockpro.ui.views;

import com.stockpro.domain.PickRoutePlan;
import com.stockpro.domain.PickRouteStep;
import com.stockpro.domain.Product;
import com.stockpro.domain.ShelfBinRecord;
import com.stockpro.service.AppSession;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.WarehouseMapPickingService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive 2D Warehouse Floorplan view displaying aisles {@code A..E}, color-coded
 * shelf capacity meters, {@code >=85%} saturation alerts, docked Shelf Inspector with
 * one-click product relocation, and a Dijkstra shortest-path smart picking checklist.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class WarehouseMapView extends VBox {

    private final ServiceContext services;
    private final ToastNotificationManager toastManager;
    private final WarehouseMapPickingService mapService;

    private final Map<String, ShelfBinRecord> binIndex = new LinkedHashMap<>();
    private final Map<Long, Integer> pickQueue = new LinkedHashMap<>();
    private final Set<String> activeRouteBins = new HashSet<>();

    private String selectedBinCode = "A1-01";
    private String aisleFilter = "ALL";
    private boolean saturatedOnlyFilter = false;

    private Label saturationAlertPill;
    private FlowPane floorplanPane;
    private Label inspectorBinBadge;
    private Label inspectorTierBadge;
    private ProgressBar weightBar;
    private Label weightLabel;
    private ProgressBar volumeBar;
    private Label volumeLabel;
    private Label saturationBanner;
    private final ObservableList<Product> binProductsModel = FXCollections.observableArrayList();
    private ListView<Product> binProductsList;
    private ComboBox<Product> moveProductPicker;
    private VBox routeStepsBox;
    private Label routeSummaryLabel;
    private PickRoutePlan latestPlan;

    /**
     * Constructs the interactive 2D Warehouse Map &amp; Pick Optimizer screen.
     *
     * @param services application service context
     * @param toastManager toast notification feedback handler
     */
    public WarehouseMapView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.toastManager = toastManager;
        this.mapService = services.getWarehouseMapPickingService();

        setSpacing(16);
        setPadding(new Insets(22, 24, 24, 24));
        buildUi();
        loadFloorplanAsync();
    }

    private void buildUi() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleCol = new VBox(3);
        Label title = new Label("2D Warehouse Map & Pick Optimizer");
        title.getStyleClass().add("page-title");
        Label sub = new Label("Inspect aisles Aâ€“E (240 shelf bins), relocate SKUs in one click, monitor 85%+ bin saturation, and optimize pick tours via Dijkstra.");
        sub.getStyleClass().add("muted-text");
        titleCol.getChildren().addAll(title, sub);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        saturationAlertPill = new Label("0 Bins >= 85%");
        saturationAlertPill.getStyleClass().add("status-pill-neutral");
        saturationAlertPill.setStyle("-fx-cursor: hand; -fx-padding: 6 12; -fx-font-weight: 700;");
        saturationAlertPill.setOnMouseClicked(e -> {
            saturatedOnlyFilter = !saturatedOnlyFilter;
            renderFloorplanGrid();
        });

        Button refreshBtn = new Button("Recalculate Loads");
        refreshBtn.setGraphic(AppIcons.of(Feather.REFRESH_CW, 14));
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> loadFloorplanAsync());

        Button optimizeBtn = new Button("Optimize Pick Route");
        optimizeBtn.setGraphic(AppIcons.of(Feather.NAVIGATION, 14, "#FFFFFF"));
        optimizeBtn.getStyleClass().add("btn-primary");
        optimizeBtn.setOnAction(e -> computePickRouteAsync());

        header.getChildren().addAll(titleCol, spacer, saturationAlertPill, refreshBtn, optimizeBtn);

        HBox filterBar = buildAisleFilterBar();

        HBox splitBody = new HBox(16);
        VBox.setVgrow(splitBody, Priority.ALWAYS);

        VBox mapCard = buildFloorplanCard();
        HBox.setHgrow(mapCard, Priority.ALWAYS);

        VBox inspectorCard = buildInspectorCard();
        inspectorCard.setMinWidth(350);
        inspectorCard.setPrefWidth(365);

        splitBody.getChildren().addAll(mapCard, inspectorCard);

        VBox pickingCard = buildSmartPickingCard();

        getChildren().addAll(header, filterBar, splitBody, pickingCard);
    }

    private HBox buildAisleFilterBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);

        ToggleGroup group = new ToggleGroup();
        String[] filters = {"ALL", "A", "B", "C", "D", "E"};
        for (String f : filters) {
            ToggleButton btn = new ToggleButton("ALL".equals(f) ? "All Aisles (Aâ€“E)" : "Aisle " + f);
            btn.setToggleGroup(group);
            btn.setSelected("ALL".equals(f));
            btn.getStyleClass().add("btn-secondary");
            btn.setOnAction(e -> {
                aisleFilter = f;
                renderFloorplanGrid();
            });
            bar.getChildren().add(btn);
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label legend = new Label("Legend: Gray Empty  |  Green <50%  |  Amber 50â€“84%  |  Red >=85% Full  |  Indigo Ring = Active Pick Stop");
        legend.getStyleClass().add("muted-text");
        bar.getChildren().addAll(sp, legend);
        return bar;
    }

    private VBox buildFloorplanCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        Label cardTitle = new Label("Live 2D Floorplan Grid (Click any bin to inspect)");
        cardTitle.getStyleClass().add("section-title");
        head.getChildren().addAll(AppIcons.of(Feather.MAP, 15, "#4F46E5"), cardTitle);

        floorplanPane = new FlowPane(12, 12);
        floorplanPane.setPadding(new Insets(4));

        ScrollPane scroll = new ScrollPane(floorplanPane);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(330);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        card.getChildren().addAll(head, scroll);
        return card;
    }

    private VBox buildInspectorCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        inspectorBinBadge = new Label("Bin A1-01");
        inspectorBinBadge.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        inspectorTierBadge = new Label("EMPTY");
        inspectorTierBadge.getStyleClass().add("status-pill-neutral");
        top.getChildren().addAll(AppIcons.of(Feather.MAP_PIN, 15, "#4F46E5"), inspectorBinBadge, sp, inspectorTierBadge);

        weightLabel = new Label("Weight: 0.0 / 120.0 kg (0.0%)");
        weightLabel.getStyleClass().add("muted-text");
        weightBar = new ProgressBar(0.0);
        weightBar.setMaxWidth(Double.MAX_VALUE);

        volumeLabel = new Label("Volume: 0.0 / 250.0 dm3 (0.0%)");
        volumeLabel.getStyleClass().add("muted-text");
        volumeBar = new ProgressBar(0.0);
        volumeBar.setMaxWidth(Double.MAX_VALUE);

        saturationBanner = new Label("Shelf nearly full (>=85%). Move overflow stock to an adjacent bay.");
        saturationBanner.setWrapText(true);
        saturationBanner.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; -fx-padding: 8 10; -fx-background-radius: 8; -fx-font-weight: 600;");
        saturationBanner.setVisible(false);
        saturationBanner.setManaged(false);

        Label itemsTitle = new Label("Stocked SKUs in Selected Bin");
        itemsTitle.getStyleClass().add("section-title");

        binProductsList = new ListView<>(binProductsModel);
        binProductsList.setPrefHeight(135);
        binProductsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                Label info = new Label(item.getSku() + " â€¢ " + item.getName() + " (Qty " + item.getQuantity() + ")");
                info.setWrapText(false);
                HBox.setHgrow(info, Priority.ALWAYS);
                Button addPick = new Button("+ Pick");
                addPick.getStyleClass().add("btn-secondary");
                addPick.setOnAction(e -> {
                    pickQueue.merge(item.getId(), 1, Integer::sum);
                    toastManager.showInfo("Added " + item.getSku() + " to pick queue.");
                    computePickRouteAsync();
                });
                row.getChildren().addAll(info, addPick);
                setGraphic(row);
            }
        });

        Label moveTitle = new Label("One-Click Move Item Into This Bin");
        moveTitle.getStyleClass().add("section-title");

        moveProductPicker = new ComboBox<>();
        moveProductPicker.setMaxWidth(Double.MAX_VALUE);
        moveProductPicker.setPromptText("Choose any product to relocate here...");
        moveProductPicker.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getSku() + " - " + item.getName() + " [" + item.getShelfLocation() + "]");
            }
        });
        moveProductPicker.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Product item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "Choose product..." : item.getSku() + " - " + item.getName());
            }
        });

        Button moveBtn = new Button("Move Selected SKU Here");
        moveBtn.setGraphic(AppIcons.of(Feather.MOVE, 14, "#FFFFFF"));
        moveBtn.getStyleClass().add("btn-primary");
        moveBtn.setMaxWidth(Double.MAX_VALUE);
        moveBtn.setOnAction(e -> moveSelectedProductHere());

        card.getChildren().addAll(
                top,
                weightLabel, weightBar,
                volumeLabel, volumeBar,
                saturationBanner,
                itemsTitle, binProductsList,
                moveTitle, moveProductPicker, moveBtn
        );
        return card;
    }

    private VBox buildSmartPickingCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(16));

        HBox head = new HBox(10);
        head.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Smart Picking Checklist (Shortest Walk Order)");
        title.getStyleClass().add("section-title");

        routeSummaryLabel = new Label("Click '+ Pick' on items or 'Seed Low/Demo Picks' to compute shortest Dijkstra route.");
        routeSummaryLabel.getStyleClass().add("muted-text");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button sampleBtn = new Button("Quick Multi-Aisle Batch");
        sampleBtn.getStyleClass().add("btn-secondary");
        sampleBtn.setOnAction(e -> seedMultiAisleSampleQueue());

        Button clearBtn = new Button("Clear Queue");
        clearBtn.getStyleClass().add("btn-secondary");
        clearBtn.setOnAction(e -> {
            pickQueue.clear();
            activeRouteBins.clear();
            latestPlan = null;
            renderPickSteps();
            renderFloorplanGrid();
        });

        head.getChildren().addAll(AppIcons.of(Feather.CHECK_SQUARE, 15, "#4F46E5"), title, sp, sampleBtn, clearBtn);

        routeStepsBox = new VBox(6);
        ScrollPane stepScroll = new ScrollPane(routeStepsBox);
        stepScroll.setFitToWidth(true);
        stepScroll.setPrefHeight(145);
        stepScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        card.getChildren().addAll(head, routeSummaryLabel, stepScroll);
        return card;
    }

    /**
     * Refreshes bin loads and floorplan grid asynchronously off the JavaFX UI thread.
     */
    public void loadFloorplanAsync() {
        AppExecutor.runAsync(
                () -> {
                    List<ShelfBinRecord> bins = mapService.listAllBins();
                    List<Product> products = services.getInventoryService().getAllProducts();
                    return new FloorplanSnapshot(bins, products);
                },
                snapshot -> {
                    binIndex.clear();
                    int saturated = 0;
                    for (ShelfBinRecord bin : snapshot.bins()) {
                        binIndex.put(bin.binCode(), bin);
                        if (bin.isNearlyFull()) {
                            saturated++;
                        }
                    }
                    saturationAlertPill.setText(saturated + " Bin(s) >= 85%");
                    saturationAlertPill.getStyleClass().setAll(saturated > 0 ? "status-pill-danger" : "status-pill-success");
                    moveProductPicker.setItems(FXCollections.observableArrayList(snapshot.products()));
                    renderFloorplanGrid();
                    selectBinAsync(selectedBinCode);
                },
                ex -> toastManager.showInfo("Failed to load floorplan: " + ex.getMessage())
        );
    }

    private void renderFloorplanGrid() {
        floorplanPane.getChildren().clear();
        Map<String, List<ShelfBinRecord>> byAisle = new LinkedHashMap<>();
        for (ShelfBinRecord bin : binIndex.values()) {
            if (!"ALL".equals(aisleFilter) && !aisleFilter.equals(bin.aisle())) {
                continue;
            }
            if (saturatedOnlyFilter && !bin.isNearlyFull()) {
                continue;
            }
            byAisle.computeIfAbsent(bin.aisle(), k -> new ArrayList<>()).add(bin);
        }

        if (byAisle.isEmpty()) {
            Label empty = new Label(saturatedOnlyFilter
                    ? "No bins currently exceed 85% capacity."
                    : "No matching bins found.");
            empty.getStyleClass().add("muted-text");
            floorplanPane.getChildren().add(empty);
            return;
        }

        boolean dark = AppSession.getInstance().isDarkMode();
        for (Map.Entry<String, List<ShelfBinRecord>> entry : byAisle.entrySet()) {
            VBox aisleCard = new VBox(6);
            aisleCard.setPadding(new Insets(10));
            aisleCard.setStyle(dark
                    ? "-fx-background-color: #0F172A; -fx-background-radius: 10; -fx-border-color: #1E293B; -fx-border-radius: 10;"
                    : "-fx-background-color: #F8FAFC; -fx-background-radius: 10; -fx-border-color: #E2E8F0; -fx-border-radius: 10;");

            Label aisleHead = new Label("Aisle " + entry.getKey());
            aisleHead.setStyle("-fx-font-weight: 700; -fx-font-size: 12px;");

            GridPane grid = new GridPane();
            grid.setHgap(4);
            grid.setVgap(4);

            for (ShelfBinRecord bin : entry.getValue()) {
                Button tile = new Button(String.format(Locale.ROOT, "R%d-%02d", bin.rowIndex(), bin.colIndex()));
                tile.setMinSize(44, 28);
                tile.setPrefSize(44, 28);
                tile.setStyle(buildTileStyle(bin, dark));
                tile.setTooltip(new Tooltip(String.format(Locale.US,
                        "%s â€¢ %d SKU(s), %d unit(s)\nWeight: %.1f / %.0f kg (%.0f%%)\nVolume: %.1f / %.0f dm3 (%.0f%%)",
                        bin.binCode(), bin.skuCount(), bin.totalUnits(),
                        bin.currentWeightKg(), bin.maxWeightKg(), bin.weightPercent(),
                        bin.currentVolumeDm3(), bin.maxVolumeDm3(), bin.volumePercent())));
                tile.setOnAction(e -> {
                    selectedBinCode = bin.binCode();
                    renderFloorplanGrid();
                    selectBinAsync(bin.binCode());
                });
                grid.add(tile, bin.colIndex() - 1, bin.rowIndex() - 1);
            }
            aisleCard.getChildren().addAll(aisleHead, grid);
            floorplanPane.getChildren().add(aisleCard);
        }
    }

    private String buildTileStyle(ShelfBinRecord bin, boolean dark) {
        String bg;
        String fg;
        switch (bin.capacityTier()) {
            case "FULL" -> {
                bg = dark ? "#7F1D1D" : "#FEE2E2";
                fg = dark ? "#FECACA" : "#991B1B";
            }
            case "MODERATE" -> {
                bg = dark ? "#78350F" : "#FEF3C7";
                fg = dark ? "#FDE68A" : "#92400E";
            }
            case "LOW" -> {
                bg = dark ? "#14532D" : "#DCFCE7";
                fg = dark ? "#BBF7D0" : "#166534";
            }
            default -> {
                bg = dark ? "#1E293B" : "#E2E8F0";
                fg = dark ? "#CBD5E1" : "#334155";
            }
        }
        String border = bin.binCode().equals(selectedBinCode)
                ? "-fx-border-color: #111827; -fx-border-width: 2;"
                : activeRouteBins.contains(bin.binCode())
                ? "-fx-border-color: #4F46E5; -fx-border-width: 2;"
                : "-fx-border-color: transparent; -fx-border-width: 1;";
        return "-fx-background-color: " + bg + "; -fx-text-fill: " + fg
                + "; -fx-font-size: 10px; -fx-font-weight: 700; -fx-background-radius: 6; -fx-border-radius: 6; -fx-cursor: hand;" + border;
    }

    private void selectBinAsync(String binCode) {
        ShelfBinRecord record = binIndex.get(binCode);
        if (record != null) {
            inspectorBinBadge.setText("Bin " + record.binCode() + " (Aisle " + record.aisle() + ")");
            inspectorTierBadge.setText(record.capacityTier());
            String pillStyle = switch (record.capacityTier()) {
                case "FULL" -> "status-pill-danger";
                case "MODERATE" -> "status-pill-warning";
                case "LOW" -> "status-pill-success";
                default -> "status-pill-neutral";
            };
            inspectorTierBadge.getStyleClass().setAll(pillStyle);

            weightBar.setProgress(Math.min(1.0, record.weightPercent() / 100.0));
            weightLabel.setText(String.format(Locale.US, "Weight: %.1f / %.1f kg (%.1f%%)",
                    record.currentWeightKg(), record.maxWeightKg(), record.weightPercent()));

            volumeBar.setProgress(Math.min(1.0, record.volumePercent() / 100.0));
            volumeLabel.setText(String.format(Locale.US, "Volume: %.1f / %.1f dm3 (%.1f%%)",
                    record.currentVolumeDm3(), record.maxVolumeDm3(), record.volumePercent()));

            boolean alert = record.isNearlyFull();
            saturationBanner.setVisible(alert);
            saturationBanner.setManaged(alert);
        }

        AppExecutor.runAsync(
                () -> mapService.listProductsInBin(binCode),
                items -> binProductsModel.setAll(items),
                ex -> toastManager.showInfo("Could not inspect bin " + binCode + ": " + ex.getMessage())
        );
    }

    private void moveSelectedProductHere() {
        Product chosen = moveProductPicker.getValue();
        if (chosen == null) {
            toastManager.showInfo("Select a product first to move it into " + selectedBinCode + ".");
            return;
        }
        String actor = AppSession.getInstance().getActorName();
        AppExecutor.runAsync(
                () -> mapService.moveItemToBin(chosen.getId(), selectedBinCode, actor),
                res -> {
                    toastManager.showInfo(res.getMessage());
                    loadFloorplanAsync();
                },
                ex -> toastManager.showInfo("Relocation failed: " + ex.getMessage())
        );
    }

    private void seedMultiAisleSampleQueue() {
        AppExecutor.runAsync(
                () -> {
                    List<Product> products = services.getInventoryService().getAllProducts();
                    Map<String, Product> distinctAisles = new HashMap<>();
                    for (Product p : products) {
                        if (p.getQuantity() > 0) {
                            String bin = mapService.normalizeBinCode(p.getShelfLocation());
                            distinctAisles.putIfAbsent(bin.substring(0, 1), p);
                            if (distinctAisles.size() >= 5) {
                                break;
                            }
                        }
                    }
                    return new ArrayList<>(distinctAisles.values());
                },
                sampled -> {
                    if (sampled.isEmpty()) {
                        toastManager.showInfo("Add inventory items before building a picking batch.");
                        return;
                    }
                    pickQueue.clear();
                    for (Product p : sampled) {
                        pickQueue.put(p.getId(), 1);
                    }
                    computePickRouteAsync();
                },
                ex -> toastManager.showInfo("Sample queue failed: " + ex.getMessage())
        );
    }

    private void computePickRouteAsync() {
        Map<Long, Integer> copy = new LinkedHashMap<>(pickQueue);
        AppExecutor.runAsync(
                () -> mapService.optimizePickList(copy),
                plan -> {
                    latestPlan = plan;
                    activeRouteBins.clear();
                    for (PickRouteStep step : plan.steps()) {
                        activeRouteBins.add(step.binCode());
                    }
                    renderPickSteps();
                    renderFloorplanGrid();
                },
                ex -> toastManager.showInfo("Route calculation failed: " + ex.getMessage())
        );
    }

    private void renderPickSteps() {
        routeStepsBox.getChildren().clear();
        if (latestPlan == null || latestPlan.steps().isEmpty()) {
            routeSummaryLabel.setText("Queue is empty. Add items from any Shelf Inspector or click 'Quick Multi-Aisle Batch'.");
            return;
        }
        routeSummaryLabel.setText(latestPlan.summary());
        for (PickRouteStep step : latestPlan.steps()) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 10, 6, 10));
            row.setStyle("-fx-background-radius: 8; -fx-border-color: rgba(79,70,229,0.22); -fx-border-radius: 8;");

            CheckBox doneBox = new CheckBox("#" + step.stepNumber() + " [" + step.binCode() + "]");
            doneBox.setStyle("-fx-font-weight: 700;");
            Label details = new Label(step.sku() + " â€¢ " + step.productName() + " x" + step.pickQuantity()
                    + "  |  " + step.instruction() + " (" + step.cumulativeDistanceMeters() + "m)");
            details.getStyleClass().add("muted-text");
            HBox.setHgrow(details, Priority.ALWAYS);

            Button jumpBtn = new Button("Focus Bin");
            jumpBtn.getStyleClass().add("btn-secondary");
            jumpBtn.setOnAction(e -> {
                selectedBinCode = step.binCode();
                renderFloorplanGrid();
                selectBinAsync(step.binCode());
            });

            doneBox.selectedProperty().addListener((obs, o, n) -> details.setOpacity(Boolean.TRUE.equals(n) ? 0.5 : 1.0));
            row.getChildren().addAll(doneBox, details, jumpBtn);
            routeStepsBox.getChildren().add(row);
        }
    }

    /**
     * Re-renders map tile colors cleanly after switching Light and Dark modes.
     */
    public void refreshTheme() {
        renderFloorplanGrid();
    }

    private record FloorplanSnapshot(List<ShelfBinRecord> bins, List<Product> products) {
    }
}
