package com.stockpro.repository;

import com.stockpro.domain.Supplier;

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
 * JDBC repository managing CRUD persistence for {@link Supplier} records in SQLite.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class SupplierRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a supplier repository using the singleton database manager.
     */
    public SupplierRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a supplier repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public SupplierRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Inserts a new supplier record into SQLite.
     *
     * @param supplier supplier entity to persist
     * @return saved supplier with generated database id
     */
    public Supplier insert(Supplier supplier) {
        LocalDateTime now = supplier.getCreatedAt() != null ? supplier.getCreatedAt() : LocalDateTime.now();
        supplier.setCreatedAt(now);
        String sql = """
                INSERT INTO suppliers (name, phone, email, delivery_days, reliability_score, linked_categories, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, supplier.getName().trim());
            ps.setString(2, supplier.getPhone());
            ps.setString(3, supplier.getEmail());
            ps.setInt(4, supplier.getDeliveryDays());
            ps.setDouble(5, supplier.getReliabilityScore());
            ps.setString(6, supplier.getLinkedCategories());
            ps.setString(7, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    supplier.setId(keys.getLong(1));
                }
            }
            return supplier;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert supplier: " + supplier.getName(), ex);
        }
    }

    /**
     * Updates an existing supplier record.
     *
     * @param supplier supplier entity containing a valid id
     * @return {@code true} if the record existed and was updated
     */
    public boolean update(Supplier supplier) {
        String sql = """
                UPDATE suppliers SET name = ?, phone = ?, email = ?, delivery_days = ?,
                    reliability_score = ?, linked_categories = ?
                WHERE id = ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, supplier.getName().trim());
            ps.setString(2, supplier.getPhone());
            ps.setString(3, supplier.getEmail());
            ps.setInt(4, supplier.getDeliveryDays());
            ps.setDouble(5, supplier.getReliabilityScore());
            ps.setString(6, supplier.getLinkedCategories());
            ps.setLong(7, supplier.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update supplier id " + supplier.getId(), ex);
        }
    }

    /**
     * Deletes a supplier by id if no restrictive purchase orders block removal.
     *
     * @param id supplier database id
     * @return {@code true} if deleted
     */
    public boolean deleteById(long id) {
        String sql = "DELETE FROM suppliers WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            return false;
        }
    }

    /**
     * Lists all suppliers sorted alphabetically by name.
     *
     * @return list of suppliers
     */
    public List<Supplier> findAll() {
        String sql = "SELECT * FROM suppliers ORDER BY name ASC";
        List<Supplier> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load suppliers", ex);
        }
    }

    /**
     * Finds a supplier by database id.
     *
     * @param id supplier id
     * @return {@link Optional} containing the supplier if present
     */
    public Optional<Supplier> findById(long id) {
        String sql = "SELECT * FROM suppliers WHERE id = ?";
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
            throw new IllegalStateException("Failed to find supplier id " + id, ex);
        }
    }

    /**
     * Maps a SQLite supplier row into a {@link Supplier} domain object.
     *
     * @param rs active JDBC result set
     * @return hydrated supplier
     * @throws SQLException if reading columns fails
     */
    private Supplier mapRow(ResultSet rs) throws SQLException {
        Supplier s = new Supplier();
        s.setId(rs.getLong("id"));
        s.setName(rs.getString("name"));
        s.setPhone(rs.getString("phone"));
        s.setEmail(rs.getString("email"));
        s.setDeliveryDays(rs.getInt("delivery_days"));
        s.setReliabilityScore(rs.getDouble("reliability_score"));
        s.setLinkedCategories(rs.getString("linked_categories"));
        String created = rs.getString("created_at");
        if (created != null && !created.isBlank()) {
            s.setCreatedAt(LocalDateTime.parse(created));
        }
        return s;
    }
}
