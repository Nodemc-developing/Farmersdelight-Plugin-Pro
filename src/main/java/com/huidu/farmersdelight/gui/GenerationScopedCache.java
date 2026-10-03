package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Old renders retain their scope and map; invalidation never lets them replace a new generation's value. */
final class GenerationScopedCache<K, V> {
    private record ScopedKey<K>(long generation, K key) { }
    private static final int MAX_ENTRIES = 4096;
    private volatile Map<ScopedKey<K>, V> entries = new ConcurrentHashMap<>();

    V get(K key) { return entries.get(scoped(key)); }

    void put(K key, V value) {
        ScopedKey<K> scoped = scoped(key);
        writable(scoped).put(scoped, value);
    }

    V computeIfAbsent(K key, Function<K, V> loader) {
        ScopedKey<K> scoped = scoped(key);
        Map<ScopedKey<K>, V> current = entries;
        V found = current.get(scoped);
        if (found != null) return found;
        return writable(scoped).computeIfAbsent(scoped, ignored -> loader.apply(key));
    }

    void clear() { entries = new ConcurrentHashMap<>(); }

    private ScopedKey<K> scoped(K key) { return new ScopedKey<>(RuntimeSnapshotPublication.generation(), key); }

    private Map<ScopedKey<K>, V> writable(ScopedKey<K> key) {
        Map<ScopedKey<K>, V> current = entries;
        if (current.size() < MAX_ENTRIES || current.containsKey(key)) return current;
        synchronized (this) {
            if (entries == current) entries = new ConcurrentHashMap<>();
            return entries;
        }
    }
}
