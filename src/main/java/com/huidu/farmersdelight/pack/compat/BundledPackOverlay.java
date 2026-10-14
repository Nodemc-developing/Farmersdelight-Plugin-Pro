package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicitly registered defaults replace verified historical defaults in private loading inputs. */
final class BundledPackOverlay {
    private final Map<Path, Map<String, Object>> documents = new LinkedHashMap<>();

    BundledPackOverlay(Map<Path, Map<String, Object>> documents) {
        documents.forEach((path, values) -> this.documents.put(path.toAbsolutePath().normalize(), ConfigPriorityFilter.copyMap(values)));
    }

    Map<String, Object> document(Path file, BundledContentManifest ownership) throws IOException {
        Map<String, Object> current = ExternalContentCoordinator.registeredDefault(file);
        if (current == null) current = documents.get(file.toAbsolutePath().normalize());
        return current != null && ownership.isBundled(file) ? ConfigPriorityFilter.copyMap(current) : null;
    }

    List<CachedConfigSection> prepare(String[] roots, List<CachedConfigSection> originals,
                                     Collection<Pack> packs, BundledContentManifest ownership) throws IOException {
        Set<String> claimed = Set.of(roots);
        Map<Path, Map<String, Object>> currentDocuments = new LinkedHashMap<>(documents);
        currentDocuments.putAll(ExternalContentCoordinator.registeredDefaults());
        List<CachedConfigSection> out = new ArrayList<>();
        for (CachedConfigSection original : originals) {
            // Generated sections already carry the current factory's expanded content and arguments.
            if (original.hasArguments() || !currentDocuments.containsKey(original.path().toAbsolutePath().normalize())
                    || !ownership.isBundled(original.path())) out.add(original);
        }
        for (var entry : currentDocuments.entrySet()) {
            if (!ownership.isBundled(entry.getKey())) continue;
            Pack owner = null;
            for (Pack pack : packs) if (pack.enabled()) for (Path folder : pack.configurationFolders())
                if (entry.getKey().startsWith(folder.toAbsolutePath().normalize())) owner = pack;
            if (owner == null) continue;
            for (var root : entry.getValue().entrySet()) {
                if (!claimed.contains(root.getKey().split("#", 2)[0]) || !(root.getValue() instanceof Map<?, ?>)) continue;
                out.add(new CachedConfigSection(owner, entry.getKey(), ConfigSection.of(root.getKey(),
                        ConfigPriorityFilter.copy(root.getValue())), null));
            }
        }
        return out;
    }
}
