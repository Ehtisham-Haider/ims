package com.stockpro.repository;

import com.stockpro.domain.Product;
import com.stockpro.domain.StockStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parameterized JDBC repository managing CRUD operations and SKU generation for {@link Product} records in SQLite.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ProductRepository {
    private final DatabaseManager dbManager;

    /**
     * Creates a repository using the singleton application database manager.
     */
    public ProductRepository() {
        this(DatabaseManager.getInstance());
    }

    /**
     * Creates a repository bound to a specific {@link DatabaseManager} instance.
     *
     * @param dbManager database manager providing pooled SQLite connections
     */
    public ProductRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * Inserts a new product into SQLite, assigning a unique auto-generated SKU code if omitted.
     *
     * @param product product entity to persist
     * @return persisted product with generated database id and SKU
     */
    public Product insert(Product product) {
        LocalDateTime now = LocalDateTime.now();
        if (product.getCreatedAt() == null) {
            product.setCreatedAt(now);
        }
        product.setUpdatedAt(now);
        if (product.getSku() == null || product.getSku().isBlank()) {
            product.setSku(generateNextSku());
        }
        if (product.getBarcode() == null || product.getBarcode().isBlank()) {
            product.setBarcode(product.getSku());
        }
        if (product.getCategory() == null || product.getCategory().isBlank()) {
            product.setCategory("General");
        }
        product.refreshStatusIfAutomatic();

        String sql = """
                INSERT INTO products (
                    sku, barcode, name, category, tags, quantity, damaged_qty,
                    low_stock_threshold, buy_price, sell_price, shelf_location,
                    expiry_date, status, manual_status_override, photo_path,
                    substitute_sku, archived, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindStatement(ps, product);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    product.setId(keys.getLong(1));
                }
            }
            return product;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to insert product: " + product.getName(), ex);
        }
    }

    /**
     * Re-inserts a deleted product preserving its original SKU and ID when possible (used for Undo).
     *
     * @param product snapshot of previously deleted product
     * @return restored product instance
     */
    public Product restore(Product product) {
        product.setUpdatedAt(LocalDateTime.now());
        String sql = """
                INSERT INTO products (
                    id, sku, barcode, name, category, tags, quantity, damaged_qty,
                    low_stock_threshold, buy_price, sell_price, shelf_location,
                    expiry_date, status, manual_status_override, photo_path,
                    substitute_sku, archived, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, product.getId());
            ps.setString(2, product.getSku());
            ps.setString(3, product.getBarcode());
            ps.setString(4, product.getName());
            ps.setString(5, product.getCategory() != null ? product.getCategory() : "General");
            ps.setString(6, product.getTags());
            ps.setInt(7, product.getQuantity());
            ps.setInt(8, product.getDamagedQty());
            ps.setInt(9, product.getLowStockThreshold());
            ps.setDouble(10, product.getBuyPrice());
            ps.setDouble(11, product.getSellPrice());
            ps.setString(12, product.getShelfLocation());
            ps.setString(13, product.getExpiryDate() != null ? product.getExpiryDate().toString() : null);
            ps.setString(14, product.getStatus().getDisplayName());
            ps.setInt(15, product.isManualStatusOverride() ? 1 : 0);
            ps.setString(16, product.getPhotoPath());
            ps.setString(17, product.getSubstituteSku());
            ps.setInt(18, product.isArchived() ? 1 : 0);
            ps.setString(19, (product.getCreatedAt() != null ? product.getCreatedAt() : LocalDateTime.now()).toString());
            ps.setString(20, product.getUpdatedAt().toString());
            ps.executeUpdate();
            return product;
        } catch (SQLException ex) {
            return insert(product);
        }
    }

    /**
     * Retrieves all store items ordered by creation order descending.
     *
     * @param includeArchived {@code true} to include discontinued/archived items
     * @return list of matching products (empty list if store has no items)
     */
    public List<Product> findAll(boolean includeArchived) {
        String sql = includeArchived
                ? "SELECT * FROM products ORDER BY id ASC"
                : "SELECT * FROM products WHERE archived = 0 ORDER BY id ASC";
        List<Product> results = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to fetch product catalog", ex);
        }
    }

    /**
     * Looks up a product by its primary database identifier.
     *
     * @param id product row id
     * @return {@link Optional} containing the product if found
     */
    public Optional<Product> findById(long id) {
        if (id <= 0) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM products WHERE id = ?";
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
            throw new IllegalStateException("Failed to find product by id " + id, ex);
        }
    }

    /**
     * Looks up a product by its unique SKU code (case-insensitive).
     *
     * @param sku SKU code such as {@code PRD-1001}
     * @return {@link Optional} containing the product if found
     */
    public Optional<Product> findBySku(String sku) {
        if (sku == null || sku.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM products WHERE UPPER(sku) = UPPER(?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sku.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to find product by SKU " + sku, ex);
        }
    }

    /**
     * Looks up a product by barcode string.
     *
     * @param barcode barcode identifier
     * @return {@link Optional} containing the matched product
     */
    public Optional<Product> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM products WHERE barcode = ? OR UPPER(sku) = UPPER(?) LIMIT 1";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String trimmed = barcode.trim();
            ps.setString(1, trimmed);
            ps.setString(2, trimmed);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to look up barcode " + barcode, ex);
        }
    }

    /**
     * Updates an existing product record in SQLite.
     *
     * @param product updated product entity with valid {@code id}
     * @return {@code true} if a row existed and was updated, {@code false} if the ID does not exist
     */
    public boolean update(Product product) {
        if (product == null || product.getId() <= 0) {
            return false;
        }
        product.setUpdatedAt(LocalDateTime.now());
        product.refreshStatusIfAutomatic();
        String sql = """
                UPDATE products SET
                    sku = ?, barcode = ?, name = ?, category = ?, tags = ?,
                    quantity = ?, damaged_qty = ?, low_stock_threshold = ?,
                    buy_price = ?, sell_price = ?, shelf_location = ?,
                    expiry_date = ?, status = ?, manual_status_override = ?,
                    photo_path = ?, substitute_sku = ?, archived = ?, updated_at = ?
                WHERE id = ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.getSku());
            ps.setString(2, product.getBarcode());
            ps.setString(3, product.getName().trim());
            ps.setString(4, product.getCategory() != null && !product.getCategory().isBlank() ? product.getCategory() : "General");
            ps.setString(5, product.getTags());
            ps.setInt(6, product.getQuantity());
            ps.setInt(7, product.getDamagedQty());
            ps.setInt(8, product.getLowStockThreshold());
            ps.setDouble(9, product.getBuyPrice());
            ps.setDouble(10, product.getSellPrice());
            ps.setString(11, product.getShelfLocation());
            ps.setString(12, product.getExpiryDate() != null ? product.getExpiryDate().toString() : null);
            ps.setString(13, product.getStatus().getDisplayName());
            ps.setInt(14, product.isManualStatusOverride() ? 1 : 0);
            ps.setString(15, product.getPhotoPath());
            ps.setString(16, product.getSubstituteSku());
            ps.setInt(17, product.isArchived() ? 1 : 0);
            ps.setString(18, product.getUpdatedAt().toString());
            ps.setLong(19, product.getId());
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update product id " + product.getId(), ex);
        }
    }

    /**
     * Deletes a product permanently by database ID.
     *
     * @param id product row id
     * @return {@code true} if the item existed and was removed, {@code false} otherwise
     */
    public boolean deleteById(long id) {
        if (id <= 0) {
            return false;
        }
        String sql = "DELETE FROM products WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete product id " + id, ex);
        }
    }

    /**
     * Removes all products from the table (primarily for empty-store testing and resets).
     *
     * @return number of rows deleted
     */
    public int deleteAll() {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate("DELETE FROM products");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to clear products table", ex);
        }
    }

    /**
     * Counts non-archived products currently stored in the catalog.
     *
     * @return active product count
     */
    public int countActive() {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM products WHERE archived = 0")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to count active products", ex);
        }
    }

    /**
     * Generates the next unique sequential SKU code formatted as {@code PRD-1001}, {@code PRD-1002}, etc.
     *
     * @return unique next SKU code
     */
    public synchronized String generateNextSku() {
        int maxNumber = 1000;
        String sql = "SELECT sku FROM products WHERE sku LIKE 'PRD-%'";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String sku = rs.getString(1);
                if (sku != null && sku.startsWith("PRD-")) {
                    try {
                        int num = Integer.parseInt(sku.substring(4));
                        if (num > maxNumber) {
                            maxNumber = num;
                        }
                    } catch (NumberFormatException ignored) {
                        // Ignore custom non-numeric suffixes.
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to generate next SKU", ex);
        }
        return "PRD-" + (maxNumber + 1);
    }

    /**
     * Binds all insert statement parameters for a product entity.
     *
     * @param ps prepared insert statement
     * @param product source product
     * @throws SQLException if JDBC parameter binding fails
     */
    private void bindStatement(PreparedStatement ps, Product product) throws SQLException {
        ps.setString(1, product.getSku());
        ps.setString(2, product.getBarcode());
        ps.setString(3, product.getName().trim());
        ps.setString(4, product.getCategory() != null ? product.getCategory() : "General");
        ps.setString(5, product.getTags());
        ps.setInt(6, product.getQuantity());
        ps.setInt(7, product.getDamagedQty());
        ps.setInt(8, product.getLowStockThreshold());
        ps.setDouble(9, product.getBuyPrice());
        ps.setDouble(10, product.getSellPrice());
        ps.setString(11, product.getShelfLocation());
        ps.setString(12, product.getExpiryDate() != null ? product.getExpiryDate().toString() : null);
        ps.setString(13, product.getStatus().getDisplayName());
        ps.setInt(14, product.isManualStatusOverride() ? 1 : 0);
        ps.setString(15, product.getPhotoPath());
        ps.setString(16, product.getSubstituteSku());
        ps.setInt(17, product.isArchived() ? 1 : 0);
        ps.setString(18, product.getCreatedAt().toString());
        ps.setString(19, product.getUpdatedAt().toString());
    }

    /**
     * Maps the current SQLite result row into a {@link Product} instance.
     *
     * @param rs active JDBC result set positioned on a row
     * @return hydrated {@link Product}
     * @throws SQLException if reading any column fails
     */
    private Product mapRow(ResultSet rs) throws SQLException {
        Product p = new Product();
        p.setId(rs.getLong("id"));
        p.setSku(rs.getString("sku"));
        p.setBarcode(rs.getString("barcode"));
        p.setName(rs.getString("name"));
        p.setCategory(rs.getString("category"));
        p.setTags(rs.getString("tags"));
        p.setQuantity(rs.getInt("quantity"));
        p.setDamagedQty(rs.getInt("damaged_qty"));
        p.setLowStockThreshold(rs.getInt("low_stock_threshold"));
        p.setBuyPrice(rs.getDouble("buy_price"));
        p.setSellPrice(rs.getDouble("sell_price"));
        p.setShelfLocation(rs.getString("shelf_location"));
        String exp = rs.getString("expiry_date");
        if (exp != null && !exp.isBlank()) {
            p.setExpiryDate(LocalDate.parse(exp));
        }
        p.setStatus(StockStatus.fromString(rs.getString("status")));
        p.setManualStatusOverride(rs.getInt("manual_status_override") == 1);
        p.setPhotoPath(rs.getString("photo_path"));
        p.setSubstituteSku(rs.getString("substitute_sku"));
        p.setArchived(rs.getInt("archived") == 1);
        String created = rs.getString("created_at");
        if (created != null && !created.isBlank()) {
            p.setCreatedAt(LocalDateTime.parse(created));
        }
        String updated = rs.getString("updated_at");
        if (updated != null && !updated.isBlank()) {
            p.setUpdatedAt(LocalDateTime.parse(updated));
        }
        return p;
    }
}
