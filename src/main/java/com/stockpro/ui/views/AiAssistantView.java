package com.stockpro.ui.views;

import com.stockpro.ai.AiAssistantService;
import com.stockpro.ai.ChartSpec;
import com.stockpro.domain.InventoryFilterCriteria;
import com.stockpro.domain.Product;
import com.stockpro.domain.Supplier;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AI Assistant &amp; Intelligence Hub matching {@code Screens and popups/AI Chat.png},
 * combining natural-language Store Q&amp;A, Ask-for-Any-Chart live JavaFX charts, supplier email drafting,
 * Explain-It-To-Me helper, Smart Natural Search, and the Offline ML Radar tab.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AiAssistantView {
    private final ServiceContext services;
    private final AiAssistantService assistantService;
    private final ToastNotificationManager toastManager;

    private VBox chatMessagesBox;
    private ScrollPane chatScroll;
    private TextField chatInput;
    private TabPane tabs;

    /**
     * Constructs the AI Assistant hub.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     */
    public AiAssistantView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.assistantService = services.getAiAssistantService();
        this.toastManager = toastManager;
    }

    /**
     * Builds the tabbed AI Assistant workspace (Chat Studio, ML Radar, and Approval Inbox slot).
     *
     * @return JavaFX root node
     */
    public Node build() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(0, 4, 8, 0));

        Label title = new Label("AI Assistant");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Ask store questions, generate live charts, draft supplier emails, and inspect ML forecasts");
        subtitle.getStyleClass().add("page-subtitle");

        tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        Tab chatTab = new Tab("💬 AI Chat Studio", buildChatStudio());
        Tab mlTab = new Tab("📡 ML Radar & Search", new MlRadarView(services).build());
        tabs.getTabs().addAll(chatTab, mlTab);
        appendExtraTabs(tabs);

        root.getChildren().addAll(title, subtitle, tabs);
        return root;
    }

    /**
     * Attaches the Human Approval Safety Inbox tab alongside Chat Studio and ML Radar.
     *
     * @param tabPane host tab pane
     */
    protected void appendExtraTabs(TabPane tabPane) {
        tabPane.getTabs().add(new Tab("✅ Approval Inbox",
                new ApprovalInboxView(services, toastManager).build()));
    }

    /**
     * Builds the conversational AI Chat workspace matching {@code AI Chat.png}.
     *
     * @return chat studio card
     */
    private Node buildChatStudio() {
        VBox container = new VBox(12);
        container.getStyleClass().add("card");
        container.setPadding(new Insets(16));

        // Smart Natural Search Bar
        HBox naturalSearchBar = new HBox(8);
        naturalSearchBar.setAlignment(Pos.CENTER_LEFT);
        TextField naturalInput = new TextField();
        naturalInput.setPromptText("Smart Natural Search (e.g. 'cheap snacks running low' or 'expiring dairy')...");
        HBox.setHgrow(naturalInput, Priority.ALWAYS);
        Button naturalBtn = new Button("Filter Store");
        naturalBtn.getStyleClass().add("btn-secondary");
        naturalBtn.setOnAction(e -> runNaturalFilterPreview(naturalInput.getText()));
        naturalSearchBar.getChildren().addAll(naturalInput, naturalBtn);

        // Chat Transcript Scroll Area
        chatMessagesBox = new VBox(12);
        chatMessagesBox.setPadding(new Insets(10));
        chatScroll = new ScrollPane(chatMessagesBox);
        chatScroll.setFitToWidth(true);
        chatScroll.setPrefHeight(360);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        appendAssistantMessage(
                "Hello! I am grounded in your local SQLite store inventory. Ask me anything, request a chart, or click a quick action below.",
                false,
                null
        );

        // Quick Action Chips matching AI Chat.png
        Button chipRunOut = makeQuickChip("Which items run out this week?",
                () -> sendUserPrompt("Which items run out this week?"));
        Button chipChart = makeQuickChip("Show Chart",
                () -> sendUserPrompt("Show a bar chart of top 5 valuable categories"));
        Button chipEmail = makeQuickChip("Write Supplier Email", this::runSupplierEmailDraft);
        Button chipExplain = makeQuickChip("Explain It To Me", this::runExplainWarning);
        Button chipBriefing = makeQuickChip("Morning Briefing", this::runMorningBriefing);

        HBox quickChips = new HBox(8, chipRunOut, chipChart, chipEmail, chipExplain, chipBriefing);
        quickChips.setAlignment(Pos.CENTER_LEFT);

        // Input Composer
        chatInput = new TextField();
        chatInput.setPromptText("Ask anything...");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        chatInput.setOnAction(e -> sendUserPrompt(chatInput.getText()));

        Button sendBtn = new Button("➤");
        sendBtn.getStyleClass().add("btn-primary");
        sendBtn.setOnAction(e -> sendUserPrompt(chatInput.getText()));

        HBox composer = new HBox(10, chatInput, sendBtn);
        composer.setAlignment(Pos.CENTER_LEFT);

        container.getChildren().addAll(naturalSearchBar, chatScroll, quickChips, composer);
        return container;
    }

    /**
     * Creates a pill-styled quick action chip.
     *
     * @param label button text
     * @param action click handler
     * @return styled {@link Button}
     */
    private Button makeQuickChip(String label, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("filter-chip");
        b.setOnAction(e -> action.run());
        return b;
    }

    /**
     * Submits a chat prompt asynchronously and appends both user bubble and grounded assistant response.
     *
     * @param rawPrompt user message text
     */
    private void sendUserPrompt(String rawPrompt) {
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return;
        }
        String question = rawPrompt.trim();
        chatInput.clear();
        appendUserBubble(question);

        AppExecutor.runAsync(
                () -> assistantService.answerQuestion(question),
                reply -> appendAssistantMessage(reply.message(), reply.usedCloud(), reply.chartSpec()),
                ex -> appendAssistantMessage("Unable to process question: " + ex.getMessage(), false, null)
        );
    }

    /**
     * Generates a ready-to-send restock email for the first available supplier.
     */
    private void runSupplierEmailDraft() {
        appendUserBubble("Write a restock email for our supplier.");
        AppExecutor.runAsync(() -> {
            List<Supplier> sups = services.getSupplierOrderService().getAllSuppliers();
            Supplier target = sups.isEmpty() ? null : sups.get(0);
            return assistantService.draftSupplierEmail(target);
        }, email -> appendAssistantMessage(email, false, null), ex -> {});
    }

    /**
     * Runs Explain-It-To-Me plain-English breakdown for store alerts.
     */
    private void runExplainWarning() {
        appendUserBubble("Explain our current ML alerts and inventory warnings simply.");
        AppExecutor.runAsync(
                () -> assistantService.explainInPlainEnglish("current ML warnings and anomalies"),
                text -> appendAssistantMessage(text, false, null),
                ex -> {}
        );
    }

    /**
     * Generates the Morning Business Briefing in the chat window.
     */
    private void runMorningBriefing() {
        appendUserBubble("Give me today's morning business briefing.");
        AppExecutor.runAsync(
                assistantService::generateMorningBriefing,
                text -> appendAssistantMessage(text, false, null),
                ex -> {}
        );
    }

    /**
     * Translates a casual search phrase into active filter criteria and lists matching products inline.
     *
     * @param phrase casual filter phrase
     */
    private void runNaturalFilterPreview(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return;
        }
        appendUserBubble("Smart Search: \"" + phrase + "\"");
        AppExecutor.runAsync(() -> {
            InventoryFilterCriteria criteria = assistantService.parseNaturalSearch(phrase);
            List<Product> hits = services.getInventoryService().filterProducts(criteria);
            return new Object[]{criteria, hits};
        }, data -> {
            InventoryFilterCriteria criteria = (InventoryFilterCriteria) data[0];
            @SuppressWarnings("unchecked")
            List<Product> hits = (List<Product>) data[1];
            String summary = hits.isEmpty()
                    ? "No items matched filter (" + criteria.getCategory() + " / " + criteria.getStatusChip() + ")."
                    : hits.stream().map(p -> "• " + p.getName() + " (" + p.getSku() + " — Qty: " + p.getQuantity()
                    + ", Price: " + com.stockpro.service.AppSession.getInstance().formatMoney(p.getSellPrice()) + ")")
                    .collect(Collectors.joining("\n"));
            appendAssistantMessage("Matched " + hits.size() + " item(s) [Category: "
                    + criteria.getCategory() + ", Status: " + criteria.getStatusChip() + "]:\n" + summary, false, null);
            toastManager.showInfo("Natural filter matched " + hits.size() + " item(s).");
        }, ex -> {});
    }

    /**
     * Appends a right-aligned Royal Indigo user message bubble.
     *
     * @param text user message
     */
    private void appendUserBubble(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_RIGHT);
        Label bubble = new Label(text);
        bubble.setWrapText(true);
        bubble.setMaxWidth(480);
        bubble.setStyle("-fx-background-color: #4F46E5; -fx-text-fill: #FFFFFF; -fx-padding: 10 14 10 14; -fx-background-radius: 14 14 2 14; -fx-font-weight: 600;");
        row.getChildren().add(bubble);
        chatMessagesBox.getChildren().add(row);
        chatScroll.setVvalue(1.0);
    }

    /**
     * Appends a left-aligned assistant card with bot badge and optional live JavaFX chart.
     *
     * @param text assistant message
     * @param usedCloud whether Groq API generated the text
     * @param spec optional {@link ChartSpec} to render live
     */
    private void appendAssistantMessage(String text, boolean usedCloud, ChartSpec spec) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.TOP_LEFT);

        Label botAvatar = new Label("🤖");
        botAvatar.setMinSize(32, 32);
        botAvatar.setAlignment(Pos.CENTER);
        botAvatar.setStyle("-fx-background-color: #EEF2FF; -fx-background-radius: 999; -fx-font-size: 16px;");

        VBox card = new VBox(8);
        card.setMaxWidth(580);
        card.setStyle("-fx-background-color: #F1F5F9; -fx-padding: 12 14 12 14; -fx-background-radius: 14 14 14 2;");

        Label msgLbl = new Label(text);
        msgLbl.setWrapText(true);
        msgLbl.setStyle("-fx-text-fill: #0F172A;");

        Label modeTag = new Label(usedCloud ? "Groq Cloud" : "Offline Local Grounded");
        modeTag.setStyle("-fx-font-size: 10.5px; -fx-text-fill: #64748B;");

        card.getChildren().addAll(msgLbl, modeTag);
        if (spec != null) {
            card.getChildren().add(renderChart(spec));
        }

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        row.getChildren().addAll(botAvatar, card, sp);
        chatMessagesBox.getChildren().add(row);
        chatScroll.setVvalue(1.0);
    }

    /**
     * Converts a {@link ChartSpec} into an interactive JavaFX BarChart, LineChart, or PieChart.
     *
     * @param spec structured chart specification
     * @return rendered JavaFX chart node
     */
    private Node renderChart(ChartSpec spec) {
        if ("PIE".equalsIgnoreCase(spec.chartType())) {
            PieChart pie = new PieChart();
            pie.setTitle(spec.title());
            pie.setPrefHeight(220);
            for (ChartSpec.ChartPoint pt : spec.points()) {
                pie.getData().add(new PieChart.Data(pt.label(), Math.max(0.0, pt.value())));
            }
            return pie;
        }
        if ("LINE".equalsIgnoreCase(spec.chartType())) {
            CategoryAxis x = new CategoryAxis();
            NumberAxis y = new NumberAxis();
            LineChart<String, Number> line = new LineChart<>(x, y);
            line.setTitle(spec.title());
            line.setPrefHeight(220);
            line.setLegendVisible(false);
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            for (ChartSpec.ChartPoint pt : spec.points()) {
                series.getData().add(new XYChart.Data<>(pt.label(), pt.value()));
            }
            line.getData().add(series);
            return line;
        }
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        BarChart<String, Number> bar = new BarChart<>(x, y);
        bar.setTitle(spec.title());
        bar.setPrefHeight(220);
        bar.setLegendVisible(false);
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (ChartSpec.ChartPoint pt : spec.points()) {
            series.getData().add(new XYChart.Data<>(pt.label(), pt.value()));
        }
        bar.getData().add(series);
        return bar;
    }
}
