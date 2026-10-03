package com.huidu.farmersdelight.fluid;

import org.bukkit.configuration.ConfigurationSection;

import java.math.BigDecimal;
import java.util.*;

/** Plain immutable fluid expressions; semantic fields are validated before registry binding. */
public final class FluidExpression {
    private static final Set<String> FIELDS = Set.of("id", "fluid", "tag", "any-of", "amount", "components", "exact-components");
    private FluidExpression() { }

    public static Object capture(Object value) { return capture(value, 0); }
    private static Object capture(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("fluid: alternatives are nested too deeply");
        if (value instanceof ConfigurationSection section) value = section.getValues(false);
        if (value instanceof String text) {
            String id = text.trim();
            identifier(id.startsWith("#") ? id.substring(1) : id);
            return id;
        }
        if (value instanceof List<?> alternatives) {
            if (alternatives.isEmpty() || alternatives.size() > 256) throw new IllegalArgumentException("fluid: alternatives must contain 1..256 entries");
            return alternatives.stream().map(part -> capture(part, depth + 1)).toList();
        }
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("fluid: expected an ID, tag, alternatives list or expression map");
        Map<String, Object> map = new LinkedHashMap<>();
        int forms = 0;
        for (var entry : raw.entrySet()) {
            String key = Objects.toString(entry.getKey());
            if (!FIELDS.contains(key) && !key.startsWith("x-") && !key.equals("extensions")) {
                throw new IllegalArgumentException("fluid." + key + ": unsupported matching field; recipe disabled");
            }
            Object part = entry.getValue();
            if (part instanceof ConfigurationSection section) part = section.getValues(false);
            switch (key) {
                case "id", "fluid", "tag" -> { forms++; identifier(Objects.toString(part, "").replaceFirst("^#", "")); }
                case "any-of" -> { forms++; part = capture(part, depth + 1); if (!(part instanceof List<?>)) throw new IllegalArgumentException("fluid.any-of must be a list"); }
                case "amount" -> { long amount = integer(part, "fluid.amount"); if (amount <= 0) throw new IllegalArgumentException("fluid.amount must be positive"); }
                case "components" -> { if (!(part instanceof Map<?, ?> values) || values.size() > 64) throw new IllegalArgumentException("fluid.components must be a map of at most 64 values"); values.keySet().forEach(id -> identifier(String.valueOf(id))); }
                case "exact-components" -> { if (!(part instanceof Boolean)) throw new IllegalArgumentException("fluid.exact-components must be a boolean"); }
                default -> { }
            }
            map.put(key, immutable(part));
        }
        if (forms != 1) throw new IllegalArgumentException("fluid: specify exactly one of id, fluid, tag or any-of");
        if (map.containsKey("exact-components") && !map.containsKey("components")) throw new IllegalArgumentException("fluid.exact-components requires components");
        return Collections.unmodifiableMap(map);
    }

    public static Object immutable(Object value) {
        if (value instanceof ConfigurationSection section) value = section.getValues(false);
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> copy = new LinkedHashMap<>();
            raw.forEach((key, part) -> copy.put(String.valueOf(key), immutable(part)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> parts) return parts.stream().map(FluidExpression::immutable).toList();
        if (value instanceof byte[] bytes) return bytes.clone();
        return value;
    }

    public static long amount(Object value, long fallback) {
        return value instanceof Map<?, ?> map && map.containsKey("amount") ? integer(map.get("amount"), "fluid.amount") : fallback;
    }
    /** One atomic operation has one quantity; alternatives cannot silently redefine that quantity. */
    public static void requireAmount(Object value, long amount) {
        if (value instanceof List<?> list) { list.forEach(part -> requireAmount(part, amount)); return; }
        if (!(value instanceof Map<?, ?> map)) return;
        if (map.containsKey("amount") && integer(map.get("amount"), "fluid.amount") != amount)
            throw new IllegalArgumentException("fluid.amount: every alternative must use the recipe's one transfer amount");
        if (map.containsKey("any-of")) requireAmount(map.get("any-of"), amount);
    }

    public static String primary(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof List<?> parts) return primary(parts.getFirst());
        Map<?, ?> map = (Map<?, ?>) value;
        if (map.containsKey("any-of")) return primary(map.get("any-of"));
        if (map.containsKey("tag")) return "#" + String.valueOf(map.get("tag")).replaceFirst("^#", "");
        return String.valueOf(map.containsKey("fluid") ? map.get("fluid") : map.get("id"));
    }

    public static String constructibleId(Object value) {
        if (value instanceof String text) return text.startsWith("#") ? null : text;
        if (!(value instanceof Map<?, ?> map) || map.containsKey("tag") || map.containsKey("any-of") || map.containsKey("components")) return null;
        return String.valueOf(map.containsKey("fluid") ? map.get("fluid") : map.get("id"));
    }

    public static String display(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, part) -> entries.add(quote(String.valueOf(key)) + ": " + flow(part)));
            return "{" + String.join(", ", entries) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(", ", list.stream().map(FluidExpression::flow).toList()) + "]";
        return String.valueOf(value);
    }
    private static String flow(Object value) { return value instanceof String text ? quote(text) : display(value); }
    private static String quote(String text) { return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'; }
    private static void identifier(String value) {
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid namespaced fluid/component identifier: " + value);
    }
    private static long integer(Object value, String field) {
        try { return new BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (NumberFormatException | ArithmeticException invalid) { throw new IllegalArgumentException(field + " must be an exact long integer", invalid); }
    }
}
