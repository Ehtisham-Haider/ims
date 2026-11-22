package com.stockpro.domain;

import java.util.List;

/**
 * Complete shortest-path picking route computed by Dijkstra warehouse routing
 * together with baseline comparison savings.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 * @param steps ordered list of picking stops starting from the packing station
 * @param totalDistanceMeters optimized round-trip walking distance in meters
 * @param baselineUnoptimizedMeters unoptimized sequential walking distance in meters
 * @param distanceSavedPercent percentage reduction vs unoptimized sequential walk
 * @param summary human-readable tour summary
 */
public record PickRoutePlan(
        List<PickRouteStep> steps,
        double totalDistanceMeters,
        double baselineUnoptimizedMeters,
        double distanceSavedPercent,
        String summary
) {
}
