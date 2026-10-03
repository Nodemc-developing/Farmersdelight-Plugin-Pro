package com.huidu.farmersdelight.block.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable, validated growth settings shared by ordinary, double and supported crops. */
public record CropBehaviorOptions(float speed, int light, int spawnLight, boolean bonemeal,
        boolean villageHarvest, boolean villagePlant, boolean water, boolean upperIndependent,
        boolean synchronizeAges, int maximumAge, int upperMaximumAge, int upperMinimumAge,
        int ropeAge, int ropeMinimumAge, int ropeMaximumHeight, String ropeBlock, List<Soil> soils) {
    public record Soil(String block, float growthModifier, float bonemealChance) { }

    public static CropBehaviorOptions parse(Map<String, Object> source, int availableMaxAge) {
        float speed = decimal(source, "grow_speed", .125f);
        int light = integer(source, "light_requirement", 6);
        int max = integer(source, "max_age", availableMaxAge);
        int upper = integer(source, "upper_max_age", max);
        List<Soil> soils = new ArrayList<>();
        Object raw = get(source, "soils");
        if (!(raw instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("soils requires at least one entry");
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map)) throw new IllegalArgumentException("Each soil must be a mapping");
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) map;
            String descriptor = String.valueOf(get(values, "block"));
            float modifier = decimal(values, "growth_modifier", 1);
            float chance = decimal(values, "bonemeal_chance", 0);
            if (descriptor.isBlank() || descriptor.equals("null")) throw new IllegalArgumentException("soil.block must be nonempty");
            if (!Float.isFinite(modifier) || modifier < -1) throw new IllegalArgumentException("growth_modifier must be >= -1");
            if (!Float.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("bonemeal_chance must be in [0, 1]");
            soils.add(new Soil(descriptor, modifier, chance));
        }
        int spawn = integer(source, "spawn_light_requirement", light);
        int height = integer(source, "ropelogged_max_height", 3);
        int upperMin = integer(source, "upper_min_age", 0);
        int ropeAge = integer(source, "ropelogged_age", Math.min(3, max));
        int ropeMin = integer(source, "ropelogged_min_age", Math.min(4, max));
        if (!Float.isFinite(speed) || speed < 0 || light < 0 || light > 15 || spawn < 0 || spawn > 15
                || max < 0 || max > availableMaxAge || upper < 0 || upper > availableMaxAge || height < 1 || height > 256
                || upperMin < 0 || upperMin > upper || ropeAge < 0 || ropeAge > max || ropeMin < 0 || ropeMin > max) {
            throw new IllegalArgumentException("Invalid crop speed, light, age or height");
        }
        return new CropBehaviorOptions(speed, light, spawn, flag(source, "is_bone_meal_target", true),
                flag(source, "can_harvest_by_villagers", false), flag(source, "can_replant_by_villagers", false),
                flag(source, "plant_in_water", false), flag(source, "upper_independent", true),
                flag(source, "sync_ages", false), max, upper, upperMin, ropeAge, ropeMin, height,
                String.valueOf(source.getOrDefault("ropelogged_rope_block", source.getOrDefault("ropelogged-rope-block", "farmersdelight:rope"))), List.copyOf(soils));
    }

    public static Object get(Map<String, Object> map, String name) {
        return map.containsKey(name) ? map.get(name) : map.get(name.replace('_', '-'));
    }
    public static int integer(Map<String, Object> map, String name, int fallback) {
        Object value = get(map, name);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw new IllegalArgumentException(name + " must be an integer");
        return n.intValue();
    }
    public static float decimal(Map<String, Object> map, String name, float fallback) {
        Object value = get(map, name);
        if (value == null) return fallback;
        if (!(value instanceof Number n)) throw new IllegalArgumentException(name + " must be numeric");
        return n.floatValue();
    }
    public static boolean flag(Map<String, Object> map, String name, boolean fallback) {
        Object value = get(map, name);
        if (value == null) return fallback;
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(name + " must be boolean");
        return flag;
    }
    public static int incrementAge(int current, int bonus, int maximum) {
        return (int) Math.max(0, Math.min(maximum, (long) current + Math.max(0, bonus)));
    }
}
