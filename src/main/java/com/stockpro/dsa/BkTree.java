package com.stockpro.dsa;

import com.stockpro.domain.Product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Burkhard-Keller Tree (BK-Tree) using Levenshtein edit distance for typo-tolerant product lookup
 * (for example, resolving {@code "keybord"} to {@code "Keyboard"}).
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class BkTree {

    /**
     * BK-Tree node storing a normalized indexed word and matching products keyed by edit distance.
     */
    private static final class Node {
        final String term;
        final List<Product> products = new ArrayList<>();
        final Map<Integer, Node> children = new HashMap<>();

        Node(String term, Product product) {
            this.term = term;
            this.products.add(product);
        }
    }

    /**
     * Fuzzy match paired with its minimum edit distance to the query.
     *
     * @param product matched product
     * @param distance Levenshtein distance
     */
    private record ScoredMatch(Product product, int distance) {
    }

    private Node root;

    /**
     * Creates an empty BK-Tree fuzzy search index.
     */
    public BkTree() {
    }

    /**
     * Rebuilds the BK-Tree from product full names and individual word tokens.
     *
     * @param products catalog items to index
     */
    public synchronized void rebuild(Collection<Product> products) {
        root = null;
        if (products == null) {
            return;
        }
        for (Product p : products) {
            if (p.getName() == null || p.getName().isBlank()) {
                continue;
            }
            insertTerm(p.getName().trim().toLowerCase(Locale.ROOT), p);
            for (String token : p.getName().trim().toLowerCase(Locale.ROOT).split("[\\s\\-_]+")) {
                if (token.length() >= 3) {
                    insertTerm(token, p);
                }
            }
        }
    }

    /**
     * Inserts a normalized term and product reference into the BK-Tree.
     *
     * @param term normalized term
     * @param product matching product
     */
    public synchronized void insertTerm(String term, Product product) {
        if (term == null || term.isBlank() || product == null) {
            return;
        }
        if (root == null) {
            root = new Node(term, product);
            return;
        }
        Node current = root;
        while (true) {
            int dist = levenshtein(current.term, term);
            if (dist == 0) {
                if (!current.products.contains(product)) {
                    current.products.add(product);
                }
                return;
            }
            Node next = current.children.get(dist);
            if (next == null) {
                current.children.put(dist, new Node(term, product));
                return;
            }
            current = next;
        }
    }

    /**
     * Queries the BK-Tree for products within {@code maxDistance} Levenshtein edits, ordered closest first.
     *
     * @param rawQuery user search string (possibly containing typos)
     * @param maxDistance maximum allowed edit distance
     * @param limit maximum results to return
     * @return fuzzy-matched products sorted by ascending edit distance
     */
    public synchronized List<Product> searchFuzzy(String rawQuery, int maxDistance, int limit) {
        if (root == null || rawQuery == null || rawQuery.isBlank() || maxDistance < 0 || limit <= 0) {
            return List.of();
        }
        String query = rawQuery.trim().toLowerCase(Locale.ROOT);
        Map<Long, ScoredMatch> bestById = new LinkedHashMap<>();
        searchRecursive(root, query, maxDistance, bestById);

        return bestById.values().stream()
                .sorted(Comparator.comparingInt(ScoredMatch::distance)
                        .thenComparing(m -> m.product().getName()))
                .limit(limit)
                .map(ScoredMatch::product)
                .toList();
    }

    /**
     * Prunes child edges outside the triangle inequality band {@code [d - maxDistance, d + maxDistance]}.
     *
     * @param node current BK-Tree node
     * @param query normalized query string
     * @param maxDistance allowed edit radius
     * @param bestById accumulated closest matches keyed by product ID
     */
    private void searchRecursive(Node node, String query, int maxDistance, Map<Long, ScoredMatch> bestById) {
        int dist = levenshtein(node.term, query);
        if (dist <= maxDistance) {
            for (Product p : node.products) {
                ScoredMatch prev = bestById.get(p.getId());
                if (prev == null || dist < prev.distance()) {
                    bestById.put(p.getId(), new ScoredMatch(p, dist));
                }
            }
        }
        int low = Math.max(1, dist - maxDistance);
        int high = dist + maxDistance;
        for (Map.Entry<Integer, Node> entry : node.children.entrySet()) {
            int edge = entry.getKey();
            if (edge >= low && edge <= high) {
                searchRecursive(entry.getValue(), query, maxDistance, bestById);
            }
        }
    }

    /**
     * Computes standard dynamic-programming Levenshtein edit distance between two strings.
     *
     * @param a first string
     * @param b second string
     * @return minimum single-character insertions, deletions, or substitutions
     */
    public static int levenshtein(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int[] prev = new int[lenB + 1];
        int[] curr = new int[lenB + 1];

        for (int j = 0; j <= lenB; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= lenA; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lenB; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[lenB];
    }
}
