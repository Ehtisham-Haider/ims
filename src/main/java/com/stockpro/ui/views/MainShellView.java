package com.stockpro.ui.views;

import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.User;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.CommandBarPopup;
import com.stockpro.ui.components.Motion;
import com.stockpro.ui.components.StockProLogo;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.ui.controllers.NavigationSection;
import com.stockpro.util.AppExecutor;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.OverrunStyle;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Primary desktop shell: an icon-rail sidebar that animates open to show labels, a minimal top
 * bar (brand on the left; currency, search, alerts, theme and profile controls on the right),
 * smooth fade/slide view transitions, the {@code Ctrl+K} command bar, keyboard shortcuts and
 * automatic EventBus listener cleanup.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class MainShellView {
    private static final double RAIL_COLLAPSED = 72;
    private static final double RAIL_EXPANDED = 224;

    /** Remembers the rail state for the rest of the session (e.g. across logout/login). */
    private static boolean sidebarCollapsed = true;

    private final ServiceContext services;
    private final Runnable onLogout;
    private final Runnable onApplyTheme;
    private final Runnable onExitApp;

    private final Map<NavigationSection, Button> navButtons = new EnumMap<>(NavigationSection.class);
    private final Map<NavigationSection, Supplier<Node>> viewFactories = new EnumMap<>(NavigationSection.class);
    private final List<Button> railButtons = new ArrayList<>();

    private StackPane rootOverlayHost;
    private StackPane contentViewport;
    private VBox sidebar;
    private Button collapseBtn;
    private Button themeToggleBtn;
    private Label alertBadge;
    private Label currencyLabel;
    private Timeline railTimeline;
    private final DoubleProperty railLabelAlpha = new SimpleDoubleProperty(1);

    private ToastNotificationManager toastManager;
    private CommandBarPopup commandBarPopup;
    private NavigationSection currentSection = NavigationSection.DASHBOARD;

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
        viewFactories.put(NavigationSection.INVENTORY, () -> createInventoryView().build());
        viewFactories.put(NavigationSection.STOCK_OPERATIONS, () -> {
            clearShortcutHooks();
            StockOperationsView stockView = new StockOperationsView(services, toastManager);
            activeViewDispose = stockView::dispose;
            return stockView.build();
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
        viewFactories.put(NavigationSection.SETTINGS, () -> createSettingsView(0).build());
    }

    private InventoryView createInventoryView() {
        InventoryView inv = new InventoryView(services, toastManager, this::refreshAlertBadge);
        clearShortcutHooks();
        setActiveAddShortcutHandler(inv::openAddProductDialog);
        setActiveSearchShortcutHandler(inv::focusSearch);
        setActiveDeleteShortcutHandler(inv::deleteSelectedRow);
        activeViewDispose = inv::dispose;
        activeViewThemeRefresh = inv::onThemeChanged;
        return inv;
    }

    private SettingsView createSettingsView(int tabIndex) {
        clearShortcutHooks();
        return new SettingsView(services, toastManager, () -> {
            updateThemeToggle();
            refreshCurrencyLabel();
            onApplyTheme.run();
        }, tabIndex);
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
     * Builds the complete shell layout with sidebar rail, top bar, content area, and overlays.
     *
     * @return root {@link Parent} node
     */
    public Parent build() {
        rootOverlayHost = new StackPane();
        BorderPane shellLayout = new BorderPane();
        shellLayout.getStyleClass().add("app-canvas");

        sidebar = buildSidebar();
        railLabelAlpha.addListener((o, a, b) -> applyRailLabelAlpha(b.doubleValue()));
        shellLayout.setLeft(sidebar);

        VBox rightColumn = new VBox();
        rightColumn.setFillWidth(true);
        HBox topBar = buildTopBar();
        contentViewport = new StackPane();
        contentViewport.setAlignment(Pos.TOP_LEFT);
        contentViewport.setPadding(new Insets(2, 16, 12, 2));
        VBox.setVgrow(contentViewport, Priority.ALWAYS);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(contentViewport.widthProperty());
        clip.heightProperty().bind(contentViewport.heightProperty());
        contentViewport.setClip(clip);

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

        applyRailState(false);
        navigateTo(NavigationSection.DASHBOARD);
        refreshAlertBadge();
        return rootOverlayHost;
    }

    /**
     * Installs global keyboard shortcuts ({@code Ctrl+K}, {@code Ctrl+N}, {@code Ctrl+F},
     * {@code Ctrl+B}, {@code Ctrl+Z}, {@code Ctrl+Y}, {@code Del}) on the host scene.
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
            if (activeAddShortcutHandler == null) {
                navigateTo(NavigationSection.INVENTORY);
            }
            if (activeAddShortcutHandler != null) {
                activeAddShortcutHandler.run();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> {
            if (activeSearchShortcutHandler != null) {
                activeSearchShortcutHandler.run();
            } else {
                commandBarPopup.show();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN),
                this::toggleSidebarCollapse);
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

    // ---------------------------------------------------------------------------------------------
    // Sidebar rail
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the icon rail: accent logo tile, primary destinations, then expand, settings,
     * logout and exit pinned to the bottom.
     *
     * @return configured sidebar {@link VBox}
     */
    private VBox buildSidebar() {
        VBox box = new VBox(8);
        box.getStyleClass().add("sidebar");
        box.setFillWidth(true);

        Node logo = StockProLogo.create(44);
        VBox.setMargin(logo, new Insets(0, 0, 40, 0));
        box.getChildren().add(logo);

        for (NavigationSection section : NavigationSection.values()) {
            if (section == NavigationSection.SETTINGS) {
                continue;
            }
            box.getChildren().add(createNavButton(section));
        }

        Region verticalSpacer = new Region();
        VBox.setVgrow(verticalSpacer, Priority.ALWAYS);
        box.getChildren().add(verticalSpacer);

        collapseBtn = railButton("Expand", Feather.CHEVRONS_RIGHT, true);
        collapseBtn.setOnAction(e -> toggleSidebarCollapse());

        Button settingsBtn = createNavButton(NavigationSection.SETTINGS);

        Button logoutBtn = railButton("Logout", Feather.LOG_OUT, true);
        logoutBtn.setOnAction(e -> logout());

        Button exitBtn = railButton("Exit StockPro", Feather.POWER, true);
        exitBtn.setGraphic(AppIcons.of(Feather.POWER, 18, "#EF4444"));
        exitBtn.setOnAction(e -> {
            cleanupBeforeLeave();
            onExitApp.run();
        });

        box.getChildren().addAll(collapseBtn, settingsBtn, logoutBtn, exitBtn);
        return box;
    }

    private Button createNavButton(NavigationSection section) {
        Button btn = railButton(section.getLabel(), featherFor(section), false);
        btn.setOnAction(e -> navigateTo(section));
        navButtons.put(section, btn);
        return btn;
    }

    private Button railButton(String label, Feather icon, boolean plain) {
        Button btn = new Button(label);
        btn.setGraphic(AppIcons.of(icon, 18));
        btn.setGraphicTextGap(12);
        btn.getStyleClass().add("nav-button");
        if (plain) {
            btn.getStyleClass().add("nav-button-plain");
        }
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setUserData(label);
        Motion.watchState(btn);
        railButtons.add(btn);
        return btn;
    }

    /**
     * Animates the rail between its icon-only and labelled widths.
     */
    private void toggleSidebarCollapse() {
        sidebarCollapsed = !sidebarCollapsed;
        applyRailState(true);
    }

    private void applyRailState(boolean animate) {
        double target = sidebarCollapsed ? RAIL_COLLAPSED : RAIL_EXPANDED;
        collapseBtn.setGraphic(AppIcons.of(sidebarCollapsed ? Feather.CHEVRONS_RIGHT : Feather.CHEVRONS_LEFT, 18));
        collapseBtn.setText(sidebarCollapsed ? "Expand" : "Collapse");
        collapseBtn.setUserData(sidebarCollapsed ? "Expand  (Ctrl+B)" : "Collapse  (Ctrl+B)");

        if (railTimeline != null) {
            railTimeline.stop();
        }
        if (!animate) {
            setRailWidth(target);
            setRailLabelsVisible(!sidebarCollapsed);
            railLabelAlpha.set(1);
            return;
        }

        Duration total = Duration.millis(300);
        if (sidebarCollapsed) {
            // Fade labels out first, then let the rail close underneath them.
            railTimeline = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(railLabelAlpha, railLabelAlpha.get())),
                    new KeyFrame(Duration.millis(110), new KeyValue(railLabelAlpha, 0, Motion.EASE)),
                    new KeyFrame(total,
                            new KeyValue(sidebar.prefWidthProperty(), target, Motion.EASE),
                            new KeyValue(sidebar.minWidthProperty(), target, Motion.EASE),
                            new KeyValue(sidebar.maxWidthProperty(), target, Motion.EASE)));
            railTimeline.setOnFinished(e -> {
                setRailLabelsVisible(false);
                railLabelAlpha.set(1);
            });
        } else {
            // Labels are laid out (invisible) immediately and fade in as the rail opens.
            railLabelAlpha.set(0);
            setRailLabelsVisible(true);
            railTimeline = new Timeline(
                    new KeyFrame(Duration.millis(120), new KeyValue(railLabelAlpha, 0)),
                    new KeyFrame(total,
                            new KeyValue(railLabelAlpha, 1, Motion.EASE),
                            new KeyValue(sidebar.prefWidthProperty(), target, Motion.EASE),
                            new KeyValue(sidebar.minWidthProperty(), target, Motion.EASE),
                            new KeyValue(sidebar.maxWidthProperty(), target, Motion.EASE)));
        }
        railTimeline.play();
    }

    private void applyRailLabelAlpha(double alpha) {
        for (Button b : railButtons) {
            Node text = b.lookup(".text");
            if (text != null) {
                text.setOpacity(alpha);
            }
        }
    }

    private void setRailWidth(double width) {
        sidebar.setMinWidth(width);
        sidebar.setPrefWidth(width);
        sidebar.setMaxWidth(width);
    }

    private void setRailLabelsVisible(boolean visible) {
        for (Button b : railButtons) {
            b.setTextOverrun(OverrunStyle.CLIP);
            b.setContentDisplay(visible ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
            b.setTooltip(visible ? null : new Tooltip(String.valueOf(b.getUserData())));
            b.applyCss();
        }
        applyRailLabelAlpha(railLabelAlpha.get());
    }

    // ---------------------------------------------------------------------------------------------
    // Top bar
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds the top bar: two-line brand on the left; currency pill, search, alerts with badge,
     * theme toggle and profile avatar on the right (mirroring the reference layout).
     *
     * @return configured header {@link HBox}
     */
    private HBox buildTopBar() {
        HBox bar = new HBox(8);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Label brandTop = new Label(AppSession.getInstance().getStoreName() != null
                && !AppSession.getInstance().getStoreName().isBlank()
                ? AppSession.getInstance().getStoreName() : "StockPro");
        brandTop.getStyleClass().add("brand-title");
        Label brandBottom = new Label("Inventory");
        brandBottom.getStyleClass().add("brand-sub");
        VBox brand = new VBox(-4, brandTop, brandBottom);
        brand.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Currency pill → Store settings
        currencyLabel = new Label();
        currencyLabel.getStyleClass().add("kbd");
        Button currencyPill = new Button();
        currencyPill.getStyleClass().add("top-pill");
        HBox pillContent = new HBox(8, currencyLabel, AppIcons.of(Feather.CHEVRON_DOWN, 14));
        pillContent.setAlignment(Pos.CENTER_LEFT);
        currencyPill.setGraphic(pillContent);
        currencyPill.setTooltip(new Tooltip("Store currency"));
        currencyPill.setOnAction(e -> navigateToSettingsTab(0));
        refreshCurrencyLabel();

        Button searchBtn = topIconButton(Feather.SEARCH, "Search products, screens and actions  (Ctrl+K)");
        searchBtn.setOnAction(e -> commandBarPopup.show());

        Button bellBtn = topIconButton(Feather.BELL, "Low / out-of-stock alerts");
        bellBtn.setOnAction(e -> toastManager.showAlertsBannerList(getUrgentProducts(), this::navigateToInventoryWithProduct));
        alertBadge = new Label("0");
        alertBadge.getStyleClass().add("badge-count");
        alertBadge.setMouseTransparent(true);
        alertBadge.setVisible(false);
        StackPane bellHost = new StackPane(bellBtn, alertBadge);
        StackPane.setAlignment(alertBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(alertBadge, new Insets(-7, -7, 0, 0));

        themeToggleBtn = topIconButton(Feather.MOON, "Toggle light / dark mode");
        themeToggleBtn.setOnAction(e -> toggleTheme());
        updateThemeToggle();

        bar.getChildren().addAll(brand, spacer, currencyPill, searchBtn, bellHost, themeToggleBtn, buildAvatar());
        return bar;
    }

    private Button topIconButton(Feather icon, String tooltip) {
        Button btn = new Button();
        btn.setGraphic(AppIcons.of(icon, 17));
        btn.getStyleClass().add("top-icon-btn");
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }

    private Node buildAvatar() {
        User current = AppSession.getInstance().getCurrentUser();
        String initials = current != null ? current.getInitials() : "SP";
        String fullName = current != null ? current.getFullName() : "Guest";
        String role = current != null ? current.getRole().getDisplayName() : "User";

        Button avatar = new Button(initials);
        avatar.getStyleClass().add("avatar");
        avatar.setTooltip(new Tooltip(fullName + " · " + role));

        MenuItem who = new MenuItem(fullName + "  ·  " + role);
        who.setDisable(true);
        MenuItem settings = new MenuItem("Settings");
        settings.setOnAction(e -> navigateTo(NavigationSection.SETTINGS));
        MenuItem logout = new MenuItem("Logout");
        logout.setOnAction(e -> logout());
        ContextMenu menu = new ContextMenu(who, new SeparatorMenuItem(), settings, logout);
        avatar.setOnAction(e -> menu.show(avatar, Side.BOTTOM, 0, 6));
        return avatar;
    }

    private void refreshCurrencyLabel() {
        if (currencyLabel == null) {
            return;
        }
        String raw = services.getSettingsRepository().get("store.currency", "USD ($)");
        int open = raw.indexOf('(');
        String code = open > 0 ? raw.substring(0, open).trim() : raw.trim();
        currencyLabel.setText(AppSession.getInstance().getCurrencySymbol() + "  " + code);
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------------

    /**
     * Switches the active center screen with a subtle fade/slide while disposing previous view
     * EventBus listeners.
     *
     * @param section destination view section
     */
    public void navigateTo(NavigationSection section) {
        if (section == null) {
            return;
        }
        disposeActiveView();
        currentSection = section;
        updateActiveNavButton(section);
        Supplier<Node> factory = viewFactories.get(section);
        if (factory != null) {
            showView(factory.get());
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
        InventoryView inv = createInventoryView();
        showView(inv.build());
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
        InventoryView inv = createInventoryView();
        showView(inv.build());
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
        showView(createSettingsView(tabIndex).build());
        refreshAlertBadge();
    }

    private void showView(Node view) {
        contentViewport.getChildren().setAll(view);
        if (view instanceof Parent parent) {
            Motion.enhance(parent);
        }
        Motion.reveal(view, 10);
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
                // A failing dispose hook must never block navigation.
            }
            activeViewDispose = null;
        }
        activeViewThemeRefresh = null;
    }

    private void cleanupBeforeLeave() {
        disposeActiveView();
        if (railTimeline != null) {
            railTimeline.stop();
        }
    }

    private void logout() {
        cleanupBeforeLeave();
        services.getAuthService().logout();
        onLogout.run();
    }

    // ---------------------------------------------------------------------------------------------
    // Theme
    // ---------------------------------------------------------------------------------------------

    /**
     * Switches runtime Light/Dark theme immediately and persists preference in SQLite.
     */
    public void toggleTheme() {
        setDarkTheme(!AppSession.getInstance().isDarkMode());
    }

    private void setDarkTheme(boolean nextDark) {
        // Freeze the current frame on top, switch themes underneath, then dissolve the old frame
        // away so every color in the window cross-fades instead of snapping.
        ImageView frozen = snapshotOverlay();
        AppSession.getInstance().setDarkMode(nextDark);
        services.getSettingsRepository().set("ui.theme", nextDark ? "dark" : "light");
        updateThemeToggle();
        onApplyTheme.run();
        if (activeViewThemeRefresh != null) {
            activeViewThemeRefresh.run();
        }
        if (frozen != null) {
            FadeTransition dissolve = new FadeTransition(Duration.millis(420), frozen);
            dissolve.setToValue(0);
            dissolve.setInterpolator(Interpolator.EASE_BOTH);
            dissolve.setOnFinished(e -> rootOverlayHost.getChildren().remove(frozen));
            // Restyling the whole window makes the next frame slow; start the dissolve only once
            // that frame has been drawn (hidden under the frozen image) so no part of it is skipped.
            new AnimationTimer() {
                private int frames;

                @Override
                public void handle(long now) {
                    if (++frames >= 2) {
                        stop();
                        dissolve.play();
                    }
                }
            }.start();
        }
        toastManager.showInfo("Switched to " + (nextDark ? "Dark" : "Light") + " Mode.");
    }

    private ImageView snapshotOverlay() {
        if (rootOverlayHost == null || rootOverlayHost.getScene() == null
                || rootOverlayHost.getWidth() <= 0 || rootOverlayHost.getHeight() <= 0) {
            return null;
        }
        double sx = 1;
        double sy = 1;
        if (rootOverlayHost.getScene().getWindow() != null) {
            sx = rootOverlayHost.getScene().getWindow().getOutputScaleX();
            sy = rootOverlayHost.getScene().getWindow().getOutputScaleY();
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(sx, sy));
        WritableImage image = rootOverlayHost.snapshot(params, null);
        ImageView view = new ImageView(image);
        view.setFitWidth(rootOverlayHost.getWidth());
        view.setFitHeight(rootOverlayHost.getHeight());
        view.setMouseTransparent(true);
        view.setManaged(false);
        rootOverlayHost.getChildren().add(view);
        return view;
    }

    /**
     * Shows a moon in light mode and a sun in dark mode on the top-bar theme toggle.
     */
    private void updateThemeToggle() {
        if (themeToggleBtn == null) {
            return;
        }
        boolean dark = AppSession.getInstance().isDarkMode();
        themeToggleBtn.setGraphic(AppIcons.of(dark ? Feather.SUN : Feather.MOON, 17));
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

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
     * Updates the top-bar alert badge count asynchronously.
     */
    public void refreshAlertBadge() {
        if (alertBadge == null) {
            return;
        }
        AppExecutor.runAsync(
                () -> getUrgentProducts().size(),
                urgentCount -> {
                    alertBadge.setText(urgentCount > 99 ? "99+" : String.valueOf(urgentCount));
                    alertBadge.setVisible(urgentCount > 0);
                    alertBadge.getStyleClass().remove("badge-count-alert");
                    if (urgentCount > 0) {
                        alertBadge.getStyleClass().add("badge-count-alert");
                    }
                },
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

    private Feather featherFor(NavigationSection section) {
        return switch (section) {
            case DASHBOARD -> Feather.GRID;
            case INVENTORY -> Feather.PACKAGE;
            case STOCK_OPERATIONS -> Feather.SHOPPING_BAG;
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
