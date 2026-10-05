package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeSpecTest {
    @Test void fillingReadsInputOutputAndExplicitMilliBucketQuantity() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                recipe:
                  station: fluid_tank
                  operation: fill
                  input: {item: minecraft:glass_bottle}
                  output: {item: minecraft:potion}
                  fluid: {match: '#c:water', amount-mb: 250}
                """);
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:water_bottle", yaml.getConfigurationSection("recipe"));
        assertEquals("fluid_filling", recipe.type());
        assertEquals("c:water", recipe.fluidId());
        assertTrue(recipe.fluidTag());
        assertEquals(250, recipe.amount());
        assertEquals(0, recipe.timeTicks());
        assertEquals("minecraft:potion", recipe.result().get("item"));
        assertEquals(1, recipe.result().get("count"));
        assertInstanceOf(RecipeIngredient.Item.class, recipe.ingredient());
    }

    @Test void drainingKeepsTheSameInputOutputShape() {
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:bucket", Map.of("station", "fluid_tank", "operation", "drain",
                "fluid", Map.of("match", "minecraft:water", "amount-mb", 1000),
                "input", Map.of("item", "minecraft:water_bucket"), "output", Map.of("item", "minecraft:bucket")));
        assertEquals("fluid_emptying", recipe.type());
        assertFalse(recipe.fluidTag());
        assertEquals("minecraft:water_bucket", ((RecipeIngredient.Item) recipe.ingredient()).key().toString());
        assertEquals("minecraft:bucket", recipe.result().get("item"));
        assertTrue(recipe.consumeFluid());
    }

    @Test void soakingRetainsAdvancedIngredientAndNonConsumingTimedRequirement() {
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:wet_material", Map.of(
                "station", "fluid_tank", "operation", "soak",
                "input", Map.of("item", "advtag:example:porous"),
                "fluid", Map.of("match", "#c:water", "amount-mb", 1, "consume", false),
                "output", Map.of("item", "minecraft:wet_sponge"), "process", Map.of("ticks", 20)));
        assertInstanceOf(RecipeIngredient.AdvancedTag.class, recipe.ingredient());
        assertEquals(20, recipe.timeTicks());
        assertFalse(recipe.consumeFluid());
        assertEquals(1, recipe.amount());
    }

    @Test void exactLongAmountsAreNeverRoundedOrOverflowed() {
        Map<String, Object> body = basic();
        body.put("fluid", Map.of("match", "minecraft:water", "amount-mb", Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, FluidRecipeSpec.parse("example:large", body).amount());
        for (Object invalid : new Object[]{0, -1, "1.5", "9223372036854775808", Double.NaN}) {
            body.put("fluid", Map.of("match", "minecraft:water", "amount-mb", invalid));
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:invalid", body));
        }
    }

    @Test void nestedChoicesAndOutputComponentsAreDeeplyImmutable() {
        Map<String, Object> components = new LinkedHashMap<>(Map.of("minecraft:custom_name", "soaked"));
        Map<String, Object> output = new LinkedHashMap<>(Map.of("item", "minecraft:wet_sponge", "components", components));
        Map<String, Object> body = basic();
        body.put("input", Map.of("item", Map.of("items", List.of("minecraft:sponge", "minecraft:paper"))));
        body.put("fluid", Map.of("match", Map.of("tag", "c:water"), "amount-mb", 1000));
        body.put("output", output);
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:immutable", body);
        components.clear(); output.clear();
        assertInstanceOf(RecipeIngredient.Choice.class, recipe.ingredient());
        assertEquals("minecraft:wet_sponge", recipe.result().get("item"));
        assertEquals("soaked", ((Map<?, ?>) recipe.result().get("components")).get("minecraft:custom_name"));
        assertThrows(UnsupportedOperationException.class, () -> recipe.result().put("count", 99));
    }

    @Test void invalidTimeCountsBooleansAndAmbiguousFluidsFailBeforePublishing() {
        for (Map<String, Object> mutation : List.of(
                Map.<String, Object>of("process", Map.of("ticks", -1)),
                Map.<String, Object>of("process", Map.of("ticks", "0.5")),
                Map.<String, Object>of("output", Map.of("item", "minecraft:wet_sponge", "count", 0)),
                Map.<String, Object>of("fluid", Map.of("match", "minecraft:water", "consume", "maybe")),
                Map.<String, Object>of("fluid", Map.of("match", Map.of("id", "minecraft:water", "tag", "c:water"))))) {
            Map<String, Object> body = basic();
            body.putAll(mutation);
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:invalid", body));
        }
    }

    @Test void defaultOutputUsesNativeContainersButSoakingRequiresOutput() {
        Map<String, Object> body = basic();
        body.remove("output");
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:missing", body));
        for (String operation : List.of("fill", "drain")) {
            body.put("operation", operation);
            assertTrue(FluidRecipeSpec.parse("example:handler", body).result().isEmpty());
            body.put("fluid", Map.of("match", "minecraft:water", "consume", false));
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:duplication", body));
            body.put("fluid", Map.of("match", "minecraft:water"));
        }
    }

    @Test void nativeBoundaryRejectsUnimplementedConditionsAndAmbiguousQuantity() {
        var unknown = basic();
        unknown.put("conditions", List.of("example:unimplemented"));
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:unknown", unknown));
        var contradictory = basic();
        contradictory.put("fluid", Map.of("match", Map.of("id", "minecraft:water", "amount", 2000), "amount-mb", 1000));
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:contradiction", contradictory));
        var multipleInputs = basic();
        multipleInputs.put("input", Map.of("item", Map.of("item", "minecraft:sponge", "count", 2)));
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:count", multipleInputs));
    }

    @Test void publicParserRequiresItsStationAndChecksPriorityAsAnExactInt() {
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:flat",
                Map.of("type", "soaking", "ingredient", "minecraft:sponge", "fluid", "minecraft:water", "result", "minecraft:wet_sponge")));
        var wrongStation = basic();
        wrongStation.put("station", "cooking_pot");
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:wrong", wrongStation));
        var body = basic();
        for (Object priority : new Object[]{"2147483648", "-2147483649", "0.25"}) {
            body.put("priority", priority);
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:priority", body));
        }
        body.put("priority", Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, FluidRecipeSpec.parse("example:priority", body).priority());
    }

    private static Map<String, Object> basic() {
        return new LinkedHashMap<>(Map.of("station", "fluid_tank", "operation", "soak",
                "input", Map.of("item", "minecraft:sponge"), "fluid", Map.of("match", "minecraft:water", "amount-mb", 1000),
                "output", Map.of("item", "minecraft:wet_sponge")));
    }
}
