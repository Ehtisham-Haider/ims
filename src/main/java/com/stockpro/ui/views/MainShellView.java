package com.stockpro.ui.views;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.StoreBranch;
import com.stockpro.domain.User;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.CommandBarPopup;
import com.stockpro.ui.components.StockProLogo;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Primary desktop shell featuring the collapsible Midnight Navy sidebar with Feather vector icons,
 * modern white/dark top header bar, Sun/Moon pill toggle, {@code Ctrl+K} Spotlight Command Bar,
 * urgent alert bell badge, exit button, keyboard shortcuts, and automatic EventBus listener cleanup.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class MainShellView {
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("hh:mm a");

    private final ServiceContext services;
    private final Runnable onLogout;
    private final Runnable onApplyTheme;
    private final Runnable onExitApp;

    private final Map<NavigationSection, Button> navButtons = new EnumMap<>(NavigationSection.class);
    private final Map<NavigationSection, Supplier<Node>> viewFactories = new EnumMap<>(NavigationSection.class);

    private StackPane rootOverlayHost;
    private StackPane contentViewport;
    private VBox sidebar;
    private Label sidebarTitle;
    private Label sidebarTagline;
    private VBox profileTextBox;
    private Button collapseBtn;
    private Button lightModePillBtn;
    private Button darkModePillBtn;
    private Button bellButton;
    private Label clockLabel;

    private ToastNotificationManager toastManager;
    private CommandBarPopup commandBarPopup;
    private Timeline clockTimeline;
    private NavigationSection currentSection = NavigationSection.DASHBOARD;
    private boolean sidebarCollapsed = false;

    private Runnable activeAddShortcutHandler;
    private Runnable activeSearchShortcutHandler;
    private Runnable activeDeleteShortcutHandler;
    private Runnable activeExportPdfHandler;
    private Runnable activeViewDispose;
    private Runnable activeViewThemeRefresh;

    /**
     * Constructs the main shell view around shared application services.
     *
     * @param services initialized service registry
     * @param onLogout callback triggered when the user logs out
     * @param onApplyTheme callback invoked when Light/Dark theme is toggled
     */
    public MainShellView(ServiceContext services, Runnable onLogout, Runnable onApplyTheme) {
        this(services, onLogout, onApplyTheme, () -> {});
    }

    /**
     * Constructs the main shell view around shared application services including window exit support.
     *
     * @param services initialized service registry
     * @param onLogout callback triggered when the user logs out
     * @param onApplyTheme callback invoked when Light/Dark theme is toggled
     * @param onExitApp callback invoked when the user clicks Exit App
     */
    public MainShellView(ServiceContext services, Runnable onLogout, Runnable onApplyTheme, Runnable onExitApp) {
        this.services = services;
        this.onLogout = onLogout;
        this.onApplyTheme = onApplyTheme;
        this.onExitApp = onExitApp != null ? onExitApp : () -> {};
        registerDefaultViews();
    }

    /**
     * Registers view factories wired with dispose hooks, theme repaint callbacks, and cross-screen navigation.
     */
    private void registerDefaultViews() {
        viewFactories.put(NavigationSection.DASHBOARD, () -> {
            clearShortcutHooks();
            DashboardView dash = new DashboardView(
                    services,
                    this::navigateTo,
                    this::navigateToInventoryWithProduct,
                    this::navigateToInventoryWithFilter,
                    () -> navigateToSettingsTab(1)
            );
            activeViewDispose = dash::dispose;
            activeViewThemeRefresh = dash::onThemeChanged;
            return dash.build();
        });
        viewFactories.put(NavigationSection.INVENTORY, () -> {
            InventoryView inv = new InventoryView(services, toastManager, this::refreshAlertBadge);
            setActiveAddShortcutHandler(inv::openAddProductDialog);
            setActiveSearchShortcutHandler(inv::focusSearch);
            setActiveDeleteShortcutHandler(inv::deleteSelectedRow);
            activeViewDispose = inv::dispose;
            activeViewThemeRefresh = inv::onThemeChanged;
            return inv.build();
        });
        viewFactories.put(NavigationSection.STOCK_OPERATIONS, () -> {
            clearShortcutHooks();
            StockOperationsView stockView = new StockOperationsView(services, toastManager);
            activeViewDispose = stockView::dispose;
            return stockView.build();
        });
        viewFactories.put(NavigationSection.WAREHOUSE_MAP, () -> {
            clearShortcutHooks();
            WarehouseMapView mapView = new WarehouseMapView(services, toastManager);
            activeViewThemeRefresh = mapView::refreshTheme;
            return mapView;
        });
        viewFactories.put(NavigationSection.SUPPLIERS_ORDERS, () -> {
            clearShortcutHooks();
            SuppliersOrdersView supView = new SuppliersOrdersView(services, toastManager);
            activeViewDispose = supView::dispose;
            return supView.build();
        });
        viewFactories.put(NavigationSection.ANALYTICS_REPORTS, () -> {
            clearShortcutHooks();
            AnalyticsReportsView view = new AnalyticsReportsView(services, toastManager, this::navigateTo);
            setActiveExportPdfHandler(view::exportExecutiveReportPdf);
            activeViewDispose = view::dispose;
            activeViewThemeRefresh = view::onThemeChanged;
            return view.build();
        });
        viewFactories.put(NavigationSection.AI_ASSISTANT, () -> {
            clearShortcutHooks();
            AiAssistantView aiView = new AiAssistantView(
                    services,
                    toastManager,
                    () -> navigateToSettingsTab(1),
                    this::navigateToInventoryWithFilter
            );
            activeViewDispose = aiView::dispose;
            activeViewThemeRefresh = aiView::onThemeChanged;
            return aiView.build();
        });
        viewFactories.put(NavigationSection.SETTINGS, () -> {
            clearShortcutHooks();
            SettingsView settingsView = new SettingsView(services, toastManager, () -> {
                updateThemeTogglePills();
                onApplyTheme.run();
            });
            return settingsView.build();
        });
    }

    private void clearShortcutHooks() {
        setActiveAddShortcutHandler(null);
        setActiveSearchShortcutHandler(null);
        setActiveDeleteShortcutHandler(null);
        setActiveExportPdfHandler(null);
    }

    /**
     * Registers or overrides the view factory for a navigation destination.
     *
     * @param section target navigation section
     * @param factory supplier creating the JavaFX view node on navigation
     */
    public void registerView(NavigationSection section, Supplier<Node> factory) {
        if (section != null && factory != null) {
            viewFactories.put(section, factory);
        }
    }

    /**
     * Builds the complete shell layout with sidebar, header bar, content area, and overlays.
     *
     * @return root {@link Parent} node
     */
    public Parent build() {
        rootOverlayHost = new StackPane();
        BorderPane shellLayout = new BorderPane();
        shellLayout.getStyleClass().add("app-canvas");

        sidebar = buildSidebar();
        shellLayout.setLeft(sidebar);

        VBox rightColumn = new VBox();
        rightColumn.setFillWidth(true);
        HBox topBar = buildTopBar();
        contentViewport = new StackPane();
        contentViewport.setPadding(new Insets(18, 24, 18, 24));
        VBox.setVgrow(contentViewport, Priority.ALWAYS);

        rightColumn.getChildren().addAll(topBar, contentViewport);
        shellLayout.setCenter(rightColumn);

        rootOverlayHost.getChildren().add(shellLayout);

        toastManager = new ToastNotificationManager(rootOverlayHost);
        commandBarPopup = new CommandBarPopup(
                rootOverlayHost,
                services.getInventoryService(),
                this::navigateTo,
                this::toggleTheme,
                this::triggerExportPdf,
                this::navigateToInventoryWithProduct,
                toastManager
        );

        navigateTo(NavigationSection.DASHBOARD);
        refreshAlertBadge();
        startClockTicker();

        return rootOverlayHost;
    }

    /**
     * Installs global keyboard shortcuts ({@code Ctrl+K}, {@code Ctrl+N}, {@code Ctrl+F},
     * {@code Ctrl+Z}, {@code Ctrl+Y}, {@code Del}) on the host scene.
     *
     * @param scene active JavaFX scene
     */
    public void bindShortcuts(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN),
                () -> commandBarPopup.show());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), () -> {
            if (activeAddShortcutHandler != null) {
                activeAddShortcutHandler.run();
            } else {
                navigateTo(NavigationSection.INVENTORY);
                if (activeAddShortcutHandler != null) {
                    activeAddShortcutHandler.run();
                }
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> {
            if (activeSearchShortcutHandler != null) {
                activeSearchShortcutHandler.run();
            } else {
                commandBarPopup.show();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN), this::performUndo);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN), this::performRedo);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE && activeDeleteShortcutHandler != null) {
                activeDeleteShortcutHandler.run();
            }
        });
        services.getUsbBarcodeScannerListener().attachToScene(scene, outcome -> {
            toastManager.showInfo(outcome.message());
            if (outcome.matched() && outcome.product() != null) {
                refreshAlertBadge();
            }
        });
    }

    /**
     * Builds the Deep Midnight Navy collapsible sidebar matching the reference mockups.
     *
     * @return configured sidebar {@link VBox}
     */
    private VBox buildSidebar() {
        VBox box = new VBox(6);
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(232);
        box.setMinWidth(74);

        HBox brandRow = new HBox(10);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(2, 4, 14, 4));

        VBox brandTexts = new VBox(1);
        sidebarTitle = new Label("StockPro");
        sidebarTitle.getStyleClass().add("sidebar-brand-title");
        sidebarTagline = new Label("Smart Inventory");
        sidebarTagline.getStyleClass().add("sidebar-brand-sub");
        brandTexts.getChildren().addAll(sidebarTitle, sidebarTagline);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        collapseBtn = new Button();
        collapseBtn.setGraphic(AppIcons.of(Feather.MENU, 16, "#94A3B8"));
        collapseBtn.setTooltip(new Tooltip("Collapse / Expand Sidebar"));
        collapseBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 5;");
        collapseBtn.setOnAction(e -> toggleSidebarCollapse());

        brandRow.getChildren().addAll(StockProLogo.create(30), brandTexts, spacer, collapseBtn);
        box.getChildren().add(brandRow);

        for (NavigationSection section : NavigationSection.values()) {
            Button btn = new Button(section.getLabel());
            btn.setGraphic(AppIcons.of(featherFor(section), 16));
            btn.setGraphicTextGap(12);
            btn.getStyleClass().add("nav-button");
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setTooltip(new Tooltip(section.getLabel()));
            btn.setOnAction(e -> navigateTo(section));
            navButtons.put(section, btn);
            box.getChildren().add(btn);
        }

        Region verticalSpacer = new Region();
        VBox.setVgrow(verticalSpacer, Priority.ALWAYS);
        box.getChildren().add(verticalSpacer);

        User current = AppSession.getInstance().getCurrentUser();
        String initials = current != null ? current.getInitials() : "SP";
        String fullName = current != null ? current.getFullName() : "Guest";
        String role = current != null ? current.getRole().getDisplayName() : "User";

        HBox userRow = new HBox(10);
        userRow.setAlignment(Pos.CENTER_LEFT);
        userRow.setPadding(new Insets(10, 6, 6, 6));

        Label avatar = new Label(initials);
        avatar.setMinSize(34, 34);
        avatar.setAlignment(Pos.CENTER);
        avatar.setStyle("-fx-background-color: #4F46E5; -fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-background-radius: 999;");

        profileTextBox = new VBox(1);
        Label nameLbl = new Label(fullName);
        nameLbl.getStyleClass().add("sidebar-profile-name");
        Label roleLbl = new Label(role);
        roleLbl.getStyleClass().add("sidebar-profile-role");
        profileTextBox.getChildren().addAll(nameLbl, roleLbl);
        userRow.getChildren().addAll(avatar, profileTextBox);

        Button logoutBtn = new Button("Logout");
        logoutBtn.setGraphic(AppIcons.of(Feather.LOG_OUT, 15));
        logoutBtn.setGraphicTextGap(12);
        logoutBtn.getStyleClass().add("nav-button");
        logoutBtn.setMaxWidth(Double.MAX_VALUE);
        logoutBtn.setOnAction(e -> {
            cleanupBeforeLeave();
            services.getAuthService().logout();
            onLogout.run();
        });

        box.getChildren().addAll(userRow, logoutBtn);
        return box;
    }

    /**
     * Builds the modern top header bar containing Spotlight search, alerts bell, Sun/Moon pill switch,
     * live clock badge, and quick Exit App action.
     *
     * @return configured header {@link HBox}
     */
    private HBox buildTopBar() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Button searchTrigger = new Button("Search products, reports, or press Ctrl+K...");
        searchTrigger.setGraphic(AppIcons.of(Feather.SEARCH, 15));
        searchTrigger.setGraphicTextGap(10);
        searchTrigger.getStyleClass().add("search-box");
        searchTrigger.setPrefWidth(360);
        searchTrigger.setAlignment(Pos.CENTER_LEFT);
        searchTrigger.setStyle("-fx-cursor: hand;");
        searchTrigger.setOnAction(e -> commandBarPopup.show());

        Button voiceMicBtn = new Button("Voice");
        voiceMicBtn.setGraphic(AppIcons.of(Feather.MIC, 14, "#4F46E5"));
        voiceMicBtn.getStyleClass().add("btn-secondary");
        voiceMicBtn.setTooltip(new Tooltip("Hands-Free Voice Command & Whisper Search"));
        voiceMicBtn.setOnAction(e -> openTopBarVoiceModal(voiceMicBtn));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ComboBox<StoreBranch> branchSwitcher = new ComboBox<>();
        branchSwitcher.setPrefWidth(190);
        branchSwitcher.setTooltip(new Tooltip("Switch active physical store / warehouse branch"));
        AppExecutor.runAsync(
                () -> services.getMultiBranchTransferRmaService().listBranches(),
                branches -> {
                    if (branches != null && !branches.isEmpty()) {
                        branchSwitcher.getItems().setAll(branches);
                        long currId = AppSession.getInstance().getActiveBranchId();
                        StoreBranch matched = branches.stream()
                                .filter(b -> b.id() == currId)
                                .findFirst()
                                .orElse(branches.get(0));
                        branchSwitcher.setValue(matched);
                        AppSession.getInstance().setActiveBranchId(matched.id());
                        AppSession.getInstance().setActiveBranchName(matched.name());
                    }
                },
                ex -> {
                });
        branchSwitcher.setOnAction(e -> {
            StoreBranch selected = branchSwitcher.getValue();
            if (selected != null) {
                AppSession.getInstance().setActiveBranchId(selected.id());
                AppSession.getInstance().setActiveBranchName(selected.name());
                toastManager.showInfo("Switched active branch to " + selected.name() + " (" + selected.branchCode() + ").");
            }
        });

        clockLabel = new Label("Live • " + LocalTime.now().format(CLOCK_FORMAT));
        clockLabel.getStyleClass().add("clock-pill");

        bellButton = new Button("0");
        bellButton.setGraphic(AppIcons.of(Feather.BELL, 15));
        bellButton.setGraphicTextGap(6);
        bellButton.getStyleClass().add("btn-secondary");
        bellButton.setTooltip(new Tooltip("View urgent Low/Out-of-Stock alerts"));
        bellButton.setOnAction(e -> {
            List<Product> urgent = getUrgentProducts();
            toastManager.showAlertsBannerList(urgent, this::navigateToInventoryWithProduct);
        });

        lightModePillBtn = new Button();
        lightModePillBtn.setGraphic(AppIcons.of(Feather.SUN, 14));
        lightModePillBtn.getStyleClass().add("theme-switch-btn");
        lightModePillBtn.setTooltip(new Tooltip("Light Mode"));
        lightModePillBtn.setOnAction(e -> setDarkTheme(false));

        darkModePillBtn = new Button();
        darkModePillBtn.setGraphic(AppIcons.of(Feather.MOON, 14));
        darkModePillBtn.getStyleClass().add("theme-switch-btn");
        darkModePillBtn.setTooltip(new Tooltip("Dark Mode"));
        darkModePillBtn.setOnAction(e -> setDarkTheme(true));

        HBox themePill = new HBox(2, lightModePillBtn, darkModePillBtn);
        themePill.getStyleClass().add("theme-switch-pill");
        themePill.setAlignment(Pos.CENTER);
        updateThemeTogglePills();

        Button exitBtn = new Button();
        exitBtn.setGraphic(AppIcons.of(Feather.POWER, 15));
        exitBtn.getStyleClass().add("icon-btn-danger");
        exitBtn.setTooltip(new Tooltip("Close StockPro Application"));
        exitBtn.setOnAction(e -> {
            cleanupBeforeLeave();
            onExitApp.run();
        });

        bar.getChildren().addAll(searchTrigger, voiceMicBtn, spacer, branchSwitcher, clockLabel, bellButton, themePill, exitBtn);
        return bar;
    }

    private void openTopBarVoiceModal(Button sourceBtn) {
        javafx.stage.Stage dlg = new javafx.stage.Stage();
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        if (sourceBtn != null && sourceBtn.getScene() != null) {
            dlg.initOwner(sourceBtn.getScene().getWindow());
        }
        dlg.setTitle("StockPro Voice Control & Whisper Search");

        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        boolean micReady = services.getVoiceAudioRecorder().isMicrophoneAvailable();
        Label hint = new Label(micReady
                ? "Speak or type a command ('Add 10 Coffee', 'Restock 5 Milk', 'Open Warehouse Map', 'Find Rice')."
                : "Simulated Voice / Whisper Command Input ('Add 10 Coffee', 'Open Warehouse Map', 'Find Rice'):");
        hint.setWrapText(true);
        hint.getStyleClass().add("muted-text");

        javafx.scene.control.TextField cmdField = new javafx.scene.control.TextField();
        cmdField.setPromptText("Speak or enter voice command...");

        Button runBtn = new Button(micReady ? "Record & Execute (Whisper)" : "Execute Voice Command");
        runBtn.setGraphic(AppIcons.of(Feather.MIC, 14, "#FFFFFF"));
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> {
            runBtn.setDisable(true);
            String typed = cmdField.getText();
            String actor = AppSession.getInstance().getActorName();
            AppExecutor.runAsync(() -> {
                byte[] wav = services.getVoiceAudioRecorder().recordClipOrSyntheticWav(2000);
                return services.getVoiceCommandEngine().transcribeAndExecute(wav, typed, actor);
            }, result -> {
                runBtn.setDisable(false);
                dlg.close();
                toastManager.showInfo(result.message());
                if (result.intent() == com.stockpro.domain.VoiceCommandResult.VoiceIntent.NAVIGATE
                        && result.targetSectionName() != null && !result.targetSectionName().isBlank()) {
                    navigateTo(NavigationSection.valueOf(result.targetSectionName()));
                } else if (result.intent() == com.stockpro.domain.VoiceCommandResult.VoiceIntent.VOICE_SEARCH
                        && result.matchedProductId() > 0) {
                    services.getProductRepository().findById(result.matchedProductId())
                            .ifPresent(this::navigateToInventoryWithProduct);
                } else {
                    refreshAlertBadge();
                }
            }, ex -> {
                runBtn.setDisable(false);
                toastManager.showInfo("Voice error: " + ex.getMessage());
            });
        });

        Button closeBtn = new Button("Cancel");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> dlg.close());

        card.getChildren().addAll(hint, cmdField, new HBox(10, runBtn, closeBtn));
        Scene sc = new Scene(card, 480, 185);
        String cssPath = AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css";
        var cssUrl = getClass().getResource(cssPath);
        if (cssUrl != null) {
            sc.getStylesheets().add(cssUrl.toExternalForm());
        }
        dlg.setScene(sc);
        dlg.showAndWait();
    }

    /**
     * Switches the active center screen cleanly while disposing previous view EventBus listeners.
     *
     * @param section destination view section
     */
    public void navigateTo(NavigationSection section) {
        if (section == null) {
            return;
        }
        disposeActiveView();
        currentSection = section;
        navButtons.forEach((sec, btn) -> {
            btn.getStyleClass().remove("nav-button-active");
            if (sec == section) {
                btn.getStyleClass().add("nav-button-active");
            }
        });
        Supplier<Node> factory = viewFactories.get(section);
        if (factory != null) {
            contentViewport.getChildren().setAll(factory.get());
        }
        refreshAlertBadge();
    }

    /**
     * Opens the Inventory screen and immediately opens the Details Drawer for the given product.
     *
     * @param product product to inspect
     */
    public void navigateToInventoryWithProduct(Product product) {
        disposeActiveView();
        currentSection = NavigationSection.INVENTORY;
        updateActiveNavButton(NavigationSection.INVENTORY);

        InventoryView inv = new InventoryView(services, toastManager, this::refreshAlertBadge);
        setActiveAddShortcutHandler(inv::openAddProductDialog);
        setActiveSearchShortcutHandler(inv::focusSearch);
        setActiveDeleteShortcutHandler(inv::deleteSelectedRow);
        activeViewDispose = inv::dispose;
        activeViewThemeRefresh = inv::onThemeChanged;

        contentViewport.getChildren().setAll(inv.build());
        if (product != null) {
            inv.selectAndInspectProduct(product);
        }
        refreshAlertBadge();
    }

    /**
     * Opens the Inventory screen pre-filtered with structured criteria (e.g. from Smart Search or Urgent Alerts).
     *
     * @param filterCriteria initial catalog criteria to apply
     */
    public void navigateToInventoryWithFilter(InventoryFilterCriteria filterCriteria) {
        disposeActiveView();
        currentSection = NavigationSection.INVENTORY;
        updateActiveNavButton(NavigationSection.INVENTORY);

        InventoryView inv = new InventoryView(services, toastManager, this::refreshAlertBadge);
        setActiveAddShortcutHandler(inv::openAddProductDialog);
        setActiveSearchShortcutHandler(inv::focusSearch);
        setActiveDeleteShortcutHandler(inv::deleteSelectedRow);
        activeViewDispose = inv::dispose;
        activeViewThemeRefresh = inv::onThemeChanged;

        contentViewport.getChildren().setAll(inv.build());
        if (filterCriteria != null) {
            inv.applyExternalCriteria(filterCriteria);
        }
        refreshAlertBadge();
    }

    /**
     * Opens Settings directly on a specific tab index (e.g. index {@code 1} for AI &amp; Groq API).
     *
     * @param tabIndex zero-based tab index
     */
    public void navigateToSettingsTab(int tabIndex) {
        disposeActiveView();
        currentSection = NavigationSection.SETTINGS;
        updateActiveNavButton(NavigationSection.SETTINGS);
        clearShortcutHooks();
        SettingsView settingsView = new SettingsView(services, toastManager, () -> {
            updateThemeTogglePills();
            onApplyTheme.run();
        }, tabIndex);
        contentViewport.getChildren().setAll(settingsView.build());
        refreshAlertBadge();
    }

    private void updateActiveNavButton(NavigationSection target) {
        navButtons.forEach((sec, btn) -> {
            btn.getStyleClass().remove("nav-button-active");
            if (sec == target) {
                btn.getStyleClass().add("nav-button-active");
            }
        });
    }

    private void disposeActiveView() {
        if (activeViewDispose != null) {
            try {
                activeViewDispose.run();
            } catch (Exception ignored) {
            }
            activeViewDispose = null;
        }
        activeViewThemeRefresh = null;
    }

    private void cleanupBeforeLeave() {
        disposeActiveView();
        if (clockTimeline != null) {
            clockTimeline.stop();
        }
    }

    /**
     * Toggles between collapsed (icon-only) and expanded sidebar widths.
     */
    private void toggleSidebarCollapse() {
        sidebarCollapsed = !sidebarCollapsed;
        sidebar.setPrefWidth(sidebarCollapsed ? 74 : 232);
        sidebarTitle.setVisible(!sidebarCollapsed);
        sidebarTitle.setManaged(!sidebarCollapsed);
        sidebarTagline.setVisible(!sidebarCollapsed);
        sidebarTagline.setManaged(!sidebarCollapsed);
        profileTextBox.setVisible(!sidebarCollapsed);
        profileTextBox.setManaged(!sidebarCollapsed);
        navButtons.forEach((sec, btn) -> btn.setText(sidebarCollapsed ? "" : sec.getLabel()));
    }

    /**
     * Switches runtime Light/Dark theme immediately and persists preference in SQLite.
     */
    public void toggleTheme() {
        setDarkTheme(!AppSession.getInstance().isDarkMode());
    }

    private void setDarkTheme(boolean nextDark) {
        AppSession.getInstance().setDarkMode(nextDark);
        services.getSettingsRepository().set("ui.theme", nextDark ? "dark" : "light");
        updateThemeTogglePills();
        onApplyTheme.run();
        if (activeViewThemeRefresh != null) {
            activeViewThemeRefresh.run();
        }
        toastManager.showInfo("Switched to " + (nextDark ? "Dark" : "Light") + " Mode.");
    }

    /**
     * Updates Sun/Moon pill toggle styling to match active theme.
     */
    private void updateThemeTogglePills() {
        if (lightModePillBtn == null || darkModePillBtn == null) {
            return;
        }
        boolean dark = AppSession.getInstance().isDarkMode();
        lightModePillBtn.getStyleClass().remove("theme-switch-btn-active");
        darkModePillBtn.getStyleClass().remove("theme-switch-btn-active");
        if (dark) {
            darkModePillBtn.getStyleClass().add("theme-switch-btn-active");
        } else {
            lightModePillBtn.getStyleClass().add("theme-switch-btn-active");
        }
    }

    /**
     * Executes Undo and displays a confirmation toast.
     */
    public void performUndo() {
        InventoryOperationResult<String> res = services.getInventoryService().undoLastAction();
        toastManager.showInfo(res.getMessage());
        refreshAlertBadge();
    }

    /**
     * Executes Redo and displays a confirmation toast.
     */
    public void performRedo() {
        InventoryOperationResult<String> res = services.getInventoryService().redoLastAction();
        toastManager.showInfo(res.getMessage());
        refreshAlertBadge();
    }

    /**
     * Updates the header notification bell badge count asynchronously.
     */
    public void refreshAlertBadge() {
        if (bellButton == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> getUrgentProducts().size(),
                urgentCount -> bellButton.setText(String.valueOf(urgentCount)),
                ex -> {}
        );
    }

    /**
     * Finds products currently marked Low Stock or Out of Stock.
     *
     * @return list of urgent products
     */
    private List<Product> getUrgentProducts() {
        return services.getInventoryService().getAllProducts().stream()
                .filter(p -> p.getStatus() == StockStatus.LOW_STOCK || p.getStatus() == StockStatus.OUT_OF_STOCK)
                .toList();
    }

    /**
     * Runs PDF report export from ANY screen (or delegates to active Analytics handler).
     */
    private void triggerExportPdf() {
        if (activeExportPdfHandler != null) {
            activeExportPdfHandler.run();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName("StockPro-Business-Report.pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Documents", "*.pdf"));
        File out = chooser.showSaveDialog(rootOverlayHost != null && rootOverlayHost.getScene() != null
                ? rootOverlayHost.getScene().getWindow() : null);
        if (out == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> services.getExecutivePdfReportGenerator().generateBusinessReport("Last 30 Days", out.toPath()),
                path -> toastManager.showInfo("Exported Business Report PDF: " + path.getFileName()),
                ex -> toastManager.showInfo("Report PDF failed: " + ex.getMessage())
        );
    }

    /**
     * Starts a lightweight UI clock ticker updating the header time pill every 30 seconds.
     */
    private void startClockTicker() {
        if (clockTimeline != null) {
            clockTimeline.stop();
        }
        clockTimeline = new Timeline(new KeyFrame(Duration.seconds(30),
                e -> clockLabel.setText("Live • " + LocalTime.now().format(CLOCK_FORMAT))));
        clockTimeline.setCycleCount(Timeline.INDEFINITE);
        clockTimeline.play();
    }

    private Feather featherFor(NavigationSection section) {
        return switch (section) {
            case DASHBOARD -> Feather.HOME;
            case INVENTORY -> Feather.PACKAGE;
            case STOCK_OPERATIONS -> Feather.SHOPPING_CART;
            case WAREHOUSE_MAP -> Feather.MAP;
            case SUPPLIERS_ORDERS -> Feather.TRUCK;
            case ANALYTICS_REPORTS -> Feather.BAR_CHART_2;
            case AI_ASSISTANT -> Feather.CPU;
            case SETTINGS -> Feather.SETTINGS;
        };
    }

    /**
     * Returns the toast notification manager attached to this shell.
     *
     * @return {@link ToastNotificationManager}
     */
    public ToastNotificationManager getToastManager() {
        return toastManager;
    }

    /**
     * Returns the currently displayed navigation section.
     *
     * @return active {@link NavigationSection}
     */
    public NavigationSection getCurrentSection() {
        return currentSection;
    }

    /**
     * Sets the active {@code Ctrl+N} shortcut handler for the visible screen.
     *
     * @param handler action invoked on {@code Ctrl+N}
     */
    public void setActiveAddShortcutHandler(Runnable handler) {
        this.activeAddShortcutHandler = handler;
    }

    /**
     * Sets the active {@code Ctrl+F} shortcut handler for the visible screen.
     *
     * @param handler action invoked on {@code Ctrl+F}
     */
    public void setActiveSearchShortcutHandler(Runnable handler) {
        this.activeSearchShortcutHandler = handler;
    }

    /**
     * Sets the active {@code Delete} key handler for the visible screen.
     *
     * @param handler action invoked on {@code Delete}
     */
    public void setActiveDeleteShortcutHandler(Runnable handler) {
        this.activeDeleteShortcutHandler = handler;
    }

    /**
     * Sets the PDF export hook used by the Spotlight Command Bar.
     *
     * @param handler action invoked on {@code export pdf}
     */
    public void setActiveExportPdfHandler(Runnable handler) {
        this.activeExportPdfHandler = handler;
    }
}
