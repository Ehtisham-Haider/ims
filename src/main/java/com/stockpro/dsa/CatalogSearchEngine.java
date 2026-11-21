package com.stockpro.dsa;

import com.stockpro.domain.Product;
import com.stockpro.repository.ProductRepository;
import com.stockpro.service.InventoryEventBus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Unified DS&amp;A search facade combining {@link SkuBarcodeIndex}, {@link ProductTrie},
 * {@link BkTree}, {@link UrgencyMinHeap}, and {@link RangeIndexTree}.
 *
 * @author Ehtisham Haider, Syed Ahmed
 */
public class CatalogSearchEngine implements AutoCloseable {
    private final ProductRepository productRepository;
    private final InventoryEventBus eventBus;
    private final Consumer<String> refreshListener = topic -> rebuildIndices();
    private final SkuBarcodeIndex skuBarcodeIndex = new SkuBarcodeIndex();
    private final ProductTrie productTrie = new ProductTrie();
    private final BkTree bkTree = new BkTree();
    private final UrgencyMinHeap urgencyMinHeap = new UrgencyMinHeap();
    private final RangeIndexTree rangeIndexTree = new RangeIndexTree();

    /**
     * Search output pairing matched items with measured lookup latency in milliseconds.
     *
     * @param results matched products
     * @param latencyMs elapsed query duration in milliseconds
     * @param strategyUsed label describing whether HashMap, Trie, or BK-Tree resolved the query
     * @author Ehtisham Haider, Syed Ahmed
     */
    public record SearchResult(List<Product> results, double latencyMs, String strategyUsed) {
    }

    /**
     * Creates a catalog search engine wired to a product repository and event bus.
     *
     * @param productRepository product persistence repository
     * @param eventBus observer bus triggering index rebuilds on updates
     */
    public CatalogSearchEngine(ProductRepository productRepository, InventoryEventBus eventBus) {
        this.productRepository = productRepository;
        this.eventBus = eventBus;
        if (eventBus != null) {
            eventBus.subscribe(refreshListener);
        }
        rebuildIndices();
    }

    /**
     * Detaches this search engine's listener from the shared {@link InventoryEventBus}.
     */
    @Override
    public void close() {
        if (eventBus != null) {
            eventBus.unsubscribe(refreshListener);
        }
    }

    /**
     * Re-indexes all 5 underlying data structures from active SQLite products.
     */
    public synchronized void rebuildIndices() {
        List<Product> products = productRepository.findAll(false);
        skuBarcodeIndex.rebuild(products);
        productTrie.rebuild(products);
        bkTree.rebuild(products);
        urgencyMinHeap.rebuild(products);
        rangeIndexTree.rebuild(products);
    }

    /**
     * Executes smart tiered search: exact O(1) SKU/barcode match &rarr; O(L) Trie autocomplete &rarr;
     * BK-Tree Levenshtein typo fallback.
     *
     * @param query raw user query string
     * @param maxResults max results to return
     * @return {@link SearchResult} with hits, latency telemetry, and algorithm label
     */
    public SearchResult search(String query, int maxResults) {
        long startNs = System.nanoTime();
        if (query == null || query.isBlank()) {
            double ms = (System.nanoTime() - startNs) / 1_000_000.0;
            return new SearchResult(List.of(), ms, "Idle");
        }

        // 1. O(1) HashMap SKU or Barcode match
        var exactHit = skuBarcodeIndex.findByBarcode(query);
        if (exactHit.isPresent()) {
            double ms = (System.nanoTime() - startNs) / 1_000_000.0;
            return new SearchResult(List.of(exactHit.get()), ms, "HashMap O(1)");
        }

        // 2. O(L) Compressed Trie prefix autocomplete
        List<Product> trieHits = productTrie.autocomplete(query, maxResults);
        if (!trieHits.isEmpty()) {
            double ms = (System.nanoTime() - startNs) / 1_000_000.0;
            return new SearchResult(trieHits, ms, "Compressed Trie O(L)");
        }

        // 3. BK-Tree Levenshtein typo-tolerant search (max edit distance 2)
        List<Product> fuzzyHits = bkTree.searchFuzzy(query, 2, maxResults);
        double ms = (System.nanoTime() - startNs) / 1_000_000.0;
        if (!fuzzyHits.isEmpty()) {
            return new SearchResult(fuzzyHits, ms, "BK-Tree + Levenshtein");
        }

        // 4. Fallback substring scan across combined index if query has longer edit distance
        Map<Long, Product> merged = new LinkedHashMap<>();
        for (Product p : productTrie.autocomplete(query.substring(0, Math.min(2, query.length())), maxResults)) {
            merged.put(p.getId(), p);
        }
        return new SearchResult(new ArrayList<>(merged.values()), ms, "Prefix Fallback");
    }

    /**
     * Returns the O(1) SKU/barcode HashMap index.
     *
     * @return {@link SkuBarcodeIndex}
     */
    public SkuBarcodeIndex getSkuBarcodeIndex() {
        return skuBarcodeIndex;
    }

    /**
     * Returns the Compressed Trie prefix index.
     *
     * @return {@link ProductTrie}
     */
    public ProductTrie getProductTrie() {
        return productTrie;
    }

    /**
     * Returns the BK-Tree fuzzy typo index.
     *
     * @return {@link BkTree}
     */
    public BkTree getBkTree() {
        return bkTree;
    }

    /**
     * Returns the Min-Heap urgency tracker.
     *
     * @return {@link UrgencyMinHeap}
     */
    public UrgencyMinHeap getUrgencyMinHeap() {
        return urgencyMinHeap;
    }

    /**
     * Returns the Red-Black Tree range query index.
     *
     * @return {@link RangeIndexTree}
     */
    public RangeIndexTree getRangeIndexTree() {
        return rangeIndexTree;
    }
}
