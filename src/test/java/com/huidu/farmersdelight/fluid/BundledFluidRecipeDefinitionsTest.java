package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BundledFluidRecipeDefinitionsTest {
    @Test void everyBundledRecipeParsesAndAdvancedIngredientGroupsHaveConcreteMembers() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "craftengine/farmersdelight_fluids/configuration/fluid_recipes.yml");
        assertNotNull(resource);
        try (resource) {
            var document = PlainYamlDocuments.parse(new String(resource.readAllBytes(), StandardCharsets.UTF_8), true);
            var recipes = document.getConfigurationSection("papersdelight_recipes");
            var groups = document.getConfigurationSection("advanced_tags");
            assertNotNull(recipes);
            assertNotNull(groups);
            assertEquals(24, recipes.getKeys(false).size());
            for (String id : recipes.getKeys(false)) {
                var recipe = FluidRecipeSpec.parse(id, recipes.getConfigurationSection(id));
                validateAdvancedGroups(recipe.ingredient(), groups, id);
            }
            assertEquals(java.util.List.of("minecraft:wheat"),
                    groups.getConfigurationSection("c:crops/wheat").getStringList("values"));
        }
    }

    private static void validateAdvancedGroups(RecipeIngredient ingredient, ConfigurationSection groups, String recipe) {
        if (ingredient instanceof RecipeIngredient.AdvancedTag tag) {
            var definition = groups.getConfigurationSection(tag.key().toString());
            assertNotNull(definition, recipe + " requires a shipped advanced group " + tag.key());
            var values = definition.getStringList("values");
            assertFalse(values.isEmpty(), recipe + " advanced group must have actual members");
            assertTrue(values.stream().allMatch(value -> value.contains(":") && !value.startsWith("#")
                    && !value.startsWith("advtag:")), recipe + " bundled leaf group must contain concrete item IDs");
        } else if (ingredient instanceof RecipeIngredient.Choice choice) {
            for (var option : choice.options()) validateAdvancedGroups(option, groups, recipe);
        }
    }
}
