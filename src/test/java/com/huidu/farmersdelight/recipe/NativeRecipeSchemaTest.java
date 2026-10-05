package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NativeRecipeSchemaTest {
    @Test void potPreservesComplexResultAndContainerInference() throws Exception {
        var body = PlainYamlDocuments.parse("""
                station: cooking_pot
                input:
                  items: ["#c:foods/raw_beef,!minecraft:rotten_flesh", "advtag:vegetables"]
                output:
                  item: farmersdelight:beef_stew
                  count: 2
                  components: {minecraft:custom_data: "{custom:1}"}
                process: {ticks: 240, experience: 0.75}
                priority: 3
                """, true);
        var normalized = NativeRecipeSchema.normalizePot(body);
        assertFalse(normalized.isSet("container"));
        assertEquals(240, normalized.getInt("cook-time"));
        assertEquals(0.75, normalized.getDouble("experience"));
        assertEquals(NativeRecipeSchema.copy(body.getConfigurationSection("output").getValues(false)), NativeRecipeSchema.copy(normalized.getConfigurationSection("result").getValues(false)));
        assertEquals(NativeRecipeSchema.copy(body.getValues(false)), NativeRecipeSchema.formatPot(normalized.getValues(false)));
    }

    @Test void fuzzyMatchingKeepsRatiosOptionsAndLiteralGroupIds() throws Exception {
        var body = PlainYamlDocuments.parse("""
                station: cooking_pot
                group: addon:large.v2
                input: {container: none}
                output: {item: minecraft:carrot}
                matching:
                  mode: fuzzy
                  perfect: {addon:food.v2: 2.5}
                  use-equivalent-foods: false
                  use-seasonings: false
                  minimum-score: 0.4
                """, true);
        var normalized = NativeRecipeSchema.normalizePot(body);
        assertEquals("none", normalized.getString("container"));
        assertEquals(2.5, normalized.getConfigurationSection("perfect").getDouble("addon:food.v2"));
        assertFalse(normalized.getBoolean("use-seasonings"));
        assertEquals(NativeRecipeSchema.copy(body.getValues(false)), NativeRecipeSchema.formatPot(normalized.getValues(false)));
    }

    @Test void boardPreservesIndependentOutputChancesAndSound() throws Exception {
        var body = PlainYamlDocuments.parse("""
                station: cutting_board
                input: {item: minecraft:melon, tools: ['#farmersdelight:tools/knives']}
                output: [{item: minecraft:melon_slice, count: 9}, {item: minecraft:melon_seeds, count: 2, chance: 0.35}]
                process: {sound: {id: minecraft:block.wood.break, volume: 0.5, pitch: 1.25}}
                """, true);
        var normalized = NativeRecipeSchema.normalizeBoard(body);
        assertEquals(1.25, normalized.getDouble("sound-pitch"));
        assertEquals(NativeRecipeSchema.copy(body.getValues(false)), NativeRecipeSchema.formatBoard(normalized.getValues(false)));
    }

    @Test void unknownConsumptionFieldsAreRejectedInsteadOfBeingIgnored() throws Exception {
        var invalid = PlainYamlDocuments.parse("station: cooking_pot\ninput: {items: [minecraft:carrot], consume: false}\noutput: {item: minecraft:carrot}\n", true);
        var failure = assertThrows(IllegalArgumentException.class, () -> NativeRecipeSchema.normalizePot(invalid));
        assertTrue(failure.getMessage().contains("input.consume"));
        assertEquals(false, invalid.getConfigurationSection("input").getBoolean("consume"));
    }

    @Test void fluidComponentsAndAmountsHaveOneUnambiguousLocation() {
        Map<String, Object> match = Map.of("any-of", List.of(Map.of("id", "minecraft:water", "components", Map.of("amount", 7), "exact-components", true), "#addon:clear"));
        var source = Map.<String, Object>of("station", "fluid_tank", "operation", "soak", "input", Map.of("item", "minecraft:carrot"),
                "output", Map.of("item", "minecraft:potato"), "fluid", Map.of("match", match, "amount-mb", 250L, "consume", false), "process", Map.of("ticks", 0L));
        var normalized = NativeRecipeSchema.normalizeFluid(source);
        assertEquals(250L, normalized.get("amount"));
        assertEquals(match, normalized.get("fluid"));
        assertEquals(source, NativeRecipeSchema.formatFluid(normalized));
        var ambiguous = Map.<String, Object>of("station", "fluid_tank", "operation", "fill", "input", Map.of("item", "minecraft:bucket"),
                "fluid", Map.of("match", Map.of("id", "minecraft:water", "amount", 250), "amount-mb", 1000));
        assertThrows(IllegalArgumentException.class, () -> NativeRecipeSchema.normalizeFluid(ambiguous));
    }
}
