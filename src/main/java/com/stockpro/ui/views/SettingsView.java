package com.stockpro.ui.views;

import com.stockpro.ai.GroqApiClient;
import com.stockpro.domain.User;
import com.stockpro.domain.UserRole;
import com.stockpro.service.AppSession;
import com.stockpro.service.BackupService;
import com.stockpro.service.BuiltInLabTestRunner;
import com.stockpro.service.DemoDataService;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ProductImageService;
import com.stockpro.service.ServiceContext;
import com.stockpro.service.TelemetryMonitorService;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ThemeManager;
import com.stockpro.ui.components.KpiStatCard;
import com.stockpro.ui.components.KpiStatCard.DeltaTone;
import com.stockpro.ui.components.StockProLogo;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.feather.Feather;

import java.io.File;
import java.util.List;

/**
 * Application Settings, dedicated User-Owned Groq AI Configuration, Staff &amp; Role Management,
 * SQLite Backup/Restore, 1,000-Item Demo Generator, Speed &amp; Health Monitor, and Built-In Lab Viva QA UI.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class SettingsView {
    private final ServiceContext services;
    private final ToastNotificationManager toastManager;
    private final Runnable onThemeChanged;
    private final int initialTabIndex;

    private final ObservableList<User> staffRows = FXCollections.observableArrayList();
    private final ObservableList<BuiltInLabTestRunner.LabCaseResult> labRows = FXCollections.observableArrayList();

    private TabPane tabs;
    private KpiStatCard memoryCard;
    private KpiStatCard searchLatencyCard;
    private KpiStatCard dbSizeCard;
    private KpiStatCard recordsCard;
    private Node rootNode;

    /**
     * Constructs the Settings &amp; Viva QA screen opening on the General tab.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     * @param onThemeChanged callback invoked when Light/Dark theme changes
     */
    public SettingsView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Runnable onThemeChanged) {
        this(services, toastManager, onThemeChanged, 0);
    }

    /**
     * Constructs the Settings workspace opening directly on the specified tab index.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     * @param onThemeChanged callback invoked when Light/Dark theme changes
     * @param initialTabIndex zero-based tab index (e.g. {@code 1} for {@code AI & Groq API})
     */
    public SettingsView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Runnable onThemeChanged,
            int initialTabIndex) {
        this.services = services;
        this.toastManager = toastManager;
        this.onThemeChanged = onThemeChanged;
        this.initialTabIndex = Math.max(0, initialTabIndex);
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
        Label subtitle = new Label("Store profile, free Groq & Pexels API keys, staff roles, SQLite backups, telemetry, and Lab Viva QA");
        subtitle.getStyleClass().add("page-subtitle");

        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        tabs.getTabs().addAll(
                new Tab("General", wrapScroll(buildGeneralTab())),
                new Tab("AI & API Keys", wrapScroll(buildGroqAiTab())),
                new Tab("Staff", wrapScroll(buildStaffTab())),
                new Tab("Backup & Demo Data", wrapScroll(buildBackupAndDemoTab())),
                new Tab("Speed & Health", wrapScroll(buildTelemetryTab())),
                new Tab("Lab Viva QA (30B.docx)", wrapScroll(buildLabQaTab())),
                new Tab("About", wrapScroll(buildAboutTab()))
        );

        if (initialTabIndex < tabs.getTabs().size()) {
            tabs.getSelectionModel().select(initialTabIndex);
        }

        page.getChildren().addAll(title, subtitle, tabs);
        this.rootNode = page;
        refreshStaffAndTelemetry();
        return page;
    }

    private ScrollPane wrapScroll(Node content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        return sp;
    }

    private Node buildGeneralTab() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(14, 4, 14, 0));

        HBox twoCol = new HBox(16);

        // Left: Store Profile
        VBox profileCard = new VBox(10);
        profileCard.getStyleClass().add("card");
        HBox.setHgrow(profileCard, Priority.ALWAYS);

        Label heading = new Label("Store Profile & Preferences");
        heading.getStyleClass().add("section-title");

        TextField storeNameF = new TextField(services.getSettingsRepository().get("store.name", "StockPro Store"));
        ComboBox<String> currencyBox = new ComboBox<>();
        currencyBox.getItems().addAll("USD ($)", "PKR (Rs)", "EUR (€)", "GBP (£)");
        currencyBox.setValue(services.getSettingsRepository().get("store.currency", "USD ($)"));
        currencyBox.setMaxWidth(Double.MAX_VALUE);

        TextField taxF = new TextField(services.getSettingsRepository().get("store.tax_rate", "15"));

        Button saveBtn = new Button("Save Changes");
        saveBtn.setGraphic(AppIcons.of(Feather.SAVE, 14));
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
                services.syncSessionSettings();
                toastManager.showInfo("Saved store profile and tax settings.");
            } catch (NumberFormatException ex) {
                toastManager.showInfo("Enter a valid numeric tax rate.");
            }
        });

        profileCard.getChildren().addAll(
                heading,
                new Label("Store Name"), storeNameF,
                new Label("Currency"), currencyBox,
                new Label("Tax / GST Rate (%)"), taxF,
                saveBtn
        );

        // Right: Theme + Quick Shortcuts matching Settings.png
        VBox rightCol = new VBox(14);
        rightCol.setPrefWidth(380);

        VBox themeCard = new VBox(12);
        themeCard.getStyleClass().add("card");
        Label tHead = new Label("Theme");
        tHead.getStyleClass().add("section-title");
        Label tSub = new Label("Switch cleanly between Cloud Light and Midnight Dark mode without restarting.");
        tSub.setWrapText(true);
        tSub.getStyleClass().add("muted-text");

        Button lightBtn = new Button("Light Mode");
        lightBtn.setGraphic(AppIcons.of(Feather.SUN, 14));
        Button darkBtn = new Button("Dark Mode");
        darkBtn.setGraphic(AppIcons.of(Feather.MOON, 14));
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

        VBox quickLinksCard = new VBox(10);
        quickLinksCard.getStyleClass().add("card");
        Label qHead = new Label("Quick Actions");
        qHead.getStyleClass().add("section-title");
        Button goAiBtn = new Button("Configure Own Free Groq API Key");
        goAiBtn.setGraphic(AppIcons.of(Feather.KEY, 14));
        goAiBtn.getStyleClass().add("btn-secondary");
        goAiBtn.setMaxWidth(Double.MAX_VALUE);
        goAiBtn.setOnAction(e -> tabs.getSelectionModel().select(1));

        Button goDemoBtn = new Button("Seed 1,000 Demo Items");
        goDemoBtn.setGraphic(AppIcons.of(Feather.ZAP, 14));
        goDemoBtn.getStyleClass().add("btn-secondary");
        goDemoBtn.setMaxWidth(Double.MAX_VALUE);
        goDemoBtn.setOnAction(e -> tabs.getSelectionModel().select(3));

        quickLinksCard.getChildren().addAll(qHead, goAiBtn, goDemoBtn);
        rightCol.getChildren().addAll(themeCard, quickLinksCard);

        twoCol.getChildren().addAll(profileCard, rightCol);
        container.getChildren().add(twoCol);
        return container;
    }

    /**
     * Builds the dedicated User-Owned Groq API setup tab with show/hide key masking,
     * model selection, instant Save &amp; Test verification, key removal, and console URL copy.
     *
     * @return Groq AI Settings node
     */
    private Node buildGroqAiTab() {
        VBox container = new VBox(16);
        container.setPadding(new Insets(14, 4, 14, 0));

        GroqApiClient groqClient = services.getGroqApiClient();

        // Status Card
        HBox statusBanner = new HBox(10);
        statusBanner.setAlignment(Pos.CENTER_LEFT);
        Label statusBadge = new Label();
        statusBadge.setStyle("-fx-font-weight: bold;");
        Label statusIcon = new Label();

        Runnable updateBanner = () -> {
            boolean configured = groqClient.isApiKeyConfigured();
            statusBanner.getStyleClass().removeAll("ai-key-banner-ok", "ai-key-banner-warn");
            statusBanner.getStyleClass().add(configured ? "ai-key-banner-ok" : "ai-key-banner-warn");
            statusIcon.setGraphic(AppIcons.of(
                    configured ? Feather.CHECK_CIRCLE : Feather.ALERT_TRIANGLE,
                    18,
                    configured ? "#10B981" : "#F59E0B"));
            statusBadge.setText(configured
                    ? "Groq API Key Configured • Active Model: " + groqClient.resolveModel()
                    : "No Groq API Key Saved — Enter your own free key below to enable live Cloud AI features");
        };
        updateBanner.run();
        statusBanner.getChildren().addAll(statusIcon, statusBadge);

        HBox split = new HBox(16);

        // Left Card: Credentials & Test
        VBox formCard = new VBox(12);
        formCard.getStyleClass().add("card");
        HBox.setHgrow(formCard, Priority.ALWAYS);

        Label fHead = new Label("Your Personal Groq API Key ($0 Free Tier)");
        fHead.getStyleClass().add("section-title");

        String savedKey = services.getSettingsRepository().get("groq.api_key", "");
        PasswordField keyMasked = new PasswordField();
        keyMasked.setText(savedKey);
        keyMasked.setPromptText("Paste your Groq API key (starts with gsk_...)");
        keyMasked.setPrefHeight(40);

        TextField keyPlain = new TextField(savedKey);
        keyPlain.setPromptText("Paste your Groq API key (starts with gsk_...)");
        keyPlain.setPrefHeight(40);
        keyPlain.setVisible(false);
        keyPlain.setManaged(false);
        keyPlain.textProperty().bindBidirectional(keyMasked.textProperty());

        StackPane keyStack = new StackPane(keyMasked, keyPlain);
        HBox.setHgrow(keyStack, Priority.ALWAYS);

        Button eyeBtn = new Button();
        eyeBtn.setGraphic(AppIcons.of(Feather.EYE, 15));
        eyeBtn.getStyleClass().add("btn-secondary");
        eyeBtn.setTooltip(new Tooltip("Show / Hide API Key"));
        eyeBtn.setOnAction(e -> {
            boolean show = !keyPlain.isVisible();
            keyPlain.setVisible(show);
            keyPlain.setManaged(show);
            keyMasked.setVisible(!show);
            keyMasked.setManaged(!show);
            eyeBtn.setGraphic(AppIcons.of(show ? Feather.EYE_OFF : Feather.EYE, 15));
        });

        HBox keyRow = new HBox(8, keyStack, eyeBtn);
        keyRow.setAlignment(Pos.CENTER_LEFT);

        ComboBox<String> modelBox = new ComboBox<>();
        modelBox.setEditable(true);
        modelBox.getItems().addAll(GroqApiClient.SUPPORTED_MODELS);
        String activeModel = groqClient.resolveModel();
        if (!modelBox.getItems().contains(activeModel)) {
            modelBox.getItems().add(0, activeModel);
        }
        modelBox.setValue(activeModel);
        modelBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modelBox, Priority.ALWAYS);

        Label diagLabel = new Label("Key is stored strictly locally inside your SQLite database (app_settings).");
        diagLabel.setWrapText(true);
        diagLabel.getStyleClass().add("muted-text");

        Button refreshModelsBtn = new Button("Fetch Live Models");
        refreshModelsBtn.setGraphic(AppIcons.of(Feather.REFRESH_CW, 14));
        refreshModelsBtn.getStyleClass().add("btn-secondary");
        refreshModelsBtn.setOnAction(e -> {
            String key = keyMasked.getText() != null && !keyMasked.getText().isBlank()
                    ? keyMasked.getText().trim()
                    : groqClient.resolveApiKey();
            if (key.isBlank()) {
                diagLabel.setText("Paste your Groq API key first, then click 'Fetch Live Models'.");
                toastManager.showInfo("Enter a Groq API key first.");
                return;
            }
            refreshModelsBtn.setDisable(true);
            diagLabel.setText("Fetching live chat models available to your Groq API key...");
            AppExecutor.runAsync(
                    () -> groqClient.fetchAvailableChatModels(key),
                    liveModels -> {
                        refreshModelsBtn.setDisable(false);
                        if (liveModels == null || liveModels.isEmpty()) {
                            diagLabel.setText("Could not load live models. Verify your Groq API key and internet connection.");
                            toastManager.showInfo("No live chat models returned. Check API key.");
                            return;
                        }
                        String current = modelBox.getEditor().getText() != null && !modelBox.getEditor().getText().isBlank()
                                ? modelBox.getEditor().getText().trim()
                                : modelBox.getValue();
                        modelBox.getItems().setAll(liveModels);
                        String selected = liveModels.contains(current) ? current : liveModels.get(0);
                        modelBox.setValue(selected);
                        modelBox.getEditor().setText(selected);
                        diagLabel.setText("Loaded " + liveModels.size() + " live Groq chat model(s) for your API key.");
                        toastManager.showInfo("Loaded " + liveModels.size() + " live Groq chat model(s).");
                    },
                    ex -> {
                        refreshModelsBtn.setDisable(false);
                        diagLabel.setText("Failed to fetch models: " + ex.getMessage());
                        toastManager.showInfo("Model fetch failed: " + ex.getMessage());
                    }
            );
        });

        HBox modelRow = new HBox(8, modelBox, refreshModelsBtn);
        modelRow.setAlignment(Pos.CENTER_LEFT);

        Button saveOnlyBtn = new Button("Save Key");
        saveOnlyBtn.setGraphic(AppIcons.of(Feather.SAVE, 14));
        saveOnlyBtn.getStyleClass().add("btn-secondary");
        saveOnlyBtn.setOnAction(e -> {
            String key = keyMasked.getText() != null ? keyMasked.getText().trim() : "";
            String model = modelBox.getEditor().getText() != null && !modelBox.getEditor().getText().isBlank()
                    ? modelBox.getEditor().getText().trim() : modelBox.getValue();
            groqClient.saveConfiguration(key, model);
            String resolved = groqClient.resolveModel();
            if (!modelBox.getItems().contains(resolved)) {
                modelBox.getItems().add(0, resolved);
            }
            modelBox.setValue(resolved);
            modelBox.getEditor().setText(resolved);
            updateBanner.run();
            diagLabel.setText(key.isBlank()
                    ? "Cleared saved API key."
                    : "Saved Groq API key & model (" + resolved + "). Click 'Save & Test Connection' to verify live.");
            toastManager.showInfo(key.isBlank() ? "Cleared Groq API key." : "Saved Groq API key locally.");
        });

        Button testSaveBtn = new Button("Save & Test Connection");
        testSaveBtn.setGraphic(AppIcons.of(Feather.CHECK_CIRCLE, 14));
        testSaveBtn.getStyleClass().add("btn-primary");
        testSaveBtn.setOnAction(e -> {
            String key = keyMasked.getText() != null ? keyMasked.getText().trim() : "";
            String model = modelBox.getEditor().getText() != null && !modelBox.getEditor().getText().isBlank()
                    ? modelBox.getEditor().getText().trim() : modelBox.getValue();
            groqClient.saveConfiguration(key, model);
            String resolved = groqClient.resolveModel();
            updateBanner.run();
            testSaveBtn.setDisable(true);
            diagLabel.setText("Contacting api.groq.com with model " + resolved + "...");

            long startedNs = System.nanoTime();
            AppExecutor.runAsync(
                    () -> groqClient.testConnection(key, resolved),
                    res -> {
                        testSaveBtn.setDisable(false);
                        long ms = Math.max(1, (System.nanoTime() - startedNs) / 1_000_000L);
                        if (res.success() && res.model() != null && !res.model().equals(modelBox.getValue())) {
                            if (!modelBox.getItems().contains(res.model())) {
                                modelBox.getItems().add(0, res.model());
                            }
                            modelBox.setValue(res.model());
                            modelBox.getEditor().setText(res.model());
                        }
                        updateBanner.run();
                        String msg = res.success()
                                ? "Connected to Groq Cloud (" + res.model() + ") in " + ms + " ms — AI features are LIVE!"
                                : res.errorNote();
                        diagLabel.setText(msg);
                        toastManager.showInfo(msg);
                    },
                    ex -> {
                        testSaveBtn.setDisable(false);
                        diagLabel.setText("Connection test failed: " + ex.getMessage());
                        toastManager.showInfo("Groq test failed: " + ex.getMessage());
                    }
            );
        });

        Button clearBtn = new Button("Remove Key");
        clearBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 14));
        clearBtn.getStyleClass().add("btn-danger-outline");
        clearBtn.setOnAction(e -> {
            keyMasked.clear();
            groqClient.clearApiKey();
            updateBanner.run();
            diagLabel.setText("Removed Groq API key. AI Assistant will prompt until a key is added.");
            toastManager.showInfo("Removed Groq API key.");
        });

        HBox btnRow = new HBox(10, testSaveBtn, saveOnlyBtn, clearBtn);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        formCard.getChildren().addAll(
                fHead,
                new Label("Groq API Key"), keyRow,
                new Label("Groq Model"), modelRow,
                diagLabel,
                btnRow
        );

        // Right Card: Free Setup Guide
        VBox guideCard = new VBox(10);
        guideCard.getStyleClass().add("card");
        guideCard.setPrefWidth(360);
        Label gHead = new Label("How to Get Your Free Groq Key");
        gHead.getStyleClass().add("section-title");
        Label steps = new Label(
                "1. Open console.groq.com/keys in your browser (100% free tier, no credit card).\n\n"
                        + "2. Click 'Create API Key' and copy the key starting with gsk_...\n\n"
                        + "3. Paste it into the box on the left and click 'Save & Test Connection'.\n\n"
                        + "4. Enjoy live AI Chat Q&A, Smart Charts, Morning Briefings & Supplier Emails!");
        steps.setWrapText(true);
        steps.getStyleClass().add("muted-text");

        Button copyUrlBtn = new Button("Copy https://console.groq.com/keys");
        copyUrlBtn.setGraphic(AppIcons.of(Feather.COPY, 13));
        copyUrlBtn.getStyleClass().add("btn-secondary");
        copyUrlBtn.setMaxWidth(Double.MAX_VALUE);
        copyUrlBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("https://console.groq.com/keys");
            Clipboard.getSystemClipboard().setContent(cc);
            toastManager.showInfo("Copied Groq console URL to clipboard!");
        });

        guideCard.getChildren().addAll(gHead, steps, copyUrlBtn);
        split.getChildren().addAll(formCard, guideCard);

        container.getChildren().addAll(statusBanner, split, buildPexelsApiSection());
        return container;
    }

    /**
     * Builds the private user-owned Pexels API section inside the AI &amp; API Keys tab.
     *
     * @return Pexels configuration section node
     */
    private Node buildPexelsApiSection() {
        VBox section = new VBox(12);
        section.setPadding(new Insets(10, 0, 0, 0));

        ProductImageService imageService = services.getProductImageService();

        Label sectionHeading = new Label("Pexels Product Photos API ($0 Free Tier — Missing Image Fallback)");
        sectionHeading.getStyleClass().add("section-title");

        HBox pexelsBanner = new HBox(10);
        pexelsBanner.setAlignment(Pos.CENTER_LEFT);
        Label pIcon = new Label();
        Label pStatusLbl = new Label();
        pStatusLbl.setStyle("-fx-font-weight: bold;");
        pStatusLbl.setWrapText(true);
        HBox.setHgrow(pStatusLbl, Priority.ALWAYS);

        Runnable updatePexelsBanner = () -> {
            boolean ready = imageService.isApiKeyConfigured();
            pexelsBanner.getStyleClass().removeAll("ai-key-banner-ok", "ai-key-banner-warn");
            pexelsBanner.getStyleClass().add(ready ? "ai-key-banner-ok" : "ai-key-banner-warn");
            pIcon.setGraphic(AppIcons.of(
                    ready ? Feather.CHECK_CIRCLE : Feather.IMAGE,
                    18,
                    ready ? "#10B981" : "#F59E0B"));
            pStatusLbl.setText(ready
                    ? "Pexels API Key Configured — Products missing photos automatically fetch real Pexels images"
                    : "No Pexels API Key Saved — Using generated offline category art; add your private key below for real photos");
        };
        updatePexelsBanner.run();
        pexelsBanner.getChildren().addAll(pIcon, pStatusLbl);

        HBox pexelsSplit = new HBox(16);

        VBox pFormCard = new VBox(12);
        pFormCard.getStyleClass().add("card");
        HBox.setHgrow(pFormCard, Priority.ALWAYS);

        Label pHead = new Label("Your Personal Pexels API Key (Stored Privately Offline)");
        pHead.getStyleClass().add("section-title");

        String savedPexelsKey = services.getSettingsRepository().get(ProductImageService.SETTINGS_KEY_PEXELS_API, "");
        PasswordField pKeyMasked = new PasswordField();
        pKeyMasked.setText(savedPexelsKey);
        pKeyMasked.setPromptText("Paste your Pexels API key (kept strictly in local SQLite)");
        pKeyMasked.setPrefHeight(40);

        TextField pKeyPlain = new TextField(savedPexelsKey);
        pKeyPlain.setPromptText("Paste your Pexels API key (kept strictly in local SQLite)");
        pKeyPlain.setPrefHeight(40);
        pKeyPlain.setVisible(false);
        pKeyPlain.setManaged(false);
        pKeyPlain.textProperty().bindBidirectional(pKeyMasked.textProperty());

        StackPane pKeyStack = new StackPane(pKeyMasked, pKeyPlain);
        HBox.setHgrow(pKeyStack, Priority.ALWAYS);

        Button pEyeBtn = new Button();
        pEyeBtn.setGraphic(AppIcons.of(Feather.EYE, 15));
        pEyeBtn.getStyleClass().add("btn-secondary");
        pEyeBtn.setTooltip(new Tooltip("Show / Hide Pexels API Key"));
        pEyeBtn.setOnAction(e -> {
            boolean show = !pKeyPlain.isVisible();
            pKeyPlain.setVisible(show);
            pKeyPlain.setManaged(show);
            pKeyMasked.setVisible(!show);
            pKeyMasked.setManaged(!show);
            pEyeBtn.setGraphic(AppIcons.of(show ? Feather.EYE_OFF : Feather.EYE, 15));
        });

        HBox pKeyRow = new HBox(8, pKeyStack, pEyeBtn);
        pKeyRow.setAlignment(Pos.CENTER_LEFT);

        Label pDiagLabel = new Label(
                "Saved privately in local SQLite (app_settings) or optional PEXELS_API_KEY environment variable — never exposed in code or Git.");
        pDiagLabel.setWrapText(true);
        pDiagLabel.getStyleClass().add("muted-text");

        Button pSaveBtn = new Button("Save Key");
        pSaveBtn.setGraphic(AppIcons.of(Feather.SAVE, 14));
        pSaveBtn.getStyleClass().add("btn-secondary");
        pSaveBtn.setOnAction(e -> {
            String key = pKeyMasked.getText() != null ? pKeyMasked.getText().trim() : "";
            imageService.saveApiKey(key);
            updatePexelsBanner.run();
            if (!key.isBlank()) {
                InventoryEventBus.getInstance().publish("CATALOG_IMAGES_UPDATED");
            }
            pDiagLabel.setText(key.isBlank()
                    ? "Cleared saved Pexels API key."
                    : "Saved Pexels API key privately in local SQLite. Click 'Save & Test Key' or 'Fetch Missing Photos'.");
            toastManager.showInfo(key.isBlank() ? "Cleared Pexels API key." : "Saved Pexels API key locally.");
        });

        Button pTestBtn = new Button("Save & Test Key");
        pTestBtn.setGraphic(AppIcons.of(Feather.CHECK_CIRCLE, 14));
        pTestBtn.getStyleClass().add("btn-primary");
        pTestBtn.setOnAction(e -> {
            String key = pKeyMasked.getText() != null ? pKeyMasked.getText().trim() : "";
            imageService.saveApiKey(key);
            updatePexelsBanner.run();
            pTestBtn.setDisable(true);
            pDiagLabel.setText("Contacting api.pexels.com...");
            AppExecutor.runAsync(
                    () -> imageService.testConnection(key),
                    res -> {
                        pTestBtn.setDisable(false);
                        updatePexelsBanner.run();
                        if (res.success()) {
                            InventoryEventBus.getInstance().publish("CATALOG_IMAGES_UPDATED");
                        }
                        pDiagLabel.setText(res.message());
                        toastManager.showInfo(res.message());
                    },
                    ex -> {
                        pTestBtn.setDisable(false);
                        pDiagLabel.setText("Pexels test failed: " + ex.getMessage());
                        toastManager.showInfo("Pexels test failed: " + ex.getMessage());
                    }
            );
        });

        Button pFetchAllBtn = new Button("Fetch Missing Product Photos");
        pFetchAllBtn.setGraphic(AppIcons.of(Feather.IMAGE, 14));
        pFetchAllBtn.getStyleClass().add("btn-secondary");
        pFetchAllBtn.setOnAction(e -> {
            pFetchAllBtn.setDisable(true);
            pDiagLabel.setText("Resolving missing product images across catalog...");
            AppExecutor.runAsync(
                    imageService::enrichMissingCatalogImages,
                    updated -> {
                        pFetchAllBtn.setDisable(false);
                        InventoryEventBus.getInstance().publish("CATALOG_IMAGES_UPDATED");
                        String msg = updated > 0
                                ? "Updated " + updated + " product photo(s)!"
                                : "All active products already have cached photos.";
                        pDiagLabel.setText(msg);
                        toastManager.showInfo(msg);
                    },
                    ex -> {
                        pFetchAllBtn.setDisable(false);
                        pDiagLabel.setText("Photo enrichment failed: " + ex.getMessage());
                        toastManager.showInfo("Photo fetch failed: " + ex.getMessage());
                    }
            );
        });

        Button pClearBtn = new Button("Remove Key");
        pClearBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 14));
        pClearBtn.getStyleClass().add("btn-danger-outline");
        pClearBtn.setOnAction(e -> {
            pKeyMasked.clear();
            imageService.clearApiKey();
            updatePexelsBanner.run();
            pDiagLabel.setText("Removed Pexels API key. StockPro falls back to crisp offline category artwork.");
            toastManager.showInfo("Removed Pexels API key.");
        });

        HBox pBtnRow = new HBox(10, pTestBtn, pSaveBtn, pFetchAllBtn, pClearBtn);
        pBtnRow.setAlignment(Pos.CENTER_LEFT);

        pFormCard.getChildren().addAll(
                pHead,
                new Label("Pexels API Key"), pKeyRow,
                pDiagLabel,
                pBtnRow
        );

        VBox pGuideCard = new VBox(10);
        pGuideCard.getStyleClass().add("card");
        pGuideCard.setPrefWidth(360);
        Label pgHead = new Label("Where to Add Your Free Pexels API Key");
        pgHead.getStyleClass().add("section-title");
        Label pSteps = new Label(
                "1. Create a free API key at pexels.com/api (no billing required).\n\n"
                        + "2. Paste it directly into the masked field on the left — it is saved strictly inside your local SQLite database (data/stockpro.db, gitignored), OR set the PEXELS_API_KEY environment variable.\n\n"
                        + "3. Click 'Save & Test Key', then click 'Fetch Missing Product Photos' to replace offline placeholders with real photos.");
        pSteps.setWrapText(true);
        pSteps.getStyleClass().add("muted-text");

        Button copyPexelsUrlBtn = new Button("Copy https://www.pexels.com/api/");
        copyPexelsUrlBtn.setGraphic(AppIcons.of(Feather.COPY, 13));
        copyPexelsUrlBtn.getStyleClass().add("btn-secondary");
        copyPexelsUrlBtn.setMaxWidth(Double.MAX_VALUE);
        copyPexelsUrlBtn.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString("https://www.pexels.com/api/");
            Clipboard.getSystemClipboard().setContent(cc);
            toastManager.showInfo("Copied Pexels API URL to clipboard!");
        });

        pGuideCard.getChildren().addAll(pgHead, pSteps, copyPexelsUrlBtn);
        pexelsSplit.getChildren().addAll(pFormCard, pGuideCard);
        section.getChildren().addAll(sectionHeading, pexelsBanner, pexelsSplit);
        return section;
    }

    private Node buildStaffTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(14, 4, 14, 0));

        VBox tableCard = new VBox(10);
        tableCard.getStyleClass().add("card");
        Label heading = new Label("Staff & Access Roles");
        heading.getStyleClass().add("section-title");

        TableView<User> table = new TableView<>(staffRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(240);

        TableColumn<User, String> uCol = new TableColumn<>("Username");
        uCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        TableColumn<User, String> fCol = new TableColumn<>("Full Name");
        fCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getFullName()));
        TableColumn<User, String> rCol = new TableColumn<>("Role");
        rCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getRole().getDisplayName()));
        TableColumn<User, String> aCol = new TableColumn<>("Status");
        aCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isActive() ? "Active" : "Disabled"));
        aCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    return;
                }
                Label pill = new Label(status);
                pill.getStyleClass().addAll("status-pill",
                        "Active".equals(status) ? "status-in-stock" : "status-out-of-stock");
                setGraphic(pill);
            }
        });
        TableColumn<User, Void> actCol = new TableColumn<>("Manage");
        actCol.setMinWidth(110);
        actCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Void v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                User u = getTableRow().getItem();
                if (u.getRole() == UserRole.OWNER) {
                    setGraphic(null);
                    return;
                }
                Button toggleBtn = new Button(u.isActive() ? "Disable" : "Enable");
                toggleBtn.getStyleClass().add("btn-secondary");
                toggleBtn.setStyle("-fx-padding: 4 10 4 10;");
                toggleBtn.setOnAction(e -> {
                    if (!AppSession.getInstance().isOwner()) {
                        toastManager.showInfo("Only the Store Owner can modify staff access.");
                        return;
                    }
                    u.setActive(!u.isActive());
                    services.getUserRepository().updateProfile(u);
                    toastManager.showInfo((u.isActive() ? "Enabled " : "Disabled ") + u.getUsername() + ".");
                    refreshStaffAndTelemetry();
                });
                setGraphic(toggleBtn);
            }
        });
        table.getColumns().addAll(List.of(uCol, fCol, rCol, aCol, actCol));

        TextField newUsername = new TextField();
        newUsername.setPromptText("Username");
        TextField newFullName = new TextField();
        newFullName.setPromptText("Full Name");
        PasswordField newPass = new PasswordField();
        newPass.setPromptText("Password (min 4 chars)");
        ComboBox<UserRole> newRole = new ComboBox<>();
        newRole.getItems().addAll(UserRole.OWNER, UserRole.MANAGER, UserRole.CASHIER);
        newRole.setValue(UserRole.CASHIER);

        Button addUserBtn = new Button("Add Staff");
        addUserBtn.setGraphic(AppIcons.of(Feather.PLUS, 13));
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
        Button backupBtn = new Button("Create Database Backup (.db)");
        backupBtn.setGraphic(AppIcons.of(Feather.DOWNLOAD, 14));
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

        Button restoreBtn = new Button("Restore From Backup (.db)");
        restoreBtn.setGraphic(AppIcons.of(Feather.UPLOAD, 14));
        restoreBtn.getStyleClass().add("btn-secondary");
        restoreBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select SQLite Backup File");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database", "*.db"));
            File src = chooser.showOpenDialog(rootNode.getScene() != null ? rootNode.getScene().getWindow() : null);
            if (src != null) {
                AppExecutor.runAsync(
                        () -> {
                            var res = backupService.restoreFromBackup(src.toPath());
                            services.getCatalogSearchEngine().rebuildIndices();
                            services.syncSessionSettings();
                            return res;
                        },
                        res -> {
                            onThemeChanged.run();
                            refreshStaffAndTelemetry();
                            toastManager.showInfo(res.getMessage());
                        },
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
        Button genBtn = new Button("Generate Demo Data (1,000 Items)");
        genBtn.setGraphic(AppIcons.of(Feather.ZAP, 14));
        genBtn.getStyleClass().add("btn-primary");
        genBtn.setOnAction(e -> {
            genBtn.setDisable(true);
            AppExecutor.runAsync(
                    () -> {
                        int count = demoService.generateRealisticDemoStore(1000);
                        int pexelsUpdated = services.getProductImageService().isApiKeyConfigured()
                                ? services.getProductImageService().enrichMissingCatalogImages()
                                : 0;
                        return new int[]{count, pexelsUpdated};
                    },
                    res -> {
                        genBtn.setDisable(false);
                        InventoryEventBus.getInstance().publish("CATALOG_IMAGES_UPDATED");
                        String extra = res[1] > 0 ? " (" + res[1] + " matched with Pexels photos)" : "";
                        toastManager.showInfo("Seeded " + res[0] + " realistic demo products + suppliers + history!" + extra);
                        refreshStaffAndTelemetry();
                    },
                    ex -> {
                        genBtn.setDisable(false);
                        toastManager.showInfo("Demo generation failed: " + ex.getMessage());
                    }
            );
        });

        Button clearBtn = new Button("Clear Store (Empty State Test)");
        clearBtn.setGraphic(AppIcons.of(Feather.TRASH_2, 14));
        clearBtn.getStyleClass().add("btn-danger-outline");
        clearBtn.setOnAction(e -> confirmClearStore(demoService));

        demoCard.getChildren().addAll(dHead, dDesc, new HBox(12, genBtn, clearBtn));
        box.getChildren().addAll(backupCard, demoCard);
        return box;
    }

    private void confirmClearStore(DemoDataService demoService) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        if (rootNode != null && rootNode.getScene() != null) {
            dialog.initOwner(rootNode.getScene().getWindow());
        }
        dialog.setTitle("Clear Entire Store Catalog");

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));
        Label h = new Label("Clear all products & stock history?");
        h.getStyleClass().add("section-title");
        Label b = new Label("This removes all active products, stock movements, and draft POs so you can test Empty Store behavior. You can re-seed 1,000 demo items anytime.");
        b.setWrapText(true);
        b.getStyleClass().add("muted-text");

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("btn-secondary");
        cancel.setOnAction(e -> dialog.close());
        Button wipe = new Button("Yes, Clear Store");
        wipe.getStyleClass().add("btn-danger-outline");
        wipe.setOnAction(e -> {
            dialog.close();
            AppExecutor.runAsync(() -> {
                demoService.clearStoreForEmptyStateTest();
                return true;
            }, ok -> {
                toastManager.showInfo("Cleared store catalog cleanly for empty-state verification.");
                refreshStaffAndTelemetry();
            }, ex -> toastManager.showInfo("Failed to clear store: " + ex.getMessage()));
        });

        HBox actions = new HBox(10, cancel, wipe);
        actions.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(h, b, actions);

        Scene sc = new Scene(card, 430, 195);
        ThemeManager.apply(sc);
        dialog.setScene(sc);
        dialog.showAndWait();
    }

    private Node buildTelemetryTab() {
        VBox box = new VBox(16);
        box.setPadding(new Insets(14, 4, 14, 0));

        memoryCard = new KpiStatCard(Feather.CPU, "#4F46E5", KpiStatCard.INVERSE, "JVM Heap Used", "0 MB", "Max: 0 MB");
        searchLatencyCard = new KpiStatCard(Feather.ZAP, "#10B981", KpiStatCard.ACCENT, "Trie / HashMap Latency", "0 ms", "Sub-millisecond");
        dbSizeCard = new KpiStatCard(Feather.DATABASE, "#8B5CF6", KpiStatCard.MUTED, "SQLite DB Size", "0 KB", "Local disk store");
        recordsCard = new KpiStatCard(Feather.HARD_DRIVE, "#F59E0B", KpiStatCard.PLAIN, "Total DB Records", "0", "Products & logs");

        HBox row = new HBox(12, memoryCard, searchLatencyCard, dbSizeCard, recordsCard);

        Button refreshBtn = new Button("Refresh Telemetry");
        refreshBtn.setGraphic(AppIcons.of(Feather.REFRESH_CW, 14));
        refreshBtn.getStyleClass().add("btn-secondary");
        refreshBtn.setOnAction(e -> refreshStaffAndTelemetry());

        box.getChildren().addAll(row, refreshBtn);
        return box;
    }

    private Node buildLabQaTab() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(14, 4, 14, 0));

        HBox hdr = new HBox(12);
        hdr.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Mandatory Lab Verification Suite (30B.docx)");
        heading.getStyleClass().add("section-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button runBtn = new Button("Run All 8 Lab Checks Live");
        runBtn.setGraphic(AppIcons.of(Feather.PLAY, 14));
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
                boolean ok = "Passed".equals(item);
                Label pill = new Label(ok ? "Passed" : "Failed");
                pill.setGraphic(AppIcons.of(ok ? Feather.CHECK : Feather.X, 12, ok ? "#15803D" : "#B91C1C"));
                pill.getStyleClass().addAll("status-pill", ok ? "status-in-stock" : "status-out-of-stock");
                setGraphic(pill);
            }
        });

        table.getColumns().addAll(List.of(numCol, nameCol, expCol, actCol, statusCol));
        box.getChildren().addAll(hdr, table);
        return box;
    }

    private Node buildAboutTab() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(22));

        HBox hdr = new HBox(12, StockProLogo.create(42));
        hdr.setAlignment(Pos.CENTER_LEFT);
        VBox titles = new VBox(2);
        Label heading = new Label("StockPro — Inventory Management System (Version 1.0.0)");
        heading.getStyleClass().add("section-title");
        Label authors = new Label("Authors: Syed Ehtisham Haider Bukhari & Faheen Ahmad Khan");
        authors.getStyleClass().add("kpi-delta-accent");
        titles.getChildren().addAll(heading, authors);
        hdr.getChildren().add(titles);

        Label stack = new Label("Architecture & Tech Stack:\n"
                + "• Desktop GUI: Java 21+, JavaFX (Canvas Vector Charts + Feather Vector Icons), Full Dark & Light Mode\n"
                + "• Persistence & Integrity: Local SQLite via HikariCP + SHA-256 Tamper-Evident Audit Chain\n"
                + "• DS&A & Offline ML: HashMap O(1), Compressed Trie, BK-Tree + Levenshtein, Min-Heap, TreeMap, Z-Score Guard\n"
                + "• Optional Cloud AI: User-owned Free Groq API Key + 3 Human-Approved Store Agents\n"
                + "• Reporting & Hardware: OpenPDF Branded Business Reports, Apache POI Excel, ZXing Code-128 & QR");
        stack.setWrapText(true);
        stack.getStyleClass().add("muted-text");

        card.getChildren().addAll(hdr, stack);
        VBox wrapper = new VBox(card);
        wrapper.setPadding(new Insets(14, 4, 14, 0));
        return wrapper;
    }

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
                memoryCard.updateMetric(t.usedHeapMb() + " MB", "Max JVM Heap: " + t.maxHeapMb() + " MB", DeltaTone.ACCENT);
                searchLatencyCard.updateMetric(
                        String.format("%.3f ms", t.trieLatencyMs()),
                        String.format("HashMap O(1): %.3f ms", t.hashMapLatencyMs()),
                        DeltaTone.POSITIVE
                );
                dbSizeCard.updateMetric(t.dbSizeKb() + " KB", "SQLite WAL store", DeltaTone.ACCENT);
                recordsCard.updateMetric(
                        String.valueOf(t.productCount()),
                        String.format("Moves: %d | Sups: %d | Audits: %d", t.movementCount(), t.supplierCount(), t.auditCount()),
                        DeltaTone.WARNING
                );
            }
        }, ex -> toastManager.showInfo("Telemetry load warning: " + ex.getMessage()));
    }
}
