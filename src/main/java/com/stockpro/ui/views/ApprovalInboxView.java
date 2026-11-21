package com.stockpro.ui.views;

import com.stockpro.ai.AgenticOrchestratorService;
import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.ProposalStatus;
import com.stockpro.service.AppSession;
import com.stockpro.service.InventoryEventBus;
import com.stockpro.service.InventoryOperationResult;
import com.stockpro.service.ServiceContext;
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
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Human Approval Safety Inbox matching {@code Screens and popups/Approvals.png}, displaying every
 * autonomous Auto-Buyer, Nightly Auditor, and Expiry Rescue recommendation with {@code Approve / Reject} buttons.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ApprovalInboxView {
    private final AgenticOrchestratorService orchestrator;
    private final ToastNotificationManager toastManager;

    private VBox cardsContainer;
    private CheckBox pendingOnlyCheck;

    /**
     * Constructs the Human Approval Safety Inbox panel.
     *
     * @param services shared service registry
     * @param toastManager toast notification helper
     */
    public ApprovalInboxView(ServiceContext services, ToastNotificationManager toastManager) {
        this.orchestrator = services.getAgenticOrchestratorService();
        this.toastManager = toastManager;
        InventoryEventBus.getInstance().subscribe(t -> refreshAsync());
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
        Label sub = new Label("Zero database writes occur silently — every AI proposal awaits explicit human confirmation.");
        sub.getStyleClass().add("muted-text");
        titleCol.getChildren().addAll(heading, sub);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        pendingOnlyCheck = new CheckBox("Pending Only");
        pendingOnlyCheck.setSelected(true);
        pendingOnlyCheck.setOnAction(e -> refreshAsync());

        Button runAgentsBtn = new Button("🤖 Run All 3 AI Agents");
        runAgentsBtn.getStyleClass().add("btn-primary");
        runAgentsBtn.setOnAction(e -> AppExecutor.runAsync(
                orchestrator::runAllAgents,
                created -> {
                    toastManager.showInfo("Agents finished scan: " + created.size() + " new proposal(s) queued.");
                    refreshAsync();
                },
                ex -> toastManager.showInfo("Agent scan error: " + ex.getMessage())
        ));

        header.getChildren().addAll(titleCol, sp, pendingOnlyCheck, runAgentsBtn);

        cardsContainer = new VBox(12);
        refreshAsync();

        ScrollPane scroll = new ScrollPane(cardsContainer);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        page.getChildren().addAll(header, scroll);
        return page;
    }

    /**
     * Reloads proposal cards from SQLite off the UI thread.
     */
    public void refreshAsync() {
        boolean pendingOnly = pendingOnlyCheck == null || pendingOnlyCheck.isSelected();
        AppExecutor.runAsync(
                () -> orchestrator.listProposals(pendingOnly),
                this::renderCards,
                ex -> {}
        );
    }

    /**
     * Renders individual proposal cards matching {@code Approvals.png}.
     *
     * @param proposals list of proposals to display
     */
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
            Label hint = new Label("Click 'Run All 3 AI Agents' above to scan for restock needs, audit anomalies, and expiring items.");
            hint.getStyleClass().add("muted-text");
            emptyCard.getChildren().addAll(lbl, hint);
            cardsContainer.getChildren().add(emptyCard);
            return;
        }

        for (AgentProposal proposal : proposals) {
            cardsContainer.getChildren().add(buildProposalCard(proposal));
        }
    }

    /**
     * Creates a styled approval card matching {@code Screens and popups/Approvals.png}.
     *
     * @param proposal proposal record
     * @return styled JavaFX card
     */
    private Node buildProposalCard(AgentProposal proposal) {
        HBox card = new HBox(16);
        card.getStyleClass().add("card");
        card.setAlignment(Pos.CENTER_LEFT);

        Label botIcon = new Label("🤖");
        botIcon.setMinSize(54, 54);
        botIcon.setAlignment(Pos.CENTER);
        botIcon.setStyle("-fx-background-color: #EEF2FF; -fx-background-radius: 999; -fx-font-size: 26px;");

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
            approveBtn.getStyleClass().add("btn-success");
            approveBtn.setOnAction(e -> {
                String actor = AppSession.getInstance().getActorName();
                boolean canApprove = AppSession.getInstance().canDeleteOrApprove();
                AppExecutor.runAsync(
                        () -> orchestrator.approveProposal(proposal.getId(), actor, canApprove),
                        (InventoryOperationResult<AgentProposal> res) -> {
                            toastManager.showInfo(res.getMessage());
                            refreshAsync();
                        },
                        ex -> toastManager.showInfo("Approval error: " + ex.getMessage())
                );
            });

            Button rejectBtn = new Button("Reject");
            rejectBtn.getStyleClass().add("btn-danger-outline");
            rejectBtn.setOnAction(e -> {
                String actor = AppSession.getInstance().getActorName();
                AppExecutor.runAsync(
                        () -> orchestrator.rejectProposal(proposal.getId(), actor),
                        res -> {
                            toastManager.showInfo(res.getMessage());
                            refreshAsync();
                        },
                        ex -> toastManager.showInfo("Rejection error: " + ex.getMessage())
                );
            });

            HBox buttons = new HBox(10, approveBtn, rejectBtn);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            content.getChildren().add(buttons);
        }

        card.getChildren().addAll(botIcon, content);
        return card;
    }
}
