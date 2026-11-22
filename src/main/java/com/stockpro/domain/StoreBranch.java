package com.stockpro.domain;

import java.time.LocalDateTime;

/**
 * Represents a physical store or regional warehouse location participating in multi-branch inventory tracking.
 *
 * @param id database row identifier
 * @param branchCode short uppercase branch identifier (e.g., {@code MAIN}, {@code NORTH})
 * @param name human-readable branch name
 * @param address street address or depot location
 * @param managerName branch manager display name
 * @param active {@code true} if the branch is currently operational
 * @param createdAt creation timestamp
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public record StoreBranch(
        long id,
        String branchCode,
        String name,
        String address,
        String managerName,
        boolean active,
        LocalDateTime createdAt) {

    @Override
    public String toString() {
        return name + " (" + branchCode + ")";
    }
}
