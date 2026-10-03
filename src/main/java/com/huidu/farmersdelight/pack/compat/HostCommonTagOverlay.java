package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loading-scoped common groups; native definitions and later host writes retain their ownership. */
final class HostCommonTagOverlay implements AutoCloseable {
    record Members(List<UniqueKey> vanilla, List<UniqueKey> custom) {
        Members { vanilla = List.copyOf(vanilla); custom = List.copyOf(custom); }
    }
    private record Owned<T>(T previous, T installed) { }
    private final Map<Key, List<UniqueKey>> vanillaTags;
    private final Map<Key, List<UniqueKey>> customTags;
    private final Map<Key, Set<Key>> vanillaReverse;
    private final Map<Key, Owned<List<UniqueKey>>> ownVanilla = new LinkedHashMap<>();
    private final Map<Key, Owned<List<UniqueKey>>> ownCustom = new LinkedHashMap<>();
    private final Map<Key, Owned<Set<Key>>> ownReverse = new LinkedHashMap<>();
    private boolean closed;

    HostCommonTagOverlay(Map<Key, List<UniqueKey>> vanillaTags, Map<Key, List<UniqueKey>> customTags,
                         Map<Key, Set<Key>> vanillaReverse) {
        this.vanillaTags = vanillaTags; this.customTags = customTags; this.vanillaReverse = vanillaReverse;
    }

    synchronized Set<String> existingMembers(Key tag) {
        if (!vanillaTags.containsKey(tag) && !customTags.containsKey(tag)) return null;
        Set<String> members = new LinkedHashSet<>();
        vanillaTags.getOrDefault(tag, List.of()).forEach(member -> members.add(member.key().toString()));
        customTags.getOrDefault(tag, List.of()).forEach(member -> members.add(member.key().toString()));
        return Set.copyOf(members);
    }

    synchronized void publish(Map<Key, Members> groups) {
        if (closed) throw new IllegalStateException("Common tag loading overlay is closed");
        clear();
        for (var entry : groups.entrySet()) {
            Key tag = entry.getKey();
            // The host's complete tag already exists, so a default group cannot add members to it.
            if (!vanillaTags.getOrDefault(tag, List.of()).isEmpty() || !customTags.getOrDefault(tag, List.of()).isEmpty()) continue;
            Members members = entry.getValue();
            if (!members.vanilla().isEmpty()) {
                ownVanilla.put(tag, new Owned<>(vanillaTags.put(tag, members.vanilla()), members.vanilla()));
                for (UniqueKey member : members.vanilla()) {
                    Key item = member.key();
                    Set<Key> current = vanillaReverse.get(item);
                    Set<Key> replacement = new LinkedHashSet<>(current == null ? Set.of() : current);
                    replacement.add(tag);
                    Set<Key> installed = Set.copyOf(replacement);
                    Owned<Set<Key>> previous = ownReverse.get(item);
                    Set<Key> original = previous != null && current == previous.installed() ? previous.previous() : current;
                    vanillaReverse.put(item, installed);
                    ownReverse.put(item, new Owned<>(original, installed));
                }
            }
            if (!members.custom().isEmpty())
                ownCustom.put(tag, new Owned<>(customTags.put(tag, members.custom()), members.custom()));
            // An explicitly empty group stays empty; it does not manufacture a host ingredient.
        }
    }

    synchronized void clear() {
        restore(vanillaTags, ownVanilla); restore(customTags, ownCustom); restore(vanillaReverse, ownReverse);
    }

    private static <T> void restore(Map<Key, T> target, Map<Key, Owned<T>> owned) {
        owned.forEach((key, value) -> {
            if (target.get(key) != value.installed()) return;
            if (value.previous() == null) target.remove(key); else target.put(key, value.previous());
        });
        owned.clear();
    }

    @Override public synchronized void close() { clear(); closed = true; }
}
