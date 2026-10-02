package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.config.StationSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipePreviewCadenceTest {
    @Test
    void configuredGameTicksProduceTheExpectedIngredientToolAndCatalystCycle() {
        for (int ticks : new int[]{20, 80, 21}) {
            int callbacks = StationSettings.previewCallbacks(ticks);
            assertEquals((ticks + 3) / 4, callbacks);
            CyclicSlot ingredient = new CyclicSlot(callbacks);
            CyclicSlot tool = new CyclicSlot(callbacks);
            CyclicSlot catalyst = new CyclicSlot(callbacks);
            for (int pass = 1; pass < callbacks; pass++) {
                assertFalse(ingredient.tick());
                assertFalse(tool.tick());
                assertFalse(catalyst.tick());
            }
            assertTrue(ingredient.tick());
            assertTrue(tool.tick());
            assertTrue(catalyst.tick());
            assertEquals(1, ingredient.current(3));
            assertEquals(1, tool.current(3));
            assertEquals(1, catalyst.current(3));
            ingredient.reset();
            assertEquals(0, ingredient.current(3));
        }
    }

    @Test
    void invalidIntervalsClampToOneCallbackAndLargeValuesDoNotOverflow() {
        assertEquals(1, StationSettings.previewCallbacks(0));
        assertEquals(1, StationSettings.previewCallbacks(-20));
        assertEquals(536870912, StationSettings.previewCallbacks(Integer.MAX_VALUE));
    }
}
