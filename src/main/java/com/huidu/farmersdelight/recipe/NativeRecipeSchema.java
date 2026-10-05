package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** File-boundary translation; gameplay keeps the existing immutable recipe objects. */
public final class NativeRecipeSchema {
    public static final String ROOT = "farmersdelight_recipes";
    public static final String EDITOR_ROOT = ROOT + "#fd_pro";
    private static final Set<String> COMMON = Set.of("station", "group", "category", "priority", "input", "output", "process", "matching", "fluid", "operation");
    private NativeRecipeSchema() { }

    public static boolean isNative(ConfigurationSection section) { return section != null && section.isSet("station"); }
    public static String station(ConfigurationSection section) { return section.getString("station", ""); }

    public static ConfigurationSection normalizePot(ConfigurationSection source) {
        Map<String, Object> nativeBody = copy(source.getValues(false));
        Map<String, Object> body = base(nativeBody, "cooking_pot");
        Map<String, Object> input = block(nativeBody, "input", Set.of("items", "container"));
        Map<String, Object> process = block(nativeBody, "process", Set.of("ticks", "experience"));
        Map<String, Object> matching = block(nativeBody, "matching", Set.of("mode", "perfect", "use-equivalent-foods", "use-seasonings", "minimum-score"));
        put(body, "ingredients", input, "items");
        put(body, "container", input, "container");
        put(body, "result", nativeBody, "output");
        put(body, "cook-time", process, "ticks");
        put(body, "experience", process, "experience");
        put(body, "match-mode", matching, "mode");
        for (String field : List.of("perfect", "use-equivalent-foods", "use-seasonings", "minimum-score")) put(body, field, matching, field);
        return section(body);
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source) {
        return normalizeBoard(source, List.of("#farmersdelight:tools/knives"));
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source, List<String> defaultTools) {
        Map<String, Object> nativeBody = copy(source.getValues(false));
        Map<String, Object> body = base(nativeBody, "cutting_board");
        Map<String, Object> input = block(nativeBody, "input", Set.of("item", "tools"));
        Map<String, Object> process = block(nativeBody, "process", Set.of("sound"));
        put(body, "input", input, "item");
        if (input.containsKey("tools")) body.put("tools", input.get("tools"));
        else body.put("tools", List.copyOf(defaultTools));
        put(body, "results", nativeBody, "output");
        Object sound = process.get("sound");
        if (sound instanceof Map<?, ?> raw) {
            Map<String, Object> settings = copy(raw);
            fields(settings, Set.of("id", "volume", "pitch"), "process.sound");
            put(body, "sound", settings, "id");
            put(body, "sound-volume", settings, "volume");
            put(body, "sound-pitch", settings, "pitch");
        } else if (sound != null) body.put("sound", sound);
        return section(body);
    }

    public static Map<String, Object> normalizeFluid(Map<String, Object> source) {
        Map<String, Object> nativeBody = copy(source);
        Map<String, Object> body = base(nativeBody, "fluid_tank");
        String type = switch (Objects.toString(nativeBody.get("operation"), "")) {
            case "fill" -> "fluid_filling";
            case "drain" -> "fluid_emptying";
            case "soak" -> "soaking";
            default -> throw new IllegalArgumentException("operation must be fill, drain or soak");
        };
        Map<String, Object> input = block(nativeBody, "input", Set.of("item"));
        Map<String, Object> process = block(nativeBody, "process", Set.of("ticks"));
        Map<String, Object> fluid = block(nativeBody, "fluid", Set.of("match", "amount-mb", "consume"));
        body.put("type", type);
        put(body, "ingredient", input, "item");
        put(body, "result", nativeBody, "output");
        put(body, "fluid", fluid, "match");
        put(body, "amount", fluid, "amount-mb");
        put(body, "consume_fluid", fluid, "consume");
        put(body, "time", process, "ticks");
        verifyAmounts(fluid.get("match"));
        return body;
    }

    public static Map<String, Object> formatPot(Map<String, Object> source) {
        Map<String, Object> body = copy(source);
        Map<String, Object> result = formattedBase(body, "cooking_pot");
        Map<String, Object> input = new LinkedHashMap<>();
        move(input, "items", body, "ingredients");
        move(input, "container", body, "container");
        result.put("input", input);
        Object output = body.get("result");
        Object count = first(body, "result-count", "result_count");
        if (output != null) {
            Map<String, Object> item = item(output);
            if (count != null) item.put("count", count);
            result.put("output", item);
        }
        Map<String, Object> process = new LinkedHashMap<>();
        Object ticks = first(body, "cook-time", "cooking_time", "cooking-time", "time");
        if (ticks != null) process.put("ticks", ticks);
        move(process, "experience", body, "experience");
        if (!process.isEmpty()) result.put("process", process);
        Map<String, Object> matching = new LinkedHashMap<>();
        Object mode = first(body, "match-mode", "match_mode");
        if (mode != null) matching.put("mode", mode);
        for (String field : List.of("perfect", "use-equivalent-foods", "use-seasonings", "minimum-score")) {
            Object value = first(body, field, field.replace('-', '_'));
            if (value != null) matching.put(field, value);
        }
        if (!matching.isEmpty()) result.put("matching", matching);
        return result;
    }

    public static Map<String, Object> formatBoard(Map<String, Object> source) {
        Map<String, Object> body = copy(source);
        Map<String, Object> result = formattedBase(body, "cutting_board");
        Map<String, Object> input = new LinkedHashMap<>();
        Object ingredient = first(body, "input", "ingredient");
        if (ingredient != null) input.put("item", ingredient);
        Object tools = first(body, "tools", "tool");
        if (tools != null) input.put("tools", tools instanceof List<?> ? tools : List.of(tools));
        result.put("input", input);
        if (body.get("results") instanceof List<?> results) result.put("output", results.stream().map(NativeRecipeSchema::item).toList());
        Object sound = body.get("sound");
        if (sound != null) {
            Map<String, Object> settings = sound instanceof Map<?, ?> raw ? copy(raw) : new LinkedHashMap<>(Map.of("id", sound));
            Object volume = first(body, "sound-volume", "sound_volume");
            Object pitch = first(body, "sound-pitch", "sound_pitch");
            if (volume != null) settings.put("volume", volume);
            if (pitch != null) settings.put("pitch", pitch);
            result.put("process", Map.of("sound", settings));
        }
        return result;
    }

    public static Map<String, Object> formatFluid(Map<String, Object> source) {
        Map<String, Object> body = copy(source);
        Map<String, Object> result = formattedBase(body, "fluid_tank");
        String type = Objects.toString(body.get("type"), "");
        result.put("operation", switch (type) {
            case "fluid_filling" -> "fill";
            case "fluid_emptying" -> "drain";
            case "soaking" -> "soak";
            default -> throw new IllegalArgumentException("Unknown internal fluid recipe type: " + type);
        });
        Object ingredient = first(body, "empty_input", "filled_input", "ingredient");
        if (ingredient != null) result.put("input", Map.of("item", ingredient));
        Object output = first(body, "filled_result", "empty_result", "result");
        if (output != null) result.put("output", item(output));
        Map<String, Object> fluid = new LinkedHashMap<>();
        if (body.containsKey("fluid")) fluid.put("match", withoutAmounts(body.get("fluid")));
        move(fluid, "amount-mb", body, "amount");
        move(fluid, "consume", body, "consume_fluid");
        result.put("fluid", fluid);
        if (body.containsKey("time")) result.put("process", Map.of("ticks", body.get("time")));
        return result;
    }

    private static Map<String, Object> base(Map<String, Object> source, String station) {
        if (!station.equals(source.get("station"))) throw new IllegalArgumentException("station must be " + station);
        fields(source, COMMON, "recipe");
        Map<String, Object> result = copy(source);
        for (String key : List.of("station", "input", "output", "process", "matching", "fluid", "operation")) result.remove(key);
        if (!station.equals("fluid_tank") && (source.containsKey("fluid") || source.containsKey("operation"))) throw new IllegalArgumentException("fluid and operation require station: fluid_tank");
        if (!station.equals("cooking_pot") && source.containsKey("matching")) throw new IllegalArgumentException("matching requires station: cooking_pot");
        if (!station.equals("cooking_pot") && source.containsKey("group")) throw new IllegalArgumentException("group requires station: cooking_pot");
        return result;
    }

    private static Map<String, Object> formattedBase(Map<String, Object> source, String station) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("station", station);
        for (String key : List.of("group", "category", "priority")) move(result, key, source, key);
        source.forEach((key, value) -> { if (key.startsWith("x-") || key.equals("extensions")) result.put(key, value); });
        return result;
    }

    private static Map<String, Object> block(Map<String, Object> parent, String field, Set<String> allowed) {
        Object raw = parent.get(field);
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException(field + " must be a mapping");
        Map<String, Object> result = copy(values);
        fields(result, allowed, field);
        return result;
    }

    private static void fields(Map<String, Object> source, Set<String> allowed, String path) {
        for (String key : source.keySet()) if (!allowed.contains(key) && !key.startsWith("x-") && !key.equals("extensions"))
            throw new IllegalArgumentException("Unknown field " + path + "." + key);
    }

    private static Map<String, Object> item(Object output) {
        Map<String, Object> result = output instanceof Map<?, ?> values ? copy(values) : new LinkedHashMap<>(Map.of("item", output));
        if (result.containsKey("id")) {
            Object id = result.remove("id");
            if (result.containsKey("item") && !Objects.equals(result.get("item"), id)) throw new IllegalArgumentException("Conflicting result item/id");
            result.putIfAbsent("item", id);
        }
        return result;
    }

    private static void verifyAmounts(Object expression) {
        if (expression instanceof Map<?, ?> map) {
            if (map.containsKey("amount")) throw new IllegalArgumentException("fluid.match.amount is unsupported; use fluid.amount-mb");
            if (map.containsKey("any-of")) verifyAmounts(map.get("any-of"));
        } else if (expression instanceof List<?> choices) choices.forEach(NativeRecipeSchema::verifyAmounts);
    }

    private static Object withoutAmounts(Object expression) {
        if (expression instanceof Map<?, ?> raw) {
            Map<String, Object> map = copy(raw); map.remove("amount");
            if (map.containsKey("any-of")) map.put("any-of", withoutAmounts(map.get("any-of")));
            return map;
        }
        if (expression instanceof List<?> list) return list.stream().map(NativeRecipeSchema::withoutAmounts).toList();
        return expression;
    }

    private static void put(Map<String, Object> result, String key, Map<String, Object> source, String field) { move(result, key, source, field); }
    private static void move(Map<String, Object> result, String key, Map<String, Object> source, String field) { if (source.containsKey(field)) result.put(key, source.get(field)); }
    private static Object first(Map<String, Object> source, String... fields) { for (String field : fields) if (source.containsKey(field)) return source.get(field); return null; }

    public static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), copyValue(value)));
        return result;
    }
    private static Object copyValue(Object value) {
        if (value instanceof ConfigurationSection section) return copy(section.getValues(false));
        if (value instanceof Map<?, ?> map) return copy(map);
        if (value instanceof List<?> list) return list.stream().map(NativeRecipeSchema::copyValue).toList();
        return value;
    }
    private static ConfigurationSection section(Map<String, Object> body) {
        YamlConfiguration result = new YamlConfiguration();
        result.options().pathSeparator('\u0001');
        body.forEach((key, value) -> com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(result, key, value));
        return result;
    }
}
