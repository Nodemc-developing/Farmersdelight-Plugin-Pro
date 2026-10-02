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
                papersdelight_recipes#addon:
                  addon:soup.v2: {type: cooking, result: minecraft:carrot, extension_note: original}
                  addon:other: {type: cutting, ingredient: minecraft:carrot}
                """, true);
        var source = new RecipeSource(directory.resolve("recipe.yml"), List.of("papersdelight_recipes#addon", "addon:soup.v2"), true, true);
        assertEquals("original", source.body(yaml).get("extension_note"));
        source.put(yaml, Map.of("type", "cooking", "result", "minecraft:potato"));
        var saved = PlainYamlDocuments.parse(yaml.saveToString(), true);
        assertEquals("minecraft:potato", source.body(saved).get("result"));
        assertTrue(saved.getConfigurationSection("papersdelight_recipes#addon").getKeys(false).contains("addon:other"));
        assertTrue(saved.getConfigurationSection("items").getKeys(false).contains("addon:model.v2"));
        source.put(saved, null);
        assertFalse(saved.getConfigurationSection("papersdelight_recipes#addon").getKeys(false).contains("addon:soup.v2"));
    }

    @Test void staleOrGeneratedRecipeSourcesCannotCreateAnUnrelatedOverride() throws Exception {
        var yaml = PlainYamlDocuments.parse("config_factory: {blueprint: {papersdelight_recipes: {}}, instances: [one]}\n", true);
        String before = yaml.saveToString();
        var generated = new RecipeSource(directory.resolve("recipe.yml"), List.of("papersdelight_recipes", "generated:meal"), true, true);
        assertThrows(IllegalStateException.class, () -> generated.put(yaml, Map.of("type", "cooking")));
        assertThrows(IllegalStateException.class, () -> generated.put(yaml, null));
        assertEquals(before, yaml.saveToString());
    }

    @Test void newSourcesCanCreateNestedGroupsWithoutTouchingOtherRoots() throws Exception {
        var yaml = PlainYamlDocuments.parse("papersdelight_recipes: {}\ncustom_cooking_pot_recipes: {}\n", true);
        var source = new RecipeSource(directory.resolve("recipe.yml"), List.of("custom_cooking_pot_recipes", "addon:big.pot", "addon:meal.v2"), true);
        source.put(yaml, Map.of("type", "cooking", "result", "minecraft:carrot"));
        assertEquals("minecraft:carrot", source.body(PlainYamlDocuments.parse(yaml.saveToString(), true)).get("result"));
        assertTrue(yaml.isConfigurationSection("papersdelight_recipes"));
    }
}
