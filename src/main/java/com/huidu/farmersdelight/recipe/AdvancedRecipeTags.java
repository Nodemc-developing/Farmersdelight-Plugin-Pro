package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit advanced groups. Matching never falls back to a CE tag or an item's base material. */
public final class AdvancedRecipeTags {
    private static final String FOOD_GROUP_SOURCE = "farmersdelight:food_groups";
    private static final Map<String, Map<String, Set<String>>> SOURCES = new LinkedHashMap<>();
    private static volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    private AdvancedRecipeTags() { }
    private static Snapshot currentSnapshot() { return RuntimeSnapshotPublication.get(AdvancedRecipeTags.class, snapshot); }

    public static synchronized void publishFoodGroups(List<FoodGroupSnapshot.Group> groups) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (FoodGroupSnapshot.Group group : groups) values.put(group.id(), group.items());
        registerSource(FOOD_GROUP_SOURCE, values);
    }

    /** Registers concrete item identities; nested references are intentionally not inferred. */
    public static synchronized void registerSource(String source, Map<String, ? extends List<String>> groups) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Advanced tag source cannot be empty");
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            String group = normalize(entry.getKey());
            if (group.isEmpty()) throw new IllegalArgumentException("Advanced tag ID cannot be empty");
            Key.of(group);
            Set<String> members = new LinkedHashSet<>();
            for (String item : entry.getValue()) {
                String normalized = normalize(item);
                if (normalized.isEmpty() || normalized.startsWith("#") || normalized.startsWith("advtag:")) {
                    throw new IllegalArgumentException("Advanced tag members must be concrete item IDs: " + item);
                }
                members.add(normalized);
            }
            frozen.put(group, Collections.unmodifiableSet(members));
        }
        SOURCES.put(source, Collections.unmodifiableMap(frozen));
        rebuild();
    }

    public static synchronized void unregisterSource(String source) {
        SOURCES.remove(source);
        rebuild();
    }

    public static void clearFoodGroups() { unregisterSource(FOOD_GROUP_SOURCE); }

    static Runnable captureFoodGroupsRollback() { return captureSourceRollback(FOOD_GROUP_SOURCE); }

    static synchronized Runnable captureSourceRollback(String source) {
        Map<String, Set<String>> previous = SOURCES.get(source);
        return () -> {
            synchronized (AdvancedRecipeTags.class) {
                if (previous == null) SOURCES.remove(source);
                else SOURCES.put(source, previous);
                rebuild();
            }
        };
    }

    public static Set<String> members(Key group) {
        return group == null ? Set.of() : members(group.toString());
    }

    public static Set<String> members(String group) {
        return currentSnapshot().groups().getOrDefault(normalize(group), Set.of());
    }

    public static boolean matches(ItemStack item, Key group) {
        if (item == null || item.getType().isAir()) return false;
        return matchesItemId(ItemUtils.resolveItemId(item), group);
    }

    public static boolean matchesItemId(String itemId, Key group) {
        return members(group).contains(normalize(itemId));
    }

    /** Reverse lookup for recipe indexes; contains group IDs without the expression prefix. */
    public static Set<String> tagsForItemId(String itemId) {
        return currentSnapshot().reverse().getOrDefault(normalize(itemId), Set.of());
    }

    /** An unknown advanced group invalidates the recipe even when another choice would be usable. */
    public static void requireDefined(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.AdvancedTag tag && members(tag.key()).isEmpty()) {
            throw new IllegalArgumentException("Unknown or empty advanced recipe tag: advtag:" + tag.key());
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (RecipeIngredient option : choice.options()) requireDefined(option);
        }
    }

    private static void rebuild() {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        for (var source : SOURCES.entrySet()) {
            if (!source.getKey().equals(FOOD_GROUP_SOURCE)) source.getValue().forEach(merged::putIfAbsent);
        }
        Map<String, Set<String>> local = SOURCES.get(FOOD_GROUP_SOURCE);
        if (local != null) merged.putAll(local);
        Map<String, Set<String>> reverse = new LinkedHashMap<>();
        merged.forEach((group, members) -> members.forEach(item ->
                reverse.computeIfAbsent(item, unused -> new LinkedHashSet<>()).add(group)));
        reverse.replaceAll((item, groups) -> Collections.unmodifiableSet(groups));
        Snapshot next = new Snapshot(Collections.unmodifiableMap(merged), Collections.unmodifiableMap(reverse));
        RuntimeSnapshotPublication.publish(AdvancedRecipeTags.class, snapshot, next);
        snapshot = next;
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private record Snapshot(Map<String, Set<String>> groups, Map<String, Set<String>> reverse) { }
}
