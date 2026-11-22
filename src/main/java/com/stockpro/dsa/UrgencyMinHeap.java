package com.stockpro.dsa;

import com.stockpro.domain.Product;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Min-Heap ({@link PriorityQueue}) tracking and surfacing the most urgent low-stock and soonest-expiring
 * inventory items in O(log n) heap extraction time.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
 */
public class UrgencyMinHeap {

    /**
     * Ranked urgency entry ordered ascending so lowest score represents highest operational urgency.
     *
     * @param product target inventory item
     * @param urgencyScore computed urgency priority score (lower is more urgent)
     * @param reason concise explanation of why the item is flagged
     * @author Syed Ehtisham Haider Bukhari, Faheen Ahmad Khan
     */
    public record UrgencyEntry(Product product, double urgencyScore, String reason)
            implements Comparable<UrgencyEntry> {
        @Override
        public int compareTo(UrgencyEntry other) {
            return Double.compare(this.urgencyScore, other.urgencyScore);
        }
    }

    private final PriorityQueue<UrgencyEntry> minHeap = new PriorityQueue<>();

    /**
     * Creates an empty urgency Min-Heap.
     */
    public UrgencyMinHeap() {
    }

    /**
     * Rebuilds the Min-Heap from current store products.
     *
     * @param products catalog items to score and heapify
     */
    public synchronized void rebuild(Collection<Product> products) {
        minHeap.clear();
        if (products == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        for (Product p : products) {
            UrgencyEntry entry = scoreProduct(p, today);
            if (entry != null) {
                minHeap.offer(entry);
            }
        }
    }

    /**
     * Returns up to {@code count} highest-urgency items without mutating the internal heap state.
     *
     * @param count maximum items to retrieve
     * @return ordered list from most urgent to least urgent
     */
    public synchronized List<UrgencyEntry> topCritical(int count) {
        if (count <= 0 || minHeap.isEmpty()) {
            return List.of();
        }
        PriorityQueue<UrgencyEntry> copy = new PriorityQueue<>(minHeap);
        List<UrgencyEntry> result = new ArrayList<>();
        while (!copy.isEmpty() && result.size() < count) {
            result.add(copy.poll());
        }
        return result;
    }

    /**
     * Returns the single most critical item at the heap root, if any.
     *
     * @return top urgency entry, or {@code null} when no urgent items exist
     */
    public synchronized UrgencyEntry peekMostUrgent() {
        return minHeap.peek();
    }

    /**
     * Returns number of urgent entries currently queued.
     *
     * @return heap size
     */
    public synchronized int size() {
        return minHeap.size();
    }

    /**
     * Computes a deterministic urgency score combining stockout ratio and near-term expiry days.
     *
     * @param p product to evaluate
     * @param today reference date
     * @return scored {@link UrgencyEntry}, or {@code null} if item has healthy stock and no near expiry
     */
    private UrgencyEntry scoreProduct(Product p, LocalDate today) {
        double score = Double.MAX_VALUE;
        StringBuilder reason = new StringBuilder();

        int threshold = Math.max(1, p.getLowStockThreshold());
        if (p.getQuantity() <= 0) {
            score = 0.0;
            reason.append("Out of Stock");
        } else if (p.getQuantity() <= threshold) {
            score = 10.0 + ((double) p.getQuantity() / threshold) * 20.0;
            reason.append("Low Stock (").append(p.getQuantity()).append("/").append(threshold).append(")");
        }

        if (p.getExpiryDate() != null) {
            long daysLeft = ChronoUnit.DAYS.between(today, p.getExpiryDate());
            if (daysLeft <= 30) {
                double expScore = Math.max(0.5, daysLeft + 2.0);
                if (expScore < score) {
                    score = expScore;
                }
                if (!reason.isEmpty()) {
                    reason.append(" • ");
                }
                reason.append(daysLeft < 0 ? "Expired" : "Expires in " + daysLeft + "d");
            }
        }

        if (score == Double.MAX_VALUE) {
            return null;
        }
        return new UrgencyEntry(p, score, reason.toString());
    }
}
