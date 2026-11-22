package com.stockpro.repository;

import com.stockpro.domain.AuditLogEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository storing and querying tamper-evident SHA-256 {@link AuditLogEntry} rows.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class AuditLogRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates an audit log repository using the singleton database manager.
     */
    public AuditLogRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates an audit log repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public AuditLogRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Appends a hash-sealed audit entry to SQLite.
     *
     * @param entry sealed audit entry
     * @return persisted entry with generated row id
     */
    public AuditLogEntry insert(AuditLogEntry entry) {
        String sql = """
                INSERT INTO audit_logs (timestamp, actor, action_type, entity_ref, details, prev_hash, curr_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.getTimestamp());
            ps.setString(2, entry.getActor());
            ps.setString(3, entry.getActionType());
            ps.setString(4, entry.getEntityRef());
            ps.setString(5, entry.getDetails());
            ps.setString(6, entry.getPrevHash());
            ps.setString(7, entry.getCurrHash());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    entry.setId(keys.getLong(1));
                }
            }
            return entry;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to append audit log entry", ex);
        }
    }

    /**
     * Returns the SHA-256 hash of the most recent audit record, or {@code "GENESIS"} when empty.
     *
     * @return latest hash link
     */
    public String findLatestHash() {
        String sql = "SELECT curr_hash FROM audit_logs ORDER BY id DESC LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return "GENESIS";
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read latest audit hash", ex);
        }
    }

    /**
     * Loads all audit entries in strict chronological insertion order for full chain verification.
     *
     * @return chronological list of audit entries
     */
    public List<AuditLogEntry> findAllChronological() {
        return queryList("SELECT * FROM audit_logs ORDER BY id ASC");
    }

    /**
     * Loads the newest audit entries up to the specified limit.
     *
     * @param limit maximum entries
     * @return newest audit entries
     */
    public List<AuditLogEntry> findRecent(int limit) {
        String sql = "SELECT * FROM audit_logs ORDER BY id DESC LIMIT ?";
        List<AuditLogEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read recent audit entries", ex);
        }
    }

    /**
     * Executes a parameterless audit select query.
     *
     * @param sql select SQL statement
     * @return list of mapped {@link AuditLogEntry} records
     */
    private List<AuditLogEntry> queryList(String sql) {
        List<AuditLogEntry> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query audit log entries", ex);
        }
    }

    /**
     * Maps a SQLite audit row into an {@link AuditLogEntry}.
     *
     * @param rs active result set
     * @return mapped entry
     * @throws SQLException if reading columns fails
     */
    private AuditLogEntry mapRow(ResultSet rs) throws SQLException {
        AuditLogEntry e = new AuditLogEntry();
        e.setId(rs.getLong("id"));
        e.setTimestamp(rs.getString("timestamp"));
        e.setActor(rs.getString("actor"));
        e.setActionType(rs.getString("action_type"));
        e.setEntityRef(rs.getString("entity_ref"));
        e.setDetails(rs.getString("details"));
        e.setPrevHash(rs.getString("prev_hash"));
        e.setCurrHash(rs.getString("curr_hash"));
        return e;
    }
}
