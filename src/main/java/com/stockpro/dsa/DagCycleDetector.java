package com.stockpro.dsa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directed Acyclic Graph (DAG) validator and topological sorter used to ensure Bill-of-Materials
 * bundle hierarchies never introduce self-loops or circular component dependencies.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public final class DagCycleDetector {

    /**
     * Prevents unnecessary instantiation of stateless graph utilities.
     */
    private DagCycleDetector() {
    }

    /**
     * Checks whether adding directed edge {@code parentId -> childId} to {@code adjacency}
     * introduces a cycle in the bundle Bill-of-Materials graph.
     *
     * @param adjacency current adjacency list mapping bundle IDs to child component IDs
     * @param parentId proposed bundle/kit parent ID
     * @param childId proposed component child ID
     * @return {@code true} if adding the edge creates a circular dependency
     */
    public static boolean wouldCreateCycle(
            Map<Long, List<Long>> adjacency,
            long parentId,
            long childId) {
        if (parentId == childId) {
            return true;
        }
        if (adjacency == null || adjacency.isEmpty()) {
            return false;
        }
        // If parentId is reachable starting from childId, adding parentId -> childId closes a cycle.
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(childId);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            if (current == parentId) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            for (Long next : adjacency.getOrDefault(current, List.of())) {
                if (next != null && !visited.contains(next)) {
                    stack.push(next);
                }
            }
        }
        return false;
    }

    /**
     * Computes a dependency-safe topological ordering over a valid DAG.
     *
     * @param adjacency adjacency list mapping bundle IDs to component IDs
     * @return ordered list of node IDs from dependencies toward top-level bundles
     * @throws IllegalStateException if a cycle already exists in the supplied graph
     */
    public static List<Long> topologicalOrder(Map<Long, List<Long>> adjacency) {
        if (adjacency == null || adjacency.isEmpty()) {
            return List.of();
        }
        Set<Long> nodes = new LinkedHashSet<>();
        for (Map.Entry<Long, List<Long>> e : adjacency.entrySet()) {
            if (e.getKey() != null) {
                nodes.add(e.getKey());
            }
            if (e.getValue() != null) {
                for (Long v : e.getValue()) {
                    if (v != null) {
                        nodes.add(v);
                    }
                }
            }
        }
        Map<Long, Integer> state = new HashMap<>();
        List<Long> order = new ArrayList<>();
        for (Long node : nodes) {
            if (state.getOrDefault(node, 0) == 0) {
                dfsTopo(node, adjacency, state, order);
            }
        }
        Collections.reverse(order);
        return order;
    }

    private static void dfsTopo(
            long node,
            Map<Long, List<Long>> adjacency,
            Map<Long, Integer> state,
            List<Long> postOrder) {
        state.put(node, 1);
        for (Long next : adjacency.getOrDefault(node, List.of())) {
            if (next == null) {
                continue;
            }
            int childState = state.getOrDefault(next, 0);
            if (childState == 1) {
                throw new IllegalStateException("Circular BOM cycle detected involving product ID " + next);
            }
            if (childState == 0) {
                dfsTopo(next, adjacency, state, postOrder);
            }
        }
        state.put(node, 2);
        postOrder.add(node);
    }
}
