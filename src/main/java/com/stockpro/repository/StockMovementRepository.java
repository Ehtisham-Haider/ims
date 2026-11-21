package com.stockpro.repository;

import com.stockpro.domain.MovementType;
import com.stockpro.domain.StockMovement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC repository persisting and querying immutable {@link StockMovement} ledger rows in SQLite.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class StockMovementRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a stock movement repository using the singleton database manager.
     */
    public StockMovementRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a stock movement repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public StockMovementRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Persists a new stock movement entry.
     *
     * @param movement movement record to insert
     * @return saved movement with assigned id
     */
    public StockMovement insert(StockMovement movement) {
        LocalDateTime ts = movement.getCreatedAt() != null ? movement.getCreatedAt() : LocalDateTime.now();
        movement.setCreatedAt(ts);
        String sql = """
                INSERT INTO stock_movements (
                    product_id, product_sku, product_name, movement_type,
                    quantity_delta, previous_qty, new_qty, reason, notes, performed_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, movement.getProductId());
            ps.setString(2, movement.getProductSku());
            ps.setString(3, movement.getProductName());
            ps.setString(4, movement.getMovementType().name());
            ps.setInt(5, movement.getQuantityDelta());
            ps.setInt(6, movement.getPreviousQty());
            ps.setInt(7, movement.getNewQty());
            ps.setString(8, movement.getReason());
            ps.setString(9, movement.getNotes());
            ps.setString(10, movement.getPerformedBy());
            ps.setString(11, ts.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    movement.setId(keys.getLong(1));
                }
            }
            return movement;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert stock movement", ex);
        }
    }

    /**
     * Retrieves the newest stock movements up to the supplied limit.
     *
     * @param limit maximum number of rows
     * @return list of movements ordered newest first
     */
    public List<StockMovement> findRecent(int limit) {
        String sql = "SELECT * FROM stock_movements ORDER BY id DESC LIMIT ?";
        List<StockMovement> list = new ArrayList<>();
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
            throw new IllegalStateException("Failed to load recent stock movements", ex);
        }
    }

    /**
     * Retrieves movements for a single product ordered newest first.
     *
     * @param productId target product id
     * @param limit maximum rows to return
     * @return list of product movements
     */
    public List<StockMovement> findByProductId(long productId, int limit) {
        String sql = "SELECT * FROM stock_movements WHERE product_id = ? ORDER BY id DESC LIMIT ?";
        List<StockMovement> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load product movements", ex);
        }
    }

    /**
     * Maps a SQLite movement row to a {@link StockMovement} instance.
     *
     * @param rs active JDBC result set
     * @return hydrated movement object
     * @throws SQLException if reading any column fails
     */
    private StockMovement mapRow(ResultSet rs) throws SQLException {
        StockMovement m = new StockMovement();
        m.setId(rs.getLong("id"));
        m.setProductId(rs.getLong("product_id"));
        m.setProductSku(rs.getString("product_sku"));
        m.setProductName(rs.getString("product_name"));
        m.setMovementType(MovementType.fromString(rs.getString("movement_type")));
        m.setQuantityDelta(rs.getInt("quantity_delta"));
        m.setPreviousQty(rs.getInt("previous_qty"));
        m.setNewQty(rs.getInt("new_qty"));
        m.setReason(rs.getString("reason"));
        m.setNotes(rs.getString("notes"));
        m.setPerformedBy(rs.getString("performed_by"));
        String ts = rs.getString("created_at");
        if (ts != null && !ts.isBlank()) {
            m.setCreatedAt(LocalDateTime.parse(ts));
        }
        return m;
    }
}
