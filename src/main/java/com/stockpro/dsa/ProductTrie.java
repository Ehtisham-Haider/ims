package com.stockpro.dsa;

import com.stockpro.domain.Product;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compressed Radix Trie supporting O(L) prefix-based live autocomplete across product names, tokens, and SKUs.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class ProductTrie {

    /**
     * Internal compressed trie node holding an edge label substring and child edges.
     */
    private static final class TrieNode {
        String edgeLabel;
        final Map<Character, TrieNode> children = new LinkedHashMap<>();
        final Map<Long, Product> matches = new LinkedHashMap<>();

        TrieNode(String edgeLabel) {
            this.edgeLabel = edgeLabel;
        }
    }

    private TrieNode root = new TrieNode("");

    /**
     * Creates an empty compressed product Trie.
     */
    public ProductTrie() {
    }

    /**
     * Rebuilds the compressed Trie from the supplied product collection.
     *
     * @param products catalog items to index
     */
    public synchronized void rebuild(Collection<Product> products) {
        root = new TrieNode("");
        if (products == null) {
            return;
        }
        for (Product p : products) {
            if (p.getName() != null && !p.getName().isBlank()) {
                insertKey(p.getName(), p);
                for (String token : p.getName().split("[\\s\\-_]+")) {
                    if (token.length() >= 2) {
                        insertKey(token, p);
                    }
                }
            }
            if (p.getSku() != null && !p.getSku().isBlank()) {
                insertKey(p.getSku(), p);
            }
        }
    }

    /**
     * Inserts a normalized search key mapped to a product into the compressed radix structure.
     *
     * @param rawKey index key (name, word token, or SKU)
     * @param product target product
     */
    public synchronized void insertKey(String rawKey, Product product) {
        if (rawKey == null || rawKey.isBlank() || product == null) {
            return;
        }
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        insertRecursive(root, key, product);
    }

    /**
     * Recursively splits compressed edges when needed and inserts the product reference.
     *
     * @param current current trie node
     * @param remaining remaining suffix to insert
     * @param product product associated with the full key
     */
    private void insertRecursive(TrieNode current, String remaining, Product product) {
        if (remaining.isEmpty()) {
            current.matches.put(product.getId(), product);
            return;
        }
        char first = remaining.charAt(0);
        TrieNode child = current.children.get(first);
        if (child == null) {
            TrieNode leaf = new TrieNode(remaining);
            leaf.matches.put(product.getId(), product);
            current.children.put(first, leaf);
            return;
        }

        String edge = child.edgeLabel;
        int common = commonPrefixLength(remaining, edge);

        if (common == edge.length()) {
            insertRecursive(child, remaining.substring(common), product);
        } else {
            // Split compressed edge into shared prefix and diverging branches.
            TrieNode splitNode = new TrieNode(edge.substring(0, common));
            current.children.put(first, splitNode);

            child.edgeLabel = edge.substring(common);
            splitNode.children.put(child.edgeLabel.charAt(0), child);

            if (common == remaining.length()) {
                splitNode.matches.put(product.getId(), product);
            } else {
                TrieNode newLeaf = new TrieNode(remaining.substring(common));
                newLeaf.matches.put(product.getId(), product);
                splitNode.children.put(newLeaf.edgeLabel.charAt(0), newLeaf);
            }
        }
    }

    /**
     * Returns up to {@code limit} unique products matching the supplied prefix in O(L + k) time.
     *
     * @param prefix prefix string typed by the user
     * @param limit maximum number of autocomplete suggestions
     * @return matching products
     */
    public synchronized List<Product> autocomplete(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        String remaining = prefix.trim().toLowerCase(Locale.ROOT);
        TrieNode current = root;

        while (!remaining.isEmpty()) {
            TrieNode child = current.children.get(remaining.charAt(0));
            if (child == null) {
                return List.of();
            }
            String edge = child.edgeLabel;
            int common = commonPrefixLength(remaining, edge);
            if (common == remaining.length()) {
                // Remaining query ended inside or exactly on this compressed edge.
                current = child;
                remaining = "";
            } else if (common == edge.length()) {
                current = child;
                remaining = remaining.substring(common);
            } else {
                return List.of();
            }
        }

        Map<Long, Product> collected = new LinkedHashMap<>();
        collectSubtree(current, collected, limit);
        return new ArrayList<>(collected.values());
    }

    /**
     * Collects distinct products from a matched subtree up to {@code limit}.
     *
     * @param node subtree root
     * @param out deduplicated map of results
     * @param limit maximum items to collect
     */
    private void collectSubtree(TrieNode node, Map<Long, Product> out, int limit) {
        for (Product p : node.matches.values()) {
            out.putIfAbsent(p.getId(), p);
            if (out.size() >= limit) {
                return;
            }
        }
        for (TrieNode child : node.children.values()) {
            collectSubtree(child, out, limit);
            if (out.size() >= limit) {
                return;
            }
        }
    }

    /**
     * Computes length of shared prefix between two strings.
     *
     * @param a first string
     * @param b second string
     * @return shared character count
     */
    private int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }
}
