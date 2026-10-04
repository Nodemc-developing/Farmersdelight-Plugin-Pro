package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OtherDelightIngredientParsingTest {
    @Test void nestedItemsAndLegacyChoiceAreOneAlternativeSetWithSnapshotsIntact() {
        var parsed = RecipeParsingSupport.parseIngredientValue(Map.of("items", List.of(
                "minecraft:carrot", Map.of("items", List.of("advtag:example:vegetables", "minecraft:carrot")),
                Map.of("choice", List.of(Map.of("item", "minecraft:paper", "nbt", "AQ=="))))));
        var options = assertInstanceOf(RecipeIngredient.Choice.class, parsed).options();
        assertEquals(3, options.size());
        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:carrot")), options.getFirst());
        assertInstanceOf(RecipeIngredient.AdvancedTag.class, options.get(1));
        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:paper"), "AQ=="), options.get(2));
        assertEquals(parsed, RecipeParsingSupport.parseIngredientValue(RecipeSerializer.serializeIngredientValue(parsed)));
    }

    @Test void configurationSectionMapsAcceptOtherDelightAlternatives() {
        var yaml = new YamlConfiguration();
        yaml.set("ingredient.items", List.of("minecraft:carrot", "minecraft:potato"));
        var parsed = RecipeParsingSupport.parseIngredientValue(yaml.getConfigurationSection("ingredient"));
        assertInstanceOf(RecipeIngredient.Choice.class, parsed);
        assertEquals("minecraft:carrot|minecraft:potato", RecipeSerializer.serializeIngredient(parsed));
    }

    @Test void invalidAlternativesDoNotBecomeItemIdsOrPartialRecipes() {
        assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue(Map.of("items", List.of())));
        assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue(Map.of("items", "minecraft:carrot")));
        assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue(Map.of("items", List.of("minecraft:carrot", Map.of("bad", "value")))));
        assertThrows(IllegalArgumentException.class, () -> RecipeParsingSupport.parseIngredientValue("advtag: "));
    }

    @Test void pathologicalNestingIsRejectedWithAUsefulError() {
        Object value = "minecraft:carrot";
        for (int i = 0; i < 34; i++) value = Map.of("items", List.of(value));
        final Object nested = value;
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RecipeParsingSupport.parseIngredientValue(nested)).getMessage().contains("nested too deeply"));
    }

    @Test void singletonAndDuplicateAlternativesCollapseWithoutChangingIngredientQuantity() {
        RecipeIngredient parsed = RecipeParsingSupport.parseIngredientValue(Map.of("items", List.of("minecraft:carrot", "minecraft:carrot")));
        assertEquals(new RecipeIngredient.Item(Key.of("minecraft:carrot")), parsed);
    }
}
