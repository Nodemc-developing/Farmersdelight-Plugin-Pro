package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecipePackFilesTest {
    @TempDir Path directory;
    private YamlConfiguration yaml(String text) throws Exception { return PlainYamlDocuments.parse(text, true); }

    @Test void installsOnlyMissingDefaultsAndNeverRestoresIntentionalDeletions() throws Exception {
        Path target = directory.resolve("recipes/pot.yml");
        var bundled = yaml("farmersdelight_recipes: {meal: {station: cooking_pot, input: {items: [minecraft:carrot]}, output: {item: minecraft:carrot}}}\n");
        assertTrue(RecipePackFiles.installDefaultDocument(target, bundled, true));
        Files.writeString(target, "farmersdelight_recipes: {}\n");
        assertFalse(RecipePackFiles.installDefaultDocument(target, bundled, true));
        assertTrue(PlainYamlDocuments.readLiteral(target).getConfigurationSection(NativeRecipeSchema.ROOT).getKeys(false).isEmpty());
        Files.delete(target);
        assertFalse(RecipePackFiles.installDefaultDocument(target, bundled, false));
        assertFalse(Files.exists(target));
    }

    @Test void routesNativeStationsPreservingSuffixLiteralIdsAndPackNamespace() throws Exception {
        Path file = directory.resolve("subpack/configuration/mixed.yml");
        var document = yaml("""
                farmersdelight_recipes#addon:
                  addon:soup.v2: {station: cooking_pot, input: {items: [minecraft:carrot]}, output: {item: minecraft:carrot}}
                  addon:slice: {station: cutting_board, input: {item: minecraft:carrot}, output: [{item: minecraft:carrot}]}
                  addon:fill: {station: fluid_tank, operation: fill, input: {item: minecraft:bucket}, fluid: {match: minecraft:water, amount-mb: 1000}}
                """);
        var cooking = RecipePackFiles.sections(Map.of(file, document), null, PackSection.COOKING_POT, Map.of(directory, "outer", file.getParent(), "actual_pack"));
        assertEquals(1, cooking.size());
        assertEquals("farmersdelight_recipes#addon", cooking.getFirst().sectionKey());
        assertEquals("actual_pack", cooking.getFirst().namespace());
        assertEquals(List.of("addon:soup.v2"), List.copyOf(cooking.getFirst().yaml().getConfigurationSection("cooking_pot_recipes").getKeys(false)));
        var board = RecipePackFiles.sections(Map.of(file, document), null, PackSection.CUTTING_BOARD);
        assertEquals(List.of("addon:slice"), List.copyOf(board.getFirst().yaml().getConfigurationSection("cutting_board_recipes").getKeys(false)));
        assertEquals(3, RecipePackFiles.sections(Map.of(file, document), null, PackSection.NATIVE_RECIPES).getFirst().yaml().getConfigurationSection(NativeRecipeSchema.ROOT).getKeys(false).size());
    }

    @Test void oldFlatRegistryRootsAreNeverLoadedAndUnknownNativeNodesRemainOwned() throws Exception {
        Path file = directory.resolve("mixed.yml");
        var document = yaml("""
                cooking_pot_recipes: {old: {ingredients: [minecraft:carrot], result: minecraft:carrot}}
                cutting_recipes: {old: {input: minecraft:carrot, results: [minecraft:carrot]}}
                legacy_recipes: {foreign: {type: cooking}}
                farmersdelight_recipes:
                  reserved: invalid
                  cooked: {station: cooking_pot, input: {items: [minecraft:carrot]}, output: {item: minecraft:carrot}}
                """);
        var shared = RecipePackFiles.sections(Map.of(file, document), null, PackSection.NATIVE_RECIPES).getFirst();
        assertEquals("invalid", shared.yaml().getConfigurationSection(NativeRecipeSchema.ROOT).getString("reserved"));
        var cooking = RecipePackFiles.sections(Map.of(file, document), null, PackSection.COOKING_POT).getFirst();
        assertEquals(List.of("cooked"), List.copyOf(cooking.yaml().getConfigurationSection("cooking_pot_recipes").getKeys(false)));
        assertTrue(RecipePackFiles.sections(Map.of(file, document), null, PackSection.CUTTING_BOARD).isEmpty());
    }

    @Test void customPotGroupsAreViewsOfFlatNativeNodes() throws Exception {
        var document = yaml("""
                farmersdelight_recipes#meals:
                  ordinary: {station: cooking_pot, input: {items: [minecraft:carrot]}, output: {item: minecraft:carrot}}
                  large: {station: cooking_pot, group: large_pot, input: {items: [minecraft:potato]}, output: {item: minecraft:potato}}
                """);
        var bridge = RecipePackFiles.bridge(document, RecipePackFiles.POT_FILE);
        assertEquals(List.of("ordinary"), List.copyOf(bridge.getConfigurationSection("cooking_pot_recipes").getKeys(false)));
        assertTrue(bridge.getConfigurationSection("custom_cooking_pot_recipes").getConfigurationSection("large_pot").isConfigurationSection("large"));
        var grouped = RecipePackFiles.sections(Map.of(directory.resolve("mixed.yml"), document), null, PackSection.CUSTOM_COOKING_POT);
        assertTrue(grouped.getFirst().yaml().getConfigurationSection("custom_cooking_pot_recipes").getConfigurationSection("large_pot").isConfigurationSection("large"));
    }

    @Test void preservesOnlyCeGeneratedNativeFactories() throws Exception {
        Path file = directory.resolve("generated.yml");
        var generated = new PackSections.Section(PackSection.NATIVE_RECIPES, "generated.yml", "addon",
                yaml("farmersdelight_recipes: {addon:soup: {station: cooking_pot, output: {item: minecraft:carrot}}}\n"), file, NativeRecipeSchema.ROOT, true);
        var source = yaml("config_factory#meals: {instances: [{item: carrot}], blueprint: {farmersdelight_recipes: {}}}\n");
        var retained = RecipePackFiles.preserveGenerated(List.of(), List.of(generated), Map.of(file, source), PackSection.COOKING_POT);
        assertEquals(1, retained.size());
        assertTrue(retained.getFirst().generated());
        assertTrue(RecipePackFiles.preserveGenerated(List.of(), List.of(generated), Map.of(file, new YamlConfiguration()), PackSection.COOKING_POT).isEmpty());
    }

    @Test void scansYamlWithoutBackupOrHiddenFiles() throws Exception {
        Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("pot.yml"), "farmersdelight_recipes: {}\n");
        Files.writeString(directory.resolve("nested/board.yaml"), "farmersdelight_recipes: {}\n");
        Files.writeString(directory.resolve("pot.yml.bak"), "invalid backup");
        Files.writeString(directory.resolve(".disabled.yml"), "farmersdelight_recipes: {}\n");
        assertEquals(2, RecipePackFiles.files(List.of(directory)).size());
    }
}
