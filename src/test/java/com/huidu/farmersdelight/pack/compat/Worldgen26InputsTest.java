package com.huidu.farmersdelight.pack.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Worldgen26InputsTest {
    @Test void knownSimpleFeatureRetainsEveryRulePredicateAndOptionalField() {
        var predicate = Map.of("type", "minecraft:all_of", "predicates", List.of(
                Map.of("type", "minecraft:matching_blocks", "blocks", "minecraft:air"),
                Map.of("type", "minecraft:matching_block_tag", "tag", "minecraft:dirt", "offset", List.of(0, -1, 0))));
        var input = Map.<String, Object>of("farmersdelight:patch_test", Map.of("unknown_definition_option", true,
                "feature", Map.of("type", "minecraft:simple_block", "config", Map.of("schedule_tick", true,
                        "to_place", Map.of("type", "minecraft:rule_based_state_provider", "rules", List.of(
                                Map.of("if_true", predicate, "then", Map.of("type", "minecraft:simple_state_provider",
                                        "state", Map.of("Name", "farmersdelight:crop", "Properties", Map.of("age", "3"))))))))));
        var output = Worldgen26Inputs.adapt(input, true, "farmersdelight:crop"::equals);
        Map<?, ?> definition = (Map<?, ?>) output.values().get("farmersdelight:patch_test");
        Map<?, ?> feature = (Map<?, ?>) definition.get("feature");
        assertFalse(feature.containsKey("config"));
        assertEquals(true, feature.get("schedule_tick"));
        Map<?, ?> provider = (Map<?, ?>) feature.get("to_place");
        assertEquals("minecraft:rule_based", provider.get("type"));
        Map<?, ?> rule = (Map<?, ?>) ((List<?>) provider.get("rules")).getFirst();
        assertEquals(predicate, rule.get("if_true"));
        assertEquals(Map.of("type", "craftengine:simple_state_provider", "state",
                Map.of("Name", "farmersdelight:crop", "Properties", Map.of("age", "3"))), rule.get("then"));
        assertEquals(true, definition.get("unknown_definition_option"));
        assertEquals(3, output.changes().size());
        assertTrue(((Map<?, ?>) ((Map<?, ?>) input.get("farmersdelight:patch_test")).get("feature")).containsKey("config"));
    }

    @Test void blockColumnMovesAllFourConfigurationFieldsAndLeavesLayerOrder() {
        List<Object> layers = List.of(Map.of("height", 1, "provider", customProvider("lower")),
                Map.of("height", 1, "provider", customProvider("upper")));
        Map<String, Object> body = Map.of("layers", layers, "direction", "up", "allowed_placement",
                Map.of("type", "minecraft:replaceable"), "prioritize_tip", false);
        var input = Map.<String, Object>of("test:rice", Map.of("feature", Map.of("type", "minecraft:block_column", "config", body)));
        var output = Worldgen26Inputs.adapt(input, true, "farmersdelight:rice"::equals);
        Map<?, ?> feature = (Map<?, ?>) ((Map<?, ?>) output.values().get("test:rice")).get("feature");
        assertEquals(5, feature.size());
        assertEquals("up", feature.get("direction"));
        assertEquals(false, feature.get("prioritize_tip"));
        assertEquals(body.get("allowed_placement"), feature.get("allowed_placement"));
        var outLayers = (List<?>) feature.get("layers");
        for (int i = 0; i < 2; ++i) {
            Map<?, ?> provider = (Map<?, ?>) ((Map<?, ?>) outLayers.get(i)).get("provider");
            assertEquals("craftengine:simple_state_provider", provider.get("type"));
            assertEquals(i == 0 ? "lower" : "upper", ((Map<?, ?>) ((Map<?, ?>) provider.get("state")).get("Properties")).get("half"));
        }
        assertEquals(body, ((Map<?, ?>) ((Map<?, ?>) input.get("test:rice")).get("feature")).get("config"));
    }

    @Test void randomOffsetKeepsTheSameDistributionsOnBothIndependentHorizontalAxes() {
        var horizontal = Map.of("type", "minecraft:trapezoid", "min", -6, "max", 6, "plateau", 0);
        var vertical = Map.of("type", "minecraft:trapezoid", "min", -3, "max", 3, "plateau", 0);
        var input = Map.<String, Object>of("test:crop", Map.of("placement", List.of(Map.of("type", "minecraft:random_offset",
                "xz_spread", horizontal, "y_spread", vertical))));
        var output = Worldgen26Inputs.adapt(input, true, ignored -> false);
        var offset = (Map<?, ?>) ((List<?>) ((Map<?, ?>) output.values().get("test:crop")).get("placement")).getFirst();
        assertEquals(Map.of("type", "minecraft:offset", "x", horizontal, "z", horizontal, "y", vertical), offset);
        assertNotSame(offset.get("x"), offset.get("z"));
        assertEquals(1, output.changes().size());
    }

    @Test void onlyRegisteredAllCustomWeightedStatesChangeProviderType() {
        var allCustom = Map.of("type", "minecraft:weighted_state_provider", "entries", List.of(
                Map.of("weight", 1, "data", Map.of("Name", "farmersdelight:rice")),
                Map.of("weight", 2, "data", Map.of("Name", "farmersdelight:rice", "Properties", Map.of("age", "3")))));
        var mixed = Map.of("type", "minecraft:weighted_state_provider", "entries", List.of(
                Map.of("weight", 1, "data", Map.of("Name", "farmersdelight:rice")),
                Map.of("weight", 2, "data", Map.of("Name", "minecraft:air"))));
        var vanilla = Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:stone"));
        var input = Map.<String, Object>of("test:a", Map.of("custom", allCustom, "mixed", mixed, "vanilla", vanilla));
        var output = Worldgen26Inputs.adapt(input, true, "farmersdelight:rice"::equals);
        var definition = (Map<?, ?>) output.values().get("test:a");
        assertEquals("craftengine:weighted_state_provider", ((Map<?, ?>) definition.get("custom")).get("type"));
        assertEquals(allCustom.get("entries"), ((Map<?, ?>) definition.get("custom")).get("entries"));
        assertEquals(mixed, definition.get("mixed"));
        assertEquals(Map.of("type", "minecraft:simple", "state", vanilla.get("state")), definition.get("vanilla"));
        assertEquals("minecraft:simple_state_provider", vanilla.get("type"));
        assertTrue(output.changes().stream().anyMatch(change -> change.action().equals("refused")));
    }

    @Test void unknownAndConflictingSchemasArePreservedWithExplicitRefusal() {
        var unknown = Map.of("type", "future:feature", "config", Map.of("a", 1));
        var extension = Map.of("type", "minecraft:simple_block", "config", Map.of("to_place", "x", "future", 2));
        var conflicting = Map.of("type", "minecraft:simple_block", "to_place", "new",
                "config", Map.of("to_place", "old"));
        var input = Map.<String, Object>of("test:a", Map.of("unknown", unknown, "extension", extension, "conflicting", conflicting));
        var result = Worldgen26Inputs.adapt(input, true, ignored -> false);
        assertEquals(input, result.values());
        assertEquals(2, result.changes().size());
        assertTrue(result.changes().stream().allMatch(change -> change.action().equals("refused")));
    }

    @Test void olderRuntimeGetsAnIndependentCopyWithNoSchemaConversion() {
        var input = Map.<String, Object>of("test:a", Map.of("feature", Map.of("type", "minecraft:simple_block", "config",
                Map.of("to_place", Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "farmersdelight:rice"))))));
        var result = Worldgen26Inputs.adapt(input, false, ignored -> { fail("Old runtime must not consult new provider conversion"); return true; });
        assertEquals(input, result.values());
        assertNotSame(input, result.values());
        assertNotSame(input.get("test:a"), result.values().get("test:a"));
        assertTrue(result.changes().isEmpty());
    }

    @Test void ruleProviderUsesTheNativeRegistryIdWithoutChangingRulesFallbackOrPredicateOrder() {
        var fallback = Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "minecraft:air"));
        var weighted = Map.of("type", "minecraft:weighted_state_provider", "entries", List.of(
                Map.of("weight", 2, "data", Map.of("Name", "minecraft:dirt")),
                Map.of("weight", 3, "data", Map.of("Name", "minecraft:stone"))));
        var conditions = List.of(Map.of("type", "minecraft:matching_fluids", "fluids", "minecraft:water"),
                Map.of("type", "minecraft:matching_blocks", "blocks", "minecraft:air"));
        var input = Map.<String, Object>of("test:native", Map.of("provider", Map.of("type", "minecraft:rule_based_state_provider",
                "fallback", fallback, "rules", List.of(Map.of("if_true", Map.of("type", "minecraft:all_of", "predicates", conditions),
                        "then", weighted)), "future_option", "preserved")));
        var result = Worldgen26Inputs.adapt(input, true, ignored -> false);
        Map<?, ?> provider = (Map<?, ?>) ((Map<?, ?>) result.values().get("test:native")).get("provider");
        assertEquals("minecraft:rule_based", provider.get("type"));
        assertEquals("minecraft:simple", ((Map<?, ?>) provider.get("fallback")).get("type"));
        Map<?, ?> rule = (Map<?, ?>) ((List<?>) provider.get("rules")).getFirst();
        assertEquals(Map.of("type", "minecraft:all_of", "predicates", conditions), rule.get("if_true"));
        assertEquals(Map.of("type", "minecraft:weighted", "entries", weighted.get("entries")), rule.get("then"));
        assertEquals("preserved", provider.get("future_option"));
        assertEquals("minecraft:rule_based_state_provider", ((Map<?, ?>) ((Map<?, ?>) input.get("test:native")).get("provider")).get("type"));
        assertEquals("minecraft:simple_state_provider", fallback.get("type"));
    }

    private static Map<String, Object> customProvider(String half) {
        return Map.of("type", "minecraft:simple_state_provider", "state", Map.of("Name", "farmersdelight:rice",
                "Properties", Map.of("half", half)));
    }
}
