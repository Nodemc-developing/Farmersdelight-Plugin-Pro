package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SpecialRecipeDefinitionsTest {
    @Test void bundledCompostCardKeepsItsRecipeConditionsAndBehaviorCatalysts() throws Exception {
        var recipes = bundled();
        assertEquals(9, recipes.getKeys(false).size());
        var card = SpecialRecipeLoader.parseRecipe("organic_compost", recipes.getConfigurationSection("organic_compost"));
        assertEquals(SpecialRecipeInfo.DISPLAY_RECIPE, card.displayType());
        assertEquals("farmersdelight:organic_compost", card.inputSlots().getFirst().itemId());
        assertEquals("farmersdelight:rich_soil", card.outputSlots().getFirst().itemId());
        assertTrue(card.hasSunlight());
        assertTrue(card.hasWater());
        assertTrue(card.hasCatalystInfo());
        assertTrue(card.catalystSlots().getFirst().isBehaviorList());
        assertEquals("farmersdelight:organic_compost", card.catalystSlots().getFirst().behaviorBlockId());
        assertEquals("activators", card.catalystSlots().getFirst().behaviorListKey());
    }

    @Test void remainingEightCardsKeepTheirItemDescriptionsAndTranslationKeys() throws Exception {
        var recipes = bundled();
        int descriptions = 0;
        for (String id : recipes.getKeys(false)) {
            if (id.equals("organic_compost")) continue;
            var card = SpecialRecipeLoader.parseRecipe(id, recipes.getConfigurationSection(id));
            assertEquals(SpecialRecipeInfo.DISPLAY_ITEM_DESCRIPTION, card.displayType(), id);
            assertFalse(card.titleKey().isBlank(), id);
            assertFalse(card.iconItemId().isBlank(), id);
            assertFalse(card.descriptionKeys().isEmpty(), id);
            assertTrue(card.descriptionKeys().stream().allMatch(key -> key.startsWith("gui.special_recipe.")), id);
            assertTrue(card.inputSlots().isEmpty(), id);
            assertTrue(card.outputSlots().isEmpty(), id);
            descriptions++;
        }
        assertEquals(8, descriptions);
    }

    private ConfigurationSection bundled() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("recipes/special_recipes.yml")) {
            assertNotNull(input);
            return PlainYamlDocuments.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8), true)
                    .getConfigurationSection("special_recipes");
        }
    }
}
