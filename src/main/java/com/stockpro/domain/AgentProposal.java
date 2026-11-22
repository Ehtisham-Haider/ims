package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Automated recommendation queued for explicit human approval before modifying store records.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AgentProposal {
    private long id;
    private String agentName;
    private String proposalType;
    private String title;
    private String reasoning;
    private double financialImpact;
    private String payloadJson;
    private ProposalStatus status = ProposalStatus.PENDING;
    private LocalDateTime createdAt;

    /**
     * Creates a new pending proposal stamped with the current timestamp.
     */
    public AgentProposal() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Returns the proposal database identifier.
     *
     * @return proposal database id
     */
    public long getId() { return id; }

    /**
     * Sets the proposal database identifier.
     *
     * @param id proposal database id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the worker name that generated this proposal.
     *
     * @return worker name that generated this proposal
     */
    public String getAgentName() { return agentName; }

    /**
     * Sets the worker name that generated this proposal.
     *
     * @param agentName worker name that generated this proposal
     */
    public void setAgentName(String agentName) { this.agentName = agentName; }

    /**
     * Returns the action type identifier.
     *
     * @return action type identifier (CREATE_PO, PRICE_DISCOUNT, AUDIT_FIX)
     */
    public String getProposalType() { return proposalType; }

    /**
     * Sets the action type identifier.
     *
     * @param proposalType action type identifier
     */
    public void setProposalType(String proposalType) { this.proposalType = proposalType; }

    /**
     * Returns the concise headline shown on the approval card.
     *
     * @return concise headline shown on the approval card
     */
    public String getTitle() { return title; }

    /**
     * Sets the concise headline shown on the approval card.
     *
     * @param title concise headline
     */
    public void setTitle(String title) { this.title = title; }

    /**
     * Returns the rationale explaining why the change is suggested.
     *
     * @return plain-English rationale explaining why the change is suggested
     */
    public String getReasoning() { return reasoning; }

    /**
     * Sets the rationale explaining why the change is suggested.
     *
     * @param reasoning plain-English rationale
     */
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    /**
     * Returns the estimated financial impact.
     *
     * @return estimated cost or revenue recovery impact
     */
    public double getFinancialImpact() { return financialImpact; }

    /**
     * Sets the estimated financial impact.
     *
     * @param financialImpact estimated financial impact
     */
    public void setFinancialImpact(double financialImpact) { this.financialImpact = financialImpact; }

    /**
     * Returns the serialized JSON payload executed upon approval.
     *
     * @return serialized JSON payload executed upon approval
     */
    public String getPayloadJson() { return payloadJson; }

    /**
     * Sets the serialized JSON payload executed upon approval.
     *
     * @param payloadJson serialized JSON payload
     */
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    /**
     * Returns the current review state.
     *
     * @return current review state
     */
    public ProposalStatus getStatus() { return status; }

    /**
     * Sets the current review state.
     *
     * @param status current review state
     */
    public void setStatus(ProposalStatus status) { this.status = status; }

    /**
     * Returns the creation timestamp.
     *
     * @return creation timestamp
     */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt creation timestamp
     */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
