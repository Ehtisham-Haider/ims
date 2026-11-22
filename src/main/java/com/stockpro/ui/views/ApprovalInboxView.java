package com.stockpro.ui.views;

import com.stockpro.ai.AgenticOrchestratorService;
import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.ProposalStatus;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
import com.stockpro.ui.components.AppIcons;
import com.stockpro.ui.components.ToastNotificationManager;
import com.stockpro.util.AppExecutor;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

import java.util.List;
import java.util.function.Consumer;

/**
 * Human Approval Safety Inbox matching
 * {@code Screens and popups/Approvals.png}, displaying every
 * autonomous Auto-Buyer, Nightly Auditor, Expiry Rescue, and 7-Agent consensus
 * recommendation
 * with Feather icons, loading guard on scan trigger, and
 * {@code Approve / Reject} buttons.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class ApprovalInboxView {
    private final ServiceContext services;
    private final AgenticOrchestratorService orchestrator;
    private final ToastNotificationManager toastManager;
    private final Consumer<String> eventListener = topic -> refreshAsync();

    private VBox cardsContainer;
    private Label brainSummaryLabel;
    private CheckBox pendingOnlyCheck;

    /**
     * Constructs the Human Approval Safety Inbox panel.
     *
     * @param services     shared service registry
     * @param toastManager toast notification helper
     */
    public ApprovalInboxView(ServiceContext services, ToastNotificationManager toastManager) {
        this.services = services;
        this.orchestrator = services.getAgenticOrchestratorService();
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
     * Builds the scrollable Approval Inbox UI.
     *
     * @return JavaFX node
     */
    public Node build() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(12, 4, 16, 0));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleCol = new VBox(2);
        Label heading = new Label("AI Agent Approval Inbox");
        heading.getStyleClass().add("section-title");
        heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label sub = new Label(
                "Zero database writes occur silently — every AI proposal awaits explicit human confirmation.");
        sub.getStyleClass().add("muted-text");
        titleCol.getChildren().addAll(heading, sub);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        pendingOnlyCheck = new CheckBox("Pending Only");
        pendingOnlyCheck.setSelected(true);
        pendingOnlyCheck.setOnAction(e -> refreshAsync());

        Button runAgentsBtn = new Button("Run All 3 AI Agents");
        runAgentsBtn.setGraphic(AppIcons.of(Feather.PLAY, 14));
        runAgentsBtn.getStyleClass().add("btn-primary");
        runAgentsBtn.setOnAction(e -> {
            runAgentsBtn.setDisable(true);
            runAgentsBtn.setText("Scanning store...");
            AppExecutor.runAsync(
                    orchestrator::runAllAgents,
                    created -> {
                        runAgentsBtn.setDisable(false);
                        runAgentsBtn.setText("Run All 3 AI Agents");
                        toastManager.showInfo("Agents finished scan: " + created.size() + " new proposal(s) queued.");
                        refreshAsync();
                    },
                    ex -> {
                        runAgentsBtn.setDisable(false);
                        runAgentsBtn.setText("Run All 3 AI Agents");
                        toastManager.showInfo("Agent scan error: " + ex.getMessage());
                    });
        });

        Button team7Btn = new Button("Run 7-Agent Store Brain");
        team7Btn.setGraphic(AppIcons.of(Feather.CPU, 14, "#FFFFFF"));
        team7Btn.getStyleClass().add("btn-primary");
        team7Btn.setOnAction(e -> {
            team7Btn.setDisable(true);
            AppExecutor.runAsync(
                    () -> services.getMultiAgentStoreBrainService().runSevenAgentTeamConsensus(),
                    created -> {
                        team7Btn.setDisable(false);
                        toastManager.showInfo(
                                "7-Agent Store Brain queued " + created.size() + " ranked consensus proposal(s).");
                        refreshAsync();
                    },
                    ex -> {
                        team7Btn.setDisable(false);
                        toastManager.showInfo("Team consensus error: " + ex.getMessage());
                    });
        });

        header.getChildren().addAll(titleCol, sp, pendingOnlyCheck, runAgentsBtn, team7Btn);

        brainSummaryLabel = new Label("Store Brain Telemetry loading...");
        brainSummaryLabel.setWrapText(true);
        brainSummaryLabel.getStyleClass().add("muted-text");

        cardsContainer = new VBox(12);
        refreshAsync();

        ScrollPane scroll = new ScrollPane(cardsContainer);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        page.getChildren().addAll(header, brainSummaryLabel, scroll);
        return page;
    }

    /**
     * Reloads proposal cards from SQLite off the UI thread.
     */
    public void refreshAsync() {
        boolean pendingOnly = pendingOnlyCheck == null || pendingOnlyCheck.isSelected();
        AppExecutor.runAsync(
                () -> new Object[] {
                        orchestrator.listProposals(pendingOnly),
                        services.getMultiAgentStoreBrainService().computeStoreHealthScore(),
                        services.getMultiAgentStoreBrainService().investigateSalesDrivers(14),
                        services.getMultiAgentStoreBrainService().getWasteEcoSummary()
                },
                arr -> {
                    @SuppressWarnings("unchecked")
                    List<AgentProposal> list = (List<AgentProposal>) arr[0];
                    var health = (com.stockpro.domain.StoreBrainModels.StoreHealthScoreReport) arr[1];
                    var det = (com.stockpro.domain.StoreBrainModels.SalesDetectiveReport) arr[2];
                    var eco = (com.stockpro.domain.StoreBrainModels.WasteEcoImpactSummary) arr[3];
                    if (brainSummaryLabel != null) {
                        brainSummaryLabel.setText(health.headline() + "  |  Sales Detective: " + det.headline()
                                + "  |  Eco Impact: " + eco.summary());
                    }
                    renderCards(list);
                },
                ex -> toastManager.showInfo("Failed to load proposals: " + ex.getMessage()));
    }

    private void renderCards(List<AgentProposal> proposals) {
        if (cardsContainer == null) {
            return;
        }
        cardsContainer.getChildren().clear();
        if (proposals.isEmpty()) {
            VBox emptyCard = new VBox(8);
            emptyCard.getStyleClass().add("card");
            emptyCard.setAlignment(Pos.CENTER);
            emptyCard.setPadding(new Insets(32));
            Label lbl = new Label("No pending AI proposals in the Approval Inbox.");
            lbl.getStyleClass().add("section-title");
            Label hint = new Label(
                    "Click 'Run All 3 AI Agents' above to scan for restock needs, audit anomalies, and expiring items.");
            hint.getStyleClass().add("muted-text");
            emptyCard.getChildren().addAll(lbl, hint);
            cardsContainer.getChildren().add(emptyCard);
            return;
        }

        for (AgentProposal proposal : proposals) {
            cardsContainer.getChildren().add(buildProposalCard(proposal));
        }
    }

    private Node buildProposalCard(AgentProposal proposal) {
        HBox card = new HBox(16);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);

        StackPane botIcon = new StackPane(AppIcons.of(Feather.CPU, 24, "#4F46E5"));
        botIcon.setMinSize(52, 52);
        botIcon.setPrefSize(52, 52);
        botIcon.setStyle("-fx-background-color: #EEF2FF; -fx-background-radius: 999;");

        VBox content = new VBox(6);
        HBox.setHgrow(content, Priority.ALWAYS);

        HBox topLine = new HBox(10);
        topLine.setAlignment(Pos.CENTER_LEFT);
        Label agentName = new Label(proposal.getAgentName());
        agentName.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label statusPill = new Label(proposal.getStatus().getDisplayName());
        statusPill.getStyleClass().addAll("status-pill", switch (proposal.getStatus()) {
            case APPROVED -> "status-in-stock";
            case REJECTED -> "status-out-of-stock";
            case PENDING -> "status-low-stock";
        });
        Label impactLbl = new Label(String.format("Impact: %s",
                AppSession.getInstance().formatMoney(proposal.getFinancialImpact())));
        impactLbl.getStyleClass().add("muted-text");
        topLine.getChildren().addAll(agentName, statusPill, impactLbl);

        Label titleLbl = new Label(proposal.getTitle());
        titleLbl.setStyle("-fx-font-weight: bold;");
        Label reasonLbl = new Label(proposal.getReasoning());
        reasonLbl.setWrapText(true);
        reasonLbl.getStyleClass().add("muted-text");

        content.getChildren().addAll(topLine, titleLbl, reasonLbl);

        if (proposal.getStatus() == ProposalStatus.PENDING) {
            Button approveBtn = new Button("Approve");
            approveBtn.setGraphic(AppIcons.of(Feather.CHECK, 13));
            approveBtn.getStyleClass().add("btn-success");
            approveBtn.setOnAction(e -> {
                String actor = AppSession.getInstance().getActorName();
                boolean canApprove = AppSession.getInstance().canDeleteOrApprove();
                AppExecutor.runAsync(
                        () -> {
                            var res = orchestrator.approveProposal(proposal.getId(), actor, canApprove);
                            if (res.isSuccess()) {
                                services.getMultiAgentStoreBrainService()
                                        .recordDecisionFeedback(proposal.getAgentName(), true);
                            }
                            return res;
                        },
                        (InventoryOperationResult<AgentProposal> res) -> {
                            toastManager.showInfo(res.getMessage());
                            refreshAsync();
                        },
                        ex -> toastManager.showInfo("Approval error: " + ex.getMessage()));
            });

            Button rejectBtn = new Button("Reject");
            rejectBtn.setGraphic(AppIcons.of(Feather.X, 13));
            rejectBtn.getStyleClass().add("btn-danger-outline");
            rejectBtn.setOnAction(e -> {
                String actor = AppSession.getInstance().getActorName();
                AppExecutor.runAsync(
                        () -> {
                            var res = orchestrator.rejectProposal(proposal.getId(), actor);
                            if (res.isSuccess()) {
                                services.getMultiAgentStoreBrainService()
                                        .recordDecisionFeedback(proposal.getAgentName(), false);
                            }
                            return res;
                        },
                        res -> {
                            toastManager.showInfo(res.getMessage());
                            refreshAsync();
                        },
                        ex -> toastManager.showInfo("Rejection error: " + ex.getMessage()));
            });

            HBox buttons = new HBox(10, approveBtn, rejectBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            content.getChildren().add(buttons);
        }

        card.getChildren().addAll(botIcon, content);
        return card;
    }
}
