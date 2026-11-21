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
     *
     * @author Ehtisham Haider, Syed Ahmed
     */
    public AuditLogEntry() {
    }

    /** @return audit entry id @author Ehtisham Haider, Syed Ahmed */
    public long getId() { return id; }

    /** @param id audit entry id @author Ehtisham Haider, Syed Ahmed */
    public void setId(long id) { this.id = id; }

    /** @return ISO-8601 timestamp string @author Ehtisham Haider, Syed Ahmed */
    public String getTimestamp() { return timestamp; }

    /** @param timestamp ISO-8601 timestamp string @author Ehtisham Haider, Syed Ahmed */
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    /** @return username who performed the action @author Ehtisham Haider, Syed Ahmed */
    public String getActor() { return actor; }

    /** @param actor username who performed the action @author Ehtisham Haider, Syed Ahmed */
    public void setActor(String actor) { this.actor = actor; }

    /** @return action category code @author Ehtisham Haider, Syed Ahmed */
    public String getActionType() { return actionType; }

    /** @param actionType action category code @author Ehtisham Haider, Syed Ahmed */
    public void setActionType(String actionType) { this.actionType = actionType; }

    /** @return target entity reference such as SKU or PO number @author Ehtisham Haider, Syed Ahmed */
    public String getEntityRef() { return entityRef; }

    /** @param entityRef target entity reference @author Ehtisham Haider, Syed Ahmed */
    public void setEntityRef(String entityRef) { this.entityRef = entityRef; }

    /** @return human-readable description of change @author Ehtisham Haider, Syed Ahmed */
    public String getDetails() { return details; }

    /** @param details human-readable description of change @author Ehtisham Haider, Syed Ahmed */
    public void setDetails(String details) { this.details = details; }

    /** @return SHA-256 hash of preceding audit row @author Ehtisham Haider, Syed Ahmed */
    public String getPrevHash() { return prevHash; }

    /** @param prevHash SHA-256 hash of preceding audit row @author Ehtisham Haider, Syed Ahmed */
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }

    /** @return SHA-256 hash sealing this audit row @author Ehtisham Haider, Syed Ahmed */
    public String getCurrHash() { return currHash; }

    /** @param currHash SHA-256 hash sealing this audit row @author Ehtisham Haider, Syed Ahmed */
    public void setCurrHash(String currHash) { this.currHash = currHash; }
}
