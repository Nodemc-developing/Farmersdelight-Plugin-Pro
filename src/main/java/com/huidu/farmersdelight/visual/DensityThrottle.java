package com.huidu.farmersdelight.visual;

import java.util.HashMap;
import java.util.Map;

public final class DensityThrottle<K> {
    private final Map<K, Long> sources = new HashMap<>();
    private long lastPrune;

    public static double rate(int density, int threshold, double minimumRate) {
        if (threshold <= 0 || density <= threshold) return 1.0D;
        double floor = Double.isFinite(minimumRate) ? Math.max(0D, Math.min(1D, minimumRate)) : 1D;
        return Math.max(floor, (double) threshold / Math.max(1, density));
    }

    synchronized double observe(K source, long now, int threshold, double minimumRate) {
        if (now - lastPrune > 200_000_000L) {
            sources.entrySet().removeIf(entry -> now - entry.getValue() > 4_000_000_000L);
            lastPrune = now;
        }
        sources.put(source, now);
        return rate(sources.size(), threshold, minimumRate);
    }
}
