package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts file formats at load/save boundaries without mutating source documents. */
public final class RecipeSchemaAdapter {
    private RecipeSchemaAdapter() { }

    private static boolean papersType(Object type, String expected) {
        return type instanceof String value && (value.equals(expected) || value.endsWith(":" + expected));
    }

    public static ConfigurationSection normalizePot(ConfigurationSection source) {
        Map<String, Object> body = values(source);
        boolean papers = papersType(body.get("type"), "cooking");
        alias(body, "cook-time", "time", "cooking_time", "cooking-time");
        alias(body, "result-count", "result_count");
        alias(body, "match-mode", "match_mode");
        alias(body, "use-equivalent-foods", "use_equivalent_foods");
        alias(body, "use-seasonings", "use_seasonings");
        alias(body, "minimum-score", "minimum_score");
        if (papers && !body.containsKey("container") && !Boolean.TRUE.equals(body.get("infer_container"))) {
            body.put("container", "none");
        }
        if (papers && !body.containsKey("cook-time")) body.put("cook-time", 200);
        if (body.containsKey("result")) body.put("result", item(body.get("result")));
        return section(body);
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source) {
        return normalizeBoard(source, List.of("#farmersdelight:tools/knives"));
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source, List<String> defaultTools) {
        Map<String, Object> body = values(source);
        boolean papers = papersType(body.get("type"), "cutting");
        alias(body, "input", "ingredient");
        if (papers && (!body.containsKey("tools") || body.get("tools") instanceof List<?> tools && tools.isEmpty())) {
            body.put("tools", List.copyOf(defaultTools));
        }
        Object rawResults = body.get("results");
        if (rawResults instanceof List<?> results) {
            List<Object> converted = new ArrayList<>(results.size());
            for (Object result : results) {
                Object normalized = item(result);
                if (normalized instanceof String id) normalized = Map.of("item", id);
                converted.add(normalized);
            }
            body.put("results", converted);
        }
        Object sound = body.get("sound");
        if (sound instanceof Map<?, ?> raw) {
            Map<String, Object> map = map(raw);
            Object id = map.containsKey("id") ? map.get("id") : map.get("sound");
            if (!(id instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("sound.id must name a sound");
            }
            body.put("sound", id);
            body.put("sound-volume", nonNegative(map.getOrDefault("volume", 1.0), "sound.volume"));
            body.put("sound-pitch", nonNegative(map.getOrDefault("pitch", 1.0), "sound.pitch"));
        }
        alias(body, "sound-volume", "sound_volume");
        alias(body, "sound-pitch", "sound_pitch");
        return section(body);
    }

    public static Map<String, Object> formatPot(Map<String, Object> source, boolean papers) {
        Map<String, Object> body = map(source);
        if (!papers) return body;
        body.put("type", "cooking");
        rename(body, "cook-time", "time");
        rename(body, "cooking_time", "time");
        rename(body, "cooking-time", "time");
        rename(body, "match-mode", "match_mode");
        rename(body, "use-equivalent-foods", "use_equivalent_foods");
        rename(body, "use-seasonings", "use_seasonings");
        rename(body, "minimum-score", "minimum_score");
        // Legacy recipes without a container used result-based inference; Papers recipes do not.
        if (!body.containsKey("container")) body.put("infer_container", true);
        Object count = body.remove("result-count");
        if (count == null) count = body.remove("result_count");
        Object result = papersItem(body.get("result"));
        if (count != null) {
            Map<String, Object> output = result instanceof Map<?, ?> raw ? map(raw) : new LinkedHashMap<>();
            if (!(result instanceof Map<?, ?>)) output.put("id", result);
            output.put("count", count);
            result = output;
        }
        if (body.containsKey("result")) body.put("result", result);
        formatIngredients(body, "ingredients");
        return typeFirst(body);
    }

    public static Map<String, Object> formatBoard(Map<String, Object> source, boolean papers) {
        Map<String, Object> body = map(source);
        if (!papers) return body;
        body.put("type", "cutting");
        rename(body, "input", "ingredient");
        if (body.containsKey("ingredient")) body.put("ingredient", papersIngredient(body.get("ingredient")));
        Object results = body.get("results");
        if (results instanceof List<?> list) {
            body.put("results", list.stream().map(RecipeSchemaAdapter::papersItem).toList());
        }
        Object volume = body.remove("sound-volume");
        if (volume == null) volume = body.remove("sound_volume");
        Object pitch = body.remove("sound-pitch");
        if (pitch == null) pitch = body.remove("sound_pitch");
        if (body.get("sound") instanceof String id && (volume != null || pitch != null)) {
            Map<String, Object> sound = new LinkedHashMap<>();
            sound.put("id", id);
            if (volume != null) sound.put("volume", volume);
            if (pitch != null) sound.put("pitch", pitch);
            body.put("sound", sound);
        }
        return typeFirst(body);
    }

    private static void formatIngredients(Map<String, Object> body, String key) {
        if (body.get(key) instanceof List<?> values) body.put(key, values.stream().map(RecipeSchemaAdapter::papersIngredient).toList());
    }

    private static Object papersIngredient(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = map(raw);
            Object choice = result.remove("choice");
            if (choice instanceof List<?> values) result.put("items", values.stream().map(RecipeSchemaAdapter::papersIngredient).toList());
            return result;
        }
        if (value instanceof String text && text.contains("|")) {
            List<String> options = new ArrayList<>();
            for (String option : text.split("\\|", -1)) {
                if (!option.isBlank()) options.add(option.trim());
            }
            if (options.isEmpty()) throw new IllegalArgumentException("Ingredient choice must not be empty");
            return Map.of("items", options);
        }
        return value;
    }

    private static Object item(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> result = map(raw);
        if (result.containsKey("id")) result.put("item", result.remove("id"));
        if (result.containsKey("count")) result.put("count", count(result.get("count")));
        if (result.containsKey("chance")) {
            double chance = number(result.get("chance"), "chance");
            result.put("chance", Math.max(0.0, Math.min(1.0, chance)));
        }
        return result;
    }

    private static Object papersItem(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> result = map(raw);
        rename(result, "item", "id");
        return result;
    }

    private static int count(Object value) {
        double number = number(value, "count");
        if (number != Math.rint(number) || number > Integer.MAX_VALUE || number < Integer.MIN_VALUE) {
            throw new IllegalArgumentException("count must be an integer");
        }
        return Math.max(1, (int) number);
    }

    private static double nonNegative(Object value, String key) {
        double result = number(value, key);
        if (result < 0.0 || result > Float.MAX_VALUE) throw new IllegalArgumentException(key + " must be non-negative and finite");
        return result;
    }

    private static double number(Object value, String key) {
        double result;
        try { result = value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(key + " must be a number", invalid); }
        if (!Double.isFinite(result)) throw new IllegalArgumentException(key + " must be finite");
        return result;
    }

    private static void alias(Map<String, Object> body, String target, String... names) {
        for (String name : names) {
            if (body.containsKey(name)) {
                body.put(target, body.get(name));
                return;
            }
        }
    }

    private static void rename(Map<String, Object> body, String from, String to) {
        if (!body.containsKey(from)) return;
        Object value = body.remove(from);
        body.putIfAbsent(to, value);
    }

    private static Map<String, Object> typeFirst(Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", body.remove("type"));
        result.putAll(body);
        return result;
    }

    private static Map<String, Object> values(ConfigurationSection section) { return map(section.getValues(false)); }

    private static Map<String, Object> map(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), copy(value)));
        return result;
    }

    private static Object copy(Object value) {
        if (value instanceof ConfigurationSection section) return values(section);
        if (value instanceof Map<?, ?> values) return map(values);
        if (value instanceof List<?> list) return list.stream().map(RecipeSchemaAdapter::copy).toList();
        return value;
    }

    private static ConfigurationSection section(Map<String, Object> body) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('\u0001');
        body.forEach((key, value) -> {
            if (value instanceof Map<?, ?> children && !key.equals("result") && !key.equals("input")) yaml.createSection(key, children);
            else yaml.set(key, value);
        });
        return yaml;
    }
}
