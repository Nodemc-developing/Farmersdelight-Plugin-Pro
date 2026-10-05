package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RecipeSchemaAdapterTest {
    @Test void nativeCookingSeparatesItemsOutputAndProcessWithoutChangingTheSource() throws Exception {
        var input = PlainYamlDocuments.parse("""
                station: cooking_pot
                input: {items: [minecraft:carrot], container: none}
                output: {item: minecraft:mushroom_stew, count: 3}
                process: {ticks: 120, experience: 0.5}
                """, true);
        String before = input.saveToString();
        ConfigurationSection parsed = RecipeSchemaAdapter.normalizePot(input);
        assertEquals(List.of("minecraft:carrot"), parsed.getStringList("ingredients"));
        assertEquals("minecraft:mushroom_stew", parsed.getConfigurationSection("result").getString("item"));
        assertEquals(3, parsed.getConfigurationSection("result").getInt("count"));
        assertEquals("none", parsed.get("container"));
        assertEquals(120, parsed.getInt("cook-time"));
        assertEquals(0.5, parsed.getDouble("experience"));
        assertEquals(before, input.saveToString());
    }

    @Test void editorOutputUsesNativeGroupsAndKeepsContainerInferenceAndFuzzyWeights() throws Exception {
        var input = PlainYamlDocuments.parse("""
                ingredients: [minecraft:carrot]
                result: minecraft:mushroom_stew
                result-count: 2
                cook-time: 240
                match-mode: fuzzy
                perfect: {minecraft:carrot: 3}
                minimum-score: 0.5
                x-note: keep
                """, true);
        var saved = RecipeSchemaAdapter.formatPot(input.getValues(false), true);
        assertEquals("cooking_pot", saved.get("station"));
        assertFalse(saved.containsKey("type"));
        assertFalse(saved.containsKey("result"));
        assertFalse(saved.containsKey("ingredients"));
        assertFalse(saved.containsKey("infer_container"));
        var document = PlainYamlDocuments.parse("recipe: {}\n", true);
        PlainYamlDocuments.setValue(document, "recipe", saved);
        var roundTrip = RecipeSchemaAdapter.normalizePot(document.getConfigurationSection("recipe"));
        assertNull(roundTrip.get("container"));
        assertEquals(240, roundTrip.getInt("cook-time"));
        assertEquals(3, roundTrip.getConfigurationSection("perfect").getInt("minecraft:carrot"));
        assertEquals("fuzzy", roundTrip.getString("match-mode"));
        assertEquals("keep", roundTrip.getString("x-note"));
        assertEquals(2, roundTrip.getConfigurationSection("result").getInt("count"));
    }

    @Test void cuttingSnapshotSoundAndChoicesRoundTripInTheNativeSchema() throws Exception {
        Map<String, Object> body = Map.of("input", Map.of("choice", List.of("minecraft:carrot", "minecraft:potato")),
                "tools", List.of("minecraft:shears"),
                "results", List.of(Map.of("item", "minecraft:paper", "count", 2, "nbt", "custom", "chance", 0.25)),
                "sound", "minecraft:block.wood.break", "sound-volume", 0.7, "sound-pitch", 1.1);
        var formatted = RecipeSchemaAdapter.formatBoard(body, true);
        assertEquals("cutting_board", formatted.get("station"));
        assertFalse(formatted.containsKey("type"));
        assertFalse(formatted.containsKey("ingredient"));
        assertFalse(formatted.containsKey("results"));
        var input = (Map<?, ?>) formatted.get("input");
        assertEquals(body.get("input"), input.get("item"));
        assertEquals(List.of("minecraft:shears"), input.get("tools"));
        var saved = PlainYamlDocuments.parse("recipe: {}\n", true);
        PlainYamlDocuments.setValue(saved, "recipe", formatted);
        var roundTrip = RecipeSchemaAdapter.normalizeBoard(saved.getConfigurationSection("recipe"));
        assertEquals("custom", roundTrip.getMapList("results").getFirst().get("nbt"));
        assertEquals(0.7, roundTrip.getDouble("sound-volume"));
        assertEquals(1.1, roundTrip.getDouble("sound-pitch"));
        assertEquals(0.25, roundTrip.getMapList("results").getFirst().get("chance"));
    }

    @Test void nativeCuttingUsesConfiguredDefaultToolsAndRejectsAnIncorrectStation() throws Exception {
        var input = PlainYamlDocuments.parse("""
                station: cutting_board
                input: {item: minecraft:carrot}
                output: [{item: minecraft:paper}]
                """, true);
        assertEquals(List.of("minecraft:shears"), RecipeSchemaAdapter.normalizeBoard(input,
                List.of("minecraft:shears")).getStringList("tools"));
        assertThrows(IllegalArgumentException.class, () -> RecipeSchemaAdapter.normalizePot(input));
    }

    @Test void internalRepresentationCopiesDoNotBecomeASecondFileFormat() throws Exception {
        var input = PlainYamlDocuments.parse("ingredients: [minecraft:carrot]\nresult: minecraft:potato\n", true);
        var copy = RecipeSchemaAdapter.normalizePot(input);
        assertNotSame(input, copy);
        assertEquals(NativeRecipeSchema.copy(input.getValues(false)), NativeRecipeSchema.copy(copy.getValues(false)));
        var serialized = RecipeSchemaAdapter.formatPot(input.getValues(false), false);
        assertEquals(input.getValues(false), serialized);
        assertFalse(serialized.containsKey("station"));
    }
}
