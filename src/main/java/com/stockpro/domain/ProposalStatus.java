package com.stockpro.domain;

/**
 * Represents the review status of an automated proposal in the safety approval inbox.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public enum ProposalStatus {
    /** Awaiting explicit staff review. */
    PENDING("Pending"),
    /** Accepted by a human reviewer and executed. */
    APPROVED("Approved"),
    /** Declined by a human reviewer with no database mutation. */
    REJECTED("Rejected");

    private final String displayName;

    /**
     * Creates a proposal status constant.
     *
     * @param displayName user-facing status label
     */
    ProposalStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the friendly status label.
     *
     * @return display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Parses a proposal status string safely.
     *
     * @param value stored status text
     * @return matching {@link ProposalStatus}, defaulting to {@link #PENDING}
     */
    public static ProposalStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        for (ProposalStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())
                    || status.displayName.equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return PENDING;
    }
}
