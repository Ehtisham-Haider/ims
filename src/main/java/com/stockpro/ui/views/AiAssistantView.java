package com.stockpro.ui.views;

import com.stockpro.ai.AiAssistantService;
import com.stockpro.ai.ChartSpec;
import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.service.AppSession;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ModernBarChart;
import com.stockpro.ui.components.ModernTrendChart;
import com.stockpro.ui.components.StockDonutChart;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Assistant &amp; Intelligence Hub matching
 * {@code Screens and popups/AI Chat.png},
 * featuring user-owned Groq API key status &amp; one-click Settings
 * configuration,
 * honest Cloud vs Offline preview routing, live
 * {@link ModernBarChart}/{@link ModernTrendChart}/{@link StockDonutChart}
 * chart specs, Run AI Audit action, Smart Natural Search with Inventory
 * drill-down, ML Radar, and Approval Inbox.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AiAssistantView {
    private final ServiceContext services;
    private final AiAssistantService assistantService;
    private final ToastNotificationManager toastManager;
    private final Runnable onOpenAiSettings;
    private final java.util.function.Consumer<InventoryFilterCriteria> onOpenFilteredInventory;

    private final List<Runnable> themeRefreshers = new ArrayList<>();
    private MlRadarView mlRadarView;
    private ApprovalInboxView approvalInboxView;

    private HBox apiStatusBanner;
    private VBox chatMessagesBox;
    private ScrollPane chatScroll;
    private TextField chatInput;
    private TabPane tabs;
    private Tab approvalTab;

    /**
     * Constructs the AI Assistant hub with core services.
     *
     * @param services     shared service registry
     * @param toastManager toast notification helper
     */
    public AiAssistantView(ServiceContext services, ToastNotificationManager toastManager) {
        this(services, toastManager, null, null);
    }

    /**
     * Constructs the AI Assistant hub wired with deep navigation into Settings and
     * Inventory filtering.
     *
     * @param services                shared service registry
     * @param toastManager            toast notification helper
     * @param onOpenAiSettings        callback opening Settings -&gt; AI &amp; Groq
     *                                API tab
     * @param onOpenFilteredInventory callback opening Inventory pre-filtered with
     *                                Smart Natural Search criteria
     */
    public AiAssistantView(
            ServiceContext services,
            ToastNotificationManager toastManager,
            Runnable onOpenAiSettings,
            java.util.function.Consumer<InventoryFilterCriteria> onOpenFilteredInventory) {
        this.services = services;
        this.assistantService = services.getAiAssistantService();
        this.toastManager = toastManager;
        this.onOpenAiSettings = onOpenAiSettings;
        this.onOpenFilteredInventory = onOpenFilteredInventory;
    }

    /**
     * Releases child tab EventBus subscriptions when navigating away.
     */
    public void dispose() {
        if (mlRadarView != null) {
            mlRadarView.dispose();
        }
        if (approvalInboxView != null) {
            approvalInboxView.dispose();
        }
    }

    /**
     * Repaints any inline generated Canvas charts on Light/Dark toggle.
     */
    public void onThemeChanged() {
        themeRefreshers.forEach(Runnable::run);
    }

    /**
     * Builds the tabbed AI Assistant workspace (Chat Studio, ML Radar, and Approval
     * Inbox).
     *
     * @return JavaFX root node
     */
    public Node build() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(0, 4, 8, 0));

        Label title = new Label("AI Assistant");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(
                "Connect your own free Groq API key in Settings for live Gen AI chat, briefings, emails & custom charts");
        subtitle.getStyleClass().add("page-subtitle");

        mlRadarView = new MlRadarView(services);
        approvalInboxView = new ApprovalInboxView(services, toastManager);

        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Tab chatTab = new Tab("AI Chat Studio", buildChatStudio());
        Tab mlTab = new Tab("ML Radar & DS&A", mlRadarView.build());
        approvalTab = new Tab("Approval Inbox", approvalInboxView.build());
        tabs.getTabs().addAll(chatTab, mlTab, approvalTab);

        root.getChildren().addAll(title, subtitle, tabs);
        return root;
    }

    /**
     * Attaches extra tabs if subclassed.
     *
     * @param tabPane host tab pane
     */
    protected void appendExtraTabs(TabPane tabPane) {
        // Approval Inbox is already registered in build().
    }

    private Node buildChatStudio() {
        VBox container = new VBox(12);
        container.getStyleClass().add("card");
        container.setPadding(new Insets(16));

        // API Key Status Banner
        apiStatusBanner = new HBox(12);
        apiStatusBanner.setAlignment(Pos.CENTER_LEFT);
        refreshApiStatusBanner();

        // Smart Natural Search Bar
        HBox naturalSearchBar = new HBox(8);
        naturalSearchBar.setAlignment(Pos.CENTER_LEFT);
        TextField naturalInput = new TextField();
        naturalInput.setPromptText("Smart Natural Search (e.g. 'cheap snacks running low' or 'expiring dairy')...");
        HBox.setHgrow(naturalInput, Priority.ALWAYS);
        Button naturalBtn = new Button("Filter Store");
        naturalBtn.setGraphic(AppIcons.of(Feather.FILTER, 13));
        naturalBtn.getStyleClass().add("btn-secondary");
        naturalBtn.setOnAction(e -> runNaturalFilterPreview(naturalInput.getText()));
        naturalInput.setOnAction(e -> runNaturalFilterPreview(naturalInput.getText()));
        naturalSearchBar.getChildren().addAll(naturalInput, naturalBtn);

        // Chat Transcript Scroll Area
        chatMessagesBox = new VBox(12);
        chatMessagesBox.setPadding(new Insets(8));
        chatScroll = new ScrollPane(chatMessagesBox);
        chatScroll.setFitToWidth(true);
        chatScroll.setPrefHeight(310);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        boolean cloudReady = assistantService.isCloudAiReady();
        appendAssistantCard(
                cloudReady
                        ? "Groq Cloud (" + assistantService.getActiveModel()
                                + ") is active and grounded in your live SQLite store catalog. Ask any question or request a chart below!"
                        : "Welcome! Live cloud AI requires your own free Groq API key (Settings -> AI & Groq API). You can add your key now or click any action to preview offline mode.",
                cloudReady ? "Groq Cloud • " + assistantService.getActiveModel() : "Setup Needed",
                cloudReady,
                null,
                null,
                null);

        // Quick Action Chips
        Button chipRunOut = makeQuickChip("Which items run out this week?", Feather.CLOCK,
                () -> sendUserPrompt("Which items run out this week?"));
        Button chipChart = makeQuickChip("Show Chart", Feather.BAR_CHART_2,
                () -> sendUserPrompt("Show a bar chart of top 5 valuable categories"));
        Button chipEmail = makeQuickChip("Write Supplier Email", Feather.MAIL, this::runSupplierEmailDraft);
        Button chipExplain = makeQuickChip("Explain It To Me", Feather.HELP_CIRCLE, this::runExplainWarning);
        Button chipAudit = makeQuickChip("Run AI Audit", Feather.SHIELD, this::runAgentAuditFromChat);
        Button chipWorld = makeQuickChip("Weather & Holidays", Feather.CLOUD, this::runWorldAwareBriefing);
        Button chipPromo = makeQuickChip("Promo Copy", Feather.TAG, this::runClearancePromoDraft);
        Button chipHelp = makeQuickChip("Help Guide", Feather.BOOK_OPEN, this::runInAppHelpPrompt);

        HBox quickChips = new HBox(8, chipRunOut, chipChart, chipEmail, chipExplain, chipAudit, chipWorld, chipPromo, chipHelp);
        quickChips.setAlignment(Pos.CENTER_LEFT);

        // Input Composer
        chatInput = new TextField();
        chatInput.setPromptText("Ask anything about your inventory, sales trends, supplier emails, or charts...");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        chatInput.setOnAction(e -> sendUserPrompt(chatInput.getText()));

        Button micBtn = new Button("Voice");
        micBtn.setGraphic(AppIcons.of(Feather.MIC, 14));
        micBtn.getStyleClass().add("btn-secondary");
        micBtn.setOnAction(e -> triggerVoiceCommandPrompt(micBtn));

        Button sendBtn = new Button("Send");
        sendBtn.setGraphic(AppIcons.of(Feather.SEND, 14));
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> sendUserPrompt(chatInput.getText()));

        HBox composer = new HBox(10, chatInput, micBtn, sendBtn);
        composer.setAlignment(Pos.CENTER_LEFT);

        container.getChildren().addAll(apiStatusBanner, naturalSearchBar, chatScroll, quickChips, composer);
        return container;
    }

    private void refreshApiStatusBanner() {
        apiStatusBanner.getChildren().clear();
        apiStatusBanner.getStyleClass().removeAll("ai-key-banner-ok", "ai-key-banner-warn");
        boolean ready = assistantService.isCloudAiReady();
        if (ready) {
            apiStatusBanner.getStyleClass().add("ai-key-banner-ok");
            Label icon = new Label();
            icon.setGraphic(AppIcons.of(Feather.CHECK_CIRCLE, 16, "#10B981"));
            Label lbl = new Label("Groq Cloud AI Connected • Model: " + assistantService.getActiveModel());
            lbl.setStyle("-fx-font-weight: bold;");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Button cfgBtn = new Button("API Settings");
            cfgBtn.setGraphic(AppIcons.of(Feather.SETTINGS, 13));
            cfgBtn.getStyleClass().add("btn-secondary");
            cfgBtn.setOnAction(e -> {
                if (onOpenAiSettings != null) {
                    onOpenAiSettings.run();
                }
            });
            apiStatusBanner.getChildren().addAll(icon, lbl, sp, cfgBtn);
        } else {
            apiStatusBanner.getStyleClass().add("ai-key-banner-warn");
            Label icon = new Label();
            icon.setGraphic(AppIcons.of(Feather.KEY, 16, "#F59E0B"));
            Label lbl = new Label("No Groq API key saved yet — add your free key in Settings to use live Cloud AI.");
            lbl.setWrapText(true);
            lbl.setStyle("-fx-font-weight: 600;");
            HBox.setHgrow(lbl, Priority.ALWAYS);
            Button addKeyBtn = new Button("Add API Key in Settings");
            addKeyBtn.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13));
            addKeyBtn.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            addKeyBtn.getStyleClass().add("btn-primary");
            addKeyBtn.setOnAction(e -> {
                if (onOpenAiSettings != null) {
                    onOpenAiSettings.run();
                }
            });
            apiStatusBanner.getChildren().addAll(icon, lbl, addKeyBtn);
        }
    }

    private Button makeQuickChip(String label, Feather feather, Runnable action) {
        Button b = new Button(label);
        b.setGraphic(AppIcons.of(feather, 13));
        b.getStyleClass().add("filter-chip");
        b.setOnAction(e -> action.run());
        return b;
    }

    private void triggerVoiceCommandPrompt(Button sourceBtn) {
        javafx.stage.Stage dlg = new javafx.stage.Stage();
        dlg.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        if (sourceBtn != null && sourceBtn.getScene() != null) {
            dlg.initOwner(sourceBtn.getScene().getWindow());
        }
        dlg.setTitle("Voice Control & Whisper Studio (whisper-large-v3-turbo)");

        VBox card = new VBox(12);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(18));

        boolean micReady = services.getVoiceAudioRecorder().isMicrophoneAvailable();
        Label statusLbl = new Label(micReady
                ? "Microphone detected — click 'Capture 3s & Transcribe' or type a spoken test phrase below."
                : "No live mic line detected — type a simulated voice phrase or run Whisper synthetic clip test.");
        statusLbl.setWrapText(true);
        statusLbl.getStyleClass().add("muted-text");

        TextField spokenField = new TextField();
        spokenField.setPromptText("e.g. 'Add 10 Arabica Coffee' | 'Restock 5 Milk' | 'Open Warehouse Map' | 'Find Basmati'");

        javafx.animation.FadeTransition pulse = new javafx.animation.FadeTransition(javafx.util.Duration.millis(650), statusLbl);
        pulse.setFromValue(1.0);
        pulse.setToValue(0.45);
        pulse.setCycleCount(javafx.animation.Animation.INDEFINITE);
        pulse.setAutoReverse(true);

        Button captureBtn = new Button(micReady ? "Capture 3s Voice (Whisper)" : "Run Voice Command");
        captureBtn.setGraphic(AppIcons.of(Feather.MIC, 14, "#FFFFFF"));
        captureBtn.getStyleClass().add("btn-primary");
        captureBtn.setOnAction(e -> {
            captureBtn.setDisable(true);
            pulse.play();
            String typedFallback = spokenField.getText();
            String actor = AppSession.getInstance().getActorName();
            AppExecutor.runAsync(() -> {
                byte[] wav = services.getVoiceAudioRecorder().recordClipOrSyntheticWav(2500);
                return services.getVoiceCommandEngine().transcribeAndExecute(wav, typedFallback, actor);
            }, result -> {
                pulse.stop();
                statusLbl.setOpacity(1.0);
                captureBtn.setDisable(false);
                dlg.close();
                if (result.intent() == com.stockpro.domain.VoiceCommandResult.VoiceIntent.CHAT_QUERY
                        && result.rawTranscript() != null && !result.rawTranscript().isBlank()) {
                    sendUserPrompt(result.rawTranscript());
                } else {
                    appendUserBubble("[Voice" + (result.usedCloudWhisper() ? " Whisper" : "") + "] " + result.rawTranscript());
                    appendAssistantCard(result.message(), "Voice Control Engine", result.executed(), null, null, null);
                    toastManager.showInfo(result.message());
                }
            }, ex -> {
                pulse.stop();
                statusLbl.setOpacity(1.0);
                captureBtn.setDisable(false);
                toastManager.showInfo("Voice processing error: " + ex.getMessage());
            });
        });

        Button closeBtn = new Button("Cancel");
        closeBtn.getStyleClass().add("btn-secondary");
        closeBtn.setOnAction(e -> dlg.close());

        card.getChildren().addAll(statusLbl, new Label("Spoken Command / Offline Transcript:"), spokenField,
                new HBox(10, captureBtn, closeBtn));
        javafx.scene.Scene sc = new javafx.scene.Scene(card, 500, 230);
        var css = getClass().getResource(AppSession.getInstance().isDarkMode() ? "/css/dark-theme.css" : "/css/light-theme.css");
        if (css != null) {
            sc.getStylesheets().add(css.toExternalForm());
        }
        dlg.setScene(sc);
        dlg.showAndWait();
    }

    private void sendUserPrompt(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return;
        }
        String question = rawPrompt.trim();
        chatInput.clear();
        appendUserBubble(question);
        executeCloudOrPromptSetup(() -> assistantService.answerQuestion(question));
    }

    private void runSupplierEmailDraft() {
        appendUserBubble("Draft a restock purchase inquiry email for our supplier.");
        executeCloudOrPromptSetup(() -> {
            List<com.stockpro.domain.Supplier> sups = services.getSupplierOrderService().getAllSuppliers();
            com.stockpro.domain.Supplier target = sups.isEmpty() ? null : sups.get(0);
            return assistantService.draftSupplierEmailReply(target);
        });
    }

    private void runExplainWarning() {
        appendUserBubble("Explain our current ML alerts and inventory warnings simply.");
        executeCloudOrPromptSetup(
                () -> assistantService.explainInPlainEnglishReply("current ML warnings and anomalies"));
    }

    private void runWorldAwareBriefing() {
        appendUserBubble("Check 7-day weather alerts, upcoming holidays, and local consumer trends.");
        AppExecutor.runAsync(() -> {
            var gen = services.getWorldAwareGenAiService();
            var w = gen.analyzeWeatherStocking(31.5204, 74.3587);
            var holidays = gen.forecastUpcomingHolidays("PK", java.time.LocalDate.now());
            var trends = gen.spotLocalTrends("PK");
            String hStr = holidays.isEmpty() ? "None" : holidays.get(0).holidayName() + " in " + holidays.get(0).daysAway() + "d (" + holidays.get(0).demandMultiplier() + "x)";
            String tStr = trends.isEmpty() ? "None" : trends.get(0).trendKeyword() + " -> " + trends.get(0).opportunityPitch();
            return "Weather Alert (" + w.source() + "): " + w.conditionHeadline() + " -> " + w.guidance()
                    + "\nUpcoming Holiday: " + hStr
                    + "\nLocal Trend Signal: " + tStr;
        }, text -> appendAssistantCard(text, "World-Aware AI", true, null, null, null),
                ex -> toastManager.showInfo("World-aware check failed: " + ex.getMessage()));
    }

    private void runClearancePromoDraft() {
        appendUserBubble("Write a 25% clearance promo blurb for a high-stock/expiring SKU.");
        AppExecutor.runAsync(() -> {
            List<Product> products = services.getInventoryService().getAllProducts();
            Product target = products.stream()
                    .max(java.util.Comparator.comparingInt(Product::getQuantity))
                    .orElse(null);
            return services.getWorldAwareGenAiService().generateClearancePromoCopy("WhatsApp", target, 25);
        }, text -> appendAssistantCard(text, "GenAI Promo Copywriter", true, null, null, null),
                ex -> toastManager.showInfo("Promo draft failed: " + ex.getMessage()));
    }

    private void runInAppHelpPrompt() {
        String q = chatInput != null && chatInput.getText() != null && !chatInput.getText().isBlank()
                ? chatInput.getText().trim()
                : "How do I use the 2D warehouse map, Vision Studio, and Voice control?";
        appendUserBubble("[In-App Help] " + q);
        String guide = services.getWorldAwareGenAiService().answerInAppHelpQuestion(q);
        appendAssistantCard(guide, "Smart In-App Help Guide", true, null, null, null);
    }

    private void runAgentAuditFromChat() {
        appendUserBubble("Run all 3 AI Agents (Auto-Buyer, Nightly Auditor, Expiry Rescue) now.");
        HBox thinkingRow = appendThinkingBubble("Running all 3 store agents...");
        AppExecutor.runAsync(
                () -> services.getAgenticOrchestratorService().runAllAgents(),
                created -> {
                    chatMessagesBox.getChildren().remove(thinkingRow);
                    Button openInboxBtn = new Button("Review in Approval Inbox (" + created.size() + " new)");
                    openInboxBtn.getStyleClass().add("btn-primary");
                    openInboxBtn.setOnAction(ev -> {
                        if (tabs != null && approvalTab != null) {
                            tabs.getSelectionModel().select(approvalTab);
                            approvalInboxView.refreshAsync();
                        }
                    });
                    appendAssistantCard(
                            "Agent scan finished: queued " + created.size()
                                    + " proposal(s) awaiting human confirmation. Zero records changed silently.",
                            "Agentic Orchestrator",
                            true,
                            null,
                            null,
                            openInboxBtn);
                },
                ex -> {
                    chatMessagesBox.getChildren().remove(thinkingRow);
                    toastManager.showInfo("Agent audit failed: " + ex.getMessage());
                });
    }

    /**
     * Checks cleanly whether the user has configured their Groq API key before
     * calling Cloud AI.
     * If unconfigured, surfaces a helpful key-setup prompt with an optional
     * explicit "Run Local Preview" button
     * rather than silently pretending offline text came from Cloud AI.
     *
     * @param taskSupplier supplier producing the assistant reply
     */
    private void executeCloudOrPromptSetup(
            java.util.function.Supplier<AiAssistantService.AssistantReply> taskSupplier) {
        if (!assistantService.isCloudAiReady()) {
            Button previewBtn = new Button("Run Local Offline Preview");
            previewBtn.getStyleClass().add("btn-secondary");
            previewBtn.setOnAction(ev -> runTaskAsync(taskSupplier));

            appendAssistantCard(
                    "No Groq API key is configured yet. Add your own free key (console.groq.com) in Settings -> AI & Groq API to power cloud AI responses, or preview local deterministic results below.",
                    "Groq API Key Required",
                    false,
                    null,
                    "Add Key in Settings",
                    previewBtn);
            return;
        }
        runTaskAsync(taskSupplier);
    }

    private void runTaskAsync(java.util.function.Supplier<AiAssistantService.AssistantReply> taskSupplier) {
        HBox thinkingRow = appendThinkingBubble(
                assistantService.isCloudAiReady()
                        ? "Thinking with Groq (" + assistantService.getActiveModel() + ")..."
                        : "Computing offline store response...");
        AppExecutor.runAsync(
                taskSupplier::get,
                reply -> {
                    chatMessagesBox.getChildren().remove(thinkingRow);
                    if (reply.usedCloud()) {
                        refreshApiStatusBanner();
                    }
                    String badge = reply.usedCloud()
                            ? "Groq Cloud • " + reply.modelUsed()
                            : "Offline Local Engine";
                    String text = reply.message();
                    if (!reply.usedCloud() && reply.errorNote() != null && !reply.errorNote().isBlank()
                            && assistantService.isCloudAiReady()) {
                        text += "\n\n[Cloud Note: " + reply.errorNote() + "]";
                    }
                    appendAssistantCard(
                            text,
                            badge,
                            reply.usedCloud(),
                            reply.chartSpec(),
                            (!reply.usedCloud() && assistantService.isCloudAiReady()) ? "Check Key in Settings" : null,
                            null);
                },
                ex -> {
                    chatMessagesBox.getChildren().remove(thinkingRow);
                    appendAssistantCard(
                            "Unable to process request: " + ex.getMessage(),
                            "Error",
                            false,
                            null,
                            null,
                            null);
                });
    }

    private void runNaturalFilterPreview(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return;
        }
        appendUserBubble("Smart Search: \"" + phrase + "\"");
        AppExecutor.runAsync(() -> {
            InventoryFilterCriteria criteria = assistantService.parseNaturalSearch(phrase);
            List<Product> hits = services.getInventoryService().filterProducts(criteria);
            return new Object[] { criteria, hits };
        }, data -> {
            InventoryFilterCriteria criteria = (InventoryFilterCriteria) data[0];
            @SuppressWarnings("unchecked")
            List<Product> hits = (List<Product>) data[1];
            String summary = hits.isEmpty()
                    ? "No items matched filter (" + criteria.getCategory() + " / " + criteria.getStatusChip() + ")."
                    : hits.stream().map(p -> "• " + p.getName() + " (" + p.getSku() + " — Qty: " + p.getQuantity()
                            + ", Price: " + AppSession.getInstance().formatMoney(p.getSellPrice()) + ")")
                            .collect(Collectors.joining("\n"));

            Button jumpInvBtn = null;
            if (onOpenFilteredInventory != null && !hits.isEmpty()) {
                jumpInvBtn = new Button("Open in Inventory");
                jumpInvBtn.setGraphic(AppIcons.of(Feather.ARROW_RIGHT, 13));
                jumpInvBtn.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
                jumpInvBtn.getStyleClass().add("btn-primary");
                jumpInvBtn.setOnAction(ev -> onOpenFilteredInventory.accept(criteria));
            }
            appendAssistantCard(
                    "Matched " + hits.size() + " item(s) [Category: " + criteria.getCategory()
                            + ", Status: " + criteria.getStatusChip() + "]:\n" + summary,
                    "Smart Natural Search",
                    false,
                    null,
                    null,
                    jumpInvBtn);
            toastManager.showInfo("Natural filter matched " + hits.size() + " item(s).");
        }, ex -> toastManager.showInfo("Natural search failed: " + ex.getMessage()));
    }

    private void appendUserBubble(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_RIGHT);
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(480);
        bubble.getStyleClass().add("chat-bubble-user");
        row.getChildren().add(bubble);
        chatMessagesBox.getChildren().add(row);
        chatScroll.setVvalue(1.0);
    }

    private HBox appendThinkingBubble(String labelText) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.TOP_LEFT);
        StackPane botAvatar = makeBotAvatar();
        VBox card = new VBox(4);
        card.getStyleClass().add("chat-bubble-ai");
        Label lbl = new Label(labelText);
        lbl.getStyleClass().add("muted-text");
        card.getChildren().add(lbl);
        row.getChildren().addAll(botAvatar, card);
        chatMessagesBox.getChildren().add(row);
        chatScroll.setVvalue(1.0);
        return row;
    }

    private void appendAssistantCard(
            String text,
            String sourceBadge,
            boolean isCloud,
            ChartSpec spec,
            String settingsBtnLabel,
            Button extraActionBtn) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.TOP_LEFT);
        StackPane botAvatar = makeBotAvatar();

        VBox card = new VBox(8);
        card.setMaxWidth(620);
        card.getStyleClass().add("chat-bubble-ai");

        Label msgLbl = new Label(text);
        msgLbl.setWrapText(true);

        HBox metaRow = new HBox(8);
        metaRow.setAlignment(Pos.CENTER_LEFT);
        Label modeTag = new Label(sourceBadge);
        modeTag.getStyleClass().addAll("status-pill", isCloud ? "status-in-stock" : "status-info-pill");
        metaRow.getChildren().add(modeTag);

        if (settingsBtnLabel != null && onOpenAiSettings != null) {
            Button cfgBtn = new Button(settingsBtnLabel);
            cfgBtn.setGraphic(AppIcons.of(Feather.KEY, 12));
            cfgBtn.getStyleClass().add("btn-primary");
            cfgBtn.setStyle("-fx-padding: 5 11 5 11;");
            cfgBtn.setOnAction(e -> onOpenAiSettings.run());
            metaRow.getChildren().add(cfgBtn);
        }
        if (extraActionBtn != null) {
            metaRow.getChildren().add(extraActionBtn);
        }

        card.getChildren().addAll(msgLbl, metaRow);
        if (spec != null) {
            card.getChildren().add(renderChart(spec));
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        row.getChildren().addAll(botAvatar, card, sp);
        chatMessagesBox.getChildren().add(row);
        chatScroll.setVvalue(1.0);
    }

    private StackPane makeBotAvatar() {
        StackPane botAvatar = new StackPane(AppIcons.of(Feather.CPU, 16, "#4F46E5"));
        botAvatar.setMinSize(32, 32);
        botAvatar.setPrefSize(32, 32);
        botAvatar.setMaxSize(32, 32);
        botAvatar.setStyle("-fx-background-color: #EEF2FF; -fx-background-radius: 999;");
        return botAvatar;
    }

    /**
     * Converts a {@link ChartSpec} into our modern responsive Canvas charts
     * ({@link ModernBarChart},
     * {@link ModernTrendChart}, or {@link StockDonutChart}).
     *
     * @param spec structured chart specification
     * @return rendered modern JavaFX chart node
     */
    private Node renderChart(ChartSpec spec) {
        if ("PIE".equalsIgnoreCase(spec.chartType())) {
            StockDonutChart donut = new StockDonutChart();
            double total = spec.points().stream().mapToDouble(p -> Math.max(0.0, p.value())).sum();
            String[] palette = { "#4F46E5", "#10B981", "#F59E0B", "#0EA5E9", "#8B5CF6" };
            StockDonutChart.Slice[] slices = new StockDonutChart.Slice[Math.min(5, spec.points().size())];
            for (int i = 0; i < slices.length; i++) {
                ChartSpec.ChartPoint pt = spec.points().get(i);
                double frac = total > 0 ? Math.max(0.0, pt.value()) / total : 0.0;
                slices[i] = new StockDonutChart.Slice(pt.label(), String.format("%.0f%%", frac * 100), frac,
                        palette[i % palette.length]);
            }
            donut.setData(slices.length > 0 ? slices[0].valueDisplay() : "0%", spec.title(), slices);
            themeRefreshers.add(donut::redraw);
            return donut;
        }
        if ("LINE".equalsIgnoreCase(spec.chartType())) {
            ModernTrendChart trend = new ModernTrendChart(220);
            trend.setPrefWidth(540);
            trend.setCurrencyFormat(false);
            List<String> x = spec.points().stream().map(ChartSpec.ChartPoint::label).toList();
            List<Double> y = spec.points().stream().map(ChartSpec.ChartPoint::value).toList();
            trend.setSeries(x, List.of(new ModernTrendChart.SeriesData(spec.title(), "#4F46E5", y)));
            themeRefreshers.add(trend::redraw);
            return trend;
        }
        ModernBarChart bar = new ModernBarChart(230);
        bar.setPrefWidth(540);
        bar.setData(spec.title(), spec.points().stream()
                .map(pt -> new ModernBarChart.BarPoint(pt.label(), pt.value()))
                .toList());
        themeRefreshers.add(bar::redraw);
        return bar;
    }
}
