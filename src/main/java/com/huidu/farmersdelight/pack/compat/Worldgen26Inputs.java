package com.huidu.farmersdelight.pack.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Adapts only known legacy feature and provider shapes in private world-generation inputs. */
final class Worldgen26Inputs {
    record Change(String definition, String field, String action, String reason) { }
    record Result(Map<String, Object> values, List<Change> changes) { }

    static Result adapt(Map<String, Object> definitions, boolean modern, Predicate<String> customBlock) {
        List<Change> changes = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        definitions.forEach((id, value) -> result.put(id, visit(value, id, "", modern, customBlock, changes)));
        return new Result(result, List.copyOf(changes));
    }

    private static Object visit(Object value, String id, String path, boolean modern, Predicate<String> customBlock,
                                List<Change> changes) {
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); ++i) result.add(visit(list.get(i), id, path + "[" + i + "]", modern, customBlock, changes));
            return result;
        }
        if (!(value instanceof Map<?, ?> map)) return value;
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, child) -> result.put(String.valueOf(key), visit(child, id,
                path.isEmpty() ? String.valueOf(key) : path + "." + key, modern, customBlock, changes)));
        String type = result.get("type") instanceof String text ? text : "";
        if (modern && type.equals("minecraft:simple_block"))
            unwrap(result, Set.of("to_place", "schedule_tick"), Set.of("to_place"), id, path, changes);
        else if (modern && type.equals("minecraft:block_column"))
            unwrap(result, Set.of("layers", "direction", "allowed_placement", "prioritize_tip"),
                    Set.of("layers", "direction", "allowed_placement", "prioritize_tip"), id, path, changes);
        else if (modern && type.equals("minecraft:random_offset")) {
            if (result.keySet().equals(Set.of("type", "xz_spread", "y_spread"))) {
                Object horizontal = result.remove("xz_spread");
                Object vertical = result.remove("y_spread");
                result.put("type", "minecraft:offset");
                result.put("x", horizontal); result.put("y", vertical);
                result.put("z", ConfigPriorityFilter.copy(horizontal));
                changes.add(new Change(id, path, "converted", "random_offset xz_spread/y_spread -> offset x/y/z"));
            } else changes.add(new Change(id, path, "refused", "Unrecognized random_offset fields; original shape preserved"));
        } else if (type.equals("minecraft:simple_state_provider")) {
            if (customState(result.get("state"), customBlock)) {
                result.put("type", "craftengine:simple_state_provider");
                changes.add(new Change(id, path + ".type", "converted", "Registered custom block state provider"));
            } else if (modern && result.containsKey("state")) {
                result.put("type", "minecraft:simple");
                changes.add(new Change(id, path + ".type", "converted", "Native simple provider registry ID changed in 26.3"));
            }
        } else if (modern && type.equals("minecraft:rule_based_state_provider") && result.get("rules") instanceof List<?>) {
            result.put("type", "minecraft:rule_based");
            changes.add(new Change(id, path + ".type", "converted", "Native rule-based provider registry ID changed in 26.3"));
        } else if (type.equals("minecraft:weighted_state_provider") && result.get("entries") instanceof List<?> entries) {
            int custom = 0;
            for (Object entry : entries)
                if (entry instanceof Map<?, ?> body && customState(body.get("data"), customBlock)) ++custom;
            if (custom > 0 && custom == entries.size()) {
                result.put("type", "craftengine:weighted_state_provider");
                changes.add(new Change(id, path + ".type", "converted", "Registered custom weighted block states"));
            } else if (custom > 0) changes.add(new Change(id, path, "refused",
                    "Mixed vanilla/custom weighted states are unsupported; original shape preserved"));
            else if (modern) {
                result.put("type", "minecraft:weighted");
                changes.add(new Change(id, path + ".type", "converted", "Native weighted provider registry ID changed in 26.3"));
            }
        }
        return result;
    }

    private static boolean customState(Object value, Predicate<String> customBlock) {
        return value instanceof Map<?, ?> state && state.get("Name") instanceof String name && customBlock.test(name);
    }

    private static void unwrap(Map<String, Object> result, Set<String> allowed, Set<String> required,
                               String id, String path, List<Change> changes) {
        if (!result.containsKey("config")) return;
        if (!(result.get("config") instanceof Map<?, ?> body) || !body.keySet().containsAll(required)
                || !allowed.containsAll(body.keySet()) || body.keySet().stream().anyMatch(result::containsKey)) {
            changes.add(new Change(id, path + ".config", "refused",
                    "Unrecognized or conflicting legacy feature fields; original shape preserved"));
            return;
        }
        result.remove("config");
        body.forEach((key, value) -> result.put(String.valueOf(key), value));
        changes.add(new Change(id, path + ".config", "converted", "Known feature configuration fields moved to feature root"));
    }
}
