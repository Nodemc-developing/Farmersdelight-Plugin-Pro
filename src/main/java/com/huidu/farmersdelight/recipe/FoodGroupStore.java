package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.compat.KaleidoscopeCompat;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.huidu.farmersdelight.pack.PackSection;

public final class FoodGroupStore {
    public static final String FILE = "recipes/food_groups.yml";
    private static final String SOURCE = "farmersdelight:food-groups";
    private static volatile Map<String, RecipeSource> sources = Map.of();
    private FoodGroupStore() { }

    public record Loaded(List<FoodGroupSnapshot.Group> local, FoodGroupSnapshot combined) { }

    static Loaded load(FarmersDelightPlugin plugin, List<FoodGroupSnapshot.Group> previous) {
        YamlConfiguration yaml = RecipeFileLoader.loadRecipeFile(plugin, FILE, false);
        Map<String, FoodGroupSnapshot.Group> localById = new LinkedHashMap<>();
        Map<String, RecipeSource> nextSources = new LinkedHashMap<>();
        List<FoodGroupSnapshot.Group> original = yaml == null ? previous : read(yaml);
        for (var group : original) {
            localById.put(group.id(), group);
            if (yaml != null) nextSources.put(group.id(), RecipePackFiles.source(plugin, FILE, group.id(), null, yaml));
        }
        for (var section : RecipePackFiles.sections(plugin, PackSection.FOOD_GROUPS)) {
            if (RecipePackFiles.file(plugin, FILE).equals(section.file())) continue;
            for (var group : read(section.yaml())) {
                if (localById.putIfAbsent(group.id(), group) == null && section.file() != null) nextSources.put(group.id(),
                        new RecipeSource(section.file(), List.of(section.sectionKey(), group.id()), false, true));
            }
        }
        List<FoodGroupSnapshot.Group> local = List.copyOf(localById.values());
        AdvancedPackGroups.load(plugin);
        AdvancedRecipeTags.publishFoodGroups(local);
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (var group : local) tags.put(group.id(), group.items());
        CommonTagResolver.registerSource(SOURCE, tags);
        Map<String, FoodGroupSnapshot.Group> combined = new LinkedHashMap<>();
        for (var group : local) combined.put(group.id(), group);
        for (var group : KaleidoscopeCompat.refresh(plugin)) combined.putIfAbsent(group.id(), group);
        sources = Map.copyOf(nextSources);
        return new Loaded(List.copyOf(local), FoodGroupSnapshot.of(List.copyOf(combined.values())));
    }

    public static List<FoodGroupSnapshot.Group> read(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("groups");
        if (root == null) return List.of();
        List<FoodGroupSnapshot.Group> groups = new ArrayList<>();
        for (var groupEntry : root.getValues(false).entrySet()) {
            String id = groupEntry.getKey();
            ConfigurationSection entry = groupEntry.getValue() instanceof ConfigurationSection value ? value : null;
            if (entry == null) throw new IllegalArgumentException("Invalid food group: " + id);
            String rawKind = entry.getString("kind", "equivalent");
            FoodGroupSnapshot.Kind kind = switch (rawKind) {
                case "equivalent" -> FoodGroupSnapshot.Kind.EQUIVALENT;
                case "seasoning" -> FoodGroupSnapshot.Kind.SEASONING;
                default -> throw new IllegalArgumentException("Unknown food group kind: " + rawKind);
            };
            groups.add(new FoodGroupSnapshot.Group(id, kind, entry.getStringList("items")));
        }
        return List.copyOf(groups);
    }

    public static RecipeSource sourceOf(String id) { return sources.get(id); }
    public static void clear() { sources = Map.of(); AdvancedPackGroups.clear(); AdvancedRecipeTags.clearFoodGroups(); CommonTagResolver.unregisterSource(SOURCE); }
}
