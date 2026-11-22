package com.stockpro.service;

import com.stockpro.domain.AuditLogEntry;
import com.stockpro.repository.AuditLogRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Maintains a tamper-evident SHA-256 hash chain over every recorded inventory and security event.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AuditService {
    private final AuditLogRepository repository;

    /**
     * Result returned by {@link #verifyIntegrity()}.
     *
     * @param intact {@code true} if every entry hash and back-link verified cleanly
     * @param totalEntries number of audit entries checked
     * @param tamperedEntryId ID of the first corrupted row, or {@code -1} when intact
     * @param message summary message suitable for UI badges and audit reports
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record SealVerificationResult(
            boolean intact,
            int totalEntries,
            long tamperedEntryId,
            String message
    ) {
    }

    /**
     * Creates an audit service using the default {@link AuditLogRepository}.
     */
    public AuditService() {
        this(new AuditLogRepository());
    }

    /**
     * Creates an audit service with an explicit repository.
     *
     * @param repository audit log repository
     */
    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a new audit event sealed with the SHA-256 hash of its content chained from the prior entry.
     *
     * @param actor username or display name of operator
     * @param actionType action code (e.g., {@code STOCK_RESTOCK}, {@code PRODUCT_DELETE})
     * @param entityRef SKU, PO number, or target entity identifier
     * @param details human-readable description
     * @return sealed and persisted {@link AuditLogEntry}
     */
    public synchronized AuditLogEntry record(String actor, String actionType, String entityRef, String details) {
        String prevHash = repository.findLatestHash();
        String timestamp = LocalDateTime.now().toString();
        String safeActor = actor != null && !actor.isBlank() ? actor : "System";
        String safeAction = actionType != null ? actionType : "EVENT";
        String safeRef = entityRef != null ? entityRef : "-";
        String safeDetails = details != null ? details : "";

        String currHash = computeEntryHash(prevHash, timestamp, safeActor, safeAction, safeRef, safeDetails);

        AuditLogEntry entry = new AuditLogEntry();
        entry.setTimestamp(timestamp);
        entry.setActor(safeActor);
        entry.setActionType(safeAction);
        entry.setEntityRef(safeRef);
        entry.setDetails(safeDetails);
        entry.setPrevHash(prevHash);
        entry.setCurrHash(currHash);
        return repository.insert(entry);
    }

    /**
     * Recomputes the SHA-256 hash chain from {@code GENESIS} across every audit entry to detect tampering.
     *
     * @return {@link SealVerificationResult} indicating whether the ledger is intact
     */
    public synchronized SealVerificationResult verifyIntegrity() {
        List<AuditLogEntry> entries = repository.findAllChronological();
        if (entries.isEmpty()) {
            return new SealVerificationResult(true, 0, -1, "Audit ledger is empty (GENESIS ready).");
        }
        String expectedPrev = "GENESIS";
        for (AuditLogEntry entry : entries) {
            if (!expectedPrev.equals(entry.getPrevHash())) {
                return new SealVerificationResult(
                        false,
                        entries.size(),
                        entry.getId(),
                        "Chain break detected at audit entry #" + entry.getId()
                );
            }
            String recalculated = computeEntryHash(
                    entry.getPrevHash(),
                    entry.getTimestamp(),
                    entry.getActor(),
                    entry.getActionType(),
                    entry.getEntityRef(),
                    entry.getDetails()
            );
            if (!recalculated.equals(entry.getCurrHash())) {
                return new SealVerificationResult(
                        false,
                        entries.size(),
                        entry.getId(),
                        "Tampering detected! Hash mismatch at audit entry #" + entry.getId()
                );
            }
            expectedPrev = entry.getCurrHash();
        }
        return new SealVerificationResult(
                true,
                entries.size(),
                -1,
                "SHA-256 Security Seal Verified (" + entries.size() + " immutable entries intact)."
        );
    }

    /**
     * Retrieves recent audit entries ordered newest first.
     *
     * @param limit maximum number of entries
     * @return recent audit entries
     */
    public List<AuditLogEntry> getRecentEntries(int limit) {
        return repository.findRecent(limit);
    }

    /**
     * Computes deterministic SHA-256 hex digest over normalized audit entry fields.
     *
     * @param prevHash hash of previous row
     * @param timestamp ISO timestamp
     * @param actor operator name
     * @param actionType action code
     * @param entityRef target entity reference
     * @param details event summary
     * @return lowercase 64-character SHA-256 hex digest
     */
    static String computeEntryHash(
            String prevHash,
            String timestamp,
            String actor,
            String actionType,
            String entityRef,
            String details) {
        String payload = String.join("|",
                prevHash != null ? prevHash : "",
                timestamp != null ? timestamp : "",
                actor != null ? actor : "",
                actionType != null ? actionType : "",
                entityRef != null ? entityRef : "",
                details != null ? details : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", ex);
        }
    }
}
