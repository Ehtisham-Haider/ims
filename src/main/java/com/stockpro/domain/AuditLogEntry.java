package com.stockpro.domain;

/**
 * Tamper-evident audit ledger entry linked by a SHA-256 hash chain.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AuditLogEntry {
    private long id;
    private String timestamp;
    private String actor;
    private String actionType;
    private String entityRef;
    private String details;
    private String prevHash;
    private String currHash;

    /**
     * Creates an empty audit entry.
     */
    public AuditLogEntry() {
    }

    /**
     * Returns the audit entry identifier.
     *
     * @return audit entry id
     */
    public long getId() { return id; }

    /**
     * Sets the audit entry identifier.
     *
     * @param id audit entry id
     */
    public void setId(long id) { this.id = id; }

    /**
     * Returns the ISO-8601 timestamp string.
     *
     * @return ISO-8601 timestamp string
     */
    public String getTimestamp() { return timestamp; }

    /**
     * Sets the ISO-8601 timestamp string.
     *
     * @param timestamp ISO-8601 timestamp string
     */
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    /**
     * Returns the username who performed the action.
     *
     * @return username who performed the action
     */
    public String getActor() { return actor; }

    /**
     * Sets the username who performed the action.
     *
     * @param actor username who performed the action
     */
    public void setActor(String actor) { this.actor = actor; }

    /**
     * Returns the action category code.
     *
     * @return action category code
     */
    public String getActionType() { return actionType; }

    /**
     * Sets the action category code.
     *
     * @param actionType action category code
     */
    public void setActionType(String actionType) { this.actionType = actionType; }

    /**
     * Returns the target entity reference such as SKU or PO number.
     *
     * @return target entity reference such as SKU or PO number
     */
    public String getEntityRef() { return entityRef; }

    /**
     * Sets the target entity reference such as SKU or PO number.
     *
     * @param entityRef target entity reference
     */
    public void setEntityRef(String entityRef) { this.entityRef = entityRef; }

    /**
     * Returns the human-readable description of the change.
     *
     * @return human-readable description of change
     */
    public String getDetails() { return details; }

    /**
     * Sets the human-readable description of the change.
     *
     * @param details human-readable description of change
     */
    public void setDetails(String details) { this.details = details; }

    /**
     * Returns the SHA-256 hash of the preceding audit row.
     *
     * @return SHA-256 hash of preceding audit row
     */
    public String getPrevHash() { return prevHash; }

    /**
     * Sets the SHA-256 hash of the preceding audit row.
     *
     * @param prevHash SHA-256 hash of preceding audit row
     */
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

    /**
     * Returns the SHA-256 hash sealing this audit row.
     *
     * @return SHA-256 hash sealing this audit row
     */
    public String getCurrHash() { return currHash; }

    /**
     * Sets the SHA-256 hash sealing this audit row.
     *
     * @param currHash SHA-256 hash sealing this audit row
     */
    public void setCurrHash(String currHash) { this.currHash = currHash; }
}
