package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact on-disk location of a recipe, independent of Bukkit's dotted path syntax. */
public record RecipeSource(Path file, List<String> keys, boolean otherDelightFormat, boolean existingNode) {
    public RecipeSource(Path file, List<String> keys, boolean otherDelightFormat) {
        this(file, keys, otherDelightFormat, false);
    }
    public RecipeSource {
        file = file.toAbsolutePath().normalize();
        keys = List.copyOf(keys);
        if (keys.isEmpty()) throw new IllegalArgumentException("A recipe source requires a node");
    }

    public Map<String, Object> body(YamlConfiguration yaml) {
        Object value = yaml;
        for (String key : keys) value = mapping(value).get(key);
        return new LinkedHashMap<>(mapping(value));
    }

    /** Rebuilds only the selected ancestors, preserving unrelated and unknown keys. */
    public void put(YamlConfiguration yaml, Object body) {
        if (existingNode && body(yaml).isEmpty()) {
            throw new IllegalStateException("The original recipe node is unavailable; generated or deleted sources cannot be overwritten");
        }
        Map<String, Object> root = new LinkedHashMap<>(yaml.getValues(false));
        replace(root, 0, body);
        String key = keys.getFirst();
        com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(yaml, key, root.get(key));
    }

    private void replace(Map<String, Object> node, int depth, Object value) {
        String key = keys.get(depth);
        if (depth == keys.size() - 1) {
            if (value == null) node.remove(key); else node.put(key, value);
            return;
        }
        Map<String, Object> child = new LinkedHashMap<>(mapping(node.get(key)));
        replace(child, depth + 1, value);
        node.put(key, child);
    }

    private static Map<String, Object> mapping(Object value) {
        if (value instanceof ConfigurationSection section) return section.getValues(false);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, entry) -> out.put(String.valueOf(key), entry));
            return out;
        }
        return Map.of();
    }
}
