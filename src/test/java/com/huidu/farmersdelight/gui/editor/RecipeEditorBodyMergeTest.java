package com.huidu.farmersdelight.gui.editor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.recipe.RecipeSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecipeEditorBodyMergeTest {
    @TempDir Path directory;
    @Test void editorChangesPreserveExtensionDataAndRemoveClearedOptionalFields() {
        var merged = RecipeEditorStore.mergeRecipeBody(Map.of("experience", 3.0, "priority", 10,
                "result", "minecraft:carrot", "custom_rewards", Map.of("coins", 5)),
                Map.of("type", "cooking", "result", "minecraft:potato", "time", 40));
        assertEquals(Map.of("coins", 5), merged.get("custom_rewards"));
        assertEquals("minecraft:potato", merged.get("result"));
        assertFalse(merged.containsKey("experience"));
        assertFalse(merged.containsKey("priority"));
    }

    @Test void deletingASourceClearsOnlyItsExistingDefaultFileOverrideFlag() throws Exception {
        Path defaults = directory.resolve("recipes.yml");
        var yaml = PlainYamlDocuments.parse("""
                papersdelight_recipes#meals:
                  addon:recipe.v2: {type: cooking, result: minecraft:carrot}
                  addon:other: {type: cooking, result: minecraft:potato}
                external-overrides:
                  cooking_pot: [addon:recipe.v2, addon:other]
                  cutting_board: [addon:recipe.v2]
                  custom_extension: kept
                """, true);
        var source = new RecipeSource(defaults, List.of("papersdelight_recipes#meals", "addon:recipe.v2"), true, true);
        RecipeEditorStore.deleteRecipeSource(yaml, source, true, defaults);
        assertTrue(source.body(yaml).isEmpty());
        var overrides = yaml.getConfigurationSection("external-overrides");
        assertEquals(List.of("addon:other"), overrides.getStringList("cooking_pot"));
        assertEquals(List.of("addon:recipe.v2"), overrides.getStringList("cutting_board"));
        assertEquals("kept", overrides.getString("custom_extension"));
        assertTrue(yaml.getConfigurationSection("papersdelight_recipes#meals").isConfigurationSection("addon:other"));

        var external = PlainYamlDocuments.parse("""
                cutting_board_recipes: {addon:recipe.v2: {input: minecraft:carrot}}
                external-overrides: {cutting_board: [addon:recipe.v2]}
                """, true);
        var externalSource = new RecipeSource(directory.resolve("other-pack.yml"),
                List.of("cutting_board_recipes", "addon:recipe.v2"), false, true);
        RecipeEditorStore.deleteRecipeSource(external, externalSource, false, defaults);
        assertEquals(List.of("addon:recipe.v2"), external.getConfigurationSection("external-overrides").getStringList("cutting_board"));
    }

    @Test void staleDeletionKeepsFlagsAndValidDeletionDoesNotCreateOverrideNodes() throws Exception {
        Path defaults = directory.resolve("recipes.yml");
        var yaml = PlainYamlDocuments.parse("""
                cooking_pot_recipes: {addon:existing.v2: {result: minecraft:carrot}}
                external-overrides: {cooking_pot: [addon:missing.v2]}
                """, true);
        String before = yaml.saveToString();
        var absent = new RecipeSource(defaults, List.of("cooking_pot_recipes", "addon:missing.v2"), false, true);
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.deleteRecipeSource(yaml, absent, true, defaults));
        assertEquals(before, yaml.saveToString());
        var withoutFlags = PlainYamlDocuments.parse("cutting_board_recipes: {addon:existing.v2: {input: minecraft:carrot}}\n", true);
        var existing = new RecipeSource(defaults, List.of("cutting_board_recipes", "addon:existing.v2"), false, true);
        RecipeEditorStore.deleteRecipeSource(withoutFlags, existing, false, defaults);
        assertFalse(withoutFlags.isSet("external-overrides"));
        assertFalse(withoutFlags.getConfigurationSection("cutting_board_recipes").isSet("addon:existing.v2"));
    }
}
