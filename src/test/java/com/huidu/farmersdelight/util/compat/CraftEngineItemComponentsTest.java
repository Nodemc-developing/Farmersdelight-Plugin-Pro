package com.huidu.farmersdelight.util.compat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineItemComponentsTest {
    @Test
    void legacyUseKeepsFoodEffectsAndComponentBearingRemainders() {
        Map<String, Object> remainder = Map.of("id", "minecraft:bowl", "components",
                Map.of("minecraft:custom_data", Map.of("serving", "kept")));
        List<Object> effects = List.of(Map.of("effect", Map.of("id", "minecraft:regeneration"), "probability", .25F));
        Map<String, Object> food = Map.of("nutrition", 8, "saturation", .6F,
                "eat_seconds", 1.6F, "using_converts_to", remainder, "effects", effects);

        Map<String, Object> result = CraftEngineItemComponents.withUseDuration(food, 120F, false);

        assertEquals(8, result.get("nutrition"));
        assertEquals(.6F, result.get("saturation"));
        assertSame(remainder, result.get("using_converts_to"));
        assertSame(effects, result.get("effects"));
        assertEquals(120F, result.get("eat_seconds"));
        assertEquals(true, result.get("can_always_eat"));
        assertFalse(result.containsKey("consume_seconds"));
        assertEquals(1.6F, food.get("eat_seconds"));
    }

    @Test
    void legacyNonFoodGetsAZeroNutritionNativeUseAction() {
        Map<String, Object> result = CraftEngineItemComponents.withUseDuration(null, 7F, false);
        assertEquals(Map.of("nutrition", 0, "saturation", 0F, "can_always_eat", true,
                "eat_seconds", 7F), result);
    }

    @Test
    void modernUseKeepsDrinkAnimationSoundAndEffectsWithoutInventingFood() {
        List<Object> effects = List.of(Map.of("type", "minecraft:clear_all_effects"));
        Map<String, Object> consumable = Map.of("animation", "drink", "sound", "minecraft:entity.generic.drink",
                "has_consume_particles", false, "on_consume_effects", effects, "consume_seconds", 1F);

        Map<String, Object> result = CraftEngineItemComponents.withUseDuration(consumable, 3.25F, true);

        assertEquals("drink", result.get("animation"));
        assertEquals("minecraft:entity.generic.drink", result.get("sound"));
        assertEquals(false, result.get("has_consume_particles"));
        assertSame(effects, result.get("on_consume_effects"));
        assertEquals(3.25F, result.get("consume_seconds"));
        assertFalse(result.containsKey("nutrition"));
        assertFalse(result.containsKey("eat_seconds"));
        assertEquals(1F, consumable.get("consume_seconds"));
    }
}
