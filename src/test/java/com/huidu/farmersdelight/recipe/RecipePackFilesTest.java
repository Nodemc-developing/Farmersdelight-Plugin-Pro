package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.pack.PackSection;
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

    private YamlConfiguration bundled(String name) throws Exception {
        return PlainYamlDocuments.parse(switch (name) {
            case RecipePackFiles.POT_FILE -> "cooking_pot_recipes:\n  default:\n    ingredients: [minecraft:carrot]\n    result: minecraft:carrot\n    cook-time: 40\n";
            case RecipePackFiles.BOARD_FILE -> "cutting_board_recipes: {}\n";
            case RecipePackFiles.GROUP_FILE -> "groups: {}\n";
            case RecipePackFiles.SPECIAL_FILE -> "special_recipes: {}\n";
            default -> throw new IllegalArgumentException(name);
        }, true);
    }

    @Test void importsTheCompleteOperatorRegistryAndNeverRestoresDeletionsAfterMigration() throws Exception {
        Path data = directory.resolve("plugin");
        Path configuration = directory.resolve("CraftEngine/resources/farmersdelight/configuration");
        Path legacy = data.resolve(RecipePackFiles.POT_FILE);
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "cooking_pot_recipes:\n  custom:\n    ingredients: [minecraft:potato]\n    result: minecraft:potato\n    cook-time: 80\n    extension_note: original\n");
        RecipePackFiles.installAndMigrate(data, configuration, this::bundled);
        Path target = configuration.resolve(RecipePackFiles.POT_FILE);
        var migrated = PlainYamlDocuments.readLiteral(target);
        assertEquals(List.of("custom"), List.copyOf(migrated.getConfigurationSection("papersdelight_recipes").getKeys(false)));
        var body = migrated.getConfigurationSection("papersdelight_recipes").getConfigurationSection("custom");
        assertEquals("cooking", body.getString("type"));
        assertEquals(80, body.getInt("time"));
        assertEquals("original", body.getString("extension_note"));
        assertTrue(Files.exists(data.resolve(".recipes-in-content-pack-v1")));
        try (var backups = Files.list(legacy.getParent())) {
            assertTrue(backups.anyMatch(path -> path.getFileName().toString().endsWith(".bak")));
        }
        Files.writeString(target, "papersdelight_recipes: {}\n");
        RecipePackFiles.installAndMigrate(data, configuration, this::bundled);
        assertTrue(PlainYamlDocuments.readLiteral(target).getConfigurationSection("papersdelight_recipes").getKeys(false).isEmpty());
        assertTrue(Files.readString(legacy).contains("extension_note: original"));
    }

    @Test void existingPackRecipeNodesRemainIndivisibleAndUnrelatedDataSurvives() throws Exception {
        Path data = directory.resolve("plugin");
        Path configuration = directory.resolve("configuration");
        Path legacy = data.resolve(RecipePackFiles.POT_FILE);
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "cooking_pot_recipes:\n  same:\n    ingredients: [minecraft:carrot]\n    result: minecraft:carrot\n    custom_extension: old\n  imported:\n    ingredients: [minecraft:potato]\n    result: minecraft:potato\n");
        Path target = configuration.resolve(RecipePackFiles.POT_FILE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "items:\n  addon:item: {material: STONE}\npapersdelight_recipes:\n  same:\n    type: cooking\n    ingredients: [minecraft:beetroot]\n    result: minecraft:beetroot\n    extension_note: pack\n");
        RecipePackFiles.installAndMigrate(data, configuration, this::bundled);
        var result = PlainYamlDocuments.readLiteral(target);
        var recipes = result.getConfigurationSection("papersdelight_recipes");
        assertEquals("minecraft:beetroot", recipes.getConfigurationSection("same").getString("result"));
        assertFalse(recipes.getConfigurationSection("same").isSet("custom_extension"));
        assertTrue(recipes.isConfigurationSection("imported"));
        assertTrue(result.getConfigurationSection("items").getKeys(false).contains("addon:item"));
    }

    @Test void malformedSourcesAbortBeforeAnyFileIsMigrated() throws Exception {
        Path data = directory.resolve("plugin");
        Path configuration = directory.resolve("configuration");
        Path invalid = data.resolve(RecipePackFiles.BOARD_FILE);
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, "cutting_board_recipes: [broken\n");
        assertThrows(Exception.class, () -> RecipePackFiles.installAndMigrate(data, configuration, this::bundled));
        assertFalse(Files.exists(configuration.resolve(RecipePackFiles.POT_FILE)));
        assertFalse(Files.exists(data.resolve(".recipes-in-content-pack-v1")));
        assertEquals("cutting_board_recipes: [broken\n", Files.readString(invalid));
    }

    @Test void routesTypedPackRootsAndKeepsExactSuffixAndLiteralIdentifiers() throws Exception {
        Path file = directory.resolve("configuration/mixed.yml");
        var yaml = PlainYamlDocuments.parse("""
                papersdelight_recipes#addon:
                  addon:soup.v2: {type: cooking, ingredients: [minecraft:carrot], result: minecraft:carrot}
                  addon:slice: {type: cutting, ingredient: minecraft:carrot, results: [minecraft:carrot]}
                  addon:manual: {type: info, item: minecraft:carrot, description: [Guide]}
                items: {addon:item: {material: STONE}}
                """, true);
        var cooking = RecipePackFiles.sections(Map.of(file, yaml), null, PackSection.COOKING_POT);
        assertEquals(1, cooking.size());
        assertEquals("papersdelight_recipes#addon", cooking.getFirst().sectionKey());
        assertEquals("farmersdelight", cooking.getFirst().namespace());
        assertEquals(List.of("addon:soup.v2"), List.copyOf(cooking.getFirst().yaml().getConfigurationSection("cooking_pot_recipes").getKeys(false)));
        assertEquals(file, cooking.getFirst().file());
        var cutting = RecipePackFiles.sections(Map.of(file, yaml), null, PackSection.CUTTING_BOARD);
        assertEquals(List.of("addon:slice"), List.copyOf(cutting.getFirst().yaml().getConfigurationSection("cutting_board_recipes").getKeys(false)));
        assertEquals(3, RecipePackFiles.sections(Map.of(file, yaml), null, PackSection.PAPERS_RECIPES)
                .getFirst().yaml().getConfigurationSection("papersdelight_recipes").getKeys(false).size());
    }

    @Test void fileScanningIncludesYamlAndIgnoresBackupsAndHiddenDocuments() throws Exception {
        Path root = directory.resolve("configuration");
        Files.createDirectories(root.resolve("recipe/nested"));
        Files.writeString(root.resolve("recipe/cooking.yml"), "papersdelight_recipes: {}\n");
        Files.writeString(root.resolve("recipe/nested/cutting.yaml"), "papersdelight_recipes: {}\n");
        Files.writeString(root.resolve("recipe/cooking.yml.bak"), "invalid backup");
        Files.writeString(root.resolve("recipe/.disabled.yml"), "papersdelight_recipes: {}\n");
        assertEquals(2, RecipePackFiles.files(List.of(root)).size());
    }

    @Test void sectionSuffixDoesNotOverrideTheEnabledPackNamespace() throws Exception {
        Path configuration = directory.resolve("configuration");
        Path subpack = configuration.resolve("enabled-subpack");
        var document = PlainYamlDocuments.parse("papersdelight_recipes#editor: {meal: {type: cooking}}\n", true);
        var sections = RecipePackFiles.sections(Map.of(subpack.resolve("recipes.yml"), document), null, PackSection.PAPERS_RECIPES,
                Map.of(configuration, "outer_pack", subpack, "actual_pack"));
        assertEquals("actual_pack", sections.getFirst().namespace());
        assertEquals("papersdelight_recipes#editor", sections.getFirst().sectionKey());
    }

    @Test void migrationRetainsAlreadyCanonicalRecipeAndFoodGroupDocuments() throws Exception {
        var recipe = RecipePackFiles.canonical(PlainYamlDocuments.parse("papersdelight_recipes: {addon:soup: {type: cooking, result: minecraft:carrot}}\n", true), RecipePackFiles.POT_FILE);
        assertTrue(recipe.getConfigurationSection("papersdelight_recipes").isSet("addon:soup"));
        var groups = RecipePackFiles.canonical(PlainYamlDocuments.parse("food_groups: {addon:vegetables: {kind: equivalent, items: [minecraft:carrot]}}\n", true), RecipePackFiles.GROUP_FILE);
        assertTrue(groups.getConfigurationSection("food_groups").isSet("addon:vegetables"));
    }

    @Test void preparedReloadRetainsCeExpandedFactoriesWithoutInventingEditableNodes() throws Exception {
        Path file = directory.resolve("configuration/generated.yml");
        var generated = new com.huidu.farmersdelight.pack.PackSections.Section(PackSection.PAPERS_RECIPES,
                "addon/configuration/generated.yml", "addon", PlainYamlDocuments.parse(
                "papersdelight_recipes: {addon:soup: {type: cooking, result: minecraft:carrot}}\n", true), file,
                "papersdelight_recipes", true);
        var source = PlainYamlDocuments.parse("config_factory#meals: {instances: [{item: carrot}], blueprint: {papersdelight_recipes: {}}}\n", true);
        var retained = RecipePackFiles.preserveGenerated(List.of(), List.of(generated), Map.of(file, source), PackSection.COOKING_POT);
        assertEquals(1, retained.size());
        assertTrue(retained.getFirst().generated());
        assertTrue(retained.getFirst().yaml().getConfigurationSection("cooking_pot_recipes").isConfigurationSection("addon:soup"));
        assertTrue(RecipePackFiles.preserveGenerated(List.of(), List.of(generated), Map.of(file, new YamlConfiguration()), PackSection.COOKING_POT).isEmpty());
    }

    @Test void sharedPapersSnapshotsRetainBadScalarsForOwnershipWhileTypedLoadersFilterThem() throws Exception {
        Path file = directory.resolve("mixed.yml");
        var document = PlainYamlDocuments.parse("""
                papersdelight_recipes#addon:
                  addon:reserved.v2: invalid
                  addon:broken: [invalid, list]
                  addon:cooked: {type: cooking, result: minecraft:carrot}
                """, true);
        var shared = RecipePackFiles.sections(Map.of(file, document), null, PackSection.PAPERS_RECIPES).getFirst();
        assertEquals("papersdelight_recipes#addon", shared.sectionKey());
        var root = shared.yaml().getConfigurationSection("papersdelight_recipes");
        assertEquals("invalid", root.getString("addon:reserved.v2"));
        assertEquals(List.of("invalid", "list"), root.getStringList("addon:broken"));
        assertEquals(3, root.getKeys(false).size());
        var cooking = RecipePackFiles.sections(Map.of(file, document), null, PackSection.COOKING_POT).getFirst();
        assertEquals(List.of("addon:cooked"), List.copyOf(cooking.yaml().getConfigurationSection("cooking_pot_recipes").getKeys(false)));
        assertTrue(RecipePackFiles.sections(Map.of(file, document), null, PackSection.CUTTING_BOARD).isEmpty());
    }
}
