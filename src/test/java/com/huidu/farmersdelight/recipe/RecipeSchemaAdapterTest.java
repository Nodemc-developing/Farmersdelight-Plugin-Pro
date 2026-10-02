package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipeSchemaAdapterTest {
    private static YamlConfiguration yaml(String contents) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(contents);
        return yaml;
    }

    @Test void cookingObjectRetainsCountAndDoesNotInferUnspecifiedContainer() throws Exception {
        YamlConfiguration input = yaml("""
                type: cooking
                ingredients: [minecraft:carrot]
                result: {id: minecraft:mushroom_stew, count: 3}
                time: 120
                experience: 0.5
                """);
        String before = input.saveToString();
        ConfigurationSection parsed = RecipeSchemaAdapter.normalizePot(input);
        assertEquals(Map.of("item", "minecraft:mushroom_stew", "count", 3), parsed.get("result"));
        assertEquals("none", parsed.get("container"));
        assertEquals(120, parsed.getInt("cook-time"));
        assertEquals(0.5, parsed.getDouble("experience"));
        assertEquals(before, input.saveToString());
    }

    @Test void migrationPreservesLegacyContainerInferenceAndFuzzyWeights() throws Exception {
        YamlConfiguration input = yaml("""
                ingredients: [minecraft:carrot]
                result: minecraft:mushroom_stew
                result-count: 2
                cook-time: 240
                match-mode: fuzzy
                perfect: {minecraft:carrot: 3}
                minimum-score: 0.5
                custom-option: keep
                """);
        Map<String, Object> saved = RecipeSchemaAdapter.formatPot(input.getValues(false), true);
        YamlConfiguration document = new YamlConfiguration();
        document.createSection("recipe", saved);
        ConfigurationSection roundTrip = RecipeSchemaAdapter.normalizePot(document.getConfigurationSection("recipe"));
        assertEquals("cooking", saved.get("type"));
        assertTrue((Boolean) saved.get("infer_container"));
        assertNull(roundTrip.get("container"));
        assertEquals(240, roundTrip.getInt("cook-time"));
        assertEquals(3, roundTrip.getConfigurationSection("perfect").getInt("minecraft:carrot"));
        assertEquals("fuzzy", roundTrip.getString("match-mode"));
        assertEquals("keep", roundTrip.getString("custom-option"));
    }

    @Test void cuttingSupportsScalarResultsAndSoundObjectsAndConfiguredDefaultTools() throws Exception {
        YamlConfiguration input = yaml("""
                type: cutting
                ingredient: {items: [minecraft:carrot, '#minecraft:logs']}
                tools: []
                results:
                  - minecraft:paper
                  - {id: minecraft:stick, count: -1, chance: 1.2}
                sound: {id: minecraft:block.wood.break, volume: 0.4, pitch: 0.8}
                """);
        ConfigurationSection normalized = RecipeSchemaAdapter.normalizeBoard(input, List.of("minecraft:shears"));
        assertEquals(List.of("minecraft:shears"), normalized.getStringList("tools"));
        assertEquals(Map.of("items", List.of("minecraft:carrot", "#minecraft:logs")), normalized.get("input"));
        assertEquals(Map.of("item", "minecraft:paper"), normalized.getMapList("results").getFirst());
        assertEquals(Map.of("item", "minecraft:stick", "count", 1, "chance", 1.0), normalized.getMapList("results").get(1));
        assertEquals("minecraft:block.wood.break", normalized.getString("sound"));
        assertEquals(0.4, normalized.getDouble("sound-volume"));
        assertEquals(0.8, normalized.getDouble("sound-pitch"));
    }

    @Test void serializedCuttingSnapshotAndSoundSettingsRoundTrip() throws Exception {
        Map<String, Object> body = Map.of("input", Map.of("choice", List.of("minecraft:carrot", "minecraft:potato")),
                "tools", List.of("minecraft:shears"),
                "results", List.of(Map.of("item", "minecraft:paper", "count", 2, "nbt", "custom", "chance", 0.25)),
                "sound", "minecraft:block.wood.break", "sound-volume", 0.7, "sound-pitch", 1.1);
        Map<String, Object> formatted = RecipeSchemaAdapter.formatBoard(body, true);
        assertEquals("cutting", formatted.get("type"));
        assertEquals(Map.of("items", List.of("minecraft:carrot", "minecraft:potato")), formatted.get("ingredient"));
        YamlConfiguration saved = new YamlConfiguration();
        saved.createSection("recipe", formatted);
        ConfigurationSection roundTrip = RecipeSchemaAdapter.normalizeBoard(saved.getConfigurationSection("recipe"));
        assertEquals("custom", roundTrip.getMapList("results").getFirst().get("nbt"));
        assertEquals(0.7, roundTrip.getDouble("sound-volume"));
        assertEquals(1.1, roundTrip.getDouble("sound-pitch"));
    }

    @Test void explicitPapersContainerIsPreserved() throws Exception {
        ConfigurationSection parsed = RecipeSchemaAdapter.normalizePot(yaml("type: cooking\ncontainer: minecraft:bowl\nresult: minecraft:mushroom_stew\n"));
        assertEquals("minecraft:bowl", parsed.get("container"));
        assertEquals(200, parsed.getInt("cook-time"));
    }

    @Test void nonFiniteAndFractionalResultsFailInsteadOfProducingInvalidStacks() throws Exception {
        for (String result : List.of("{id: minecraft:paper, count: 1.5}", "{id: minecraft:paper, chance: .nan}")) {
            YamlConfiguration input = yaml("type: cutting\nresults: [" + result + "]\n");
            assertThrows(IllegalArgumentException.class, () -> RecipeSchemaAdapter.normalizeBoard(input));
        }
        assertThrows(IllegalArgumentException.class, () -> RecipeSchemaAdapter.normalizeBoard(
                yaml("type: cutting\nsound: {id: minecraft:block.wood.break, volume: .inf}\n")));
    }

    @Test void infoIsAnItemDescriptionWithLiteralMiniMessageText() throws Exception {
        var info = SpecialRecipeLoader.parsePapersInfo("example:notes", yaml("""
                type: info
                item: minecraft:carrot
                description: ['<green>Notes', '<lang:item.minecraft.carrot>']
                """));
        assertEquals("minecraft:carrot", info.iconItemId());
        assertEquals(List.of("<green>Notes", "<lang:item.minecraft.carrot>"), info.descriptionKeys());
        assertEquals("papers_info", info.displayType());
        assertTrue(info.inputSlots().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SpecialRecipeLoader.parsePapersInfo("example:bad", yaml("description: [hi]\n")));
    }
}
