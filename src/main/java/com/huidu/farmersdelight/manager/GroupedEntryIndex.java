package com.huidu.farmersdelight.manager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Replacement-aware secondary indexes. All returned collections are detached snapshots. */
final class GroupedEntryIndex<K, G, D, V> {
    private record Entry<G, D, V>(G group, Set<D> dependencies, V value) { }
    private final Map<K, Entry<G, D, V>> entries = new HashMap<>();
    private final Map<G, Map<K, V>> groups = new HashMap<>();
    private final Map<D, Set<K>> dependencies = new HashMap<>();

    synchronized void put(K key, G group, Set<D> dependsOn, V value) {
        Entry<G, D, V> previous = entries.get(key);
        if (previous != null) remove(key, previous.value());
        Set<D> frozen = Set.copyOf(dependsOn);
        entries.put(key, new Entry<>(group, frozen, value));
        groups.computeIfAbsent(group, ignored -> new HashMap<>()).put(key, value);
        frozen.forEach(dependency -> dependencies.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(key));
    }

    synchronized boolean remove(K key, V expected) {
        Entry<G, D, V> entry = entries.get(key);
        if (entry == null || entry.value() != expected) return false;
        entries.remove(key);
        Map<K, V> grouped = groups.get(entry.group());
        grouped.remove(key);
        if (grouped.isEmpty()) groups.remove(entry.group());
        for (D dependency : entry.dependencies()) {
            Set<K> keys = dependencies.get(dependency);
            keys.remove(key);
            if (keys.isEmpty()) dependencies.remove(dependency);
        }
        return true;
    }

    synchronized Map<K, V> group(G group) { return Map.copyOf(groups.getOrDefault(group, Map.of())); }
    synchronized int groupSize(G group) { return groups.getOrDefault(group, Map.of()).size(); }
    synchronized Set<G> groups() { return Set.copyOf(groups.keySet()); }
    synchronized Map<K, V> affected(D dependency) {
        Map<K, V> result = new HashMap<>();
        for (K key : dependencies.getOrDefault(dependency, Set.of())) result.put(key, entries.get(key).value());
        return result;
    }
    synchronized void clear() { entries.clear(); groups.clear(); dependencies.clear(); }
}
