package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.template.ArgumentString;
import net.momirealms.craftengine.core.plugin.config.template.argument.TemplateArgument;
import net.momirealms.craftengine.core.util.Key;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class ConfigPriorityFilter {
    record Result(List<CachedConfigSection> configs, List<PendingConfigSection> pending) { }
    private record Location(CachedConfigSection cached, String key, PendingConfigSection pending) { }

    static Result filterBlockStateMappings(List<CachedConfigSection> configs,
                                          Function<String, Integer> registryId,
                                          BundledContentManifest ownership, ContentConflicts report) throws IOException {
        List<DefinitionPriority.Definition<Location>> all = new ArrayList<>();
        Map<java.nio.file.Path, Boolean> own = new LinkedHashMap<>();
        int unresolved = 0;
        for (CachedConfigSection cached : configs) {
            boolean bundled = bundled(ownership, own, cached.path());
            for (String key : cached.config().keySet()) {
                Integer stateId = null;
                try {
                    // SectionConfigParser passes these keys directly to the native parser without ID-template expansion.
                    stateId = registryId.apply(key);
                } catch (RuntimeException invalid) {
                    // The original section parser retains responsibility for malformed states and arguments.
                }
                String identity = stateId != null && stateId >= 0 ? "registry:" + stateId : "unresolved:" + unresolved++;
                all.add(new DefinitionPriority.Definition<>(identity,
                        cached.path() + "#" + cached.config().path() + "." + key,
                        bundled, new Location(cached, key, null)));
            }
        }
        List<DefinitionPriority.Definition<Location>> selected;
        try { selected = DefinitionPriority.select("craftengine:block_state_mapping", all, report); }
        finally { report.save(); }
        Map<CachedConfigSection, Map<String, Object>> retained = new IdentityHashMap<>();
        for (var winner : selected) {
            Location location = winner.value();
            retained.computeIfAbsent(location.cached(), ignored -> new LinkedHashMap<>())
                    .put(location.key(), copy(location.cached().config().get(location.key())));
        }
        List<CachedConfigSection> retainedConfigs = new ArrayList<>();
        for (CachedConfigSection cached : configs) {
            Map<String, Object> values = retained.get(cached);
            if (values != null && !values.isEmpty()) retainedConfigs.add(new CachedConfigSection(cached.pack(),
                    cached.path(), ConfigSection.of(cached.config().path(), values), copyArguments(cached.arguments())));
        }
        return new Result(retainedConfigs, List.of());
    }

    static Result filter(String type, List<CachedConfigSection> configs, List<PendingConfigSection> pending,
                         BundledContentManifest ownership, ContentConflicts report) throws IOException {
        List<DefinitionPriority.Definition<Location>> all = new ArrayList<>();
        Map<java.nio.file.Path, Boolean> own = new LinkedHashMap<>();
        for (CachedConfigSection cached : configs) {
            boolean bundled = bundled(ownership, own, cached.path());
            for (String key : cached.config().keySet()) {
                String parsed = key;
                if (cached.hasArguments() && parsed.contains("$")) parsed = ArgumentString
                        .preParse(cached.config().path(), parsed).get(cached.config().path(), cached.arguments()).toString();
                String namespace = cached.pack().namespace();
                if (type.equals("farmersdelight:pack_sections")) {
                    int suffix = cached.config().path().indexOf('#');
                    if (suffix >= 0 && suffix + 1 < cached.config().path().length())
                        namespace = cached.config().path().substring(suffix + 1).trim();
                }
                String id = Key.withDefaultNamespace(parsed, namespace).toString();
                // One parser can own several independent roots, notably the addon's recipe families.
                String root = cached.config().path().split("#", 2)[0];
                String scope = type.equals("farmersdelight:pack_sections") ? root + "/" : "";
                all.add(new DefinitionPriority.Definition<>(scope + id, cached.path() + "#" + cached.config().path(),
                        bundled, new Location(cached, key, null)));
            }
        }
        for (PendingConfigSection entry : pending) all.add(new DefinitionPriority.Definition<>(entry.id().toString(),
                entry.path() + "#" + entry.section().path(), bundled(ownership, own, entry.path()),
                new Location(null, null, entry)));
        List<DefinitionPriority.Definition<Location>> selected;
        try { selected = DefinitionPriority.select(type, all, report); }
        finally { report.save(); }
        Map<CachedConfigSection, Map<String, Object>> retained = new IdentityHashMap<>();
        List<PendingConfigSection> retainedPending = new ArrayList<>();
        for (var winner : selected) {
            Location location = winner.value();
            if (location.pending() != null) {
                var p = location.pending();
                retainedPending.add(new PendingConfigSection(p.pack(), p.path(), p.id(),
                        ConfigSection.of(p.section().path(), loadingValues(p.path(), p.section().values()))));
            } else retained.computeIfAbsent(location.cached(), ignored -> new LinkedHashMap<>())
                    .put(location.key(), copy(location.cached().config().get(location.key())));
        }
        List<CachedConfigSection> retainedConfigs = new ArrayList<>();
        for (var cached : configs) {
            Map<String, Object> values = retained.get(cached);
            if (values != null && !values.isEmpty()) retainedConfigs.add(new CachedConfigSection(cached.pack(),
                    cached.path(), ConfigSection.of(cached.config().path(), values), copyArguments(cached.arguments())));
        }
        return new Result(retainedConfigs, retainedPending);
    }

    static CachedConfigSection loadingCopy(CachedConfigSection cached) {
        return new CachedConfigSection(cached.pack(), cached.path(),
                ConfigSection.of(cached.config().path(), loadingValues(cached.path(), cached.config().values())),
                copyArguments(cached.arguments()));
    }

    static Map<String, Object> loadingValues(java.nio.file.Path path, Map<String, Object> values) {
        return HostVersionInputs.expand(path, values);
    }

    private static Map<String, TemplateArgument> copyArguments(Map<String, TemplateArgument> arguments) {
        // The host injects reserved ID arguments into this map even when it starts empty.
        return arguments == null ? null : new LinkedHashMap<>(arguments);
    }

    private static boolean bundled(BundledContentManifest ownership, Map<java.nio.file.Path, Boolean> cache,
                                   java.nio.file.Path path) throws IOException {
        Boolean value = cache.get(path);
        if (value != null) return value;
        boolean result = ownership.isBundled(path);
        cache.put(path, result);
        return result;
    }

    static Map<String, Object> copyMap(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((key, value) -> out.put(key, copy(value)));
        return out;
    }

    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, child) -> out.put(String.valueOf(key), copy(child)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(child -> out.add(copy(child)));
            return out;
        }
        return value;
    }
}
