package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSourceTest {
    @TempDir Path directory;

    @Test void writesOnlyTheExactRecipeNodeAndKeepsDottedIdentifiersAndSiblings() throws Exception {
        var yaml = PlainYamlDocuments.parse("""
                items: {addon:model.v2: {material: STONE}}
                farmersdelight_recipes#addon:
                  addon:soup.v2: {type: cooking, result: minecraft:carrot, extension_note: original}
                  addon:other: {type: cutting, ingredient: minecraft:carrot}
                """, true);
        var source = new RecipeSource(directory.resolve("recipe.yml"), List.of("farmersdelight_recipes#addon", "addon:soup.v2"), true, true);
        assertEquals("original", source.body(yaml).get("extension_note"));
        source.put(yaml, Map.of("type", "cooking", "result", "minecraft:potato"));
        var saved = PlainYamlDocuments.parse(yaml.saveToString(), true);
        assertEquals("minecraft:potato", source.body(saved).get("result"));
        assertTrue(saved.getConfigurationSection("farmersdelight_recipes#addon").getKeys(false).contains("addon:other"));
        assertTrue(saved.getConfigurationSection("items").getKeys(false).contains("addon:model.v2"));
        source.put(saved, null);
        assertFalse(saved.getConfigurationSection("farmersdelight_recipes#addon").getKeys(false).contains("addon:soup.v2"));
    }

    @Test void staleOrGeneratedRecipeSourcesCannotCreateAnUnrelatedOverride() throws Exception {
        var yaml = PlainYamlDocuments.parse("config_factory: {blueprint: {farmersdelight_recipes: {}}, instances: [one]}\n", true);
        String before = yaml.saveToString();
        var generated = new RecipeSource(directory.resolve("recipe.yml"), List.of("farmersdelight_recipes", "generated:meal"), true, true);
        assertThrows(IllegalStateException.class, () -> generated.put(yaml, Map.of("type", "cooking")));
        assertThrows(IllegalStateException.class, () -> generated.put(yaml, null));
        assertEquals(before, yaml.saveToString());
    }

    @Test void newSourcesCanCreateNestedGroupsWithoutTouchingOtherRoots() throws Exception {
        var yaml = PlainYamlDocuments.parse("farmersdelight_recipes: {}\ncustom_cooking_pot_recipes: {}\n", true);
        var source = new RecipeSource(directory.resolve("recipe.yml"), List.of("custom_cooking_pot_recipes", "addon:big.pot", "addon:meal.v2"), true);
        source.put(yaml, Map.of("type", "cooking", "result", "minecraft:carrot"));
        assertEquals("minecraft:carrot", source.body(PlainYamlDocuments.parse(yaml.saveToString(), true)).get("result"));
        assertTrue(yaml.isConfigurationSection("farmersdelight_recipes"));
    }

    @Test void occupiedInvalidNodesAndEmptyMapsRemainDetectable() throws Exception {
        var yaml = PlainYamlDocuments.parse("farmersdelight_recipes: {addon:invalid: 7, addon:empty: {}}\n", true);
        for (String id : List.of("addon:invalid", "addon:empty")) {
            var source = new RecipeSource(directory.resolve("recipes.yml"), List.of("farmersdelight_recipes", id), true);
            assertTrue(source.exists(yaml));
            assertTrue(source.body(yaml).isEmpty());
        }
        assertFalse(new RecipeSource(directory.resolve("recipes.yml"),
                List.of("farmersdelight_recipes", "addon:absent"), true).exists(yaml));
    }
}
