package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.recipe.RecipeParsingSupport;
import com.huidu.farmersdelight.recipe.RecipeSource;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeFilesTest {
    @TempDir Path directory;

    @AfterEach void clearReloadDiagnostics() { RecipeFileLoader.resetReportedIssues(); }

    @Test void failedPublicationRestoresSourcePathsAndOccupiedIds() throws Exception {
        FluidRecipeFiles files = new FluidRecipeFiles(null);
        files.loadSections(List.of(section("previous.yml", """
                papersdelight_recipes:
                  addon:previous:
                    type: fluid_filling
                    fluid: minecraft:water
                    empty_input: minecraft:bowl
                    filled_result: minecraft:carrot
                """)));
        var field = FluidRecipeFiles.class.getDeclaredField("state");
        field.setAccessible(true);
        Object previous = field.get(files);
        Runnable restore = files.captureReloadRollback();
        var candidate = section("candidate.yml", """
                papersdelight_recipes:
                  addon:occupied: {type: cooking}
                """);

        assertThrows(IllegalStateException.class,
                () -> com.huidu.farmersdelight.recipe.RecipePublicationTransaction.run(() -> {
                    files.loadSections(List.of(candidate));
                    throw new IllegalStateException("later publication stage failed");
                }, restore));

        assertSame(previous, field.get(files));
        assertFalse(files.deleteAsync("addon:occupied").join());
    }

    @Test void canonicalFieldsRoundTripEveryFluidRecipeKindWithCountsAndAdvancedChoices() {
        for (String type : List.of("fluid_filling", "fluid_emptying", "soaking")) {
            var recipe = new FluidRecipeSpec("addon:recipe.v2", type, RecipeParsingSupport.parseIngredientValue("advtag:addon:empty|minecraft:bowl"),
                    Map.of("item", "minecraft:carrot", "count", 3, "custom_extension", "original"), "minecraft:water", false, 250, 40, true, 5);
            var canonical = FluidRecipeFiles.body(recipe);
            assertEquals("minecraft:water", canonical.get("fluid"));
            assertEquals(250L, canonical.get("amount"));
            var reread = FluidRecipeSpec.parse(recipe.id(), canonical);
            assertEquals(recipe.ingredient().stableKey(), reread.ingredient().stableKey());
            assertEquals(3, reread.result().get("count"));
            assertEquals("original", reread.result().get("custom_extension"));
            assertEquals(250, reread.amount());
            assertEquals(40, reread.timeTicks());
            assertEquals(type, reread.type());
        }
    }

    @Test void writesOnlyTheSelectedSourceAndPreservesUnknownSiblingAndFluidData() throws Exception {
        Path file = directory.resolve("configuration/recipes/mixed.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                items: {addon:icon.v2: {material: STONE}}
                papersdelight_recipes#addon:
                  addon:fill.v2:
                    type: fluid_filling
                    empty_input: minecraft:bowl
                    filled_result: minecraft:carrot
                    fluid: {id: minecraft:water, amount: 1000, custom_extension: preserved}
                    custom_rewards: {coins: 5}
                  addon:cook: {type: cooking, ingredients: [minecraft:carrot], result: minecraft:carrot}
                """);
        var source = new RecipeSource(file, List.of("papersdelight_recipes#addon", "addon:fill.v2"), true, true);
        var recipe = new FluidRecipeSpec("addon:fill.v2", "fluid_filling", RecipeParsingSupport.parseIngredientValue("minecraft:bowl"),
                Map.of("item", "minecraft:potato", "count", 2), "minecraft:water", false, 500, 0, true, 0);
        FluidRecipeFiles.write(source, FluidRecipeFiles.body(recipe));
        var saved = PlainYamlDocuments.readLiteral(file);
        var body = source.body(saved);
        assertEquals(500L, ((Number) body.get("amount")).longValue());
        assertFalse(((org.bukkit.configuration.ConfigurationSection) body.get("fluid")).isSet("amount"));
        assertEquals("preserved", ((org.bukkit.configuration.ConfigurationSection) body.get("fluid")).getString("custom_extension"));
        assertTrue(body.containsKey("custom_rewards"));
        assertTrue(saved.getConfigurationSection("papersdelight_recipes#addon").isSet("addon:cook"));
        assertTrue(saved.getConfigurationSection("items").isSet("addon:icon.v2"));
        FluidRecipeFiles.write(source, null);
        assertFalse(PlainYamlDocuments.readLiteral(file).getConfigurationSection("papersdelight_recipes#addon").isSet("addon:fill.v2"));
    }

    @Test void newFilesAreCreatedAtomicallyWhileBrokenSourcesAreLeftUntouched() throws Exception {
        Path file = directory.resolve("configuration/recipes/new.yml");
        var source = new RecipeSource(file, List.of("papersdelight_recipes#fd_pro", "addon:soak"), true);
        FluidRecipeFiles.write(source, Map.of("type", "soaking", "ingredient", "minecraft:carrot"));
        assertEquals("soaking", source.body(PlainYamlDocuments.readLiteral(file)).get("type"));
        Files.writeString(file, "papersdelight_recipes: [broken\n");
        assertThrows(Exception.class, () -> FluidRecipeFiles.write(source, Map.of("type", "soaking")));
        assertEquals("papersdelight_recipes: [broken\n", Files.readString(file));
    }

    @Test void originalScalarFluidKeepsOtherDelightShapeAndDropsOldAmountAliases() {
        var merged = FluidRecipeFiles.merge(Map.of("fluid", "minecraft:water", "amount", 1000),
                Map.of("fluid", "#c:water", "amount", 250L));
        assertEquals("#c:water", merged.get("fluid"));
        assertEquals(250L, merged.get("amount"));
        var compact = FluidRecipeFiles.merge(Map.of("fluid", Map.of("id", "minecraft:water", "amount", 1000)),
                Map.of("fluid", "#c:water", "amount", 250L));
        assertEquals("#c:water", compact.get("fluid"));
    }

    @Test void aNewRecipeCannotReplaceAnUnclaimedExistingNode() throws Exception {
        Path file = directory.resolve("conflict.yml");
        Files.writeString(file, "papersdelight_recipes#fd_pro:\n  addon:shared: {type: cooking, custom: original}\n");
        var source = new RecipeSource(file, List.of("papersdelight_recipes#fd_pro", "addon:shared"), true);
        assertThrows(IllegalStateException.class, () -> FluidRecipeFiles.write(source, Map.of("type", "soaking")));
        assertEquals("cooking", source.body(PlainYamlDocuments.readLiteral(file)).get("type"));
    }

    @Test void anyOtherDelightRecipeKindOwnsItsIdBeforeSchedulingANewFluidSave() throws Exception {
        FluidRecipeFiles files = new FluidRecipeFiles(null);
        var first = section("first.yml", """
                papersdelight_recipes:
                  addon:meal.v2: {type: cooking}
                  addon:cut: {type: cutting}
                  addon:extension: {type: addon:unknown}
                  addon:scalar: reserved
                """);
        var second = section("second.yml", """
                papersdelight_recipes:
                  addon:invalid_fluid: {type: fluid_filling, fluid: minecraft:water, empty_input: minecraft:bowl, amount: 0}
                """);
        assertTrue(files.loadSections(List.of(first, second)).isEmpty());
        for (String id : List.of("addon:meal.v2", "addon:cut", "addon:extension", "addon:scalar", "addon:invalid_fluid")) {
            var recipe = new FluidRecipeSpec(id, "fluid_filling", RecipeParsingSupport.parseIngredientValue("minecraft:bowl"),
                    Map.of(), "minecraft:water", false, 1000, 0, true, 0);
            assertFalse(files.saveAsync(recipe).join(), "The conflict must return before resolving files or using a scheduler");
            assertFalse(files.deleteAsync(id).join(), "A fluid editor must not delete a different recipe kind");
        }
    }

    @Test void fluidParseAndDuplicateProblemsCountOncePerReloadAndSource() throws Exception {
        RecipeFileLoader.resetReportedIssues();
        try {
            FluidRecipeFiles files = new FluidRecipeFiles(null);
            var first = section("first.yml", """
                    papersdelight_recipes:
                      addon:valid: {type: fluid_filling, fluid: minecraft:water, empty_input: minecraft:bowl}
                      addon:invalid: {type: soaking, fluid: minecraft:water, ingredient: minecraft:bowl, amount: 0}
                    """);
            var duplicate = section("duplicate.yml", """
                    papersdelight_recipes:
                      addon:valid: {type: fluid_filling, fluid: minecraft:water, empty_input: minecraft:bowl}
                    """);
            assertEquals(1, files.loadSections(List.of(first, duplicate)).size());
            assertEquals(2, RecipeFileLoader.reportedIssueCount());
            files.loadSections(List.of(first, duplicate));
            assertEquals(2, RecipeFileLoader.reportedIssueCount());
            RecipeFileLoader.resetReportedIssues();
            files.loadSections(List.of(first, duplicate));
            assertEquals(2, RecipeFileLoader.reportedIssueCount());
        } finally { RecipeFileLoader.resetReportedIssues(); }
    }

    private PackSections.Section section(String file, String yaml) throws Exception {
        return new PackSections.Section(PackSection.OTHER_DELIGHT_RECIPES, file, "addon", PlainYamlDocuments.parse(yaml, true),
                directory.resolve(file), "papersdelight_recipes");
    }
}
