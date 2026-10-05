package com.huidu.farmersdelight.pack.compat;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts versioned item metadata in a loading copy after template expansion. */
final class ItemVersionInputs {
    private ItemVersionInputs() { }

    static Map<String, Object> adapt(Map<String, Object> input, boolean separateConsumable) {
        return adapt(input, separateConsumable, false);
    }

    static Map<String, Object> adapt(Map<String, Object> input, boolean separateConsumable,
                                     boolean modernTooltip) {
        Map<String, Object> result = ConfigPriorityFilter.copyMap(input);
        if (!(result.get("data") instanceof Map<?, ?> rawData)) return result;
        Map<String, Object> data = strings(rawData);
        adaptTooltip(data, modernTooltip);
        result.put("data", data);
        if (separateConsumable) return result;
        if (!(data.get("components") instanceof Map<?, ?> rawComponents)) return result;
        Map<String, Object> components = strings(rawComponents);
        Object remainder = components.get("minecraft:use_remainder");
        if (remainder == null) return result;
        Map<String, Object> food = new LinkedHashMap<>();
        if (data.get("food") instanceof Map<?, ?> configured) {
            configured.forEach((key, value) -> food.put(String.valueOf(key).replace('-', '_'), value));
        }
        if (components.get("minecraft:food") instanceof Map<?, ?> configured) food.putAll(strings(configured));
        if (!food.containsKey("nutrition") || !food.containsKey("saturation")) {
            throw new IllegalArgumentException("minecraft:use_remainder before 1.21.2 requires explicit food nutrition and saturation");
        }
        if (food.containsKey("using_converts_to") && !food.get("using_converts_to").equals(remainder)) {
            throw new IllegalArgumentException("Conflicting food.using_converts_to and minecraft:use_remainder");
        }
        food.put("using_converts_to", remainder);
        components.remove("minecraft:use_remainder");
        components.put("minecraft:food", food);
        // The complete native food value replaces the shorthand processor, preserving its extra fields.
        data.remove("food");
        data.remove("components");
        data.put("components", components);
        result.put("data", data);
        return result;
    }

    private static void adaptTooltip(Map<String, Object> data, boolean modernTooltip) {
        Object underscored = data.get("hide_tooltip");
        Object hyphenated = data.get("hide-tooltip");
        if (!(underscored instanceof Boolean) && !(hyphenated instanceof Boolean)) return;
        if (data.containsKey("hide_tooltip") && data.containsKey("hide-tooltip")
                && !java.util.Objects.equals(underscored, hyphenated)) {
            throw new IllegalArgumentException("Conflicting data.hide_tooltip and data.hide-tooltip");
        }
        boolean hide = (Boolean) (underscored instanceof Boolean ? underscored : hyphenated);
        Object configuredComponents = data.get("components");
        if (data.containsKey("components") && !(configuredComponents instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Boolean hide_tooltip requires data.components to be a map");
        }
        Map<String, Object> components = configuredComponents instanceof Map<?, ?> configured
                ? strings(configured) : new LinkedHashMap<>();
        if (modernTooltip) {
            Object configuredDisplay = components.get("minecraft:tooltip_display");
            if (components.containsKey("minecraft:tooltip_display") && !(configuredDisplay instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("minecraft:tooltip_display must be a map");
            }
            Map<String, Object> display = configuredDisplay instanceof Map<?, ?> configured
                    ? strings(configured) : new LinkedHashMap<>();
            if (display.containsKey("hide_tooltip") && !Boolean.valueOf(hide).equals(display.get("hide_tooltip"))) {
                throw new IllegalArgumentException("Conflicting data.hide_tooltip and minecraft:tooltip_display.hide_tooltip");
            }
            display.put("hide_tooltip", hide);
            components.put("minecraft:tooltip_display", display);
        } else {
            if (!hide && components.containsKey("minecraft:hide_tooltip")) {
                throw new IllegalArgumentException("Conflicting data.hide_tooltip=false and minecraft:hide_tooltip");
            }
            if (hide && !components.containsKey("minecraft:hide_tooltip")) {
                components.put("minecraft:hide_tooltip", new LinkedHashMap<>());
            }
        }
        data.remove("hide_tooltip");
        data.remove("hide-tooltip");
        // Native components run after the remaining shorthand processors.
        if (configuredComponents != null || !components.isEmpty()) {
            data.remove("components");
            data.put("components", components);
        }
    }

    private static Map<String, Object> strings(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
