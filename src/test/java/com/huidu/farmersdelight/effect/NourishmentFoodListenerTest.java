package com.huidu.farmersdelight.effect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NourishmentFoodListenerTest {
    @Test void legacyFoodEffectsAndRemaindersAddedLaterPreventResettingTheWholeComponent() {
        var defaults = java.util.Map.<String, Object>of("nutrition", 5, "saturation", .6F);
        var installed = new java.util.HashMap<>(defaults);
        installed.put("can_always_eat", true);
        assertTrue(NourishmentFoodListener.sameExceptAlwaysEat(installed, defaults));
        installed.put("effects", java.util.List.of(java.util.Map.of("effect", "minecraft:speed")));
        assertFalse(NourishmentFoodListener.sameExceptAlwaysEat(installed, defaults));
        installed.remove("effects");
        installed.put("using_converts_to", java.util.Map.of("id", "minecraft:bowl"));
        assertFalse(NourishmentFoodListener.sameExceptAlwaysEat(installed, defaults));
        assertFalse(NourishmentFoodListener.sameExceptAlwaysEat(installed, null));
    }
    @Test void componentRetractionPreservesLaterChangesAndRejectsCorruptMarkers() {
        int[] original = {5, Float.floatToIntBits(.6f), 1};
        assertTrue(NourishmentFoodListener.canRestore(original, 5, .6f, true));
        assertFalse(NourishmentFoodListener.canRestore(original, 6, .6f, true));
        assertFalse(NourishmentFoodListener.canRestore(original, 5, .7f, true));
        assertFalse(NourishmentFoodListener.canRestore(original, 5, .6f, false));
        assertFalse(NourishmentFoodListener.canRestore(new int[]{5, Float.floatToIntBits(.6f), 2}, 5, .6f, true));
        assertFalse(NourishmentFoodListener.canRestore(new int[]{5}, 5, .6f, true));
        assertFalse(NourishmentFoodListener.canRestore(null, 5, .6f, true));
    }
}
