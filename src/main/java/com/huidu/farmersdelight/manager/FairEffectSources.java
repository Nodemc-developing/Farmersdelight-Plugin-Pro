package com.huidu.farmersdelight.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/** Rotates source opportunities before any audience lookup or particle allocation. */
final class FairEffectSources<K> {
    private static final class Seen {
        long tick;
        Seen(long tick) { this.tick = tick; }
    }
    private final LinkedHashMap<K, Seen> seen = new LinkedHashMap<>();
    private final ArrayList<K> rotation = new ArrayList<>();
    private final Set<K> admitted = new HashSet<>();
    private long epoch = Long.MIN_VALUE;
    private int cursor;

    synchronized boolean admit(K source, long tick, int opportunities) {
        if (epoch != tick) {
            epoch = tick;
            seen.entrySet().removeIf(entry -> tick - entry.getValue().tick > 80L);
            rotation.clear();
            for (K existing : seen.keySet()) rotation.add(existing);
            admitted.clear();
            int count = Math.min(Math.max(0, opportunities), rotation.size());
            for (int index = 0; index < count; index++) admitted.add(rotation.get((cursor + index) % rotation.size()));
            if (!rotation.isEmpty()) cursor = (cursor + count) % rotation.size();
            rotation.clear();
        }
        Seen previous = seen.get(source);
        if (previous == null) {
            seen.put(source, new Seen(tick));
            if (admitted.size() < opportunities) admitted.add(source);
        } else previous.tick = tick;
        return admitted.contains(source);
    }
}
