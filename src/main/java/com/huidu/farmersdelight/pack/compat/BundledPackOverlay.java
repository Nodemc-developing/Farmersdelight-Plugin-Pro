package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.sparrow.yaml.SparrowYaml;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Current defaults replace verified historical defaults only in private loading inputs. */
final class BundledPackOverlay {
    private final Map<Path, Map<String, Object>> documents = new LinkedHashMap<>();
    private final List<ManagedResourceLayer.Source> assets = new ArrayList<>();

    BundledPackOverlay(Map<Path, Map<String, Object>> documents) {
        documents.forEach((path, values) -> this.documents.put(path.toAbsolutePath().normalize(), ConfigPriorityFilter.copyMap(values)));
    }

    BundledPackOverlay(JavaPlugin plugin) throws Exception {
        Path jar = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        Path resources = plugin.getDataFolder().toPath().getParent().resolve("CraftEngine/resources").toAbsolutePath().normalize();
        Set<String> assetPrefixes = new java.util.TreeSet<>();
        if (!java.nio.file.Files.isRegularFile(jar)) return;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("craftengine/")) continue;
                int asset = name.indexOf("/resourcepack/");
                if (asset >= 0) { assetPrefixes.add(name.substring(0, asset + "/resourcepack/".length())); continue; }
                if (!name.contains("/configuration/") || !(name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json"))) continue;
                Path target = resources.resolve(name.substring("craftengine/".length())).normalize();
                if (!target.startsWith(resources)) throw new IOException("Unsafe bundled configuration path " + name);
                try (var input = zip.getInputStream(entry)) {
                    documents.put(target, ConfigPriorityFilter.copyMap(SparrowYaml.create()
                            .load(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getValues()));
                }
            }
        }
        assetPrefixes.forEach(prefix -> assets.add(new ManagedResourceLayer.Source(jar, true, prefix, true)));
    }

    List<ManagedResourceLayer.Source> assets() { return List.copyOf(assets); }

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
