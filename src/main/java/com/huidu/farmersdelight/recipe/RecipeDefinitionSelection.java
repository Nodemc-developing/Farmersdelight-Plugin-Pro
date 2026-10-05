package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Filters worker-owned documents only. Original documents and editor locations remain intact. */
final class RecipeDefinitionSelection {
    private record Node(ConfigurationSection parent, String key) { }

    static void apply(Map<Path, YamlConfiguration> documents, Map<Path, String> namespaces) throws IOException {
        for (Path file : List.copyOf(documents.keySet())) {
            Map<String, Object> current = ExternalContentCoordinator.currentBundledDocument(file);
            if (current == null) continue;
            YamlConfiguration replacement = new YamlConfiguration();
            replacement.options().pathSeparator('\u0001');
            current.forEach((key, value) -> com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(replacement, key, value));
            documents.put(file, replacement);
        }
        Map<String, List<ExternalContentCoordinator.SourceDefinition<Node>>> categories = new LinkedHashMap<>();
        for (var document : documents.entrySet()) {
            String namespace = namespace(document.getKey(), namespaces);
            for (String rootKey : document.getValue().getKeys(false)) {
                String rootName = rootKey.split("#", 2)[0];
                ConfigurationSection root = document.getValue().getConfigurationSection(rootKey);
                if (root == null) continue;
                for (String id : root.getKeys(false)) {
                    ConfigurationSection body = root.getConfigurationSection(id);
                    if (body == null) continue;
                    String category = category(rootName, body);
                    if (category != null) add(categories, category, fullId(namespace, id), document.getKey(), rootKey, root, id);
                }
            }
        }
        for (var category : categories.entrySet()) {
            var selected = ExternalContentCoordinator.selectDefinitions("recipe:" + category.getKey(), category.getValue());
            var winners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Node, Boolean>());
            selected.forEach(source -> winners.add(source.value()));
            for (var source : category.getValue()) if (!winners.contains(source.value()))
                source.value().parent().set(source.value().key(), null);
        }
    }

    private static void add(Map<String, List<ExternalContentCoordinator.SourceDefinition<Node>>> categories,
                            String category, String id, Path file, String root, ConfigurationSection parent, String key) {
        categories.computeIfAbsent(category, ignored -> new ArrayList<>()).add(
                new ExternalContentCoordinator.SourceDefinition<>(id, file, file + "#" + root + "/" + key, new Node(parent, key)));
    }

    private static String category(String root, ConfigurationSection body) {
        if (root.equals(NativeRecipeSchema.ROOT)) {
            String station = NativeRecipeSchema.station(body);
            String group = body.getString("group", "");
            return switch (station) {
                case "cooking_pot" -> group.isBlank() ? "cooking" : "custom_cooking:" + group;
                case "cutting_board" -> "cutting";
                case "fluid_tank" -> "fluid:" + body.getString("operation", "");
                default -> null;
            };
        }
        for (PackSection kind : PackSection.values()) if (root.equals(kind.sectionId()) || root.equals(kind.rootKey())) {
            if (kind == PackSection.COOKING_POT || kind == PackSection.CUTTING_BOARD || kind == PackSection.CUSTOM_COOKING_POT) return null;
            return kind == PackSection.COOKING_POT ? "cooking" : kind == PackSection.CUTTING_BOARD ? "cutting" : kind.name();
        }
        return root.equals("food_groups") ? "FOOD_GROUPS" : null;
    }

    private static String fullId(String namespace, String id) { return id.indexOf(':') >= 0 ? id : namespace + ":" + id; }
    private static String namespace(Path file, Map<Path, String> roots) {
        String namespace = "farmersdelight";
        int length = -1;
        for (var root : roots.entrySet()) if (file.startsWith(root.getKey()) && root.getKey().getNameCount() > length) {
            namespace = root.getValue(); length = root.getKey().getNameCount();
        }
        return namespace;
    }
}
