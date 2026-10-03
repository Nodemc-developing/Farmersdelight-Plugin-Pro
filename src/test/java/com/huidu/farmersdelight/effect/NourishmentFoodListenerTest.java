package com.huidu.farmersdelight.effect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NourishmentFoodListenerTest {
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
