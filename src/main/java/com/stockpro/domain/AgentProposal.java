package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Automated recommendation queued for explicit human approval before modifying store records.
 *
 * @author Ehtisham Haider, Syed Ahmed
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
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public AgentProposal() {
        this.createdAt = LocalDateTime.now();
    }

    /** @return proposal database id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id proposal database id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return worker name that generated this proposal @author Ehtisham Haider, Syed Ahmed */
    public String getAgentName() { return agentName; }

    /** @param agentName worker name that generated this proposal @author Ehtisham Haider, Syed Ahmed */
    public void setAgentName(String agentName) { this.agentName = agentName; }

    /** @return action type identifier (CREATE_PO, PRICE_DISCOUNT, AUDIT_FIX) @author Ehtisham Haider, Syed Ahmed */
    public String getProposalType() { return proposalType; }

    /** @param proposalType action type identifier @author Ehtisham Haider, Syed Ahmed */
    public void setProposalType(String proposalType) { this.proposalType = proposalType; }

    /** @return concise headline shown on the approval card @author Ehtisham Haider, Syed Ahmed */
    public String getTitle() { return title; }

    /** @param title concise headline @author Ehtisham Haider, Syed Ahmed */
    public void setTitle(String title) { this.title = title; }

    /** @return plain-English rationale explaining why the change is suggested @author Ehtisham Haider, Syed Ahmed */
    public String getReasoning() { return reasoning; }

    /** @param reasoning plain-English rationale @author Ehtisham Haider, Syed Ahmed */
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    /** @return estimated cost or revenue recovery impact @author Ehtisham Haider, Syed Ahmed */
    public double getFinancialImpact() { return financialImpact; }

    /** @param financialImpact estimated financial impact @author Ehtisham Haider, Syed Ahmed */
    public void setFinancialImpact(double financialImpact) { this.financialImpact = financialImpact; }

    /** @return serialized JSON payload executed upon approval @author Ehtisham Haider, Syed Ahmed */
    public String getPayloadJson() { return payloadJson; }

    /** @param payloadJson serialized JSON payload @author Ehtisham Haider, Syed Ahmed */
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    /** @return current review state @author Ehtisham Haider, Syed Ahmed */
    public ProposalStatus getStatus() { return status; }

    /** @param status current review state @author Ehtisham Haider, Syed Ahmed */
    public void setStatus(ProposalStatus status) { this.status = status; }

    /** @return creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** @param createdAt creation timestamp @author Ehtisham Haider, Syed Ahmed */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
