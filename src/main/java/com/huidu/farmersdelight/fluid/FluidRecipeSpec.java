package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.NativeRecipeSchema;
import com.huidu.farmersdelight.recipe.RecipeParsingSupport;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable recipe data. Fluid amounts use mB; delay is applied by the owning entity scheduler. */
public record FluidRecipeSpec(String id, String type, RecipeIngredient ingredient, Map<String, Object> result,
                              String fluidId, boolean fluidTag, long amount, long timeTicks,
                              boolean consumeFluid, int priority, Object fluidExpression) {
    public FluidRecipeSpec(String id, String type, RecipeIngredient ingredient, Map<String, Object> result,
                           String fluidId, boolean fluidTag, long amount, long timeTicks, boolean consumeFluid, int priority) {
        this(id, type, ingredient, result, fluidId, fluidTag, amount, timeTicks, consumeFluid, priority,
                (fluidTag ? "#" : "") + fluidId);
    }
    public FluidRecipeSpec {
        id = Objects.requireNonNull(id, "recipe id");
        type = Objects.requireNonNull(type, "recipe type").trim().toLowerCase(Locale.ROOT);
        if (!List.of("fluid_filling", "fluid_emptying", "soaking").contains(type)) {
            throw new IllegalArgumentException("Unsupported fluid recipe type: " + type);
        }
        Objects.requireNonNull(ingredient, "ingredient");
        result = immutableMap(result == null ? Map.of() : result);
        fluidId = canonicalId(fluidId);
        fluidExpression = FluidExpression.capture(fluidExpression);
        if (amount <= 0) throw new IllegalArgumentException("Fluid recipe amount must be positive mB");
        FluidExpression.requireAmount(fluidExpression, amount);
        if (timeTicks < 0) throw new IllegalArgumentException("Fluid recipe time cannot be negative ticks");
        if (type.equals("soaking") && result.isEmpty()) {
            throw new IllegalArgumentException("Soaking recipe requires result");
        }
    }

    public static FluidRecipeSpec parse(String id, ConfigurationSection section) {
        return parse(id, section.getValues(false));
    }

    public static FluidRecipeSpec parse(String id, Map<String, Object> body) {
        if (!"fluid_tank".equals(body.get("station"))) {
            throw new IllegalArgumentException("Fluid recipe station must be fluid_tank");
        }
        return parseCanonical(id, NativeRecipeSchema.normalizeFluid(body));
    }

    private static FluidRecipeSpec parseCanonical(String id, Map<String, Object> body) {
        String type = Objects.toString(body.get("type"), "").trim().toLowerCase(Locale.ROOT);
        String inputKey = switch (type) {
            case "fluid_filling" -> "empty_input";
            case "fluid_emptying" -> "filled_input";
            case "soaking" -> "ingredient";
            default -> throw new IllegalArgumentException("Unsupported fluid recipe type: " + type);
        };
        String outputKey = switch (type) {
            case "fluid_filling" -> "filled_result";
            case "fluid_emptying" -> "empty_result";
            default -> "result";
        };
        Object input = body.containsKey(inputKey) ? body.get(inputKey) : body.get("ingredient");
        Object output = body.containsKey(outputKey) ? body.get(outputKey) : body.get("result");
        Object fluidValue = body.get("fluid");
        long amount = exactLong(body.get("amount"), 1000, "amount");
        fluidValue = FluidExpression.capture(fluidValue);
        amount = FluidExpression.amount(fluidValue, amount);
        String fluid = FluidExpression.primary(fluidValue);
        boolean tag = fluid.startsWith("#");
        if (fluid.startsWith("#")) { tag = true; fluid = fluid.substring(1); }
        boolean consume = bool(body.get("consume_fluid"), true, "consume_fluid");
        // Filling/emptying must conserve fluid even when an unrelated option is present.
        if (!type.equals("soaking") && !consume) {
            throw new IllegalArgumentException("consume_fluid: false is only valid for soaking");
        }
        return new FluidRecipeSpec(id, type, RecipeParsingSupport.parseIngredientValue(input(input, 0)), resultMap(output),
                fluid, tag, amount, exactLong(body.get("time"), 0, "time"),
                consume, exactInt(body.get("priority"), 0, "priority"), fluidValue);
    }
    private static Object input(Object raw, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Input ingredient is nested too deeply");
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        if (!(raw instanceof Map<?, ?> source)) return raw;
        Map<String, Object> map = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("x-") || key.equals("extensions")) continue;
            if (!List.of("id", "item", "nbt", "items", "choice", "count").contains(key))
                throw new IllegalArgumentException("Unsupported input matching field: " + key);
            map.put(key, entry.getValue());
        }
        if (map.containsKey("count") && exactLong(map.remove("count"), 1, "input.count") != 1)
            throw new IllegalArgumentException("Fluid recipes process one input item; input.count must be one");
        if (map.containsKey("id")) {
            if (map.containsKey("item")) throw new IllegalArgumentException("Input requires id or item, not both");
            map.put("item", map.remove("id"));
        }
        if (map.containsKey("items") && map.containsKey("choice")) throw new IllegalArgumentException("Input requires items or choice, not both");
        String choices = map.containsKey("items") ? "items" : "choice";
        if (map.containsKey(choices)) {
            if (map.containsKey("item") || map.containsKey("nbt")) throw new IllegalArgumentException("Input choice cannot also specify an item or NBT");
            if (!(map.get(choices) instanceof List<?> values) || values.isEmpty() || values.size() > 256) throw new IllegalArgumentException("Input choices require 1..256 entries");
            map.put(choices, values.stream().map(value -> input(value, depth + 1)).toList());
        }
        return map;
    }

    private static Map<String, Object> resultMap(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        Map<String, Object> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            if (!result.containsKey("item") && result.containsKey("id")) result.put("item", result.get("id"));
            if (!result.containsKey("item") && !result.containsKey("nbt")) {
                throw new IllegalArgumentException("Fluid recipe result requires an item id or NBT snapshot");
            }
        } else {
            result.put("item", raw.toString().trim());
        }
        Object item = result.get("item");
        if (item != null && item.toString().isBlank()) throw new IllegalArgumentException("Result item cannot be blank");
        long count = exactLong(result.get("count"), 1, "result.count");
        if (count <= 0 || count > Integer.MAX_VALUE) throw new IllegalArgumentException("Result count must be a positive integer");
        result.put("count", (int) count);
        return result;
    }

    private static String canonicalId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Fluid identity cannot be empty");
        String value = id.trim().toLowerCase(Locale.ROOT);
        Key.of(value);
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Fluid identity must be namespaced: " + value);
        }
        return value;
    }

    private static boolean bool(Object value, boolean fallback, String field) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        if (value.toString().equalsIgnoreCase("true")) return true;
        if (value.toString().equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(field + " must be a boolean");
    }

    private static long exactLong(Object value, long fallback, String field) {
        if (value == null) return fallback;
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be a whole number within the signed long range", invalid);
        }
    }

    private static int exactInt(Object value, int fallback, String field) {
        try { return Math.toIntExact(exactLong(value, fallback, field)); }
        catch (ArithmeticException invalid) {
            throw new IllegalArgumentException(field + " must be within the signed int range", invalid);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof ConfigurationSection section) return immutableMap(section.getValues(false));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(FluidRecipeSpec::immutableValue).toList();
        return value;
    }
}
