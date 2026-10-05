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
        var merged = RecipeEditorStore.mergeRecipeBody(Map.of("station", "cooking_pot", "priority", 10,
                "output", Map.of("item", "minecraft:carrot"), "process", Map.of("ticks", 80, "experience", 3.0),
                "extensions", Map.of("rewards", Map.of("coins", 5))),
                Map.of("station", "cooking_pot", "output", Map.of("item", "minecraft:potato"), "process", Map.of("ticks", 40)));
        assertEquals(Map.of("rewards", Map.of("coins", 5)), merged.get("extensions"));
        assertEquals(Map.of("item", "minecraft:potato"), merged.get("output"));
        assertEquals(Map.of("ticks", 40), merged.get("process"));
        assertFalse(merged.containsKey("priority"));
    }

    @Test void nestedExtensionDataSurvivesEditingWithoutRestoringClearedGameplayFields() {
        var previous = Map.<String, Object>of("station", "cooking_pot",
                "input", Map.of("items", List.of(Map.of("item", "minecraft:carrot", "nbt", "old",
                        "metadata", Map.of("note", "ingredient"))), "x-input", "kept"),
                "output", Map.of("item", "minecraft:carrot", "count", 4, "components",
                        Map.of("minecraft:custom_data", Map.of("x-functional", 7)), "x-output", "kept"),
                "process", Map.of("ticks", 120, "experience", 2.0, "x-process", "kept"),
                "matching", Map.of("mode", "fuzzy", "perfect", Map.of("x-functional", 4), "x-match", "kept"));
        var edited = Map.<String, Object>of("station", "cooking_pot", "input",
                Map.of("items", List.of(Map.of("item", "minecraft:potato"))), "output", Map.of("item", "minecraft:potato"),
                "process", Map.of("ticks", 80), "matching", Map.of("mode", "fuzzy", "perfect", Map.of("minecraft:potato", 1)));
        var merged = RecipeEditorStore.mergeRecipeBody(previous, edited);
        var input = (Map<?, ?>) merged.get("input");
        assertEquals("kept", input.get("x-input"));
        var leaf = (Map<?, ?>) ((List<?>) input.get("items")).getFirst();
        assertEquals(Map.of("note", "ingredient"), leaf.get("metadata"));
        assertFalse(leaf.containsKey("nbt"));
        var output = (Map<?, ?>) merged.get("output");
        assertEquals("kept", output.get("x-output"));
        assertFalse(output.containsKey("count"));
        assertFalse(output.containsKey("components"));
        assertEquals(Map.of("ticks", 80, "x-process", "kept"), merged.get("process"));
        var matching = (Map<?, ?>) merged.get("matching");
        assertEquals("kept", matching.get("x-match"));
        assertEquals(Map.of("minecraft:potato", 1), matching.get("perfect"));
        assertFalse(merged.containsKey("extensions"));
    }

    @Test void removedNestedBlocksArchiveExtensionsInsteadOfReactivatingAnOldOption() {
        var merged = RecipeEditorStore.mergeRecipeBody(Map.of("station", "cutting_board", "process",
                Map.of("sound", Map.of("id", "old", "volume", 2.0, "extensions", Map.of("note", "keep")))),
                Map.of("station", "cutting_board", "output", List.of(Map.of("item", "minecraft:stick"))));
        assertFalse(merged.containsKey("process"));
        var saved = (Map<?, ?>) ((Map<?, ?>) merged.get("extensions")).get("saved_fields");
        assertEquals(Map.of("note", "keep"), saved.get("recipe/process/sound/extensions"));
    }

    @Test void removedComponentAndFuzzyMapsAreNotMistakenForExtensionMetadata() {
        var merged = RecipeEditorStore.mergeRecipeBody(Map.of("station", "cooking_pot",
                "output", Map.of("item", "minecraft:carrot", "components", Map.of("minecraft:custom_data",
                        Map.of("x-functional", 4, "metadata", Map.of("consume", true)))),
                "matching", Map.of("mode", "fuzzy", "perfect", Map.of("x-functional", 6))),
                Map.of("station", "cooking_pot", "output", Map.of("item", "minecraft:potato")));
        assertEquals(Map.of("item", "minecraft:potato"), merged.get("output"));
        assertFalse(merged.containsKey("matching"));
        assertFalse(merged.containsKey("extensions"));
    }

    @Test void archivingKeepsOpaqueRootExtensionsAndUnrelatedExtensionKeys() {
        var merged = RecipeEditorStore.mergeRecipeBody(Map.of("station", "cooking_pot", "extensions", "opaque",
                "matching", Map.of("mode", "fuzzy", "extensions", Map.of("item", "metadata", "count", 9))),
                Map.of("station", "cooking_pot", "output", Map.of("item", "minecraft:potato")));
        var extensions = (Map<?, ?>) merged.get("extensions");
        assertEquals("opaque", extensions.get("original_extensions"));
        var saved = (Map<?, ?>) extensions.get("saved_fields");
        assertEquals(Map.of("item", "metadata", "count", 9), saved.get("recipe/matching/extensions"));
        assertFalse(merged.containsKey("matching"));
    }

    @Test void nativeSaveKeepsSourceSuffixSiblingsAndRejectsOccupiedOrObsoleteSources() throws Exception {
        var yaml = PlainYamlDocuments.parse("""
                farmersdelight_recipes#meals:
                  addon:recipe.v2: {station: cooking_pot, output: {item: minecraft:carrot}, x-note: keep}
                  addon:other: {station: cutting_board, output: [{item: minecraft:stick}]}
                items: {addon:model.v2: {material: STONE}}
                """, true);
        var source = new RecipeSource(directory.resolve("recipes.yml"),
                List.of("farmersdelight_recipes#meals", "addon:recipe.v2"), true, true);
        RecipeEditorStore.saveRecipeSource(yaml, source, Map.of("station", "cooking_pot",
                "group", "addon:pot", "output", Map.of("item", "minecraft:potato")));
        assertEquals("keep", source.body(yaml).get("x-note"));
        assertEquals("addon:pot", source.body(yaml).get("group"));
        assertEquals(Map.of("item", "minecraft:potato"), com.huidu.farmersdelight.recipe.NativeRecipeSchema.copy(source.body(yaml)).get("output"));
        assertTrue(yaml.getConfigurationSection("farmersdelight_recipes#meals").isConfigurationSection("addon:other"));
        assertTrue(yaml.getConfigurationSection("items").isConfigurationSection("addon:model.v2"));
        String before = yaml.saveToString();
        var occupied = new RecipeSource(source.file(), source.keys(), true);
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.saveRecipeSource(yaml, occupied,
                Map.of("station", "cutting_board")));
        var obsolete = new RecipeSource(source.file(), List.of("old_recipes", "addon:recipe.v2"), false);
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.saveRecipeSource(yaml, obsolete,
                Map.of("station", "cooking_pot")));
        assertEquals(before, yaml.saveToString());
    }

    @Test void aChangedStationRefusesSavingAndDeletingBeforeTouchingAnyFileNode() throws Exception {
        var yaml = PlainYamlDocuments.parse("""
                farmersdelight_recipes#meals:
                  addon:recipe.v2: {station: fluid_tank, operation: fill}
                external-overrides: {cooking_pot: [addon:recipe.v2]}
                """, true);
        var source = new RecipeSource(directory.resolve("recipes.yml"),
                List.of("farmersdelight_recipes#meals", "addon:recipe.v2"), true, true);
        String before = yaml.saveToString();
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.saveRecipeSource(yaml, source,
                Map.of("station", "cooking_pot", "output", Map.of("item", "minecraft:carrot")), true));
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.deleteRecipeSource(yaml, source, true, source.file()));
        assertEquals(before, yaml.saveToString());
        var fresh = new RecipeSource(source.file(), List.of("farmersdelight_recipes#meals", "addon:new"), true);
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.saveRecipeSource(yaml, fresh,
                Map.of("station", "cutting_board", "output", List.of(Map.of("item", "minecraft:stick"))), true));
        assertEquals(before, yaml.saveToString());
        RecipeEditorStore.saveRecipeSource(yaml, fresh, Map.of("station", "cooking_pot",
                "output", Map.of("item", "minecraft:carrot")), true);
        assertTrue(fresh.exists(yaml));
    }

    @Test void deletingASourceClearsOnlyItsExistingDefaultFileOverrideFlag() throws Exception {
        Path defaults = directory.resolve("recipes.yml");
        var yaml = PlainYamlDocuments.parse("""
                farmersdelight_recipes#meals:
                  addon:recipe.v2: {station: cooking_pot, output: {item: minecraft:carrot}}
                  addon:other: {station: cooking_pot, output: {item: minecraft:potato}}
                external-overrides:
                  cooking_pot: [addon:recipe.v2, addon:other]
                  cutting_board: [addon:recipe.v2]
                  custom_extension: kept
                """, true);
        var source = new RecipeSource(defaults, List.of("farmersdelight_recipes#meals", "addon:recipe.v2"), true, true);
        RecipeEditorStore.deleteRecipeSource(yaml, source, true, defaults);
        assertTrue(source.body(yaml).isEmpty());
        var overrides = yaml.getConfigurationSection("external-overrides");
        assertEquals(List.of("addon:other"), overrides.getStringList("cooking_pot"));
        assertEquals(List.of("addon:recipe.v2"), overrides.getStringList("cutting_board"));
        assertEquals("kept", overrides.getString("custom_extension"));
        assertTrue(yaml.getConfigurationSection("farmersdelight_recipes#meals").isConfigurationSection("addon:other"));

        var external = PlainYamlDocuments.parse("""
                farmersdelight_recipes: {addon:recipe.v2: {station: cutting_board, input: {item: minecraft:carrot}}}
                external-overrides: {cutting_board: [addon:recipe.v2]}
                """, true);
        var externalSource = new RecipeSource(directory.resolve("other-pack.yml"),
                List.of("farmersdelight_recipes", "addon:recipe.v2"), true, true);
        RecipeEditorStore.deleteRecipeSource(external, externalSource, false, defaults);
        assertEquals(List.of("addon:recipe.v2"), external.getConfigurationSection("external-overrides").getStringList("cutting_board"));
    }

    @Test void staleDeletionKeepsFlagsAndValidDeletionDoesNotCreateOverrideNodes() throws Exception {
        Path defaults = directory.resolve("recipes.yml");
        var yaml = PlainYamlDocuments.parse("""
                farmersdelight_recipes: {addon:existing.v2: {station: cooking_pot, output: {item: minecraft:carrot}}}
                external-overrides: {cooking_pot: [addon:missing.v2]}
                """, true);
        String before = yaml.saveToString();
        var absent = new RecipeSource(defaults, List.of("farmersdelight_recipes", "addon:missing.v2"), true, true);
        assertThrows(IllegalStateException.class, () -> RecipeEditorStore.deleteRecipeSource(yaml, absent, true, defaults));
        assertEquals(before, yaml.saveToString());
        var withoutFlags = PlainYamlDocuments.parse("farmersdelight_recipes: {addon:existing.v2: {station: cutting_board, input: {item: minecraft:carrot}}}\n", true);
        var existing = new RecipeSource(defaults, List.of("farmersdelight_recipes", "addon:existing.v2"), true, true);
        RecipeEditorStore.deleteRecipeSource(withoutFlags, existing, false, defaults);
        assertFalse(withoutFlags.isSet("external-overrides"));
        assertFalse(withoutFlags.getConfigurationSection("farmersdelight_recipes").isSet("addon:existing.v2"));
    }
}
