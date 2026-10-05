package com.huidu.farmersdelight.item.behavior;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkilletItemModelDefaultsTest {
    @Test void minimalPortableItemHasUsableCookingAndFoodOverlayDefaults() {
        var item = SkilletItemBehavior.FACTORY.create(null, Path.of("minimal.yml"), Key.of("farmersdelight:skillet"),
                ConfigSection.ofRoot(Map.of("type", "farmersdelight:skillet_item", "block", "farmersdelight:skillet")));
        assertEquals(NamespacedKey.fromString("farmersdelight:skillet_cooking"), item.cookingModel());
        assertEquals(NamespacedKey.fromString("farmersdelight:item/skillet_food"), item.ingredientOverlayModel());
        assertTrue(item.usesDefaultCookingModel());
    }

    @Test void defaultsFollowTheRealItemNamespaceAndPath() {
        var item = SkilletItemBehavior.FACTORY.create(null, Path.of("pan.yml"), Key.of("custom:copper/pan"), null);
        assertEquals(NamespacedKey.fromString("custom:copper/pan_cooking"), item.cookingModel());
        assertTrue(item.usesDefaultCookingModel());
    }

    @Test void authoredModelOptionsRetainTheirExactIdentifiers() {
        var item = SkilletItemBehavior.FACTORY.create(null, Path.of("authored.yml"), Key.of("addon:pan"),
                ConfigSection.ofRoot(Map.of("cooking-model", "art:held_pan", "ingredient-overlay-model", "art:item/food")));
        assertEquals(NamespacedKey.fromString("art:held_pan"), item.cookingModel());
        assertEquals(NamespacedKey.fromString("art:item/food"), item.ingredientOverlayModel());
        assertFalse(item.usesDefaultCookingModel());
    }
}
