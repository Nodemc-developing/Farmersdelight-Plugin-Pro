package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.recipe.NativeRecipeSchema;
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
                farmersdelight_recipes:
                  addon:previous:
                    station: fluid_tank
                    operation: fill
                    input: {item: minecraft:bowl}
                    output: {item: minecraft:carrot}
                    fluid: {match: minecraft:water, amount-mb: 1000}
                """)));
        var field = FluidRecipeFiles.class.getDeclaredField("state");
        field.setAccessible(true);
        Object previous = field.get(files);
        Runnable restore = files.captureReloadRollback();
        var candidate = section("candidate.yml", """
                farmersdelight_recipes:
                  addon:occupied: {station: cooking_pot}
                """);
        assertThrows(IllegalStateException.class,
                () -> com.huidu.farmersdelight.recipe.RecipePublicationTransaction.run(() -> {
                    files.loadSections(List.of(candidate));
                    throw new IllegalStateException("later publication stage failed");
                }, restore));
        assertSame(previous, field.get(files));
        assertFalse(files.deleteAsync("addon:occupied").join());
    }

    @Test void canonicalFieldsRoundTripEveryOperationWithCountsAndAdvancedChoices() {
        for (String type : List.of("fluid_filling", "fluid_emptying", "soaking")) {
            var recipe = new FluidRecipeSpec("addon:recipe.v2", type,
                    RecipeParsingSupport.parseIngredientValue("advtag:addon:empty|minecraft:bowl"),
                    Map.of("item", "minecraft:carrot", "count", 3, "x-extension", "original"),
                    "minecraft:water", false, 250, 40, true, 5);
            var canonical = FluidRecipeFiles.body(recipe);
            assertEquals("fluid_tank", canonical.get("station"));
            assertEquals("minecraft:water", ((Map<?, ?>) canonical.get("fluid")).get("match"));
            assertEquals(250L, ((Map<?, ?>) canonical.get("fluid")).get("amount-mb"));
            assertFalse(canonical.containsKey("type"));
            assertFalse(canonical.containsKey("amount"));
            var reread = FluidRecipeSpec.parse(recipe.id(), canonical);
            assertEquals(recipe.ingredient().stableKey(), reread.ingredient().stableKey());
            assertEquals(3, reread.result().get("count"));
            assertEquals("original", reread.result().get("x-extension"));
            assertEquals(250, reread.amount());
            assertEquals(40, reread.timeTicks());
            assertEquals(5, reread.priority());
            assertEquals(type, reread.type());
        }
    }

    @Test void writesOnlyTheSelectedSourceAndPreservesUnknownSiblingAndFluidData() throws Exception {
        Path file = directory.resolve("configuration/recipes/mixed.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                items: {addon:icon.v2: {material: STONE}}
                farmersdelight_recipes#addon:
                  addon:fill.v2:
                    station: fluid_tank
                    operation: fill
                    input: {item: minecraft:bowl}
                    output: {item: minecraft:carrot}
                    fluid:
                      match: {id: minecraft:water, x-extension: preserved}
                      amount-mb: 1000
                      x-fluid-note: untouched
                    process: {ticks: 0, x-process-note: keep}
                    x-rewards: {coins: 5}
                  addon:cook: {station: cooking_pot}
                """);
        var source = new RecipeSource(file, List.of("farmersdelight_recipes#addon", "addon:fill.v2"), true, true);
        var recipe = new FluidRecipeSpec("addon:fill.v2", "fluid_filling", RecipeParsingSupport.parseIngredientValue("minecraft:bowl"),
                Map.of("item", "minecraft:potato", "count", 2), "minecraft:water", false, 500, 0, true, 0);
        FluidRecipeFiles.write(source, FluidRecipeFiles.body(recipe));
        var saved = PlainYamlDocuments.readLiteral(file);
        var body = source.body(saved);
        var fluid = (org.bukkit.configuration.ConfigurationSection) body.get("fluid");
        assertEquals(500L, ((Number) fluid.get("amount-mb")).longValue());
        assertFalse(fluid.getConfigurationSection("match").isSet("amount"));
        assertEquals("preserved", fluid.getConfigurationSection("match").getString("x-extension"));
        assertEquals("untouched", fluid.getString("x-fluid-note"));
        assertEquals("keep", ((org.bukkit.configuration.ConfigurationSection) body.get("process")).getString("x-process-note"));
        assertTrue(body.containsKey("x-rewards"));
        assertTrue(saved.getConfigurationSection("farmersdelight_recipes#addon").isSet("addon:cook"));
        assertTrue(saved.getConfigurationSection("items").isSet("addon:icon.v2"));
        FluidRecipeFiles.write(source, null);
        assertFalse(PlainYamlDocuments.readLiteral(file).getConfigurationSection("farmersdelight_recipes#addon").isSet("addon:fill.v2"));
    }

    @Test void newFilesAreCreatedAtomicallyWhileBrokenSourcesAreLeftUntouched() throws Exception {
        Path file = directory.resolve("configuration/recipes/new.yml");
        var source = new RecipeSource(file, List.of(NativeRecipeSchema.EDITOR_ROOT, "addon:soak"), true);
        FluidRecipeFiles.write(source, Map.of("station", "fluid_tank", "operation", "soak"));
        assertEquals("soak", source.body(PlainYamlDocuments.readLiteral(file)).get("operation"));
        Files.writeString(file, "farmersdelight_recipes: [broken\n");
        assertThrows(Exception.class, () -> FluidRecipeFiles.write(source, Map.of("station", "fluid_tank")));
        assertEquals("farmersdelight_recipes: [broken\n", Files.readString(file));
    }

    @Test void canonicalFluidQuantityHasNoRemovedAmountAliasesOrPredicates() {
        var previous = Map.<String, Object>of("station", "fluid_tank", "operation", "soak", "fluid",
                Map.of("match", Map.of("id", "minecraft:water", "amount", 1000, "components", Map.of("test:warm", true)), "amount-mb", 1000));
        var edited = Map.<String, Object>of("station", "fluid_tank", "operation", "soak", "fluid",
                Map.of("match", "#c:water", "amount-mb", 250L));
        var merged = FluidRecipeFiles.merge(previous, edited);
        assertEquals(Map.of("match", "#c:water", "amount-mb", 250L), merged.get("fluid"));
        assertFalse(merged.containsKey("amount"));
    }

    @Test void aNewRecipeCannotReplaceAnUnclaimedExistingNode() throws Exception {
        Path file = directory.resolve("conflict.yml");
        Files.writeString(file, "farmersdelight_recipes#fd_pro:\n  addon:shared: {station: cooking_pot, x-custom: original}\n");
        var source = new RecipeSource(file, List.of(NativeRecipeSchema.EDITOR_ROOT, "addon:shared"), true);
        assertThrows(IllegalStateException.class, () -> FluidRecipeFiles.write(source, Map.of("station", "fluid_tank")));
        assertEquals("cooking_pot", source.body(PlainYamlDocuments.readLiteral(file)).get("station"));
    }

    @Test void anyNativeRecipeStationOwnsItsIdBeforeSchedulingANewFluidSave() throws Exception {
        FluidRecipeFiles files = new FluidRecipeFiles(null);
        var first = section("first.yml", """
                farmersdelight_recipes:
                  addon:meal.v2: {station: cooking_pot}
                  addon:cut: {station: cutting_board}
                  addon:extension: {station: addon:unknown}
                  addon:scalar: reserved
                """);
        var second = section("second.yml", """
                farmersdelight_recipes:
                  addon:invalid_fluid: {station: fluid_tank, operation: fill, fluid: {match: minecraft:water, amount-mb: 0}, input: {item: minecraft:bowl}}
                """);
        assertTrue(files.loadSections(List.of(first, second)).isEmpty());
        for (String id : List.of("addon:meal.v2", "addon:cut", "addon:extension", "addon:scalar", "addon:invalid_fluid")) {
            var recipe = new FluidRecipeSpec(id, "fluid_filling", RecipeParsingSupport.parseIngredientValue("minecraft:bowl"),
                    Map.of(), "minecraft:water", false, 1000, 0, true, 0);
            assertFalse(files.saveAsync(recipe).join(), "Conflict must return before resolving files or using a scheduler");
            assertFalse(files.deleteAsync(id).join(), "Fluid editor cannot delete a different station");
        }
    }

    @Test void aPendingFluidEditCannotOverwriteOrDeleteANodeWhoseStationChanged() throws Exception {
        Path file = directory.resolve("changed-station.yml");
        String changed = "farmersdelight_recipes#addon:\n  addon:shared: {station: cooking_pot, x-note: external-edit}\n";
        Files.writeString(file, changed);
        var source = new RecipeSource(file, List.of("farmersdelight_recipes#addon", "addon:shared"), true, true);
        assertThrows(IllegalStateException.class, () -> FluidRecipeFiles.write(source,
                Map.of("station", "fluid_tank", "operation", "fill")));
        assertEquals(changed, Files.readString(file));
        assertThrows(IllegalStateException.class, () -> FluidRecipeFiles.write(source, null));
        assertEquals(changed, Files.readString(file));
    }

    @Test void fluidParseAndDuplicateProblemsCountOncePerReloadAndSource() throws Exception {
        FluidRecipeFiles files = new FluidRecipeFiles(null);
        var first = section("first.yml", """
                farmersdelight_recipes:
                  addon:valid: {station: fluid_tank, operation: fill, fluid: {match: minecraft:water}, input: {item: minecraft:bowl}}
                  addon:invalid: {station: fluid_tank, operation: soak, fluid: {match: minecraft:water, amount-mb: 0}, input: {item: minecraft:bowl}}
                """);
        var duplicate = section("duplicate.yml", """
                farmersdelight_recipes:
                  addon:valid: {station: fluid_tank, operation: fill, fluid: {match: minecraft:water}, input: {item: minecraft:bowl}}
                """);
        assertEquals(1, files.loadSections(List.of(first, duplicate)).size());
        assertEquals(2, RecipeFileLoader.reportedIssueCount());
        files.loadSections(List.of(first, duplicate));
        assertEquals(2, RecipeFileLoader.reportedIssueCount());
        RecipeFileLoader.resetReportedIssues();
        files.loadSections(List.of(first, duplicate));
        assertEquals(2, RecipeFileLoader.reportedIssueCount());
    }

    private PackSections.Section section(String file, String yaml) throws Exception {
        return new PackSections.Section(PackSection.NATIVE_RECIPES, file, "addon", PlainYamlDocuments.parse(yaml, true),
                directory.resolve(file), NativeRecipeSchema.ROOT);
    }
}
