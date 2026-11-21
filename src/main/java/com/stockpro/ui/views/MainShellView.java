package com.stockpro.ui.views;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;
import com.stockpro.domain.User;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.CommandBarPopup;
import com.stockpro.ui.components.StockProLogo;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.ui.controllers.NavigationSection;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Primary desktop shell featuring the collapsible Midnight Navy sidebar, white header bar,
 * Light/Dark theme switch, {@code Ctrl+K} Spotlight Command Bar, toast notifications, and keyboard shortcuts.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class MainShellView {
    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("hh:mm a");

    private final ServiceContext services;
    private final Runnable onLogout;
    private final Runnable onApplyTheme;

    private final Map<NavigationSection, Button> navButtons = new EnumMap<>(NavigationSection.class);
    private final Map<NavigationSection, Supplier<Node>> viewFactories = new EnumMap<>(NavigationSection.class);

    private StackPane rootOverlayHost;
    private StackPane contentViewport;
    private VBox sidebar;
    private Label sidebarTitle;
    private VBox profileTextBox;
    private Button collapseBtn;
    private Button themeToggleBtn;
    private Button bellButton;
    private Label clockLabel;

    private ToastNotificationManager toastManager;
    private CommandBarPopup commandBarPopup;
    private NavigationSection currentSection = NavigationSection.DASHBOARD;
    private boolean sidebarCollapsed = false;

    private Runnable activeAddShortcutHandler;
    private Runnable activeSearchShortcutHandler;
    private Runnable activeDeleteShortcutHandler;
    private Runnable activeExportPdfHandler;

    /**
     * Constructs the main shell view around shared application services.
     *
     * @param services initialized service registry
     * @param onLogout callback triggered when the user logs out
     * @param onApplyTheme callback invoked when Light/Dark theme is toggled
     */
    public MainShellView(ServiceContext services, Runnable onLogout, Runnable onApplyTheme) {
        this.services = services;
        this.onLogout = onLogout;
        this.onApplyTheme = onApplyTheme;
        registerDefaultPlaceholders();
    }

    /**
     * Registers default placeholder panels for upcoming feature branches until replaced by full views.
     */
    private void registerDefaultPlaceholders() {
        for (NavigationSection section : NavigationSection.values()) {
            viewFactories.put(section, () -> createSectionPlaceholder(section));
        }
        viewFactories.put(NavigationSection.DASHBOARD, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            return new DashboardView(services, this::navigateTo).build();
        });
        viewFactories.put(NavigationSection.INVENTORY, () -> {
            InventoryView inv = new InventoryView(services, toastManager, this::refreshAlertBadge);
            setActiveAddShortcutHandler(inv::openAddProductDialog);
            setActiveSearchShortcutHandler(inv::focusSearch);
            setActiveDeleteShortcutHandler(inv::deleteSelectedRow);
            return inv.build();
        });
        viewFactories.put(NavigationSection.STOCK_OPERATIONS, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            return new StockOperationsView(services, toastManager).build();
        });
        viewFactories.put(NavigationSection.SUPPLIERS_ORDERS, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            return new SuppliersOrdersView(services, toastManager).build();
        });
        viewFactories.put(NavigationSection.ANALYTICS_REPORTS, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            AnalyticsReportsView view = new AnalyticsReportsView(services, toastManager, this::navigateTo);
            setActiveExportPdfHandler(view::exportExecutiveReportPdf);
            return view.build();
        });
        viewFactories.put(NavigationSection.AI_ASSISTANT, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            return new AiAssistantView(services, toastManager).build();
        });
        viewFactories.put(NavigationSection.SETTINGS, () -> {
            setActiveAddShortcutHandler(null);
            setActiveSearchShortcutHandler(null);
            setActiveDeleteShortcutHandler(null);
            return new SettingsView(services, toastManager, () -> {
                updateThemeToggleLabel();
                onApplyTheme.run();
            }).build();
        });
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
        contentViewport.setPadding(new Insets(22, 26, 22, 26));
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
                this::inspectProductFromSearch,
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
                toastManager.showInfo("Shortcut Ctrl+N: Switched to Inventory to add a product.");
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
     * Builds the Deep Midnight Navy collapsible sidebar matching the reference design.
     *
     * @return configured sidebar {@link VBox}
     */
    private VBox buildSidebar() {
        VBox box = new VBox(8);
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(240);
        box.setMinWidth(74);

        HBox brandRow = new HBox(10);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(4, 4, 18, 4));

        sidebarTitle = new Label("StockPro");
        sidebarTitle.getStyleClass().add("sidebar-brand-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        collapseBtn = new Button("☰");
        collapseBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94A3B8; -fx-cursor: hand; -fx-font-size: 15px;");
        collapseBtn.setOnAction(e -> toggleSidebarCollapse());

        brandRow.getChildren().addAll(StockProLogo.create(28), sidebarTitle, spacer, collapseBtn);
        box.getChildren().add(brandRow);

        for (NavigationSection section : NavigationSection.values()) {
            Button btn = new Button(iconFor(section) + "  " + section.getLabel());
            btn.getStyleClass().add("nav-button");
            btn.setMaxWidth(Double.MAX_VALUE);
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
        userRow.setPadding(new Insets(10, 6, 8, 6));

        Label avatar = new Label(initials);
        avatar.setMinSize(34, 34);
        avatar.setAlignment(Pos.CENTER);
        avatar.setStyle("-fx-background-color: #4F46E5; -fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-background-radius: 999;");

        profileTextBox = new VBox(1);
        Label nameLbl = new Label(fullName);
        nameLbl.setStyle("-fx-text-fill: #FFFFFF; -fx-font-weight: bold; -fx-font-size: 13px;");
        Label roleLbl = new Label(role);
        roleLbl.setStyle("-fx-text-fill: #94A3B8; -fx-font-size: 11px;");
        profileTextBox.getChildren().addAll(nameLbl, roleLbl);
        userRow.getChildren().addAll(avatar, profileTextBox);

        Button logoutBtn = new Button("↪  Logout");
        logoutBtn.getStyleClass().add("nav-button");
        logoutBtn.setMaxWidth(Double.MAX_VALUE);
        logoutBtn.setOnAction(e -> {
            services.getAuthService().logout();
            onLogout.run();
        });

        box.getChildren().addAll(userRow, logoutBtn);
        return box;
    }

    /**
     * Builds the white top header bar containing Spotlight search trigger, notification bell, theme switch, and clock.
     *
     * @return configured header {@link HBox}
     */
    private HBox buildTopBar() {
        HBox bar = new HBox(14);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Button searchTrigger = new Button("🔍  Search anything...          Ctrl+K");
        searchTrigger.getStyleClass().add("search-box");
        searchTrigger.setPrefWidth(360);
        searchTrigger.setAlignment(Pos.CENTER_LEFT);
        searchTrigger.setStyle("-fx-cursor: hand;");
        searchTrigger.setOnAction(e -> commandBarPopup.show());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bellButton = new Button("🔔 0");
        bellButton.getStyleClass().add("btn-secondary");
        bellButton.setOnAction(e -> {
            List<Product> urgent = getUrgentProducts();
            toastManager.showAlertsBannerList(urgent, this::inspectProductFromSearch);
        });

        themeToggleBtn = new Button();
        themeToggleBtn.getStyleClass().add("btn-secondary");
        updateThemeToggleLabel();
        themeToggleBtn.setOnAction(e -> toggleTheme());

        clockLabel = new Label("● Live • " + LocalTime.now().format(CLOCK_FORMAT));
        clockLabel.setStyle("-fx-background-color: #EEF2FF; -fx-text-fill: #4F46E5; -fx-font-weight: bold; -fx-padding: 6 12 6 12; -fx-background-radius: 999;");

        bar.getChildren().addAll(searchTrigger, spacer, bellButton, themeToggleBtn, clockLabel);
        return bar;
    }

    /**
     * Switches the active center screen to the specified navigation section.
     *
     * @param section destination view section
     */
    public void navigateTo(NavigationSection section) {
        if (section == null) {
            return;
        }
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
     * Toggles between collapsed (icon-only) and expanded sidebar widths.
     */
    private void toggleSidebarCollapse() {
        sidebarCollapsed = !sidebarCollapsed;
        sidebar.setPrefWidth(sidebarCollapsed ? 74 : 240);
        sidebarTitle.setVisible(!sidebarCollapsed);
        sidebarTitle.setManaged(!sidebarCollapsed);
        profileTextBox.setVisible(!sidebarCollapsed);
        profileTextBox.setManaged(!sidebarCollapsed);
        navButtons.forEach((sec, btn) -> btn.setText(sidebarCollapsed ? iconFor(sec) : iconFor(sec) + "  " + sec.getLabel()));
    }

    /**
     * Switches runtime Light/Dark theme immediately and persists preference in SQLite.
     */
    public void toggleTheme() {
        boolean nextDark = !AppSession.getInstance().isDarkMode();
        AppSession.getInstance().setDarkMode(nextDark);
        services.getSettingsRepository().set("ui.theme", nextDark ? "dark" : "light");
        updateThemeToggleLabel();
        onApplyTheme.run();
        toastManager.showInfo("Switched to " + (nextDark ? "Dark" : "Light") + " Mode.");
    }

    /**
     * Updates the theme switch button icon/label to reflect current state.
     */
    private void updateThemeToggleLabel() {
        boolean dark = AppSession.getInstance().isDarkMode();
        themeToggleBtn.setText(dark ? "☀ Light" : "🌙 Dark");
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
     * Updates the header notification bell count with low-stock and out-of-stock items.
     */
    public void refreshAlertBadge() {
        if (bellButton == null) {
            return;
        }
        int urgentCount = getUrgentProducts().size();
        bellButton.setText("🔔 " + urgentCount);
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
     * Handles product selection from Spotlight search or alert popup.
     *
     * @param product chosen product
     */
    private void inspectProductFromSearch(Product product) {
        navigateTo(NavigationSection.INVENTORY);
        toastManager.showInfo("Selected " + product.getName() + " (" + product.getSku() + ")");
    }

    /**
     * Runs PDF report export handler or navigates to Analytics & Reports.
     */
    private void triggerExportPdf() {
        if (activeExportPdfHandler != null) {
            activeExportPdfHandler.run();
        } else {
            navigateTo(NavigationSection.ANALYTICS_REPORTS);
            toastManager.showInfo("Opened Analytics & Reports.");
        }
    }

    /**
     * Starts a lightweight UI clock ticker updating the header time pill every 30 seconds.
     */
    private void startClockTicker() {
        Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(30),
                e -> clockLabel.setText("● Live • " + LocalTime.now().format(CLOCK_FORMAT))));
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
    }

    /**
     * Builds a clean themed view placeholder card for a navigation section.
     *
     * @param section navigation section
     * @return placeholder {@link Node}
     */
    private Node createSectionPlaceholder(NavigationSection section) {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        Label title = new Label(section.getLabel());
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(services.getInventoryService().viewInventorySummaryMessage()
                + " • Signed in as " + AppSession.getInstance().getActorName());
        subtitle.getStyleClass().add("page-subtitle");
        box.getChildren().addAll(title, subtitle);
        return box;
    }

    /**
     * Returns an icon glyph for each sidebar section.
     *
     * @param section target section
     * @return compact symbol icon
     */
    private String iconFor(NavigationSection section) {
        return switch (section) {
            case DASHBOARD -> "🏠";
            case INVENTORY -> "📦";
            case STOCK_OPERATIONS -> "🛒";
            case SUPPLIERS_ORDERS -> "🚚";
            case ANALYTICS_REPORTS -> "📊";
            case AI_ASSISTANT -> "✨";
            case SETTINGS -> "⚙";
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
