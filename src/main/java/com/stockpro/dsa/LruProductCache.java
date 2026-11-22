package com.stockpro.dsa;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe bounded O(1) Least-Recently-Used (LRU) cache tracking hit rates
 * and eviction metrics for hot catalog SKU lookups at mega-scale.
 *
 * @param <K> lookup key type (for example normalized SKU or barcode)
 * @param <V> cached value type
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class LruProductCache<K, V> {

    private final int capacity;
    private final Map<K, V> backingMap;
    private long hits;
    private long misses;
    private long evictions;

    /**
     * Constructs an access-ordered LRU cache bounded by the supplied capacity.
     *
     * @param capacity maximum cached entries (minimum {@code 1})
     */
    public LruProductCache(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.backingMap = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                boolean evict = size() > LruProductCache.this.capacity;
                if (evict) {
                    evictions++;
                }
                return evict;
            }
        };
    }

    /**
     * Fetches a cached entry in O(1) time, promoting it to most-recently-used.
     *
     * @param key lookup key
     * @return {@link Optional} containing cached value if present
     */
    public synchronized Optional<V> get(K key) {
        if (key == null) {
            misses++;
            return Optional.empty();
        }
        V val = backingMap.get(key);
        if (val != null) {
            hits++;
            return Optional.of(val);
        }
        misses++;
        return Optional.empty();
    }

    /**
     * Inserts or updates an entry in O(1) time, evicting the oldest entry if full.
     *
     * @param key cache key (non-null)
     * @param value cached value (non-null)
     */
    public synchronized void put(K key, V value) {
        if (key == null || value == null) {
            return;
        }
        backingMap.put(key, value);
    }

    /**
     * Removes a single stale entry from the cache.
     *
     * @param key cache key to invalidate
     */
    public synchronized void invalidate(K key) {
        if (key != null) {
            backingMap.remove(key);
        }
    }

    /**
     * Clears all cached entries and resets hit/miss/eviction counters.
     */
    public synchronized void clear() {
        backingMap.clear();
        hits = 0L;
        misses = 0L;
        evictions = 0L;
    }

    /**
     * Returns current number of cached entries.
     *
     * @return entry count
     */
    public synchronized int size() {
        return backingMap.size();
    }

    /**
     * Returns configured maximum capacity.
     *
     * @return cache capacity limit
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Returns total cache hits recorded.
     *
     * @return cache hit count
     */
    public synchronized long getHits() {
        return hits;
    }

    /**
     * Returns total cache misses recorded.
     *
     * @return cache miss count
     */
    public synchronized long getMisses() {
        return misses;
    }

    /**
     * Returns total eldest entries evicted due to capacity bounds.
     *
     * @return eviction count
     */
    public synchronized long getEvictions() {
        return evictions;
    }

    /**
     * Returns cache hit rate percentage in the range {@code 0.0..100.0}.
     *
     * @return hit rate percentage
     */
    public synchronized double getHitRatePercent() {
        long total = hits + misses;
        return total == 0L ? 0.0 : Math.round(((hits * 100.0) / total) * 100.0) / 100.0;
    }
}
