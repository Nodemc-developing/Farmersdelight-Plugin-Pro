package com.huidu.farmersdelight.fluid;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import java.util.*;

/** Adapts newly parsed container definitions before CE constructs their block loot and controllers. */
public final class FluidContentFormat {
    private FluidContentFormat() {}
    private static final Map<String, Object> MENU_DEFAULTS = Map.ofEntries(
            Map.entry("layout", "jug"), Map.entry("theme", "auto"),
            Map.entry("title-key", "container.farmersdelight.jug"),
            Map.entry("background-image", "farmersdelight:jug"),
            Map.entry("background-offset", -8), Map.entry("title-offset", -98),
            Map.entry("empty-item", "farmersdelight:gui_space_with_tooltip"),
            Map.entry("border-item", "farmersdelight:gui_space"),
            Map.entry("bucket-item", "farmersdelight:jug_capacity_bucket"),
            Map.entry("bottle-item", "farmersdelight:jug_capacity_bottle"),
            Map.entry("progress-item-prefix", "farmersdelight:soaking_progress_"),
            Map.entry("fluid-item-prefix", "farmersdelight:jug_fluid_"));
    static Map<String, Object> menuDefaults() { return MENU_DEFAULTS; }
    public static CachedConfigSection transform(String parserType, CachedConfigSection source) {
        if (!source.config().path().split("#", 2)[0].equals("items") || !PapersFluidAliases.ownsJug()) return source;
        Map<String, Object> definitions = new LinkedHashMap<>();
        Map<String, Object> visuals = new LinkedHashMap<>();
        source.config().values().forEach((id, value) -> {
            String key = Key.withDefaultNamespace(id, source.pack().namespace()).toString();
            definitions.put(id, item(key, value, visuals));
        });
        visuals.forEach(definitions::putIfAbsent);
        return new CachedConfigSection(source.pack(), source.path(), ConfigSection.of(source.config().path(), definitions), source.arguments());
    }
    static Object item(String id, Object value) {
        return item(id, value, new LinkedHashMap<>());
    }
    private static Object item(String id, Object value, Map<String, Object> visuals) {
        if (!(value instanceof Map<?, ?>)) return value;
        Object originalBehavior = ((Map<?, ?>) value).get("behavior");
        Map<String, Object> jugItem = jugItem(originalBehavior);
        if (!hasJug(originalBehavior) && jugItem == null) return value;
        if (hasUnresolvedTemplate(value)) throw new IllegalArgumentException("Jug configuration must be expanded before its fluid-preserving loot is adapted: " + id);
        Map<String, Object> item = mutable(value);
        Map<String, Object> settings = map(item.get("settings"));
        Object container = settings.containsKey("libuid:fluid_container") ? settings.get("libuid:fluid_container") : settings.get("fluidcore:container");
        Object rawCapacity = map(container).getOrDefault("capacity", 16000L);
        long capacity;
        try { capacity = new java.math.BigDecimal(rawCapacity.toString()).longValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid new container capacity at " + id, invalid); }
        if (capacity <= 0) throw new IllegalArgumentException("Container capacity must be positive at " + id);
        String prefix = null;
        if (jugItem != null) {
            Object model = jugItem.get("model");
            if (!(model instanceof String fluidModel) || !fluidModel.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
                throw new IllegalArgumentException("New jug_item requires a namespaced fluid model prefix at " + id);
            prefix = id.substring(0, id.indexOf(':')) + ":fluidcore_visual/" + id.substring(id.indexOf(':') + 1);
            boolean transparent = booleanValue(jugItem.getOrDefault("transparent", false), "transparent");
            visualItems(visuals, prefix, item.get("model"), fluidModel, transparent);
        }
        Object behavior = item.get("behavior");
        if (behavior instanceof List<?> list) {
            List<Object> adapted = new ArrayList<>(); for (Object part : list) adapted.add(behavior(id, part, capacity, prefix));
            item.put("behavior", adapted);
        } else item.put("behavior", behavior(id, behavior, capacity, prefix));
        return item;
    }
    private static Map<String, Object> jugItem(Object value) {
        if (value instanceof List<?> list) { for (Object entry : list) { var item = jugItem(entry); if (item != null) return item; } return null; }
        if (value instanceof Map<?, ?> map && "papersdelight:jug_item".equals(map.get("type"))) return mutable(value);
        return null;
    }
    private static boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean bool) return bool;
        throw new IllegalArgumentException("New container " + field + " must be a boolean");
    }
    private static void visualItems(Map<String, Object> output, String prefix, Object configuredShell, String fluidPrefix, boolean transparent) {
        Object shell = configuredShell instanceof String path ? Map.of("type", "minecraft:model", "path", path) : copy(configuredShell);
        if (!(shell instanceof Map<?, ?>)) throw new IllegalArgumentException("New jug visual requires its shell model definition");
        if (transparent && shell instanceof Map<?, ?> map) {
            Map<String, Object> tinted = mutable(map);
            if ("minecraft:model".equals(tinted.get("type"))) tinted.put("tints", List.of(Map.of("type", "minecraft:custom_model_data", "index", 0, "default", 16777215)));
            shell = tinted;
        }
        output.put(prefix + "/empty", Map.of("material", "minecraft:paper", "model", shell));
        output.put(prefix + "/waterlogged/empty", Map.of("material", "minecraft:paper", "model", Map.of("type", "minecraft:empty")));
        for (int level = 1; level <= 16; level++) {
            String suffix = "level_" + String.format(Locale.ROOT, "%02d", level);
            Object fluid = Map.of("type", "minecraft:model", "path", fluidPrefix + "_model_" + String.format(Locale.ROOT, "%02d", level),
                    "tints", List.of(Map.of("type", "minecraft:custom_model_data", "index", 1, "default", 4159204), Map.of("type", "minecraft:custom_model_data", "index", 1, "default", 4159204)));
            output.put(prefix + "/" + suffix, Map.of("material", "minecraft:paper", "model", Map.of("type", "minecraft:composite", "models", List.of(shell, fluid))));
            output.put(prefix + "/waterlogged/" + suffix, Map.of("material", "minecraft:paper", "model", fluid));
        }
    }
    private static boolean hasJug(Object value) {
        if (value instanceof List<?> list) return list.stream().anyMatch(FluidContentFormat::hasJug);
        if (!(value instanceof Map<?, ?> map) || !"block_item".equals(map.get("type"))) return false;
        Object block = map.get("block");
        return block instanceof Map<?, ?> definition && definition.get("behavior") instanceof Map<?, ?> behavior
                && "papersdelight:jug".equals(behavior.get("type"));
    }
    private static boolean hasUnresolvedTemplate(Object value) {
        if (value instanceof String text) return text.contains("${") || text.contains("$()");
        if (value instanceof List<?> list) return list.stream().anyMatch(FluidContentFormat::hasUnresolvedTemplate);
        if (!(value instanceof Map<?, ?> map)) return false;
        for (var entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equals("template") && !"default:loot_table/self".equals(entry.getValue())) return true;
            if (hasUnresolvedTemplate(entry.getValue())) return true;
        }
        return false;
    }
    private static Object behavior(String id, Object raw, long capacity, String prefix) {
        if (!(raw instanceof Map<?, ?>)) return raw;
        Map<String, Object> behavior = mutable(raw);
        if ("papersdelight:jug_item".equals(behavior.get("type")) && prefix != null) { behavior.put("item-model", prefix); return behavior; }
        if (!"block_item".equals(behavior.get("type"))) return behavior;
        Map<String, Object> block = map(behavior.get("block"));
        Map<String, Object> jug = map(block.get("behavior"));
        if (!"papersdelight:jug".equals(jug.get("type"))) return behavior;
        jug.putIfAbsent("capacity", capacity);
        Object configuredMenu = jug.get("menu");
        if (jug.containsKey("menu") && !(configuredMenu instanceof Map<?, ?>))
            throw new IllegalArgumentException("New container menu must be a section at " + id + "/behavior/block/behavior/menu");
        Map<String, Object> menu = mutable(MENU_DEFAULTS);
        if (configuredMenu instanceof Map<?, ?>) menu.putAll(mutable(configuredMenu));
        jug.put("menu", menu);
        if (prefix != null) {
            jug.put("item-model", prefix);
            Map<String, Object> states = map(block.get("states")), appearances = map(states.get("appearances"));
            appearances.replaceAll((name, value) -> {
                Map<String, Object> appearance = map(value); Map<String, Object> renderer = map(appearance.get("entity_renderer"));
                if (renderer.isEmpty()) {
                    renderer.put("type", "item_display"); renderer.put("item", prefix + "/empty");
                    Map<String, Object> model = map(appearance.get("model")); if (model.containsKey("y")) renderer.put("yaw", model.get("y"));
                }
                if (!"item_display".equals(renderer.get("type"))) throw new IllegalArgumentException("New jug renderer requires an item_display at " + id);
                Object tint = renderer.get("tint_source");
                if (tint != null && (!(tint instanceof Map<?, ?> configured) || !"fluidcore:tank".equals(configured.get("type"))))
                    throw new IllegalArgumentException("New jug renderer has an incompatible tint_source at " + id + "/states/appearances/" + name + "; original tint behavior cannot be discarded");
                renderer.putIfAbsent("tint_source", Map.of("type", "fluidcore:tank"));
                appearance.put("entity_renderer", renderer); return appearance;
            });
            states.put("appearances", appearances); block.put("states", states);
        }
        block.put("behavior", jug);
        Map<String, Object> loot = map(block.get("loot"));
        if (loot.size() == 1 && "default:loot_table/self".equals(loot.get("template"))) {
            loot = new LinkedHashMap<>(Map.of("pools", List.of(Map.of("rolls", 1, "entries", List.of(Map.of(
                    "type", "item", "item", id, "functions", List.of(Map.of("type", "set_count", "count", 1), Map.of("type", "fluidcore:preserve_tank"))))))));
        } else {
            int count = preserveEntry(loot, id);
            if (count != 1) throw new IllegalArgumentException("New jug loot requires exactly one count-one self item entry at " + id);
        }
        block.put("loot", loot); behavior.put("block", block); return behavior;
    }
    private static int preserveEntry(Object value, String id) {
        if (value instanceof List<?> list) { int count = 0; for (Object entry : list) count += preserveEntry(entry, id); return count; }
        if (!(value instanceof Map<?, ?>)) return 0;
        @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) value;
        if ("item".equals(entry.get("type")) && id.equals(entry.get("item"))) {
            List<Object> functions = new ArrayList<>(); Object configured = entry.get("functions");
            if (configured instanceof List<?> list) functions.addAll(list);
            int preserves = 0;
            for (Object function : functions) if (function instanceof Map<?, ?> map) {
                if ("fluidcore:preserve_tank".equals(map.get("type"))) preserves++;
                if ("set_count".equals(map.get("type")) && !"1".equals(String.valueOf(map.get("count")))) throw new IllegalArgumentException("Fluid-preserving jug loot count must be exactly one");
            }
            if (preserves > 1) throw new IllegalArgumentException("Repeated fluid-preserving loot function");
            if (preserves == 0) functions.add(Map.of("type", "fluidcore:preserve_tank"));
            entry.put("functions", functions); return 1;
        }
        int count = 0; for (Object nested : entry.values()) count += preserveEntry(nested, id); return count;
    }
    private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? mutable(value) : new LinkedHashMap<>(); }
    private static Map<String, Object> mutable(Object value) {
        Map<String, Object> result = new LinkedHashMap<>(); ((Map<?, ?>) value).forEach((key, nested) -> result.put(String.valueOf(key), copy(nested))); return result;
    }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?>) return mutable(value);
        if (value instanceof List<?> list) { List<Object> copy = new ArrayList<>(); list.forEach(nested -> copy.add(copy(nested))); return copy; }
        return value;
    }
}
