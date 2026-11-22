package com.stockpro.service;

import com.stockpro.domain.PickRoutePlan;
import com.stockpro.domain.Product;
import com.stockpro.domain.ShelfBinRecord;
import com.stockpro.dsa.WarehousePickRouter;
import com.stockpro.repository.DatabaseManager;
import com.stockpro.repository.ProductRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warehouse Map &amp; Picking service managing 240 grid shelf bins ({@code A1-01..E4-12}),
 * weight/volume fullness monitoring, {@code >=85%} shelf saturation alerts,
 * one-click product bin relocation, and Dijkstra shortest-path pick lists.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class WarehouseMapPickingService {

    private static final Pattern STRICT_BIN = Pattern.compile("^([A-E])([1-4])-(\\d{2})$");
    private static final Pattern SHORT_AISLE_NUM = Pattern.compile("^([A-E])[- ]?(\\d{1,2})$");

    private final DatabaseManager dbManager;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final InventoryEventBus eventBus;
    private final WarehousePickRouter pickRouter;

    /**
     * Constructs the warehouse floorplan and picking optimization service.
     *
     * @param dbManager SQLite connection manager
     * @param productRepository product persistence repository
     * @param auditService tamper-evident audit logger
     * @param eventBus shared UI refresh event bus
     */
    public WarehouseMapPickingService(
            DatabaseManager dbManager,
            ProductRepository productRepository,
            AuditService auditService,
            InventoryEventBus eventBus) {
        this.dbManager = dbManager;
        this.productRepository = productRepository;
        this.auditService = auditService;
        this.eventBus = eventBus;
        this.pickRouter = new WarehousePickRouter();
    }

    /**
     * Normalizes free-text or legacy shelf codes (e.g., {@code A-03}, {@code B2}, {@code C3-07})
     * into one of the canonical {@code A1-01..E4-12} shelf bin coordinates.
     *
     * @param rawShelfLocation product {@code shelf_location} string
     * @return canonical bin code in {@code [A-E][1-4]-[01-12]}
     */
    public String normalizeBinCode(String rawShelfLocation) {
        if (rawShelfLocation == null || rawShelfLocation.isBlank()) {
            return "A1-01";
        }
        String clean = rawShelfLocation.trim().toUpperCase(Locale.ROOT);
        Matcher strict = STRICT_BIN.matcher(clean);
        if (strict.matches()) {
            int col = Integer.parseInt(strict.group(3));
            if (col >= 1 && col <= 12) {
                return clean;
            }
        }
        Matcher shortMatch = SHORT_AISLE_NUM.matcher(clean);
        if (shortMatch.matches()) {
            String aisle = shortMatch.group(1);
            int num = Math.max(1, Integer.parseInt(shortMatch.group(2)));
            int row = ((num - 1) / 12) % 4 + 1;
            int col = ((num - 1) % 12) + 1;
            return String.format(Locale.ROOT, "%s%d-%02d", aisle, row, col);
        }
        int hash = Math.abs(clean.hashCode());
        char aisle = (char) ('A' + (hash % 5));
        int row = ((hash / 5) % 4) + 1;
        int col = ((hash / 20) % 12) + 1;
        return String.format(Locale.ROOT, "%c%d-%02d", aisle, row, col);
    }

    /**
     * Synchronizes {@code shelf_bins} current weight and cubic volume from live product quantities
     * and returns all 240 shelf bins ordered by aisle, row, and column.
     *
     * @return list of {@link ShelfBinRecord} ordered across aisles {@code A..E}
     */
    public List<ShelfBinRecord> listAllBins() {
        Map<String, int[]> skuAndUnitCounts = recalculateAndPersistBinLoads();
        List<ShelfBinRecord> bins = new ArrayList<>(240);
        String sql = """
                SELECT bin_code, aisle, row_index, col_index,
                       max_weight_kg, current_weight_kg, max_volume_dm3, current_volume_dm3
                FROM shelf_bins
                ORDER BY aisle ASC, row_index ASC, col_index ASC
                """;
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String code = rs.getString("bin_code");
                int[] counts = skuAndUnitCounts.getOrDefault(code, new int[]{0, 0});
                bins.add(new ShelfBinRecord(
                        code,
                        rs.getString("aisle"),
                        rs.getInt("row_index"),
                        rs.getInt("col_index"),
                        rs.getDouble("max_weight_kg"),
                        roundTwo(rs.getDouble("current_weight_kg")),
                        rs.getDouble("max_volume_dm3"),
                        roundTwo(rs.getDouble("current_volume_dm3")),
                        counts[0],
                        counts[1]
                ));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list warehouse shelf bins.", ex);
        }
        return bins;
    }

    /**
     * Lists all active products whose normalized bin matches {@code binCode}.
     *
     * @param binCode target bin coordinate such as {@code A1-01}
     * @return matching products sorted by name
     */
    public List<Product> listProductsInBin(String binCode) {
        String normalized = normalizeBinCode(binCode);
        List<Product> all = productRepository.findAll(false);
        List<Product> matches = new ArrayList<>();
        for (Product product : all) {
            if (normalized.equals(normalizeBinCode(product.getShelfLocation()))) {
                matches.add(product);
            }
        }
        return matches;
    }

    /**
     * Returns shelf bins exceeding the specified capacity percentage (default 85%).
     *
     * @param thresholdPercent saturation percentage threshold
     * @return list of saturated {@link ShelfBinRecord} entries
     */
    public List<ShelfBinRecord> listSaturatedBins(double thresholdPercent) {
        double threshold = thresholdPercent <= 0.0 ? ShelfBinRecord.SATURATION_ALERT_PERCENT : thresholdPercent;
        List<ShelfBinRecord> alerts = new ArrayList<>();
        for (ShelfBinRecord bin : listAllBins()) {
            if (bin.maxUtilizationPercent() >= threshold) {
                alerts.add(bin);
            }
        }
        return alerts;
    }

    /**
     * Updates maximum physical weight and cubic volume limits for a specific shelf bin.
     *
     * @param binCode bin code such as {@code A1-01}
     * @param maxWeightKg safe maximum weight in kg
     * @param maxVolumeDm3 maximum volume in cubic decimeters
     * @return updated {@link ShelfBinRecord} wrapped in {@link InventoryOperationResult}
     */
    public InventoryOperationResult<ShelfBinRecord> updateBinCapacity(
            String binCode,
            double maxWeightKg,
            double maxVolumeDm3) {
        if (binCode == null || binCode.isBlank()) {
            return InventoryOperationResult.fail("Bin code cannot be blank.");
        }
        if (maxWeightKg <= 0.0 || maxVolumeDm3 <= 0.0) {
            return InventoryOperationResult.fail("Maximum weight and volume capacities must be positive.");
        }
        String code = binCode.trim().toUpperCase(Locale.ROOT);
        if (!STRICT_BIN.matcher(code).matches()) {
            return InventoryOperationResult.fail("Invalid shelf bin code: " + binCode);
        }
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE shelf_bins SET max_weight_kg = ?, max_volume_dm3 = ? WHERE bin_code = ?")) {
            ps.setDouble(1, maxWeightKg);
            ps.setDouble(2, maxVolumeDm3);
            ps.setString(3, code);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                return InventoryOperationResult.fail("Shelf bin does not exist: " + code);
            }
        } catch (SQLException ex) {
            return InventoryOperationResult.fail("Failed to update bin capacity: " + ex.getMessage());
        }
        Optional<ShelfBinRecord> refreshed = findBin(code);
        eventBus.publish("BIN_UPDATED");
        return refreshed
                .map(b -> InventoryOperationResult.ok(b, "Updated bin limits for " + code))
                .orElseGet(() -> InventoryOperationResult.fail("Shelf bin not found after update: " + code));
    }

    /**
     * Moves a product into a target shelf bin, recalculates old and new shelf utilization,
     * flags saturation warnings above 85%, and logs an immutable audit event.
     *
     * @param productId product database id
     * @param targetBinCode destination bin code ({@code A1-01..E4-12})
     * @param actor authenticated staff username
     * @return operation result containing updated {@link Product} or validation failure
     */
    public InventoryOperationResult<Product> moveItemToBin(long productId, String targetBinCode, String actor) {
        Optional<Product> foundOpt = productRepository.findById(productId);
        if (foundOpt.isEmpty()) {
            return InventoryOperationResult.fail("Product #" + productId + " does not exist.");
        }
        if (targetBinCode == null || targetBinCode.isBlank()) {
            return InventoryOperationResult.fail("Target shelf bin code cannot be blank.");
        }
        String cleanBin = targetBinCode.trim().toUpperCase(Locale.ROOT);
        if (!STRICT_BIN.matcher(cleanBin).matches()) {
            return InventoryOperationResult.fail("Invalid warehouse bin format. Expected A1-01 through E4-12.");
        }
        if (!binExists(cleanBin)) {
            return InventoryOperationResult.fail("Target bin does not exist in floorplan: " + cleanBin);
        }

        Product product = foundOpt.get();
        String previousBin = product.getShelfLocation();
        product.setShelfLocation(cleanBin);
        product.setUpdatedAt(LocalDateTime.now());
        productRepository.update(product);

        Optional<ShelfBinRecord> binState = findBin(cleanBin);
        String who = actor != null && !actor.isBlank() ? actor : "system";
        auditService.record(
                who,
                "BIN_MOVE",
                product.getSku(),
                "Moved '" + product.getName() + "' from " + previousBin + " to " + cleanBin
        );
        eventBus.publish("BIN_MOVE");

        if (binState.isPresent() && binState.get().isNearlyFull()) {
            String msg = String.format(Locale.US,
                    "Moved %s to %s (Warning: bin at %.1f%% capacity).",
                    product.getName(), cleanBin, binState.get().maxUtilizationPercent());
            return InventoryOperationResult.ok(product, msg);
        }
        return InventoryOperationResult.ok(product, "Moved " + product.getName() + " to shelf bin " + cleanBin + ".");
    }

    /**
     * Generates a Dijkstra shortest-path pick tour starting and ending at the packing station.
     *
     * @param productQuantities map of product id to unit pick quantity
     * @return shortest-path {@link PickRoutePlan} with savings comparison
     */
    public PickRoutePlan optimizePickList(Map<Long, Integer> productQuantities) {
        if (productQuantities == null || productQuantities.isEmpty()) {
            return new PickRoutePlan(List.of(), 0.0, 0.0, 0.0, "Select at least one SKU to generate a pick route.");
        }
        List<WarehousePickRouter.PickTarget> targets = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : productQuantities.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            Optional<Product> opt = productRepository.findById(entry.getKey());
            if (opt.isEmpty()) {
                continue;
            }
            Product p = opt.get();
            String bin = normalizeBinCode(p.getShelfLocation());
            targets.add(new WarehousePickRouter.PickTarget(
                    bin, p.getId(), p.getSku(), p.getName(), entry.getValue()
            ));
        }
        return pickRouter.planShortestPickTour(targets);
    }

    /**
     * Convenience wrapper planning a shortest pick tour for an ordered list of product ids
     * picking up to 2 available units each.
     *
     * @param productIds list of product database ids
     * @return optimized {@link PickRoutePlan}
     */
    public PickRoutePlan optimizePickListByProductIds(List<Long> productIds) {
        Map<Long, Integer> requests = new java.util.LinkedHashMap<>();
        if (productIds != null) {
            for (Long id : productIds) {
                if (id != null && id > 0) {
                    requests.merge(id, 1, Integer::sum);
                }
            }
        }
        return optimizePickList(requests);
    }

    /**
     * Looks up a single synchronized shelf bin record by its bin code.
     *
     * @param binCode canonical bin code such as {@code A1-01}
     * @return optional matching {@link ShelfBinRecord}
     */
    public Optional<ShelfBinRecord> findBin(String binCode) {
        String norm = normalizeBinCode(binCode);
        for (ShelfBinRecord record : listAllBins()) {
            if (record.binCode().equals(norm)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    private boolean binExists(String binCode) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM shelf_bins WHERE bin_code = ?")) {
            ps.setString(1, binCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            return false;
        }
    }

    private Map<String, int[]> recalculateAndPersistBinLoads() {
        List<Product> products = productRepository.findAll(false);
        Map<String, double[]> binLoads = new HashMap<>();
        Map<String, int[]> counts = new HashMap<>();

        for (Product product : products) {
            String bin = normalizeBinCode(product.getShelfLocation());
            int totalUnits = Math.max(0, product.getQuantity()) + Math.max(0, product.getDamagedQty());
            double unitWeightKg = estimateUnitWeightKg(product);
            double unitVolumeDm3 = estimateUnitVolumeDm3(product);

            double[] acc = binLoads.computeIfAbsent(bin, k -> new double[2]);
            acc[0] += totalUnits * unitWeightKg;
            acc[1] += totalUnits * unitVolumeDm3;

            int[] c = counts.computeIfAbsent(bin, k -> new int[2]);
            c[0] += 1;
            c[1] += totalUnits;
        }

        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement reset = conn.createStatement()) {
                reset.executeUpdate("UPDATE shelf_bins SET current_weight_kg = 0.0, current_volume_dm3 = 0.0");
            }
            try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE shelf_bins SET current_weight_kg = ?, current_volume_dm3 = ? WHERE bin_code = ?")) {
                for (Map.Entry<String, double[]> entry : binLoads.entrySet()) {
                    upd.setDouble(1, roundTwo(entry.getValue()[0]));
                    upd.setDouble(2, roundTwo(entry.getValue()[1]));
                    upd.setString(3, entry.getKey());
                    upd.addBatch();
                }
                upd.executeBatch();
            }
            conn.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to recalculate warehouse bin loads.", ex);
        }
        return counts;
    }

    /**
     * Estimates single-unit physical weight in kilograms using product title/category cues.
     *
     * @param product product entity
     * @return estimated weight per unit in kg
     */
    public static double estimateUnitWeightKg(Product product) {
        if (product == null) {
            return 0.5;
        }
        String name = (product.getName() == null ? "" : product.getName()).toLowerCase(Locale.ROOT);
        if (name.contains("5kg")) {
            return 5.0;
        }
        if (name.contains("1kg")) {
            return 1.0;
        }
        if (name.contains("1l")) {
            return 1.05;
        }
        String cat = (product.getCategory() == null ? "" : product.getCategory()).toLowerCase(Locale.ROOT);
        return switch (cat) {
            case "groceries" -> 1.3;
            case "household" -> 1.1;
            case "dairy", "drinks" -> 0.8;
            case "electronics" -> 0.75;
            default -> 0.45;
        };
    }

    /**
     * Estimates single-unit shelf volume in cubic decimeters (liters) using category cues.
     *
     * @param product product entity
     * @return estimated volume per unit in dm^3
     */
    public static double estimateUnitVolumeDm3(Product product) {
        if (product == null) {
            return 1.2;
        }
        String name = (product.getName() == null ? "" : product.getName()).toLowerCase(Locale.ROOT);
        if (name.contains("5kg")) {
            return 6.5;
        }
        String cat = (product.getCategory() == null ? "" : product.getCategory()).toLowerCase(Locale.ROOT);
        return switch (cat) {
            case "groceries", "household" -> 2.4;
            case "electronics" -> 2.1;
            case "bakery", "snacks" -> 1.8;
            default -> 1.3;
        };
    }

    private double roundTwo(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
