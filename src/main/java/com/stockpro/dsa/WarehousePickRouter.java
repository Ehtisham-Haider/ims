package com.stockpro.dsa;

import com.stockpro.domain.PickRoutePlan;
import com.stockpro.domain.PickRouteStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warehouse floorgraph pathfinder implementing Dijkstra's shortest path across aisles {@code A..E},
 * front/rear cross-aisles, rows {@code 1..4}, and columns {@code 01..12}, plus 2-opt tour refinement
 * for multi-item smart picking lists.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class WarehousePickRouter {

    /** Graph origin representing the dispatch packing station near front of Aisle A. */
    public static final String PACK_STATION = "PACK_STATION";

    private static final int AISLE_COUNT = 5;
    private static final int ROW_COUNT = 4;
    private static final int COL_COUNT = 12;
    private static final double AISLE_SPACING_METERS = 6.0;
    private static final double BAY_STEP_METERS = 0.4;
    private static final double FRONT_ENTRY_OFFSET = 1.6;
    private static final double REAR_Y_METERS = 21.0;
    private static final Pattern STRICT_BIN = Pattern.compile("^([A-E])([1-4])-(\\d{2})$");

    private final Map<String, List<GraphEdge>> adjacency = new HashMap<>();

    /**
     * Target pick request descriptor passed to {@link WarehousePickRouter#planShortestPickTour(List)}.
     *
     * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
     * @param binCode normalized shelf bin code
     * @param productId product database id
     * @param sku product SKU code
     * @param productName product display name
     * @param pickQuantity units requested
     */
    public record PickTarget(
            String binCode,
            long productId,
            String sku,
            String productName,
            int pickQuantity
    ) {
    }

    private record GraphEdge(String targetNode, double weightMeters) {
    }

    private record QueueNode(String nodeId, double distance) {
    }

    /**
     * Builds the connected 2D warehouse topology across all 240 shelf bins and cross-aisle junctions.
     */
    public WarehousePickRouter() {
        buildFloorGraph();
    }

    private void buildFloorGraph() {
        for (int a = 0; a < AISLE_COUNT; a++) {
            String aisle = String.valueOf((char) ('A' + a));
            String frontJunction = "F_" + aisle;
            String rearJunction = "R_" + aisle;

            if (a > 0) {
                String prevAisle = String.valueOf((char) ('A' + (a - 1)));
                addBidirectionalEdge(frontJunction, "F_" + prevAisle, AISLE_SPACING_METERS);
                addBidirectionalEdge(rearJunction, "R_" + prevAisle, AISLE_SPACING_METERS);
            }

            List<String> aisleBins = new ArrayList<>();
            for (int r = 1; r <= ROW_COUNT; r++) {
                for (int c = 1; c <= COL_COUNT; c++) {
                    aisleBins.add(String.format(Locale.ROOT, "%s%d-%02d", aisle, r, c));
                }
            }

            for (int idx = 0; idx < aisleBins.size(); idx++) {
                String bin = aisleBins.get(idx);
                if (idx == 0) {
                    addBidirectionalEdge(frontJunction, bin, FRONT_ENTRY_OFFSET);
                } else {
                    addBidirectionalEdge(aisleBins.get(idx - 1), bin, BAY_STEP_METERS);
                }
                if (idx == aisleBins.size() - 1) {
                    double tailY = FRONT_ENTRY_OFFSET + idx * BAY_STEP_METERS;
                    addBidirectionalEdge(bin, rearJunction, Math.max(0.8, REAR_Y_METERS - tailY));
                }
            }
        }
        addBidirectionalEdge(PACK_STATION, "F_A", 1.0);
    }

    private void addBidirectionalEdge(String from, String to, double distance) {
        adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(new GraphEdge(to, distance));
        adjacency.computeIfAbsent(to, k -> new ArrayList<>()).add(new GraphEdge(from, distance));
    }

    /**
     * Computes shortest walkable distance in meters between two nodes using Dijkstra's algorithm.
     *
     * @param sourceNode origin bin code or {@link #PACK_STATION}
     * @param targetNode destination bin code or {@link #PACK_STATION}
     * @return shortest distance in meters
     */
    public double shortestDistanceMeters(String sourceNode, String targetNode) {
        String start = normalizeNode(sourceNode);
        String goal = normalizeNode(targetNode);
        if (start.equals(goal)) {
            return 0.0;
        }
        Map<String, Double> dist = computeShortestMap(start);
        return dist.getOrDefault(goal, 0.0);
    }

    /**
     * Runs Dijkstra single-source shortest paths from {@code startNode} across all reachable floor nodes.
     *
     * @param startNode starting bin code or {@link #PACK_STATION}
     * @return map of node id to minimum distance in meters
     */
    public Map<String, Double> computeShortestMap(String startNode) {
        String root = normalizeNode(startNode);
        Map<String, Double> dist = new HashMap<>();
        PriorityQueue<QueueNode> pq = new PriorityQueue<>(Comparator.comparingDouble(QueueNode::distance));
        dist.put(root, 0.0);
        pq.offer(new QueueNode(root, 0.0));

        while (!pq.isEmpty()) {
            QueueNode cur = pq.poll();
            if (cur.distance() > dist.getOrDefault(cur.nodeId(), Double.POSITIVE_INFINITY)) {
                continue;
            }
            List<GraphEdge> edges = adjacency.getOrDefault(cur.nodeId(), List.of());
            for (GraphEdge edge : edges) {
                double candidate = cur.distance() + edge.weightMeters();
                if (candidate < dist.getOrDefault(edge.targetNode(), Double.POSITIVE_INFINITY)) {
                    dist.put(edge.targetNode(), candidate);
                    pq.offer(new QueueNode(edge.targetNode(), candidate));
                }
            }
        }
        return dist;
    }

    /**
     * Plans a minimal-backtracking warehouse pick tour starting and returning at {@link #PACK_STATION}.
     *
     * @param targets unordered picking items requested
     * @return optimized {@link PickRoutePlan} with baseline savings comparison
     */
    public PickRoutePlan planShortestPickTour(List<PickTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return new PickRoutePlan(List.of(), 0.0, 0.0, 0.0, "Add items to build a shortest pick route.");
        }

        List<PickTarget> normalized = new ArrayList<>();
        for (PickTarget target : targets) {
            if (target == null) {
                continue;
            }
            normalized.add(new PickTarget(
                    normalizeNode(target.binCode()),
                    target.productId(),
                    target.sku(),
                    target.productName(),
                    Math.max(1, target.pickQuantity())
            ));
        }
        if (normalized.isEmpty()) {
            return new PickRoutePlan(List.of(), 0.0, 0.0, 0.0, "No valid pick targets provided.");
        }

        Map<String, Map<String, Double>> distanceCache = new HashMap<>();
        distanceCache.put(PACK_STATION, computeShortestMap(PACK_STATION));
        for (PickTarget t : normalized) {
            distanceCache.computeIfAbsent(t.binCode(), this::computeShortestMap);
        }

        double baselineRoundTrip = computeTourLength(normalized, distanceCache);

        // Nearest-neighbor constructive tour using exact Dijkstra floor distances
        List<PickTarget> remaining = new ArrayList<>(normalized);
        List<PickTarget> tour = new ArrayList<>(normalized.size());
        String currentNode = PACK_STATION;
        while (!remaining.isEmpty()) {
            Map<String, Double> fromCur = distanceCache.get(currentNode);
            int bestIdx = 0;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                double d = fromCur.getOrDefault(remaining.get(i).binCode(), Double.POSITIVE_INFINITY);
                if (d < bestDist) {
                    bestDist = d;
                    bestIdx = i;
                }
            }
            PickTarget chosen = remaining.remove(bestIdx);
            tour.add(chosen);
            currentNode = chosen.binCode();
        }

        // 2-opt local optimization to eliminate backtracking across aisles
        if (tour.size() >= 3) {
            boolean improved = true;
            while (improved) {
                improved = false;
                double currentLen = computeTourLength(tour, distanceCache);
                for (int i = 0; i < tour.size() - 1; i++) {
                    for (int j = i + 1; j < tour.size(); j++) {
                        List<PickTarget> candidate = new ArrayList<>(tour);
                        Collections.reverse(candidate.subList(i, j + 1));
                        double candLen = computeTourLength(candidate, distanceCache);
                        if (candLen + 1e-6 < currentLen) {
                            tour = candidate;
                            improved = true;
                            break;
                        }
                    }
                    if (improved) {
                        break;
                    }
                }
            }
        }

        List<PickRouteStep> steps = new ArrayList<>(tour.size());
        double cumulative = 0.0;
        String prevNode = PACK_STATION;
        for (int i = 0; i < tour.size(); i++) {
            PickTarget item = tour.get(i);
            double leg = distanceCache.get(prevNode).getOrDefault(item.binCode(), 0.0);
            cumulative += leg;
            Matcher m = STRICT_BIN.matcher(item.binCode());
            String aisle = m.matches() ? m.group(1) : "A";
            int row = m.matches() ? Integer.parseInt(m.group(2)) : 1;
            int col = m.matches() ? Integer.parseInt(m.group(3)) : 1;
            String direction = buildDirectionHint(prevNode, item.binCode(), aisle, row, col);
            steps.add(new PickRouteStep(
                    i + 1,
                    item.binCode(),
                    aisle,
                    row,
                    col,
                    item.productId(),
                    item.sku(),
                    item.productName(),
                    item.pickQuantity(),
                    roundOneDecimal(leg),
                    roundOneDecimal(cumulative),
                    direction
            ));
            prevNode = item.binCode();
        }
        double returnLeg = distanceCache.get(prevNode).getOrDefault(PACK_STATION, 0.0);
        double optimizedTotal = roundOneDecimal(cumulative + returnLeg);
        double baselineTotal = roundOneDecimal(baselineRoundTrip);
        double savedPct = baselineTotal <= 0.01
                ? 0.0
                : Math.max(0.0, roundOneDecimal(((baselineTotal - optimizedTotal) * 100.0) / baselineTotal));

        String summary = String.format(Locale.US,
                "%d stop(s) • %.1fm optimal walk (baseline %.1fm, saved %.1f%%)",
                steps.size(), optimizedTotal, baselineTotal, savedPct);
        return new PickRoutePlan(steps, optimizedTotal, baselineTotal, savedPct, summary);
    }

    private double computeTourLength(List<PickTarget> order, Map<String, Map<String, Double>> distanceCache) {
        if (order.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        String prev = PACK_STATION;
        for (PickTarget target : order) {
            total += distanceCache.get(prev).getOrDefault(target.binCode(), 0.0);
            prev = target.binCode();
        }
        total += distanceCache.get(prev).getOrDefault(PACK_STATION, 0.0);
        return total;
    }

    private String normalizeNode(String rawNode) {
        if (rawNode == null || rawNode.isBlank() || PACK_STATION.equalsIgnoreCase(rawNode.trim())) {
            return PACK_STATION;
        }
        String clean = rawNode.trim().toUpperCase(Locale.ROOT);
        if (adjacency.containsKey(clean)) {
            return clean;
        }
        return "A1-01";
    }

    private String buildDirectionHint(String prevNode, String targetBin, String aisle, int row, int col) {
        if (PACK_STATION.equals(prevNode)) {
            return String.format(Locale.ROOT, "Leave Packing Station -> Enter Aisle %s, Row %d, Bay %02d (%s)",
                    aisle, row, col, targetBin);
        }
        String prevAisle = prevNode.substring(0, 1);
        if (prevAisle.equals(aisle)) {
            return String.format(Locale.ROOT, "Continue along Aisle %s -> Bay %s (Row %d, Col %02d)",
                    aisle, targetBin, row, col);
        }
        return String.format(Locale.ROOT, "Cross to Aisle %s via nearest cross-aisle -> %s",
                aisle, targetBin);
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
