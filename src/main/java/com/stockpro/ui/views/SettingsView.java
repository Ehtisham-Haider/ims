package com.stockpro.ui.views;

import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.service.AppSession;
import com.stockpro.service.BackupService;
import com.stockpro.service.BuiltInLabTestRunner;
import com.stockpro.service.DemoDataService;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.TelemetryMonitorService;
import com.stockpro.ui.components.KpiStatCard;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;

/**
 * Application Settings, Staff Management, SQLite Backup/Restore, 1,000-Item Demo Generator,
 * Speed &amp; Health Monitor, and Built-In Lab Viva Test Runner UI matching {@code Screens and popups/Settings.png}.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SettingsView {
    private final ServiceContext services;
    private final ToastNotificationManager toastManager;
    private final Runnable onThemeChanged;

    private final ObservableList<User> staffRows = FXCollections.observableArrayList();
    private final ObservableList<BuiltInLabTestRunner.LabCaseResult> labRows = FXCollections.observableArrayList();

    private KpiStatCard memoryCard;
    private KpiStatCard searchLatencyCard;
    private KpiStatCard dbSizeCard;
    private KpiStatCard recordsCard;
    private Node rootNode;

    /**
     * Constructs the Settings &amp; Viva QA screen.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     * @param onThemeChanged callback invoked when Light/Dark theme changes
     */
    public SettingsView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Runnable onThemeChanged) {
        this.services = services;
        this.toastManager = toastManager;
        this.onThemeChanged = onThemeChanged;
    }

    /**
     * Builds the tabbed Settings workspace matching {@code Settings.png}.
     *
     * @return JavaFX node
     */
    public Node build() {
        VBox page = new VBox(14);
        page.setPadding(new Insets(0, 4, 12, 0));

        Label title = new Label("Settings");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Store profile, staff roles, Groq API key, backups, 1,000-item demo data, telemetry, and Lab QA");
        subtitle.getStyleClass().add("page-subtitle");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        tabs.getTabs().addAll(
                new Tab("General", buildGeneralTab()),
                new Tab("Staff", buildStaffTab()),
                new Tab("Backup & Demo Data", buildBackupAndDemoTab()),
                new Tab("Speed & Health", buildTelemetryTab()),
                new Tab("Lab Viva QA (30B.docx)", buildLabQaTab()),
                new Tab("About", buildAboutTab())
        );

        page.getChildren().addAll(title, subtitle, tabs);
        this.rootNode = page;
        refreshStaffAndTelemetry();
        return page;
    }

    /**
     * Builds the General Settings tab (Store profile, theme switcher, and Free Groq API key + test).
     *
     * @return scrollable General Settings node
     */
    private Node buildGeneralTab() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(14, 4, 14, 0));

        HBox twoCol = new HBox(16);

        // Left: Store Profile + Groq Key
        VBox profileCard = new VBox(10);
        profileCard.getStyleClass().add("card");
        HBox.setHgrow(profileCard, Priority.ALWAYS);

        Label heading = new Label("General Settings");
        heading.getStyleClass().add("section-title");

        TextField storeNameF = new TextField(services.getSettingsRepository().get("store.name", "StockPro Store"));
        ComboBox<String> currencyBox = new ComboBox<>();
        currencyBox.getItems().addAll("USD ($)", "PKR (Rs)", "EUR (€)", "GBP (£)");
        currencyBox.setValue(services.getSettingsRepository().get("store.currency", "USD ($)"));
        currencyBox.setMaxWidth(Double.MAX_VALUE);

        TextField taxF = new TextField(services.getSettingsRepository().get("store.tax_rate", "15"));

        PasswordField groqKeyF = new PasswordField();
        groqKeyF.setText(services.getSettingsRepository().get("groq.api_key", ""));
        groqKeyF.setPromptText("Optional gsk_... free Groq API key (blank = 100% offline fallback)");

        Button testGroqBtn = new Button("Test Connection");
        testGroqBtn.getStyleClass().add("btn-secondary");
        Label groqStatus = new Label("Offline local engine active when key is blank.");
        groqStatus.getStyleClass().add("muted-text");
        testGroqBtn.setOnAction(e -> AppExecutor.runAsync(
                () -> services.getGroqApiClient().testConnection(groqKeyF.getText()),
                res -> {
                    String msg = res.success()
                            ? "Connected to Groq (" + res.model() + ")"
                            : res.errorNote();
                    groqStatus.setText(msg);
                    toastManager.showInfo(msg);
                },
                ex -> toastManager.showInfo("Groq test failed: " + ex.getMessage())
        ));

        HBox groqRow = new HBox(8, groqKeyF, testGroqBtn);
        HBox.setHgrow(groqKeyF, Priority.ALWAYS);

        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("btn-primary");
        saveBtn.setOnAction(e -> {
            try {
                double rate = Double.parseDouble(taxF.getText().trim());
                if (rate < 0 || rate > 100) {
                    toastManager.showInfo("Tax rate must be between 0 and 100%.");
                    return;
                }
                services.getSettingsRepository().set("store.name", storeNameF.getText().trim());
                services.getSettingsRepository().set("store.currency", currencyBox.getValue());
                services.getSettingsRepository().set("store.tax_rate", String.valueOf(rate));
                services.getSettingsRepository().set("groq.api_key", groqKeyF.getText().trim());
                services.syncSessionSettings();
                toastManager.showInfo("Saved store profile and preferences.");
            } catch (NumberFormatException ex) {
                toastManager.showInfo("Enter a valid numeric tax rate.");
            }
        });

        profileCard.getChildren().addAll(
                heading,
                new Label("Store Name"), storeNameF,
                new Label("Currency"), currencyBox,
                new Label("Tax Rate (%)"), taxF,
                new Label("Free Groq API Key"), groqRow, groqStatus,
                saveBtn
        );

        // Right: Appearance Theme Selector matching Settings.png
        VBox themeCard = new VBox(12);
        themeCard.getStyleClass().add("card");
        themeCard.setPrefWidth(360);
        Label tHead = new Label("Theme");
        tHead.getStyleClass().add("section-title");
        Label tSub = new Label("Switch cleanly between Cloud Light and Midnight Dark mode without restarting.");
        tSub.setWrapText(true);
        tSub.getStyleClass().add("muted-text");

        Button lightBtn = new Button("☀ Light");
        Button darkBtn = new Button("🌙 Dark");
        lightBtn.getStyleClass().add("filter-chip");
        darkBtn.getStyleClass().add("filter-chip");
        if (AppSession.getInstance().isDarkMode()) {
            darkBtn.getStyleClass().add("filter-chip-active");
        } else {
            lightBtn.getStyleClass().add("filter-chip-active");
        }

        lightBtn.setOnAction(e -> {
            AppSession.getInstance().setDarkMode(false);
            services.getSettingsRepository().set("ui.theme", "light");
            lightBtn.getStyleClass().add("filter-chip-active");
            darkBtn.getStyleClass().remove("filter-chip-active");
            onThemeChanged.run();
        });
        darkBtn.setOnAction(e -> {
            AppSession.getInstance().setDarkMode(true);
            services.getSettingsRepository().set("ui.theme", "dark");
            darkBtn.getStyleClass().add("filter-chip-active");
            lightBtn.getStyleClass().remove("filter-chip-active");
            onThemeChanged.run();
        });

        HBox themePills = new HBox(10, lightBtn, darkBtn);
        themeCard.getChildren().addAll(tHead, tSub, themePills);

        twoCol.getChildren().addAll(profileCard, themeCard);
        container.getChildren().add(twoCol);
        return container;
    }

    /**
     * Builds the Staff &amp; Roles management tab.
     *
     * @return staff management panel
     */
    private Node buildStaffTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(14, 4, 14, 0));

        VBox tableCard = new VBox(10);
        tableCard.getStyleClass().add("card");
        Label heading = new Label("Staff & Access Roles");
        heading.getStyleClass().add("section-title");

        TableView<User> table = new TableView<>(staffRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);

        TableColumn<User, String> uCol = new TableColumn<>("Username");
        uCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        TableColumn<User, String> fCol = new TableColumn<>("Full Name");
        fCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFullName()));
        TableColumn<User, String> rCol = new TableColumn<>("Role");
        rCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRole().getDisplayName()));
        TableColumn<User, String> aCol = new TableColumn<>("Status");
        aCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Disabled"));
        table.getColumns().addAll(List.of(uCol, fCol, rCol, aCol));

        TextField newUsername = new TextField();
        newUsername.setPromptText("Username");
        TextField newFullName = new TextField();
        newFullName.setPromptText("Full Name");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("Password (min 4 chars)");
        ComboBox<UserRole> newRole = new ComboBox<>();
        newRole.getItems().addAll(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER);
        newRole.setValue(UserRole.CASHIER);

        Button addUserBtn = new Button("+ Add Staff");
        addUserBtn.getStyleClass().add("btn-primary");
        addUserBtn.setOnAction(e -> {
            InventoryOperationResult<User> res = services.getAuthService().registerStaff(
                    newUsername.getText(), newPass.getText(), newFullName.getText(), newRole.getValue());
            toastManager.showInfo(res.getMessage());
            if (res.isSuccess()) {
                newUsername.clear();
                newFullName.clear();
                newPass.clear();
                refreshStaffAndTelemetry();
            }
        });

        HBox addRow = new HBox(8, newUsername, newFullName, newPass, newRole, addUserBtn);
        tableCard.getChildren().addAll(heading, table, addRow);
        box.getChildren().add(tableCard);
        return box;
    }

    /**
     * Builds the Database Backup/Restore and 1,000-Item Demo Data Generator tab.
     *
     * @return backup and demo tools panel
     */
    private Node buildBackupAndDemoTab() {
        VBox box = new VBox(16);
        box.setPadding(new Insets(14, 4, 14, 0));

        // Backup & Restore Card
        VBox backupCard = new VBox(10);
        backupCard.getStyleClass().add("card");
        Label bHead = new Label("One-Click SQLite Database Backup & Restore");
        bHead.getStyleClass().add("section-title");
        Label bDesc = new Label("Exports a consistent standalone SQLite snapshot using VACUUM INTO and validates backups before restoring.");
        bDesc.getStyleClass().add("muted-text");

        BackupService backupService = services.getBackupService();
        Button backupBtn = new Button("💾 Create Database Backup (.db)");
        backupBtn.getStyleClass().add("btn-primary");
        backupBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("stockpro-backup.db");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database", "*.db"));
            File dest = chooser.showSaveDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
            if (dest != null) {
                AppExecutor.runAsync(
                        () -> backupService.createBackup(dest.toPath()),
                        path -> toastManager.showInfo("Saved verified SQLite backup: " + path.getFileName()),
                        ex -> toastManager.showInfo("Backup failed: " + ex.getMessage())
                );
            }
        });

        Button restoreBtn = new Button("♻ Restore From Backup (.db)");
        restoreBtn.getStyleClass().add("btn-secondary");
        restoreBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select SQLite Backup File");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database", "*.db"));
            File src = chooser.showOpenDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
            if (src != null) {
                AppExecutor.runAsync(
                        () -> backupService.restoreFromBackup(src.toPath()),
                        res -> toastManager.showInfo(res.getMessage()),
                        ex -> toastManager.showInfo("Restore failed: " + ex.getMessage())
                );
            }
        });

        backupCard.getChildren().addAll(bHead, bDesc, new HBox(12, backupBtn, restoreBtn));

        // Demo Data & Empty State Reset Card
        VBox demoCard = new VBox(10);
        demoCard.getStyleClass().add("card");
        Label dHead = new Label("Live Demo Data Generator (1,000 Products)");
        dHead.getStyleClass().add("section-title");
        Label dDesc = new Label("Populate 1,000 realistic items, 6 suppliers, and 250 sales/restock entries in milliseconds, or wipe the store to verify Empty State handling.");
        dDesc.getStyleClass().add("muted-text");
        dDesc.setWrapText(true);

        DemoDataService demoService = services.getDemoDataService();
        Button genBtn = new Button("⚡ Generate Demo Data (1,000 Items)");
        genBtn.getStyleClass().add("btn-primary");
        genBtn.setOnAction(e -> AppExecutor.runAsync(
                () -> demoService.generateRealisticDemoStore(1000),
                count -> {
                    toastManager.showInfo("Seeded " + count + " realistic demo products + suppliers + history!");
                    refreshStaffAndTelemetry();
                },
                ex -> toastManager.showInfo("Demo generation failed: " + ex.getMessage())
        ));

        Button clearBtn = new Button("🧹 Clear Store (Empty State Test)");
        clearBtn.getStyleClass().add("btn-danger-outline");
        clearBtn.setOnAction(e -> AppExecutor.runAsync(() -> {
            demoService.clearStoreForEmptyStateTest();
            return true;
        }, ok -> {
            toastManager.showInfo("Cleared store catalog cleanly for empty-state verification.");
            refreshStaffAndTelemetry();
        }, ex -> {}));

        demoCard.getChildren().addAll(dHead, dDesc, new HBox(12, genBtn, clearBtn));
        box.getChildren().addAll(backupCard, demoCard);
        return box;
    }

    /**
     * Builds the Speed &amp; Health Monitor telemetry tab.
     *
     * @return telemetry panel
     */
    private Node buildTelemetryTab() {
        VBox box = new VBox(16);
        box.setPadding(new Insets(14, 4, 14, 0));

        memoryCard = new KpiStatCard("🧠", "#4F46E5", "kpi-card-blue", "JVM Heap Used", "0 MB", "Max: 0 MB");
        searchLatencyCard = new KpiStatCard("⚡", "#10B981", "kpi-card-green", "Trie / HashMap Latency", "0 ms", "Sub-millisecond");
        dbSizeCard = new KpiStatCard("🗄", "#8B5CF6", "kpi-card-purple", "SQLite DB Size", "0 KB", "Local disk store");
        recordsCard = new KpiStatCard("📚", "#F59E0B", "kpi-card-amber", "Total DB Records", "0", "Products & logs");

        HBox row = new HBox(14, memoryCard, searchLatencyCard, dbSizeCard, recordsCard);

        Button refreshBtn = new Button("↻ Refresh Telemetry");
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshStaffAndTelemetry());

        box.getChildren().addAll(row, refreshBtn);
        return box;
    }

    /**
     * Builds the interactive Built-In Lab Test Runner UI executing all 8 mandatory {@code 30B.docx} checks.
     *
     * @return Lab Viva QA runner panel
     */
    private Node buildLabQaTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(14, 4, 14, 0));

        HBox hdr = new HBox(12);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Mandatory Lab Verification Suite (30B.docx)");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button runBtn = new Button("▶ Run All 8 Lab Checks Live");
        runBtn.getStyleClass().add("btn-primary");
        runBtn.setOnAction(e -> runBuiltInLabSuite());
        hdr.getChildren().addAll(heading, sp, runBtn);

        TableView<BuiltInLabTestRunner.LabCaseResult> table = new TableView<>(labRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(340);

        TableColumn<BuiltInLabTestRunner.LabCaseResult, String> numCol = new TableColumn<>("#");
        numCol.setMaxWidth(48);
        numCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().caseNumber())));

        TableColumn<BuiltInLabTestRunner.LabCaseResult, String> nameCol = new TableColumn<>("Test Case");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().testCaseName()));

        TableColumn<BuiltInLabTestRunner.LabCaseResult, String> expCol = new TableColumn<>("Expected Result");
        expCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().expectedResult()));

        TableColumn<BuiltInLabTestRunner.LabCaseResult, String> actCol = new TableColumn<>("Actual Result");
        actCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().actualResult()));

        TableColumn<BuiltInLabTestRunner.LabCaseResult, String> statusCol = new TableColumn<>("Status");
        statusCol.setMaxWidth(115);
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().passed() ? "Passed" : "Failed"));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label("Passed".equals(item) ? "✔ Passed" : "✕ Failed");
                pill.getStyleClass().addAll("status-pill",
                        "Passed".equals(item) ? "status-in-stock" : "status-out-of-stock");
                setGraphic(pill);
            }
        });

        table.getColumns().addAll(List.of(numCol, nameCol, expCol, actCol, statusCol));
        box.getChildren().addAll(hdr, table);
        return box;
    }

    /**
     * Builds the About StockPro V1 tab displaying authors and architecture summary.
     *
     * @return about card
     */
    private Node buildAboutTab() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        Label heading = new Label("StockPro — Inventory Management System (Version 1.0.0)");
        heading.getStyleClass().add("section-title");
        Label authors = new Label("Authors: Ehtisham Haider & Syed Ahmed");
        authors.setStyle("-fx-font-weight: bold;");
        Label stack = new Label("Stack: Java 21+ • JavaFX • SQLite + HikariCP • Custom DS&A (HashMap, Compressed Trie, BK-Tree, Min-Heap, TreeMap) • Pure-Java Offline ML • ZXing • Apache POI • OpenPDF • Optional Groq API");
        stack.setWrapText(true);
        stack.getStyleClass().add("muted-text");

        card.getChildren().addAll(heading, authors, stack);
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new Insets(14, 4, 14, 0));
        return wrapper;
    }

    /**
     * Executes all 8 mandatory lab checks asynchronously and renders green Passed badges.
     */
    private void runBuiltInLabSuite() {
        AppExecutor.runAsync(
                () -> services.getBuiltInLabTestRunner().runAllMandatoryLabChecks(),
                list -> {
                    labRows.setAll(list);
                    long passed = list.stream().filter(BuiltInLabTestRunner.LabCaseResult::passed).count();
                    toastManager.showInfo("Lab QA Complete: " + passed + " / " + list.size() + " checks passed!");
                },
                ex -> toastManager.showInfo("Lab QA failed: " + ex.getMessage())
        );
    }

    /**
     * Refreshes staff directory rows and live JVM/search telemetry off the FX thread.
     */
    private void refreshStaffAndTelemetry() {
        AppExecutor.runAsync(() -> new Object[]{
                services.getUserRepository().findAll(),
                services.getTelemetryMonitorService().captureSnapshot()
        }, data -> {
            @SuppressWarnings("unchecked")
            List<User> users = (List<User>) data[0];
            TelemetryMonitorService.TelemetrySnapshot t = (TelemetryMonitorService.TelemetrySnapshot) data[1];

            staffRows.setAll(users);
            if (memoryCard != null) {
                memoryCard.updateMetric(t.usedHeapMb() + " MB", "Max JVM Heap: " + t.maxHeapMb() + " MB", "#4F46E5");
                searchLatencyCard.updateMetric(
                        String.format("%.3f ms", t.trieLatencyMs()),
                        String.format("HashMap O(1): %.3f ms", t.hashMapLatencyMs()),
                        "#15803D"
                );
                dbSizeCard.updateMetric(t.dbSizeKb() + " KB", "SQLite WAL store", "#6D28D9");
                recordsCard.updateMetric(
                        String.valueOf(t.productCount()),
                        String.format("Moves: %d | Sups: %d | Audits: %d", t.movementCount(), t.supplierCount(), t.auditCount()),
                        "#B45309"
                );
            }
        }, ex -> {});
    }
}
