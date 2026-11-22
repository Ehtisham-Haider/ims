package com.stockpro.repository;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.stockpro.domain.PurchaseOrder;
import com.stockpro.domain.PurchaseOrderItem;

import java.lang.reflect.Type;
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
 * JDBC repository storing and hydrating {@link PurchaseOrder} headers and JSON line items in SQLite.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class PurchaseOrderRepository {
    private static final Type ITEMS_LIST_TYPE = new TypeToken<List<PurchaseOrderItem>>() {}.getType();
    private final DatabaseManager dbManager;
    private final Gson gson = new Gson();

    /**
     * Creates a purchase order repository using the singleton database manager.
     */
    public PurchaseOrderRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a purchase order repository bound to a specific {@link DatabaseManager}.
     *
     * @param dbManager database manager instance
     */
    public PurchaseOrderRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Inserts a new purchase order with an auto-generated {@code PO-10XX} number when omitted.
     *
     * @param order purchase order to persist
     * @return saved order with assigned id and PO number
     */
    public PurchaseOrder insert(PurchaseOrder order) {
        LocalDateTime now = LocalDateTime.now();
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        if (order.getPoNumber() == null || order.getPoNumber().isBlank()) {
            order.setPoNumber(generateNextPoNumber());
        }
        order.recalculateTotal();

        String sql = """
                INSERT INTO purchase_orders (
                    po_number, supplier_id, supplier_name, items_json, total_cost, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, order.getPoNumber());
            ps.setLong(2, order.getSupplierId());
            ps.setString(3, order.getSupplierName());
            ps.setString(4, gson.toJson(order.getItems()));
            ps.setDouble(5, order.getTotalCost());
            ps.setString(6, order.getStatus());
            ps.setString(7, now.toString());
            ps.setString(8, now.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    order.setId(keys.getLong(1));
                }
            }
            return order;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert purchase order", ex);
        }
    }

    /**
     * Updates the lifecycle status ({@code Draft}, {@code Sent}, {@code Delivered}) of an existing order.
     *
     * @param orderId purchase order database id
     * @param newStatus updated status label
     * @return {@code true} if the order existed and was updated
     */
    public boolean updateStatus(long orderId, String newStatus) {
        String sql = "UPDATE purchase_orders SET status = ?, updated_at = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newStatus);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setLong(3, orderId);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update PO status", ex);
        }
    }

    /**
     * Retrieves all purchase orders newest first.
     *
     * @return list of purchase orders
     */
    public List<PurchaseOrder> findAll() {
        String sql = "SELECT * FROM purchase_orders ORDER BY id DESC";
        List<PurchaseOrder> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
            return list;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list purchase orders", ex);
        }
    }

    /**
     * Looks up a purchase order by id.
     *
     * @param id order id
     * @return {@link Optional} containing the order if present
     */
    public Optional<PurchaseOrder> findById(long id) {
        String sql = "SELECT * FROM purchase_orders WHERE id = ?";
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
            throw new IllegalStateException("Failed to find purchase order id " + id, ex);
        }
    }

    /**
     * Generates the next sequential PO identifier such as {@code PO-1001}.
     *
     * @return unique PO number string
     */
    private synchronized String generateNextPoNumber() {
        int maxNum = 1000;
        String sql = "SELECT po_number FROM purchase_orders WHERE po_number LIKE 'PO-%'";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String code = rs.getString(1);
                if (code != null && code.startsWith("PO-")) {
                    try {
                        int val = Integer.parseInt(code.substring(3));
                        if (val > maxNum) {
                            maxNum = val;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return "PO-" + (maxNum + 1);
    }

    /**
     * Maps a SQLite row into a {@link PurchaseOrder}.
     *
     * @param rs active JDBC result set
     * @return hydrated purchase order
     * @throws SQLException if reading columns fails
     */
    private PurchaseOrder mapRow(ResultSet rs) throws SQLException {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(rs.getLong("id"));
        po.setPoNumber(rs.getString("po_number"));
        po.setSupplierId(rs.getLong("supplier_id"));
        po.setSupplierName(rs.getString("supplier_name"));
        List<PurchaseOrderItem> items = gson.fromJson(rs.getString("items_json"), ITEMS_LIST_TYPE);
        po.setItems(items);
        po.setTotalCost(rs.getDouble("total_cost"));
        po.setStatus(rs.getString("status"));
        String created = rs.getString("created_at");
        if (created != null && !created.isBlank()) {
            po.setCreatedAt(LocalDateTime.parse(created));
        }
        String updated = rs.getString("updated_at");
        if (updated != null && !updated.isBlank()) {
            po.setUpdatedAt(LocalDateTime.parse(updated));
        }
        return po;
    }
}
