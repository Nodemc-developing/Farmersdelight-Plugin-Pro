package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.pack.PackSection;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Expands explicit cross-pack advanced groups once at reload, before recipe matching indexes are built. */
public final class AdvancedPackGroups {
    private static final String SOURCE = "otherdelight:advanced_tags";
    private AdvancedPackGroups() { }

    public static void load(FarmersDelightPlugin plugin) {
        Map<String, List<String>> definitions = new LinkedHashMap<>();
        Consumer<String> diagnostic = message -> plugin.getLogger().warning("[advanced_tags] " + message);
        for (var section : RecipePackFiles.sections(plugin, PackSection.ADVANCED_TAGS)) {
            ConfigurationSection root = section.yaml().getConfigurationSection("advanced_tags");
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                ConfigurationSection body = root.getConfigurationSection(id);
                Object values = body == null ? null : body.get("values");
                if (!(values instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
                    diagnostic.accept(section.source() + ": " + id + ".values must be a list of item IDs or advanced references");
                    continue;
                }
                List<String> members = new ArrayList<>(list.size());
                for (Object value : list) members.add(((String) value).trim().toLowerCase(java.util.Locale.ROOT));
                if (definitions.putIfAbsent(id, List.copyOf(members)) != null) diagnostic.accept(section.source() + ": duplicate group " + id + "; the first definition is retained");
            }
        }
        AdvancedRecipeTags.registerSource(SOURCE, resolve(definitions, diagnostic));
    }

    public static void clear() { AdvancedRecipeTags.unregisterSource(SOURCE); }

    static Runnable captureReloadRollback() { return AdvancedRecipeTags.captureSourceRollback(SOURCE); }

    static Map<String, List<String>> resolve(Map<String, List<String>> definitions, Consumer<String> diagnostic) {
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        Set<String> invalid = new LinkedHashSet<>();
        for (String id : definitions.keySet()) {
            try { expand(id, definitions, resolved, new LinkedHashSet<>(), invalid); }
            catch (IllegalArgumentException error) { diagnostic.accept(id + ": " + error.getMessage()); }
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    private static List<String> expand(String id, Map<String, List<String>> definitions, Map<String, List<String>> resolved,
                                       Set<String> active, Set<String> invalid) {
        if (resolved.containsKey(id)) return resolved.get(id);
        if (invalid.contains(id)) throw new IllegalArgumentException("Invalid referenced advanced group: advtag:" + id);
        List<String> values = definitions.get(id);
        if (values == null) throw new IllegalArgumentException("Unknown advanced group: advtag:" + id);
        if (!active.add(id)) throw new IllegalArgumentException("Advanced group reference cycle: " + String.join(" -> ", active) + " -> " + id);
        try {
            if (active.size() > 128) throw new IllegalArgumentException("Advanced group references exceed 128 levels");
            Set<String> members = new LinkedHashSet<>();
            for (String value : values) {
                if (value.startsWith("advtag:")) members.addAll(expand(value.substring("advtag:".length()), definitions, resolved, active, invalid));
                else if (value.startsWith("#") || value.isBlank()) throw new IllegalArgumentException("Expected a concrete item ID or advanced reference: " + value);
                else members.add(value);
            }
            List<String> result = List.copyOf(members);
            resolved.put(id, result);
            return result;
        } catch (IllegalArgumentException error) {
            invalid.add(id);
            throw error;
        } finally { active.remove(id); }
    }
}
