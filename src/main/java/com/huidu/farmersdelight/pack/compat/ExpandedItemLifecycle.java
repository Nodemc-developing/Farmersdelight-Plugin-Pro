package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.util.Key;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retains expanded items for the host's tag and ingredient post-processing phase. */
final class ExpandedItemLifecycle {
    static List<PendingConfigSection> ordered(List<PendingConfigSection> originals, List<Key> derived,
                                            Map<Key, PendingConfigSection> parsed) {
        Map<Key, PendingConfigSection> retained = new LinkedHashMap<>();
        for (PendingConfigSection original : originals) {
            PendingConfigSection expanded = parsed.get(original.id());
            if (expanded != null) retained.putIfAbsent(original.id(), expanded);
        }
        for (Key id : derived) {
            PendingConfigSection expanded = parsed.get(id);
            if (expanded != null) retained.putIfAbsent(id, expanded);
        }
        return List.copyOf(retained.values());
    }

    static void postProcess(List<PendingConfigSection> target, List<PendingConfigSection> expanded, Runnable delegate) {
        try {
            target.clear(); target.addAll(expanded);
            delegate.run();
        } finally {
            // These loading inputs have been consumed; old unexpanded entries must not return on reload.
            target.clear();
        }
    }
}
