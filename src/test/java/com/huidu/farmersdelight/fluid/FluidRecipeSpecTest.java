package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeSpecTest {
    @Test void papersFillingFieldsAndMilliBucketUnitAreKept() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                recipe:
                  type: fluid_filling
                  fluid: '#c:water'
                  amount: 250
                  empty_input: minecraft:glass_bottle
                  filled_result: minecraft:potion
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

    @Test void papersEmptyingUsesItsOwnInputAndOutputFieldNames() {
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:bucket", Map.of("type", "fluid_emptying",
                "fluid", "minecraft:water", "amount", 1000, "filled_input", "minecraft:water_bucket",
                "empty_result", "minecraft:bucket"));
        assertFalse(recipe.fluidTag());
        assertEquals("minecraft:water_bucket", ((RecipeIngredient.Item) recipe.ingredient()).key().toString());
        assertEquals("minecraft:bucket", recipe.result().get("item"));
        assertTrue(recipe.consumeFluid());
    }

    @Test void soakingRetainsAdvancedIngredientAndNonConsumingTimedRequirement() {
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:wet_material", Map.of("type", "soaking",
                "ingredient", "advtag:example:porous", "fluid", "#c:water", "amount", 1,
                "result", "minecraft:wet_sponge", "time", 20, "consume_fluid", false));
        assertInstanceOf(RecipeIngredient.AdvancedTag.class, recipe.ingredient());
        assertEquals(20, recipe.timeTicks());
        assertFalse(recipe.consumeFluid());
        assertEquals(1, recipe.amount());
    }

    @Test void exactLongAmountsAreNeverRoundedOrOverflowed() {
        Map<String, Object> body = basic();
        body.put("amount", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, FluidRecipeSpec.parse("example:large", body).amount());
        for (Object invalid : new Object[]{0, -1, "1.5", "9223372036854775808", Double.NaN}) {
            body.put("amount", invalid);
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:invalid", body));
        }
    }

    @Test void independentAliasesPreserveNestedChoicesAndDeeplyImmutableOutput() {
        Map<String, Object> components = new LinkedHashMap<>(Map.of("minecraft:custom_name", "soaked"));
        Map<String, Object> output = new LinkedHashMap<>(Map.of("id", "minecraft:wet_sponge", "components", components));
        Map<String, Object> body = basic();
        body.put("ingredient", Map.of("items", java.util.List.of("minecraft:sponge", "minecraft:paper")));
        body.put("fluid", Map.of("tag", "c:water", "amount", 1000));
        body.put("result", output);
        FluidRecipeSpec recipe = FluidRecipeSpec.parse("example:immutable", body);
        components.clear();
        output.clear();
        assertInstanceOf(RecipeIngredient.Choice.class, recipe.ingredient());
        assertEquals("minecraft:wet_sponge", recipe.result().get("item"));
        assertEquals("soaked", ((Map<?, ?>) recipe.result().get("components")).get("minecraft:custom_name"));
        assertThrows(UnsupportedOperationException.class, () -> recipe.result().put("count", 99));
    }

    @Test void invalidTimeCountsBooleansAndAmbiguousFluidsFailBeforePublishing() {
        for (Map<String, Object> mutation : java.util.List.of(
                Map.<String, Object>of("time", -1), Map.<String, Object>of("time", "0.5"),
                Map.<String, Object>of("result", Map.of("id", "minecraft:wet_sponge", "count", 0)),
                Map.<String, Object>of("consume_fluid", "maybe"),
                Map.<String, Object>of("fluid", Map.of("id", "minecraft:water", "tag", "c:water")))) {
            Map<String, Object> body = basic();
            body.putAll(mutation);
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:invalid", body));
        }
    }

    @Test void emptyDefaultOutputRequiresAContainerButSoakingAlwaysRequiresResult() {
        Map<String, Object> body = basic();
        body.remove("result");
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:missing", body));
        body.put("type", "fluid_filling");
        assertTrue(FluidRecipeSpec.parse("example:handler", body).result().isEmpty());
        body.put("consume_fluid", false);
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeSpec.parse("example:duplication", body));
    }

    private static Map<String, Object> basic() {
        return new LinkedHashMap<>(Map.of("type", "soaking", "ingredient", "minecraft:sponge",
                "fluid", "minecraft:water", "amount", 1000, "result", "minecraft:wet_sponge"));
    }
}
