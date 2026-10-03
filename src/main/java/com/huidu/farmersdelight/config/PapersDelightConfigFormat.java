package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Public configuration format and the cached compatibility snapshot used by existing consumers. */
public final class PapersDelightConfigFormat {
    public static final int VERSION = 3;
    public static final String FORMAT_KEY = "config_format";
    public static final String FORMAT = "Farmersdelight-Plugin-Pro";

    private static final Set<String> OPTION_NAMES = Set.of(("""
            actionbar-refresh-ticks actionbar-separator active-block-threshold advancements allow-conductors
            allow-hopper allow-solid-supports-below-max-light always-valid-supports anvil auraskills auto-completion
            auto-disable-missing auto-disable-on-conflict backstabbing bell-ring-max-distance bubble budgets buff
            cache-player-limit categories chance channels chunk-effect-packet-budget color combat comfort compatibility conductor
            conductor-tags conductors container-inference container-returns cook-time-multiplier cooking cooking-pot
            cooking-pot-density-threshold cooking-pot-total-threshold cooldown-seconds cooling-decrement count crackle
            craftengine-free-state-threshold craftengine-resources custom-block custom-block-tag custom-blocks
            cutting-board damage-type datapacks debug default-cook-time default-display-offset default-enchantability default-tools
            definition disable-above-active-pots discovery dispenser-behavior display display-item-spread durable
            effect-tick-interval-ticks effects enabled enchantments entries excluded-remainders experience-reward
            extra-enchantments fallback fire fire-aspect-bonus food-groups force-disable force-enable groups handheld handheld-jump-flip
            heal-amount heal-interval-ticks heat heat-source heat-sources hopper-interactions id install-datapack
            interaction-mode interval item-spread item-tags items jump-flip jumps kaleidoscope knife-items knife-tags knives
            language layout-mode lit locked-display locked-icon look-dot-threshold max-bell-distance max-cook-time max-level max-light
            merge-missing-bundled min-cook-time mode multiplier multiplier-base multiplier-per-level mushroom-colonies
            notify nourishment offset-x offset-y offset-z overlay override-offers pack-contents-on-break performance
            persistence pet-tempt-tick-budget pitch pitch-max pitch-min placement players-only progress-display
            proxy-display raw recipe-auto-fill recipe-editor recipe-navigation recipe-only-placement recipes reload
            reload-visual-refreshes-per-tick require-knife restore-retry-delay-ticks retrieve-pitch retrieve-volume rope
            rotation-interval-ticks scale secondary show-recipe-name shutdown-wait-millis sizzle skill skillet
            slot-offsets smoke sound sounds speed startup-chunk-loads-per-tick states steam stove styles sync-batch-size
            sync-interval-ticks table tags tags-enabled tick-budget tool-sounds type unlock-on-obtain
            update-interval-ticks vanilla-block vanilla-block-tag vanilla-blocks vanilla-tags view-distance
            viewer-distance visibility-distance volume warnings weight y-offset pet-foods require-tamed restore-health
            sound-volume sound-pitch particle-type particle-count tempt move-speed tick-interval ignore-owned-tamed
            default-display-position default-display-translation default-display-rotation default-display-scale
            default-display-style default-display-item default-position default-offset default-translation
            default-rotation default-scale default-style display-overrides display-tag-overrides display-item
            position offset translation rotation style entities foods duration always-eat stats flush-interval-seconds
            particle-throttle stove-threshold stove-max-rate cooking-pot-threshold cooking-pot-max-rate skewer
            ambient-sound-threshold ambient-sound-max-rate container tick-interval-ticks connection-particle-packet-budget
            display-block block-display-overrides item-display-overrides stack-y-offset stack-xz-offset
            translate-x translate-y translate-z rotation-pitch rotation-y rotation-roll fortune-bonus place-item
            remove-item carve-tool add-food add-food-cold place-food item-smoke-chance item-smoke-count
            fire-aspect-particle xz-spread velocity-y-base velocity-y-extra velocity-xz origin-x origin-y origin-z
            """).trim().split("\\s+"));

    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    static {
        PATHS.put("language", "lang");
        PATHS.put("enchantments", "enchantment");
        PATHS.put("enchantment.enabled", "enchantment.enable");
        PATHS.put("buff.comfort", "comfort_effect");
        PATHS.put("buff.nourishment", "nourishment_effect");
        PATHS.put("comfort_effect.enabled", "comfort_effect.enable");
        PATHS.put("nourishment_effect.enabled", "nourishment_effect.enable");
        for (String effect : List.of("comfort", "nourishment")) {
            PATHS.put("buff.display.styles." + effect + ".color", effect + "_effect.bossbar.color");
            PATHS.put("buff.display.styles." + effect + ".overlay", effect + "_effect.bossbar.style");
        }
        PATHS.put("performance.shutdown_wait_millis", "stats.shutdown_wait_millis");
        PATHS.put("stats.enabled", "stats.enable");
        PATHS.put("stats.flush_interval_seconds", "stats.flush_interval");
        PATHS.put("rope.max_bell_distance", "rope.bell_ring_max_distance");
        PATHS.put("skillet.handheld_jump_flip", "skillet.handheld.jump_flip");
        PATHS.put("cutting_board.allow_hopper", "cutting_board.hopper_interaction");
        PATHS.put("cutting_board.dispenser_behavior", "cutting_board.dispenser_cutting");
        PATHS.put("cutting_board.sounds.retrieve_volume", "cutting_board.sounds.remove_item.volume");
        PATHS.put("cutting_board.sounds.retrieve_pitch", "cutting_board.sounds.remove_item.pitch");
        PATHS.put("cutting_board.sounds.tool_sounds", "cutting_board.tool_sounds");
        PATHS.put("skillet.display.y_offset", "skillet.display.translate_y");
        for (String station : List.of("cooking_pot", "skillet", "stove")) {
            PATHS.put(station + ".effects.viewer_distance", station + ".particles.view_distance_blocks");
        }
    }

    private PapersDelightConfigFormat() { }

    /** Only an unmistakable foreign file has its unrelated generation counter ignored. */
    public static boolean isForeignConfiguration(ConfigurationSection source) {
        return source != null && !source.contains(FORMAT_KEY, true)
                && source.contains("license", true) && source.contains("lang", true)
                && source.contains("heat_sources", true) && source.contains("cooking_pot", true)
                && source.contains("cutting_board", true) && source.contains("stats", true)
                && source.isList("heat_sources") && source.isConfigurationSection("cooking_pot")
                && source.isConfigurationSection("cutting_board") && source.isConfigurationSection("stats");
    }

    public static boolean mayMigrate(ConfigurationSection source) {
        return ConfigFileUpdater.deployedVersion(source) <= VERSION || isForeignConfiguration(source);
    }

    /** Renames known option keys only. Namespaced item IDs and unknown fields remain intact. */
    public static boolean normalize(ConfigurationSection source) {
        Map<String, Object> before = values(source);
        Map<String, Object> after = canonical(before);
        if (before.equals(after)) return false;
        replace(source, after);
        return true;
    }

    /** A reload builds this once; event handlers read the published snapshot without translating paths. */
    public static YamlConfiguration runtimeView(FileConfiguration source) {
        YamlConfiguration view = runtimeViewOf(source);
        if (source.getDefaults() != null) view.setDefaults(runtimeViewOf(source.getDefaults()));
        return view;
    }

    public static YamlConfiguration runtimeViewOf(ConfigurationSection source) {
        Map<String, Object> canonical = canonical(values(source));
        Map<String, Object> legacy = renameKnown(canonical, false);
        for (Map.Entry<String, String> path : PATHS.entrySet()) {
            Object value = get(canonical, path.getValue());
            if (value != null) put(legacy, legacyPath(path.getKey()),
                    value instanceof Map<?, ?> map ? renameKnown(stringMap(map), false) : copy(value), true);
        }
        // Whole-section aliases are rebuilt after their nested semantic aliases.
        Object enchantment = legacy.get("enchantment");
        if (enchantment instanceof Map<?, ?> map) {
            Map<String, Object> copy = stringMap(map);
            Object enabled = get(canonical, "enchantment.enable");
            if (enabled != null) copy.put("enabled", enabled);
            legacy.put("enchantments", copy);
        }
        for (String effect : List.of("comfort", "nourishment")) {
            Object section = legacy.get(effect + "_effect");
            if (section instanceof Map<?, ?> map) {
                Map<String, Object> copy = stringMap(map);
                Object enabled = get(canonical, effect + "_effect.enable");
                if (enabled != null) copy.put("enabled", enabled);
                put(legacy, "buff." + effect, copy, true);
            }
            Object style = get(canonical, effect + "_effect.bossbar.style");
            if (style != null) put(legacy, "buff.display.styles." + effect + ".overlay",
                    runtimeBarStyle(String.valueOf(style)), true);
        }
        Object heat = canonical.get("heat_sources");
        if (heat instanceof List<?> list) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object raw : list) {
                if (!(raw instanceof Map<?, ?> entry)) continue;
                Map<String, Object> mapped = stringMap(entry);
                move(mapped, "material", "vanilla-block", false);
                Object material = mapped.get("vanilla-block");
                if (material != null) mapped.put("vanilla-block", vanillaId(material));
                move(mapped, "vanilla_block_tag", "vanilla-block-tag", false);
                move(mapped, "ce_block", "custom-block", false);
                move(mapped, "ce_block_tag", "custom-block-tag", false);
                move(mapped, "heat_source", "heat-source", false);
                entries.add(mapped);
            }
            put(legacy, "heat-sources.entries", entries, true);
            for (String field : List.of("tags", "vanilla-blocks", "vanilla-tags", "custom-blocks", "conductors", "conductor-tags")) {
                put(legacy, "heat-sources." + field, List.of(), false);
            }
        }
        Object legacyHeat = canonical.get("heat_source_legacy");
        if (legacyHeat instanceof Map<?, ?> map) {
            Map<String, Object> fields = renameKnown(stringMap(map), false);
            fields.forEach((key, value) -> put(legacy, "heat-sources." + key, value, true));
        }
        Object interval = get(canonical, "cooking_pot.particles.interval_ticks");
        if (interval instanceof Number number) {
            long ticks = Math.max(1L, number.longValue());
            long callbacks = ticks / 4L + (ticks % 4L == 0 ? 0 : 1);
            put(legacy, "cooking-pot.effects.interval", (int) Math.min(Integer.MAX_VALUE, callbacks), true);
        }
        configureDisplay(canonical, legacy, "cutting_board", "cutting-board", true);
        configureDisplay(canonical, legacy, "skillet", "skillet.display", false);
        configureStoveDisplay(canonical, legacy);
        Object toolSounds = get(legacy, "cutting-board.sounds.tool-sounds");
        if (toolSounds instanceof Map<?, ?> map && map.containsKey("knife")) {
            Map<String, Object> sounds = stringMap(map);
            Object knife = sounds.remove("knife");
            sounds.putIfAbsent("#farmersdelight:tools/knives", knife);
            put(legacy, "cutting-board.sounds.tool-sounds", sounds, true);
        }
        Object pet = canonical.get("pet_food");
        if (pet instanceof Map<?, ?> map) {
            Map<String, Object> definitions = new LinkedHashMap<>();
            if (map.get("definitions") instanceof Map<?, ?> explicit) definitions.putAll(renameKnown(stringMap(explicit), false));
            for (String group : List.of("dog_food", "horse_feed")) {
                if (map.containsKey(group)) definitions.put(group, copy(map.get(group)));
            }
            legacy.put("pet-foods", definitions);
        }
        Map<String, Object> combined = stringMap(canonical);
        merge(combined, legacy, true);
        YamlConfiguration view = new YamlConfiguration();
        replace(view, combined);
        return view;
    }

    public static List<String> canonicalRegistrySections(List<String> legacy) {
        List<String> result = new ArrayList<>();
        for (String path : legacy) {
            String canonical = canonicalPath(path);
            if (!result.contains(canonical)) result.add(canonical);
        }
        result.add("pet_food.definitions");
        return result;
    }

    /** Fields with no corresponding implementation are retained and reported, rather than silently promised. */
    public static List<String> unsupportedOptions(ConfigurationSection source) {
        List<String> result = new ArrayList<>();
        for (String path : List.of("garlic_effect", "stats.io_wait_millis")) {
            if (source.contains(path, true)) result.add(path);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> canonical(Map<String, Object> input) {
        Map<String, Object> output = renameKnown(input, true);
        for (Map.Entry<String, String> path : PATHS.entrySet()) move(output, path.getKey(), path.getValue(), false);
        Object heat = output.get("heat_sources");
        if (heat instanceof Map<?, ?> map) {
            Map<String, Object> fields = stringMap(map);
            output.put("heat_sources", canonicalHeatSources(fields));
            fields.remove("entries");
            fields.entrySet().removeIf(entry -> entry.getValue() instanceof List<?> list && list.isEmpty());
            if (!fields.isEmpty()) put(output, "heat_source_legacy", fields, false);
        }
        move(output, "pet_foods", "pet_food.definitions", false);
        Object interval = remove(output, "cooking_pot.effects.interval");
        if (interval instanceof Number number) put(output, "cooking_pot.particles.interval_ticks",
                Math.max(1L, Math.min(Integer.MAX_VALUE, number.longValue())) * 4L, false);
        for (String station : List.of("cutting_board", "skillet")) {
            String oldSpread = station.equals("cutting_board") ? station + ".display_item_spread"
                    : station + ".display.item_spread";
            Object spread = remove(output, oldSpread);
            if (spread instanceof Number number) put(output, station + ".display.stack_xz_offset",
                    number.doubleValue() / 2.0D, false);
        }
        Object offset = remove(output, "cutting_board.default_display_offset");
        List<Double> vector = vector(offset);
        if (vector != null) {
            String[] coordinates = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) put(output, "cutting_board.display.translate_" + coordinates[i], vector.get(i), false);
        }
        for (String effect : List.of("comfort", "nourishment")) {
            Object style = get(output, effect + "_effect.bossbar.style");
            if (style != null) put(output, effect + "_effect.bossbar.style", publicBarStyle(String.valueOf(style)), true);
        }
        pruneEmptyContainers(output);
        return output;
    }

    private static List<Map<String, Object>> canonicalHeatSources(Map<String, Object> source) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Object oldEntries = source.get("entries");
        if (oldEntries instanceof List<?> list) for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> map)) continue;
            Map<String, Object> entry = stringMap(map);
            move(entry, "vanilla_block", "material", false);
            if (entry.containsKey("material")) entry.put("material", String.valueOf(entry.get("material"))
                    .replace("minecraft:", "").toUpperCase(Locale.ROOT));
            move(entry, "custom_block", "ce_block", false);
            move(entry, "custom_block_tag", "ce_block_tag", false);
            entries.add(entry);
        }
        return entries;
    }

    private static void configureDisplay(Map<String, Object> canonical, Map<String, Object> legacy,
                                         String station, String target, boolean board) {
        String display = station + ".display";
        Object x = get(canonical, display + ".translate_x");
        Object y = get(canonical, display + ".translate_y");
        Object z = get(canonical, display + ".translate_z");
        if (x != null || y != null || z != null) put(legacy, target + (board ? ".default-display-offset" : ".position"),
                List.of(number(x, 0), number(y, 0), number(z, 0)), true);
        Object scale = get(canonical, display + ".scale");
        if (board && scale != null) put(legacy, target + ".default-display-scale", scale, true);
        Object pitch = get(canonical, display + ".rotation_pitch");
        Object yaw = get(canonical, display + ".rotation_y");
        Object roll = get(canonical, display + ".rotation_roll");
        if (pitch != null || yaw != null || roll != null) put(legacy,
                target + (board ? ".default-display-rotation" : ".rotation"),
                List.of(number(pitch, 90), number(yaw, 0), number(roll, 0)), true);
        Object spread = get(canonical, display + ".stack_xz_offset");
        if (spread instanceof Number value) put(legacy, target + (board ? ".display-item-spread" : ".item-spread"),
                value.doubleValue() * 2.0D, true);
    }

    private static void configureStoveDisplay(Map<String, Object> canonical, Map<String, Object> legacy) {
        if (get(canonical, "stove.display.slot_offsets") != null) return;
        Object x = get(canonical, "stove.display.slot_1_x");
        Object z = get(canonical, "stove.display.slot_1_z");
        Object y = get(canonical, "stove.display.translate_y");
        if (x == null && z == null && y == null) return;
        double column = number(get(canonical, "stove.display.col_spacing"), 0.3);
        double row = number(get(canonical, "stove.display.row_spacing"), 0.4);
        List<String> slots = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) for (int col = 0; col < 3; col++) {
            slots.add((number(x, 0.3) - col * column) + "," + number(y, 1.02) + "," + (number(z, 0.2) - rowIndex * row));
        }
        put(legacy, "stove.display.slot-offsets", slots, true);
    }

    private static String publicBarStyle(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.equals("PROGRESS") ? "SOLID" : normalized.replace("NOTCHED_", "SEGMENTED_");
    }

    private static String runtimeBarStyle(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.equals("SOLID") ? "PROGRESS" : normalized.replace("SEGMENTED_", "NOTCHED_");
    }

    private static String canonicalPath(String legacy) {
        String path = transformPath(legacy, true);
        for (Map.Entry<String, String> rename : PATHS.entrySet()) {
            if (path.equals(rename.getKey()) || path.startsWith(rename.getKey() + ".")) {
                path = rename.getValue() + path.substring(rename.getKey().length());
            }
        }
        return path;
    }

    private static String legacyPath(String path) { return transformPath(path, false); }

    private static String transformPath(String path, boolean toPublic) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) parts[i] = optionName(parts[i], toPublic);
        return String.join(".", parts);
    }

    private static String optionName(String key, boolean toPublic) {
        if (key.contains(":") || key.startsWith("#")) return key;
        String legacy = key.replace('_', '-');
        return OPTION_NAMES.contains(legacy) ? (toPublic ? legacy.replace('-', '_') : legacy) : key;
    }

    private static Map<String, Object> renameKnown(Map<String, Object> source, boolean toPublic) {
        Map<String, Object> result = new LinkedHashMap<>();
        // Explicit public keys beat legacy aliases regardless of their YAML insertion order.
        for (int pass = 0; pass < 2; pass++) for (Map.Entry<String, Object> entry : source.entrySet()) {
            String name = optionName(entry.getKey(), toPublic);
            boolean original = name.equals(entry.getKey());
            if ((pass == 0) != original) continue;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) value = renameKnown(stringMap(map), toPublic);
            else if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>();
                for (Object element : list) copy.add(element instanceof Map<?, ?> map
                        ? renameKnown(stringMap(map), toPublic) : copy(element));
                value = copy;
            }
            Object present = result.get(name);
            if (present instanceof Map<?, ?> existing && value instanceof Map<?, ?> candidate) {
                Map<String, Object> combined = stringMap(existing);
                merge(combined, stringMap(candidate), false);
                result.put(name, combined);
            } else if (!result.containsKey(name)) result.put(name, copy(value));
        }
        return result;
    }

    private static Map<String, Object> values(ConfigurationSection section) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            if (!section.contains(key, true)) continue;
            Object value = section.get(key);
            result.put(key, value instanceof ConfigurationSection nested ? values(nested) : copy(value));
        }
        return result;
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), copy(value)));
        return result;
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) return stringMap(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) result.add(copy(element));
            return result;
        }
        return value;
    }

    private static void replace(ConfigurationSection target, Map<String, Object> values) {
        Map<String, List<String>> comments = new LinkedHashMap<>();
        for (String key : target.getKeys(true)) {
            if (!target.getComments(key).isEmpty()) comments.put(canonicalPath(key), target.getComments(key));
        }
        for (String key : new ArrayList<>(target.getKeys(false))) target.set(key, null);
        write(target, values);
        comments.forEach((path, text) -> { if (target.contains(path, true)) target.setComments(path, text); });
    }

    private static void write(ConfigurationSection target, Map<String, Object> values) {
        values.forEach((key, value) -> {
            if (value instanceof Map<?, ?> nested) write(target.createSection(key), stringMap(nested));
            else target.set(key, copy(value));
        });
    }

    private static Object get(Map<String, Object> source, String path) {
        Object cursor = source;
        for (String key : path.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map)) return null;
            cursor = map.get(key);
        }
        return cursor;
    }

    private static Object remove(Map<String, Object> source, String path) {
        String[] keys = path.split("\\.");
        Map<String, Object> cursor = source;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = cursor.get(keys[i]);
            if (!(next instanceof Map<?, ?> map)) return null;
            cursor = mutableMap(map);
        }
        return cursor.remove(keys[keys.length - 1]);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Map<?, ?> map) { return (Map<String, Object>) map; }

    private static void put(Map<String, Object> target, String path, Object value, boolean overwrite) {
        String[] keys = path.split("\\.");
        Map<String, Object> cursor = target;
        for (int i = 0; i < keys.length - 1; i++) {
            Object present = cursor.get(keys[i]);
            if (!(present instanceof Map<?, ?>)) {
                if (present != null && !overwrite) return;
                present = new LinkedHashMap<String, Object>();
                cursor.put(keys[i], present);
            }
            cursor = mutableMap((Map<?, ?>) present);
        }
        String key = keys[keys.length - 1];
        if (overwrite || !cursor.containsKey(key)) cursor.put(key, copy(value));
    }

    private static void move(Map<String, Object> source, String from, String to, boolean overwrite) {
        Object value = remove(source, from);
        if (value == null) return;
        Object present = get(source, to);
        if (present instanceof Map<?, ?> current && value instanceof Map<?, ?> old) {
            Map<String, Object> merged = stringMap(current);
            merge(merged, stringMap(old), overwrite);
            put(source, to, merged, true);
        } else put(source, to, value, overwrite);
    }

    private static void merge(Map<String, Object> target, Map<String, Object> source, boolean overwrite) {
        source.forEach((key, value) -> {
            Object current = target.get(key);
            if (current instanceof Map<?, ?> old && value instanceof Map<?, ?> incoming) {
                Map<String, Object> combined = stringMap(old);
                merge(combined, stringMap(incoming), overwrite);
                target.put(key, combined);
            } else if (overwrite || !target.containsKey(key)) target.put(key, copy(value));
        });
    }

    private static void pruneEmptyContainers(Map<String, Object> source) {
        // Remove only containers emptied by semantic moves. Explicit empty registries must survive.
        for (String path : List.of("buff.display.styles.comfort", "buff.display.styles.nourishment", "buff.display.styles",
                "cooking_pot.effects", "skillet.effects", "stove.effects")) {
            Object value = get(source, path);
            if (value instanceof Map<?, ?> map && map.isEmpty()) remove(source, path);
        }
    }

    private static String vanillaId(Object value) {
        String id = String.valueOf(value).toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static List<Double> vector(Object value) {
        if (value == null) return null;
        List<?> entries = value instanceof List<?> list ? list : List.of(String.valueOf(value).split(","));
        if (entries.size() != 3) return null;
        List<Double> result = new ArrayList<>();
        for (Object entry : entries) {
            try { result.add(Double.parseDouble(String.valueOf(entry))); }
            catch (NumberFormatException invalid) { return null; }
        }
        return result;
    }
}
