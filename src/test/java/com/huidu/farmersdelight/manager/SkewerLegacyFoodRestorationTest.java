package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkewerLegacyFoodRestorationTest {
    @Test void nourishmentRetractionDoesNotLeaveTheTemporaryCookingDuration() {
        var original = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "can_always_eat", true);
        var installed = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "can_always_eat", true, "eat_seconds", 8F);
        var current = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "can_always_eat", false, "eat_seconds", 8D);
        var restored = SkewerUseLease.retractLegacyFood(current, installed, original);
        assertFalse((Boolean) restored.get("can_always_eat"), "The other owner's retraction must survive");
        assertFalse(restored.containsKey("eat_seconds"), "Absent original duration selects the native 1.6-second default");
        assertFalse(SkewerUseLease.sameLegacyFood(restored, original), "The current food must not reset to an obsolete always-eat value");
        assertEquals(8D, current.get("eat_seconds"));
    }

    @Test void effectsNutritionAndContainerChangesSurviveRemovalOfOwnedFields() {
        var original = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "eat_seconds", 2.5F);
        var installed = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "eat_seconds", 8F, "can_always_eat", true);
        var effects = List.of(Map.of("effect", "custom-effect"));
        var container = Map.of("id", "minecraft:bowl", "count", 1);
        var current = Map.<String, Object>of("nutrition", 7, "saturation", 5F, "eat_seconds", 8F,
                "can_always_eat", true, "effects", effects, "using_converts_to", container, "extension", "retained");
        var restored = SkewerUseLease.retractLegacyFood(current, installed, original);
        assertEquals(2.5F, restored.get("eat_seconds"));
        assertFalse(restored.containsKey("can_always_eat"));
        assertEquals(7, restored.get("nutrition"));
        assertEquals(5F, restored.get("saturation"));
        assertEquals(effects, restored.get("effects"));
        assertEquals(container, restored.get("using_converts_to"));
        assertEquals("retained", restored.get("extension"));
        assertFalse(SkewerUseLease.sameLegacyFood(restored, original));
    }

    @Test void anotherOwnersUseDurationIsNotOverwritten() {
        var original = Map.<String, Object>of("nutrition", 4, "saturation", 2F);
        var installed = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "eat_seconds", 8F, "can_always_eat", true);
        var current = Map.<String, Object>of("nutrition", 4, "saturation", 2F, "eat_seconds", 12F, "can_always_eat", false);
        assertEquals(current, SkewerUseLease.retractLegacyFood(current, installed, original));
    }

    @Test void anOriginallyAbsentFoodComponentCanResetAfterOnlyOurChangesAreRetracted() {
        var installed = Map.<String, Object>of("nutrition", 0, "saturation", 0F, "eat_seconds", 8F, "can_always_eat", true);
        var restored = SkewerUseLease.retractLegacyFood(installed, installed, null);
        assertTrue(SkewerUseLease.sameLegacyFood(restored, null));
        assertFalse(SkewerUseLease.sameLegacyFood(Map.of("nutrition", 0, "saturation", 0F, "extension", true), null));
        assertFalse(SkewerUseLease.sameLegacyFood(Map.of("nutrition", 1, "saturation", 0F), null));
    }

    @Test void nativeOmittedDefaultsAndExplicitDefaultsRepresentTheSameCompleteFood() {
        var original = Map.<String, Object>of("nutrition", 4, "saturation", 2F);
        var explicit = Map.<String, Object>of("nutrition", 4, "saturation", 2D,
                "eat_seconds", 1.6D, "can_always_eat", false, "effects", List.of());
        assertTrue(SkewerUseLease.sameLegacyFood(explicit, original));
        assertFalse(SkewerUseLease.sameLegacyFood(Map.of("nutrition", 4, "saturation", 2F,
                "using_converts_to", Map.of("id", "minecraft:bowl")), original));
    }
}
