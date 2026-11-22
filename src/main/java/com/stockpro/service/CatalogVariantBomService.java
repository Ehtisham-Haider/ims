package com.stockpro.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.stockpro.domain.BatchLot;
import com.stockpro.domain.BatchRecallTrace;
import com.stockpro.domain.BundleComponent;
import com.stockpro.domain.MovementType;
import com.stockpro.domain.Product;
import com.stockpro.domain.ProductAttachment;
import com.stockpro.domain.ProductVariant;
import com.stockpro.domain.SerialNumberRecord;
import com.stockpro.domain.StockMovement;
import com.stockpro.domain.UomConversion;
import com.stockpro.dsa.DagCycleDetector;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;
import com.stockpro.repository.StockMovementRepository;

import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manages Version 2 enterprise catalog structures: parent-child matrix variants with cascading parent edits,
 * {@link BigDecimal} UoM pack-to-piece conversions, DAG-checked BOM bundles with component deduction,
 * serial number tracking, forward/backward batch recall tracing, PDF spec sheet storage, and JSON custom fields.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class CatalogVariantBomService {
    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {
    }.getType();

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private final Path attachmentsRootDir;

    /**
     * Constructs the Version 2 catalog variant and BOM service using the default {@code data/spec-sheets} vault.
     *
     * @param dbManager database manager
     * @param productRepository product repository
     * @param movementRepository stock movement repository
     * @param auditService SHA-256 audit logger
     * @param eventBus shared UI refresh event bus
     */
    public CatalogVariantBomService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            StockMovementRepository movementRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this(dbManager, productRepository, movementRepository, auditService, eventBus, Path.of("data", "spec-sheets"));
    }

    /**
     * Constructs the Version 2 catalog variant and BOM service targeting a specific attachment directory.
     *
     * @param dbManager database manager
     * @param productRepository product repository
     * @param movementRepository stock movement repository
     * @param auditService SHA-256 audit logger
     * @param eventBus shared UI refresh event bus
     * @param attachmentsRootDir local directory storing PDF specification attachments
     */
    public CatalogVariantBomService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            StockMovementRepository movementRepository,
            AuditService auditService,
            InventoryEventBus eventBus,
            Path attachmentsRootDir) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
        this.movementRepository = movementRepository;
        this.auditService = auditService;
        this.eventBus = eventBus != null ? eventBus : InventoryEventBus.getInstance();
        this.attachmentsRootDir = attachmentsRootDir != null ? attachmentsRootDir : Path.of("data", "spec-sheets");
    }

    /**
     * Creates a matrix variant child SKU linked to an existing parent catalog product.
     *
     * @param parentProductId parent product ID
     * @param variantLabel human-readable variant suffix (e.g., {@code Midnight / L})
     * @param color optional color attribute
     * @param size optional size attribute
     * @param flavor optional flavor attribute
     * @param sellPriceDelta unit sell price adjustment relative to the parent
     * @param initialQty initial stock quantity on hand
     * @return wrapped {@link ProductVariant} or validation failure
     */
    public InventoryOperationResult<ProductVariant> createMatrixVariant(
            long parentProductId,
            String variantLabel,
            String color,
            String size,
            String flavor,
            double sellPriceDelta,
            int initialQty) {
        Optional<Product> parentOpt = productRepository.findById(parentProductId);
        if (parentOpt.isEmpty()) {
            return InventoryOperationResult.fail("Parent product not found.");
        }
        if (initialQty < 0) {
            return InventoryOperationResult.fail("Variant initial quantity cannot be negative.");
        }
        String label = variantLabel != null ? variantLabel.trim() : "";
        if (label.isBlank()) {
            label = buildVariantLabel(color, size, flavor);
        }
        if (label.isBlank()) {
            return InventoryOperationResult.fail("Variant option (size, color, or flavor) is required.");
        }

        Product parent = parentOpt.get();
        List<ProductVariant> existing = getVariantsForParent(parentProductId);
        String variantSku = parent.getSku() + "-V" + (existing.size() + 1);
        while (productRepository.findBySku(variantSku).isPresent()) {
            variantSku = variantSku + "X";
        }

        Product child = Product.copyOf(parent);
        child.setId(0);
        child.setSku(variantSku);
        child.setBarcode(variantSku);
        child.setName(parent.getName() + " — " + label);
        child.setQuantity(initialQty);
        child.setManualStatusOverride(false);
        child.setSellPrice(Math.max(0.0, Math.round((parent.getSellPrice() + sellPriceDelta) * 100.0) / 100.0));
        child.refreshStatusIfAutomatic();
        Product savedChild = productRepository.insert(child);

        String now = LocalDateTime.now().toString();
        String sql = """
                INSERT INTO product_variants (
                    parent_product_id, child_product_id, variant_sku, variant_name,
                    color, size, flavor, sell_price_delta, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, parentProductId);
            ps.setLong(2, savedChild.getId());
            ps.setString(3, variantSku);
            ps.setString(4, label);
            ps.setString(5, safeTrim(color));
            ps.setString(6, safeTrim(size));
            ps.setString(7, safeTrim(flavor));
            ps.setDouble(8, sellPriceDelta);
            ps.setString(9, now);
            ps.executeUpdate();

            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            eventBus.publish("VARIANT_CREATED");
            return InventoryOperationResult.ok(
                    new ProductVariant(id, parentProductId, savedChild.getId(), variantSku, label,
                            safeTrim(color), safeTrim(size), safeTrim(flavor), sellPriceDelta, LocalDateTime.parse(now)),
                    "Created variant '" + savedChild.getName() + "'.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create matrix variant", ex);
        }
    }

    /**
     * Returns all child matrix variants belonging to a parent product.
     *
     * @param parentProductId parent product ID
     * @return list of {@link ProductVariant} records
     */
    public List<ProductVariant> getVariantsForParent(long parentProductId) {
        String sql = """
                SELECT id, parent_product_id, child_product_id, variant_sku, variant_name,
                       color, size, flavor, sell_price_delta, created_at
                FROM product_variants
                WHERE parent_product_id = ?
                ORDER BY id ASC
                """;
        List<ProductVariant> out = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, parentProductId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long childRaw = rs.getLong("child_product_id");
                    Long childId = rs.wasNull() ? null : childRaw;
                    out.add(new ProductVariant(
                            rs.getLong("id"),
                            rs.getLong("parent_product_id"),
                            childId,
                            rs.getString("variant_sku"),
                            rs.getString("variant_name"),
                            rs.getString("color"),
                            rs.getString("size"),
                            rs.getString("flavor"),
                            rs.getDouble("sell_price_delta"),
                            LocalDateTime.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query variants", ex);
        }
        return out;
    }

    /**
     * Updates parent product category, tags, and base selling price and cascades those updates
     * proportionally across every linked child variant product.
     *
     * @param parentProductId parent product ID
     * @param newCategory updated category (null preserves current)
     * @param newTags updated tags (null preserves current)
     * @param newBaseSellPrice updated base selling price
     * @return number of child variant products updated
     */
    public InventoryOperationResult<Integer> cascadeParentEdits(
            long parentProductId,
            String newCategory,
            String newTags,
            double newBaseSellPrice) {
        Optional<Product> parentOpt = productRepository.findById(parentProductId);
        if (parentOpt.isEmpty()) {
            return InventoryOperationResult.fail("Parent product not found.");
        }
        if (newBaseSellPrice < 0.0) {
            return InventoryOperationResult.fail("Base selling price cannot be negative.");
        }
        Product parent = parentOpt.get();
        String effCategory = (newCategory != null && !newCategory.isBlank()) ? newCategory.trim() : parent.getCategory();
        String effTags = newTags != null ? newTags.trim() : parent.getTags();
        parent.setCategory(effCategory);
        parent.setTags(effTags);
        parent.setSellPrice(newBaseSellPrice);
        productRepository.update(parent);

        List<ProductVariant> variants = getVariantsForParent(parentProductId);
        int updatedChildren = 0;
        for (ProductVariant v : variants) {
            if (v.childProductId() != null) {
                Optional<Product> childOpt = productRepository.findById(v.childProductId());
                if (childOpt.isPresent()) {
                    Product child = childOpt.get();
                    child.setCategory(effCategory);
                    child.setTags(effTags);
                    child.setSellPrice(Math.max(0.0, Math.round((newBaseSellPrice + v.sellPriceDelta()) * 100.0) / 100.0));
                    productRepository.update(child);
                    updatedChildren++;
                }
            }
        }
        eventBus.publish("VARIANT_CASCADED");
        return InventoryOperationResult.ok(
                updatedChildren,
                "Updated parent and cascaded edits to " + updatedChildren + " variant(s).");
    }

    /**
     * Registers or updates a unit-of-measure conversion factor relative to base sellable pieces.
     *
     * @param productId target product ID
     * @param unitName unit label (e.g., {@code Box}, {@code Carton})
     * @param factorToBase exact piece count per 1 unit (e.g., {@code 12})
     * @param unitRole {@code PURCHASE}, {@code STORAGE}, or {@code SELLING}
     * @return wrapped {@link UomConversion}
     */
    public InventoryOperationResult<UomConversion> registerUomRule(
            long productId,
            String unitName,
            BigDecimal factorToBase,
            String unitRole) {
        if (unitName == null || unitName.isBlank()) {
            return InventoryOperationResult.fail("Unit name is required.");
        }
        if (factorToBase == null || factorToBase.compareTo(BigDecimal.ZERO) <= 0) {
            return InventoryOperationResult.fail("Conversion factor must be strictly positive.");
        }
        String cleanUnit = unitName.trim();
        String cleanRole = (unitRole != null && !unitRole.isBlank()) ? unitRole.trim().toUpperCase(Locale.ROOT) : "SELLING";
        String factorStr = factorToBase.stripTrailingZeros().toPlainString();
        String now = LocalDateTime.now().toString();

        String sql = """
                INSERT INTO uom_conversions (product_id, unit_name, factor_to_base, unit_role, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(product_id, unit_name) DO UPDATE SET
                    factor_to_base = excluded.factor_to_base,
                    unit_role = excluded.unit_role,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setString(2, cleanUnit);
            ps.setString(3, factorStr);
            ps.setString(4, cleanRole);
            ps.setString(5, now);
            ps.executeUpdate();
            return InventoryOperationResult.ok(
                    new UomConversion(0L, productId, cleanUnit, factorToBase, cleanRole, LocalDateTime.parse(now)),
                    "Saved UoM rule: 1 " + cleanUnit + " = " + factorStr + " base piece(s).");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save UoM conversion", ex);
        }
    }

    /**
     * Lists all custom UoM conversion rules registered for a product.
     *
     * @param productId target product ID
     * @return list of {@link UomConversion} definitions
     */
    public List<UomConversion> listUomRules(long productId) {
        String sql = "SELECT id, product_id, unit_name, factor_to_base, unit_role, updated_at FROM uom_conversions WHERE product_id = ? ORDER BY id ASC";
        List<UomConversion> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new UomConversion(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getString("unit_name"),
                            new BigDecimal(rs.getString("factor_to_base")),
                            rs.getString("unit_role"),
                            LocalDateTime.parse(rs.getString("updated_at"))
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read UoM rules", ex);
        }
        return list;
    }

    /**
     * Converts a quantity between any two registered UoM units using exact {@link BigDecimal} arithmetic.
     *
     * @param productId target product ID
     * @param quantity input quantity
     * @param fromUnit source unit name
     * @param toUnit destination unit name
     * @return converted quantity rounded cleanly when fractional
     */
    public BigDecimal convertUnits(long productId, BigDecimal quantity, String fromUnit, String toUnit) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Quantity to convert cannot be negative.");
        }
        BigDecimal fromFactor = resolveUnitFactor(productId, fromUnit);
        BigDecimal toFactor = resolveUnitFactor(productId, toUnit);
        return quantity.multiply(fromFactor)
                .divide(toFactor, 4, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private BigDecimal resolveUnitFactor(long productId, String unitName) {
        if (unitName == null || unitName.isBlank()) {
            throw new IllegalArgumentException("Unit name is required.");
        }
        String clean = unitName.trim().toLowerCase(Locale.ROOT);
        for (UomConversion rule : listUomRules(productId)) {
            if (rule.unitName().trim().toLowerCase(Locale.ROOT).equals(clean)) {
                return rule.factorToBase();
            }
        }
        if ("piece".equals(clean) || "pieces".equals(clean) || "unit".equals(clean) || "units".equals(clean) || "pcs".equals(clean)) {
            return BigDecimal.ONE;
        }
        throw new IllegalArgumentException("Unknown unit '" + unitName + "' for product ID " + productId);
    }

    /**
     * Adds a component dependency edge to a bundle/kit after validating via {@link DagCycleDetector}
     * that no circular BOM cycle would be created.
     *
     * @param bundleProductId parent bundle/kit product ID
     * @param componentProductId required component product ID
     * @param componentQty units of component consumed per sold bundle
     * @param unitName unit label
     * @return operation result with saved {@link BundleComponent} or cycle error
     */
    public InventoryOperationResult<BundleComponent> addBundleComponent(
            long bundleProductId,
            long componentProductId,
            int componentQty,
            String unitName) {
        if (componentQty <= 0) {
            return InventoryOperationResult.fail("Component quantity must be positive.");
        }
        Optional<Product> parentOpt = productRepository.findById(bundleProductId);
        Optional<Product> compOpt = productRepository.findById(componentProductId);
        if (parentOpt.isEmpty() || compOpt.isEmpty()) {
            return InventoryOperationResult.fail("Both bundle and component products must exist.");
        }

        Map<Long, List<Long>> graph = loadBundleAdjacencyGraph();
        if (DagCycleDetector.wouldCreateCycle(graph, bundleProductId, componentProductId)) {
            return InventoryOperationResult.fail(
                    "Circular bundle BOM detected: adding '" + compOpt.get().getName()
                            + "' inside '" + parentOpt.get().getName() + "' creates a cycle.");
        }

        String cleanUnit = (unitName != null && !unitName.isBlank()) ? unitName.trim() : "Piece";
        String now = LocalDateTime.now().toString();
        String sql = """
                INSERT INTO product_bundles (bundle_product_id, component_product_id, component_qty, unit_name, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(bundle_product_id, component_product_id) DO UPDATE SET
                    component_qty = excluded.component_qty,
                    unit_name = excluded.unit_name
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bundleProductId);
            ps.setLong(2, componentProductId);
            ps.setInt(3, componentQty);
            ps.setString(4, cleanUnit);
            ps.setString(5, now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save BOM bundle edge", ex);
        }

        Product comp = compOpt.get();
        return InventoryOperationResult.ok(
                new BundleComponent(0L, bundleProductId, componentProductId, comp.getSku(), comp.getName(),
                        componentQty, cleanUnit, LocalDateTime.parse(now)),
                "Linked component '" + comp.getName() + "' (x" + componentQty + ") to bundle.");
    }

    /**
     * Lists all direct BOM components inside a bundle/kit.
     *
     * @param bundleProductId parent bundle product ID
     * @return list of {@link BundleComponent} entries
     */
    public List<BundleComponent> getBundleComponents(long bundleProductId) {
        String sql = """
                SELECT b.id, b.bundle_product_id, b.component_product_id, p.sku, p.name,
                       b.component_qty, b.unit_name, b.created_at
                FROM product_bundles b
                JOIN products p ON p.id = b.component_product_id
                WHERE b.bundle_product_id = ?
                ORDER BY b.id ASC
                """;
        List<BundleComponent> list = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, bundleProductId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new BundleComponent(
                            rs.getLong("id"),
                            rs.getLong("bundle_product_id"),
                            rs.getLong("component_product_id"),
                            rs.getString("sku"),
                            rs.getString("name"),
                            rs.getInt("component_qty"),
                            rs.getString("unit_name"),
                            LocalDateTime.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to fetch bundle BOM components", ex);
        }
        return list;
    }

    /**
     * Deducts component inventory atomically when selling units of a combo bundle.
     * Fails cleanly with zero stock mutations if any required component lacks stock.
     *
     * @param bundleProductId parent bundle product ID
     * @param bundlesSold count of bundles sold
     * @param actor staff username recording the sale
     * @return count of components deducted wrapped in {@link InventoryOperationResult}
     */
    public InventoryOperationResult<Integer> deductBundleComponentsOnSale(
            long bundleProductId,
            int bundlesSold,
            String actor) {
        if (bundlesSold <= 0) {
            return InventoryOperationResult.fail("Bundle sale quantity must be positive.");
        }
        List<BundleComponent> components = getBundleComponents(bundleProductId);
        if (components.isEmpty()) {
            return InventoryOperationResult.fail("No BOM components linked to bundle ID " + bundleProductId + ".");
        }

        List<Product> compProducts = new ArrayList<>();
        for (BundleComponent bc : components) {
            Product comp = productRepository.findById(bc.componentProductId()).orElse(null);
            if (comp == null) {
                return InventoryOperationResult.fail("Missing component ID " + bc.componentProductId());
            }
            int needed = bc.componentQty() * bundlesSold;
            if (comp.getQuantity() < needed) {
                return InventoryOperationResult.fail(
                        "Insufficient component stock for '" + comp.getName() + "': need " + needed
                                + ", available " + comp.getQuantity() + ".");
            }
            compProducts.add(comp);
        }

        String actorName = (actor != null && !actor.isBlank()) ? actor.trim() : "System";
        for (int i = 0; i < components.size(); i++) {
            BundleComponent bc = components.get(i);
            Product comp = compProducts.get(i);
            int delta = -(bc.componentQty() * bundlesSold);
            int prev = comp.getQuantity();
            int next = prev + delta;
            comp.setQuantity(next);
            comp.refreshStatusIfAutomatic();
            productRepository.update(comp);
            if (movementRepository != null) {
                StockMovement move = new StockMovement();
                move.setProductId(comp.getId());
                move.setProductSku(comp.getSku());
                move.setProductName(comp.getName());
                move.setMovementType(MovementType.SALE);
                move.setQuantityDelta(delta);
                move.setPreviousQty(prev);
                move.setNewQty(next);
                move.setReason("Bundle BOM Component Deduction");
                move.setNotes("Auto-deducted for x" + bundlesSold + " bundle sale (bundle ID " + bundleProductId + ")");
                move.setPerformedBy(actorName);
                movementRepository.insert(move);
            }
        }
        if (auditService != null) {
            auditService.record(actorName, "BOM_BUNDLE_SALE", "PRD_ID:" + bundleProductId,
                    "Deducted " + components.size() + " BOM components for " + bundlesSold + " bundle(s)");
        }
        eventBus.publish("STOCK_ADJUSTED");
        return InventoryOperationResult.ok(components.size(),
                "Deducted " + components.size() + " component(s) for " + bundlesSold + " bundle(s).");
    }

    private Map<Long, List<Long>> loadBundleAdjacencyGraph() {
        Map<Long, List<Long>> adj = new HashMap<>();
        String sql = "SELECT bundle_product_id, component_product_id FROM product_bundles";
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long p = rs.getLong(1);
                long c = rs.getLong(2);
                adj.computeIfAbsent(p, k -> new ArrayList<>()).add(c);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load BOM graph", ex);
        }
        return adj;
    }

    /**
     * Captures a unique factory serial number against a product and optional supplier batch lot.
     *
     * @param productId target product ID
     * @param serialCode unique hardware serial string
     * @param batchCode optional batch/lot code
     * @param branchId holding branch ID (defaults to 1)
     * @param notes optional audit notes
     * @return wrapped {@link SerialNumberRecord}
     */
    public InventoryOperationResult<SerialNumberRecord> registerSerialNumber(
            long productId,
            String serialCode,
            String batchCode,
            long branchId,
            String notes) {
        if (serialCode == null || serialCode.isBlank()) {
            return InventoryOperationResult.fail("Serial number code is required.");
        }
        if (productRepository.findById(productId).isEmpty()) {
            return InventoryOperationResult.fail("Target product not found.");
        }
        String cleanSerial = serialCode.trim();
        if (findBySerialCode(cleanSerial).isPresent()) {
            return InventoryOperationResult.fail("Serial number '" + cleanSerial + "' is already registered.");
        }
        long targetBranch = branchId > 0 ? branchId : 1L;
        String now = LocalDateTime.now().toString();
        String sql = """
                INSERT INTO serial_numbers (product_id, serial_code, batch_code, status, branch_id, notes, updated_at)
                VALUES (?, ?, ?, 'IN_STOCK', ?, ?, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, productId);
            ps.setString(2, cleanSerial);
            ps.setString(3, safeTrim(batchCode));
            ps.setLong(4, targetBranch);
            ps.setString(5, safeTrim(notes));
            ps.setString(6, now);
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return InventoryOperationResult.ok(
                    new SerialNumberRecord(id, productId, cleanSerial, safeTrim(batchCode),
                            "IN_STOCK", targetBranch, safeTrim(notes), LocalDateTime.parse(now)),
                    "Registered serial '" + cleanSerial + "'.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to register serial number", ex);
        }
    }

    /**
     * Updates the lifecycle state of a registered factory serial number.
     *
     * @param serialCode exact serial string
     * @param newStatus updated state ({@code IN_STOCK}, {@code SOLD}, {@code RMA_RETURNED}, {@code DEFECTIVE})
     * @param notes optional update notes
     * @return updated {@link SerialNumberRecord}
     */
    public InventoryOperationResult<SerialNumberRecord> updateSerialStatus(
            String serialCode,
            String newStatus,
            String notes) {
        if (serialCode == null || serialCode.isBlank()) {
            return InventoryOperationResult.fail("Serial code is required.");
        }
        Optional<SerialNumberRecord> found = findBySerialCode(serialCode.trim());
        if (found.isEmpty()) {
            return InventoryOperationResult.fail("Serial code not found.");
        }
        String status = (newStatus != null && !newStatus.isBlank()) ? newStatus.trim().toUpperCase(Locale.ROOT) : "SOLD";
        String now = LocalDateTime.now().toString();
        String mergedNotes = (notes != null && !notes.isBlank()) ? notes.trim() : found.get().notes();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE serial_numbers SET status = ?, notes = ?, updated_at = ? WHERE serial_code = ?")) {
            ps.setString(1, status);
            ps.setString(2, mergedNotes);
            ps.setString(3, now);
            ps.setString(4, serialCode.trim());
            ps.executeUpdate();
            return InventoryOperationResult.ok(findBySerialCode(serialCode.trim()).orElse(found.get()),
                    "Updated serial '" + serialCode.trim() + "' to " + status + ".");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update serial status", ex);
        }
    }

    /**
     * Looks up a registered unit serial number by exact serial code.
     *
     * @param serialCode unique serial string
     * @return matching {@link SerialNumberRecord}, if present
     */
    public Optional<SerialNumberRecord> findBySerialCode(String serialCode) {
        if (serialCode == null || serialCode.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, product_id, serial_code, batch_code, status, branch_id, notes, updated_at FROM serial_numbers WHERE serial_code = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serialCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapSerial(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to look up serial number", ex);
        }
        return Optional.empty();
    }

    /**
     * Lists all serial numbers associated with a given product.
     *
     * @param productId target product ID
     * @return list of {@link SerialNumberRecord} rows
     */
    public List<SerialNumberRecord> getSerialsForProduct(long productId) {
        String sql = "SELECT id, product_id, serial_code, batch_code, status, branch_id, notes, updated_at FROM serial_numbers WHERE product_id = ? ORDER BY id DESC";
        List<SerialNumberRecord> out = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapSerial(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list serial numbers", ex);
        }
        return out;
    }

    private static SerialNumberRecord mapSerial(ResultSet rs) throws SQLException {
        return new SerialNumberRecord(
                rs.getLong("id"),
                rs.getLong("product_id"),
                rs.getString("serial_code"),
                rs.getString("batch_code"),
                rs.getString("status"),
                rs.getLong("branch_id"),
                rs.getString("notes"),
                LocalDateTime.parse(rs.getString("updated_at"))
        );
    }

    /**
     * Registers a supplier manufacturing batch/lot for recall traceability.
     *
     * @param batchCode unique batch/lot identifier
     * @param productId target product ID
     * @param supplierId optional originating supplier ID
     * @param quantityReceived received unit count
     * @param expiryDate optional lot expiry date
     * @return wrapped {@link BatchLot}
     */
    public InventoryOperationResult<BatchLot> registerBatchLot(
            String batchCode,
            long productId,
            Long supplierId,
            int quantityReceived,
            LocalDate expiryDate) {
        if (batchCode == null || batchCode.isBlank()) {
            return InventoryOperationResult.fail("Batch code is required.");
        }
        if (quantityReceived <= 0) {
            return InventoryOperationResult.fail("Batch quantity must be positive.");
        }
        String cleanCode = batchCode.trim();
        String now = LocalDateTime.now().toString();
        String sql = """
                INSERT INTO batch_lots (batch_code, product_id, supplier_id, quantity_received, expiry_date, recalled, recall_reason, received_at)
                VALUES (?, ?, ?, ?, ?, 0, NULL, ?)
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cleanCode);
            ps.setLong(2, productId);
            if (supplierId != null) {
                ps.setLong(3, supplierId);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            ps.setInt(4, quantityReceived);
            ps.setString(5, expiryDate != null ? expiryDate.toString() : null);
            ps.setString(6, now);
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return InventoryOperationResult.ok(
                    new BatchLot(id, cleanCode, productId, supplierId, quantityReceived, expiryDate,
                            false, null, LocalDateTime.parse(now)),
                    "Registered batch lot '" + cleanCode + "'.");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to register batch lot", ex);
        }
    }

    /**
     * Flags a supplier batch lot for recall and returns the complete forward/backward traceability snapshot.
     *
     * @param batchCode batch code to recall
     * @param reason recall reason
     * @return traced {@link BatchRecallTrace}
     */
    public InventoryOperationResult<BatchRecallTrace> flagBatchRecall(String batchCode, String reason) {
        if (batchCode == null || batchCode.isBlank()) {
            return InventoryOperationResult.fail("Batch code is required.");
        }
        String cleanReason = (reason != null && !reason.isBlank()) ? reason.trim() : "Supplier Quality Recall";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE batch_lots SET recalled = 1, recall_reason = ? WHERE batch_code = ?")) {
            ps.setString(1, cleanReason);
            ps.setString(2, batchCode.trim());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                return InventoryOperationResult.fail("Batch lot '" + batchCode.trim() + "' not found.");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to flag batch recall", ex);
        }
        if (auditService != null) {
            auditService.record("RecallOfficer", "BATCH_RECALL", batchCode.trim(), cleanReason);
        }
        return traceBatchRecall(batchCode.trim());
    }

    /**
     * Runs a forward and backward batch recall trace returning originating supplier, shelf location,
     * linked serial numbers, and recent stock movements.
     *
     * @param batchCode batch lot code
     * @return {@link BatchRecallTrace} report
     */
    public InventoryOperationResult<BatchRecallTrace> traceBatchRecall(String batchCode) {
        if (batchCode == null || batchCode.isBlank()) {
            return InventoryOperationResult.fail("Batch code is required.");
        }
        String sql = """
                SELECT b.*, p.sku AS p_sku, p.name AS p_name, p.shelf_location AS p_shelf,
                       p.quantity AS p_qty, s.name AS sup_name
                FROM batch_lots b
                JOIN products p ON p.id = b.product_id
                LEFT JOIN suppliers s ON s.id = b.supplier_id
                WHERE b.batch_code = ?
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, batchCode.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return InventoryOperationResult.fail("Batch lot '" + batchCode.trim() + "' not found.");
                }
                long supRaw = rs.getLong("supplier_id");
                Long supId = rs.wasNull() ? null : supRaw;
                String expRaw = rs.getString("expiry_date");
                BatchLot lot = new BatchLot(
                        rs.getLong("id"),
                        rs.getString("batch_code"),
                        rs.getLong("product_id"),
                        supId,
                        rs.getInt("quantity_received"),
                        expRaw != null && !expRaw.isBlank() ? LocalDate.parse(expRaw) : null,
                        rs.getInt("recalled") == 1,
                        rs.getString("recall_reason"),
                        LocalDateTime.parse(rs.getString("received_at"))
                );
                List<SerialNumberRecord> serials = new ArrayList<>();
                try (PreparedStatement psSer = conn.prepareStatement(
                        "SELECT id, product_id, serial_code, batch_code, status, branch_id, notes, updated_at FROM serial_numbers WHERE batch_code = ?")) {
                    psSer.setString(1, lot.batchCode());
                    try (ResultSet rsSer = psSer.executeQuery()) {
                        while (rsSer.next()) {
                            serials.add(mapSerial(rsSer));
                        }
                    }
                }
                List<StockMovement> moves = movementRepository != null
                        ? movementRepository.findByProductId(lot.productId(), 10)
                        : List.of();
                String supName = rs.getString("sup_name");
                return InventoryOperationResult.ok(
                        new BatchRecallTrace(
                                lot,
                                rs.getString("p_sku"),
                                rs.getString("p_name"),
                                supName != null ? supName : "Unlinked Supplier",
                                rs.getString("p_shelf") != null ? rs.getString("p_shelf") : "Unassigned",
                                serials,
                                moves,
                                rs.getInt("p_qty")
                        ),
                        "Batch trace loaded for " + lot.batchCode() + ".");
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to trace batch recall", ex);
        }
    }

    /**
     * Copies a PDF manual, warranty card, or specification file into the product's local document vault
     * and records its metadata in SQLite.
     *
     * @param productId target product ID
     * @param sourceFile path to the document to store
     * @param docType document role ({@code SPEC_SHEET}, {@code WARRANTY}, {@code SAFETY_GUIDE})
     * @return wrapped {@link ProductAttachment}
     */
    public InventoryOperationResult<ProductAttachment> attachSpecSheet(
            long productId,
            Path sourceFile,
            String docType) {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return InventoryOperationResult.fail("Selected specification file does not exist.");
        }
        if (productRepository.findById(productId).isEmpty()) {
            return InventoryOperationResult.fail("Target product not found.");
        }
        String fileName = sourceFile.getFileName().toString();
        String role = (docType != null && !docType.isBlank()) ? docType.trim().toUpperCase(Locale.ROOT) : "SPEC_SHEET";
        Path prodDir = attachmentsRootDir.resolve("prd-" + productId);
        Path target = prodDir.resolve(System.currentTimeMillis() + "-" + fileName);
        try {
            Files.createDirectories(prodDir);
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
            long sizeBytes = Files.size(target);
            String now = LocalDateTime.now().toString();
            String sql = """
                    INSERT INTO product_attachments (product_id, file_name, file_path, doc_type, file_size_bytes, added_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, productId);
                ps.setString(2, fileName);
                ps.setString(3, target.toAbsolutePath().toString());
                ps.setString(4, role);
                ps.setLong(5, sizeBytes);
                ps.setString(6, now);
                ps.executeUpdate();
                long id = 0;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        id = keys.getLong(1);
                    }
                }
                return InventoryOperationResult.ok(
                        new ProductAttachment(id, productId, fileName, target.toAbsolutePath().toString(),
                                role, sizeBytes, LocalDateTime.parse(now)),
                        "Attached '" + fileName + "'.");
            }
        } catch (IOException | SQLException ex) {
            return InventoryOperationResult.fail("Unable to attach file: " + ex.getMessage());
        }
    }

    /**
     * Lists all PDF manuals, warranty cards, and spec sheets attached to a product.
     *
     * @param productId target product ID
     * @return list of {@link ProductAttachment} metadata records
     */
    public List<ProductAttachment> listAttachments(long productId) {
        String sql = "SELECT id, product_id, file_name, file_path, doc_type, file_size_bytes, added_at FROM product_attachments WHERE product_id = ? ORDER BY id DESC";
        List<ProductAttachment> out = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProductAttachment(
                            rs.getLong("id"),
                            rs.getLong("product_id"),
                            rs.getString("file_name"),
                            rs.getString("file_path"),
                            rs.getString("doc_type"),
                            rs.getLong("file_size_bytes"),
                            LocalDateTime.parse(rs.getString("added_at"))
                    ));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list product attachments", ex);
        }
        return out;
    }

    /**
     * Persists arbitrary category-specific extra fields (e.g., {@code Brand}, {@code Voltage}, {@code Fabric})
     * as structured JSON without altering the core {@code products} table schema.
     *
     * @param productId target product ID
     * @param fields key-value custom attribute map
     * @return saved attribute count wrapped in {@link InventoryOperationResult}
     */
    public InventoryOperationResult<Integer> saveCustomFields(long productId, Map<String, String> fields) {
        if (productRepository.findById(productId).isEmpty()) {
            return InventoryOperationResult.fail("Target product not found.");
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    cleaned.put(e.getKey().trim(), e.getValue().trim());
                }
            }
        }
        String json = GSON.toJson(cleaned);
        String now = LocalDateTime.now().toString();
        String sql = """
                INSERT INTO product_custom_fields (product_id, attributes_json, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(product_id) DO UPDATE SET
                    attributes_json = excluded.attributes_json,
                    updated_at = excluded.updated_at
                """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            ps.setString(2, json);
            ps.setString(3, now);
            ps.executeUpdate();
            return InventoryOperationResult.ok(cleaned.size(), "Saved " + cleaned.size() + " custom attribute(s).");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save custom JSON fields", ex);
        }
    }

    /**
     * Retrieves all structured JSON custom attributes attached to a product.
     *
     * @param productId target product ID
     * @return ordered key-value map (empty when none saved)
     */
    public Map<String, String> getCustomFields(long productId) {
        String sql = "SELECT attributes_json FROM product_custom_fields WHERE product_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString(1);
                    if (json != null && !json.isBlank()) {
                        Map<String, String> parsed = GSON.fromJson(json, STRING_MAP_TYPE);
                        return parsed != null ? parsed : new LinkedHashMap<>();
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read custom JSON fields", ex);
        }
        return new LinkedHashMap<>();
    }

    private static String buildVariantLabel(String color, String size, String flavor) {
        List<String> parts = new ArrayList<>();
        if (color != null && !color.isBlank()) {
            parts.add(color.trim());
        }
        if (size != null && !size.isBlank()) {
            parts.add(size.trim());
        }
        if (flavor != null && !flavor.isBlank()) {
            parts.add(flavor.trim());
        }
        return String.join(" / ", parts);
    }

    private static String safeTrim(String text) {
        return text != null && !text.isBlank() ? text.trim() : "";
    }
}
