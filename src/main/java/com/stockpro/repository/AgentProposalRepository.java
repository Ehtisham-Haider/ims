package com.stockpro.repository;

import com.stockpro.domain.AgentProposal;
import com.stockpro.domain.ProposalStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC repository persisting and retrieving autonomous worker recommendations in {@code agent_proposals}.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class AgentProposalRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a proposal repository backed by the singleton database manager.
     */
    public AgentProposalRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a proposal repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public AgentProposalRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Inserts a newly drafted automated proposal.
     *
     * @param proposal proposal record to store
     * @return saved proposal with generated database id
     */
    public AgentProposal insert(AgentProposal proposal) {
        LocalDateTime ts = proposal.getCreatedAt() != null ? proposal.getCreatedAt() : LocalDateTime.now();
        proposal.setCreatedAt(ts);
        String sql = """
                INSERT INTO agent_proposals (
                    agent_name, proposal_type, title, reasoning, financial_impact, payload_json, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, proposal.getAgentName());
            ps.setString(2, proposal.getProposalType());
            ps.setString(3, proposal.getTitle());
            ps.setString(4, proposal.getReasoning());
            ps.setDouble(5, proposal.getFinancialImpact());
            ps.setString(6, proposal.getPayloadJson());
            ps.setString(7, proposal.getStatus().name());
            ps.setString(8, ts.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    proposal.setId(keys.getLong(1));
                }
            }
            return proposal;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert agent proposal", ex);
        }
    }

    /**
     * Retrieves all proposals matching the requested status, newest first.
     *
     * @param status target {@link ProposalStatus}
     * @return list of matching proposals
     */
    public List<AgentProposal> findByStatus(ProposalStatus status) {
        String sql = "SELECT * FROM agent_proposals WHERE status = ? ORDER BY id DESC";
        List<AgentProposal> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list proposals by status", ex);
        }
    }

    /**
     * Retrieves all proposals newest first.
     *
     * @return list of all proposals
     */
    public List<AgentProposal> findAll() {
        String sql = "SELECT * FROM agent_proposals ORDER BY id DESC";
        List<AgentProposal> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load agent proposals", ex);
        }
    }

    /**
     * Looks up a proposal by database id.
     *
     * @param id proposal row id
     * @return {@link Optional} containing proposal if found
     */
    public Optional<AgentProposal> findById(long id) {
        String sql = "SELECT * FROM agent_proposals WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to look up proposal id " + id, ex);
        }
    }

    /**
     * Checks whether an identical pending proposal already exists to avoid inbox duplicates.
     *
     * @param title proposal title
     * @return {@code true} if a pending proposal with the same title already exists
     */
    public boolean existsPendingByTitle(String title) {
        String sql = "SELECT COUNT(*) FROM agent_proposals WHERE status = 'PENDING' AND title = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * Updates the review status of a proposal after human Approve or Reject action.
     *
     * @param id proposal row id
     * @param status new {@link ProposalStatus}
     * @return {@code true} if updated
     */
    public boolean updateStatus(long id, ProposalStatus status) {
        String sql = "UPDATE agent_proposals SET status = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setLong(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update proposal status", ex);
        }
    }

    /**
     * Maps a SQLite proposal row into an {@link AgentProposal}.
     *
     * @param rs active result set
     * @return hydrated proposal
     * @throws SQLException if reading columns fails
     */
    private AgentProposal mapRow(ResultSet rs) throws SQLException {
        AgentProposal p = new AgentProposal();
        p.setId(rs.getLong("id"));
        p.setAgentName(rs.getString("agent_name"));
        p.setProposalType(rs.getString("proposal_type"));
        p.setTitle(rs.getString("title"));
        p.setReasoning(rs.getString("reasoning"));
        p.setFinancialImpact(rs.getDouble("financial_impact"));
        p.setPayloadJson(rs.getString("payload_json"));
        p.setStatus(ProposalStatus.fromString(rs.getString("status")));
        String ts = rs.getString("created_at");
        if (ts != null && !ts.isBlank()) {
            p.setCreatedAt(LocalDateTime.parse(ts));
        }
        return p;
    }
}
