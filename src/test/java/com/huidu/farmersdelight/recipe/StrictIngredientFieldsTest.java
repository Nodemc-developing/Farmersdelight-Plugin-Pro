package com.huidu.farmersdelight.recipe;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class StrictIngredientFieldsTest {
    @Test void unknownMatchingAndConsumptionConditionsCannotBecomeOrdinaryIdMatches() {
        for (String field : List.of("count", "consume", "components", "exact-components", "conditions")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue(
                    Map.of("item", "minecraft:carrot", field, Map.of("unknown", 1))));
            assertTrue(failure.getMessage().contains(field));
        }
    }
    @Test void unknownConditionsInNestedAlternativesAlsoRefuseTheRecipe() {
        assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue(
                Map.of("items", List.of("minecraft:carrot", Map.of("item", "minecraft:apple", "consume", false)))));
    }
    @Test void extensionMetadataDoesNotChangeTheIngredientIdentity() {
        var ingredient = RecipeParsingSupport.parseIngredientValue(Map.of("item", "minecraft:carrot",
                "extensions", Map.of("integration", Map.of("key", "unchanged"))));
        assertEquals("item:minecraft:carrot", ingredient.stableKey());
    }
}
