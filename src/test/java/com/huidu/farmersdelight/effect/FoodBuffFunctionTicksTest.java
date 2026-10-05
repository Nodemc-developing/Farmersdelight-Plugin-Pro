package com.huidu.farmersdelight.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodBuffFunctionTicksTest {
    @Test void nativeDurationsUseTicksAndRoundOnlyToTheExistingBuffSecondResolution() {
        assertEquals(30, FoodBuffFunction.ticksToSeconds(600));
        assertEquals(1, FoodBuffFunction.ticksToSeconds(20));
        assertEquals(2, FoodBuffFunction.ticksToSeconds(21));
        assertEquals(1, FoodBuffFunction.ticksToSeconds(0));
        assertEquals(107374183, FoodBuffFunction.ticksToSeconds(Integer.MAX_VALUE));
    }
}
