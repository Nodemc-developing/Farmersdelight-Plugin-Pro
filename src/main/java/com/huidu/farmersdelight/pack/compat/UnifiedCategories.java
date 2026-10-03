package com.huidu.farmersdelight.pack.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Builds a single browsing tree without changing the source category documents. */
final class UnifiedCategories {
    static final String ROOT = "farmersdelight:default";
    static final String LEGACY_ROOT = "farmersdelight:categories";
    private static final String PREFIX = "farmersdelight:";
    static final List<String> GROUPS = List.of("tools", "ingredients", "crops", "processed", "foods",
            "feast", "decoration", "wild_plants", "pet_foods");
    private static final Set<String> LEGACY_GROUPS = Set.of("block", "item", "food", "weapon", "gui", "item_display");
    private static final List<String> LABELS = List.of("工具", "食材", "农作物", "加工食品", "食物",
            "盛宴", "装饰", "野生植物", "宠物食物");
    private static final List<String> ICONS = List.of("iron_knife", "straw", "cabbage", "minced_beef", "hamburger",
            "shepherds_pie_block", "tatami", "wild_onions", "dog_food");
    private static final Set<String> STATIONS = Set.of("stove", "cooking_pot", "skillet", "cutting_board");
    private static final Set<String> CROPS = Set.of("cabbage", "tomato", "onion", "rice", "cabbage_seeds", "tomato_seeds");
    private static final Set<String> PROCESSED = Set.of("fried_egg", "pumpkin_slice", "cabbage_leaf", "pie_crust",
            "wheat_dough", "raw_pasta", "minced_beef", "beef_patty", "chicken_cuts", "cooked_chicken_cuts",
            "bacon", "cooked_bacon", "cod_slice", "cooked_cod_slice", "salmon_slice", "cooked_salmon_slice",
            "mutton_chops", "cooked_mutton_chops", "ham", "smoked_ham");
    private static final Set<String> FEASTS = Set.of("roast_chicken", "stuffed_pumpkin", "honey_glazed_ham",
            "shepherds_pie", "gleaming_salad", "apple_pie", "chocolate_pie", "sweet_berry_cheesecake",
            "stuffed_pumpkin_block", "shepherds_pie_block", "honey_glazed_ham_block", "roast_chicken_block",
            "gleaming_salad_block", "rice_roll_medley_block");
    private static final Set<String> WILD = Set.of("wild_onions", "wild_cabbages", "wild_tomatoes", "wild_carrots",
            "wild_potatoes", "wild_beetroots", "sandy_shrub", "wild_rice", "brown_mushroom_colony", "red_mushroom_colony");
    private static final Set<String> PETS = Set.of("dog_food", "horse_feed");

    private UnifiedCategories() { }

    static Map<String, Object> merge(Map<String, Object> input, Predicate<String> registeredItem) {
        Map<String, Object> output = copyMap(input);
        if (!recognized(input)) return output;
        Map<String, Boolean> emptyGroups = new LinkedHashMap<>();
        for (int index = 0; index < GROUPS.size(); index++) {
            String group = GROUPS.get(index), id = PREFIX + group;
            Map<String, Object> existing = body(input.get(id));
            boolean explicitlyEmpty = existing != null && existing.get("list") instanceof List<?> list && list.isEmpty();
            boolean unresolvedList = existing != null && existing.containsKey("list") && !(existing.get("list") instanceof List<?>);
            emptyGroups.put(group, explicitlyEmpty || unresolvedList);
            Map<String, Object> current = existing == null ? new LinkedHashMap<>() : copyMap(existing);
            current.put("name", name(current.get("name"), LABELS.get(index), "gray"));
            current.put("hidden", true);
            current.putIfAbsent("icon", PREFIX + ICONS.get(index));
            if (!current.containsKey("list")) current.put("list", new ArrayList<>());
            else if (current.get("list") instanceof List<?> list && !list.isEmpty()) current.put("list", union(list, List.of()));
            output.put(id, current);
        }

        for (String legacy : List.of("block", "item", "food", "weapon")) {
            Map<String, Object> category = body(input.get(PREFIX + legacy));
            if (category == null || !(category.get("list") instanceof List<?> members)) continue;
            for (Object member : members) if (member instanceof String id) {
                if (id.equals("#" + PREFIX + "gui") || id.equals("#" + PREFIX + "item_display")) continue;
                String destination = groupFor(legacy, id);
                append(output, destination, id, emptyGroups);
            }
        }
        appendRegistered(output, "decoration", "farmersdelight:glass_jug", emptyGroups, registeredItem);
        appendRegistered(output, "decoration", "farmersdelight:basket", emptyGroups, registeredItem);
        appendRegistered(output, "decoration", "farmersdelight:tray", emptyGroups, registeredItem);
        appendRegistered(output, "foods", "farmersdelight:barbecue_stick", emptyGroups, registeredItem);

        List<String> compatibility = new ArrayList<>();
        for (var pair : List.of(List.of("jug", "fluid_jug"), List.of("glass_jug", "fluid_tank"))) {
            String standard = PREFIX + pair.get(0), legacy = PREFIX + pair.get(1);
            if (!registeredItem.test(legacy)) continue;
            if (registeredItem.test(standard)) compatibility.add(legacy);
            else append(output, "decoration", legacy, emptyGroups);
        }
        if (!compatibility.isEmpty() && !input.containsKey(PREFIX + "compatibility")) {
            Map<String, Object> hidden = new LinkedHashMap<>();
            hidden.putIfAbsent("name", "<!i><gray>兼容物品</gray>");
            hidden.put("hidden", true);
            hidden.putIfAbsent("icon", compatibility.getFirst());
            if (!hidden.containsKey("list") || hidden.get("list") instanceof List<?> list && !list.isEmpty())
                hidden.put("list", union(hidden.get("list"), compatibility));
            output.put(PREFIX + "compatibility", hidden);
        }

        Map<String, Object> existingRoot = body(input.get(ROOT));
        Map<String, Object> legacyRoot = body(input.get(LEGACY_ROOT));
        Map<String, Object> root = existingRoot != null ? copyMap(existingRoot)
                : legacyRoot != null ? copyMap(legacyRoot) : new LinkedHashMap<>();
        root.put("name", name(root.get("name"), "农夫乐事", "gold"));
        root.put("hidden", false);
        root.putIfAbsent("priority", 1);
        root.putIfAbsent("icon", PREFIX + "cooking_pot");
        boolean preservedRootList = existingRoot != null && existingRoot.containsKey("list")
                && (!(existingRoot.get("list") instanceof List<?> list) || list.isEmpty());
        if (!preservedRootList) {
            LinkedHashSet<Object> members = new LinkedHashSet<>();
            GROUPS.forEach(group -> members.add("#" + PREFIX + group));
            retainCustomReferences(members, existingRoot);
            retainCustomReferences(members, legacyRoot);
            root.put("list", new ArrayList<>(members));
        }
        output.put(ROOT, root);
        for (String id : input.keySet()) if (id.equals(LEGACY_ROOT)
                || id.startsWith(PREFIX) && LEGACY_GROUPS.contains(id.substring(PREFIX.length()))) {
            Map<String, Object> current = body(output.get(id));
            if (current != null) current.put("hidden", true);
        }
        return output;
    }

    static boolean recognized(Map<String, Object> input) {
        if (body(input.get(LEGACY_ROOT)) != null) return true;
        Map<String, Object> root = body(input.get(ROOT));
        if (root == null) return false;
        if (root.get("list") instanceof List<?> members)
            return members.isEmpty() || GROUPS.stream().anyMatch(group -> members.contains("#" + PREFIX + group));
        return false;
    }

    private static void retainCustomReferences(Set<Object> target, Map<String, Object> root) {
        if (root == null || !(root.get("list") instanceof List<?> list)) return;
        for (Object member : list) {
            if (member instanceof String id && id.startsWith("#" + PREFIX)) {
                String group = id.substring(("#" + PREFIX).length());
                if (GROUPS.contains(group) || LEGACY_GROUPS.contains(group)) continue;
            }
            target.add(copy(member));
        }
    }

    private static String groupFor(String legacy, String id) {
        String item = id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : id;
        if (STATIONS.contains(item) || legacy.equals("weapon")) return "tools";
        if (CROPS.contains(item)) return "crops";
        if (PROCESSED.contains(item)) return "processed";
        if (FEASTS.contains(item)) return "feast";
        if (WILD.contains(item)) return "wild_plants";
        if (PETS.contains(item)) return "pet_foods";
        return switch (legacy) {
            case "block" -> "decoration";
            case "item" -> "ingredients";
            default -> "foods";
        };
    }

    private static void appendRegistered(Map<String, Object> output, String group, String id,
                                         Map<String, Boolean> empty, Predicate<String> registered) {
        if (registered.test(id)) append(output, group, id, empty);
    }

    private static void append(Map<String, Object> output, String group, String id, Map<String, Boolean> empty) {
        if (Boolean.TRUE.equals(empty.get(group))) return;
        Map<String, Object> category = body(output.get(PREFIX + group));
        if (category != null) category.put("list", union(category.get("list"), List.of(id)));
    }

    private static List<Object> union(Object first, List<?> second) {
        LinkedHashSet<Object> members = new LinkedHashSet<>();
        if (first instanceof List<?> list) list.forEach(value -> members.add(copy(value)));
        second.forEach(value -> members.add(copy(value)));
        return new ArrayList<>(members);
    }

    private static String name(Object original, String fallback, String color) {
        String label = original instanceof String text ? text : fallback;
        label = label.replaceAll("(?i)</?(?:!?(?:i|italic)|reset|black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|dark_gray|blue|green|aqua|red|light_purple|yellow|white|#[0-9a-f]{6})>", "");
        return "<!i><" + color + ">" + label + "</" + color + ">";
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> body(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
    private static Map<String, Object> copyMap(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> result.put(key, copy(value)));
        return result;
    }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, child) -> result.put(key, copy(child)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(); list.forEach(child -> result.add(copy(child))); return result;
        }
        return value;
    }
}
