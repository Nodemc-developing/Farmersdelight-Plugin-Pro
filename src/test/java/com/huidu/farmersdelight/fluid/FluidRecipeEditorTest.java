package com.huidu.farmersdelight.fluid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeEditorTest {
    @Test void numericFieldsWithoutAnOriginalLongDoNotUnboxNull() {
        var spec = new FluidRecipeSpec("example:fill", "fluid_filling",
                new com.huidu.farmersdelight.recipe.RecipeIngredient.Item(
                        net.momirealms.craftengine.core.util.Key.of("minecraft:bucket")),
                java.util.Map.of(), "minecraft:water", false, Long.MAX_VALUE, 0, true, 0);
        assertEquals(Long.MAX_VALUE, FluidRecipeEditor.originalNumber(spec, "amount"));
        assertEquals(0L, FluidRecipeEditor.originalNumber(spec, "time"));
        assertNull(FluidRecipeEditor.originalNumber(spec, "priority"));
        assertNull(FluidRecipeEditor.originalNumber(spec, "consume_fluid"));
        assertNull(FluidRecipeEditor.originalNumber(null, "amount"));
    }
    @Test void unchangedLoadedLongValuesRemainExactBeyondTheSliderRange() {
        for (long original : new long[]{Long.MAX_VALUE, 9_007_199_254_740_993L, 1000L, 0L}) {
            assertEquals(original, FluidRecipeEditor.longValue((double) original, original, "amount"));
        }
    }

    @Test void changedAmountsMustRemainIntegralAndExactlyRepresentable() {
        assertEquals(250L, FluidRecipeEditor.longValue(250D, 1000L, "amount"));
        assertEquals(9_007_199_254_740_991L,
                FluidRecipeEditor.longValue(9_007_199_254_740_991D, null, "amount"));
        for (double invalid : new double[]{1.5D, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, 9_007_199_254_740_992D}) {
            assertThrows(IllegalArgumentException.class, () -> FluidRecipeEditor.longValue(invalid, null, "amount"));
        }
    }

    @Test void highLoadedValueCannotBeRoundedIntoAnUnrelatedNewAmount() {
        long original = Long.MAX_VALUE;
        double changed = Math.nextDown((double) original);
        assertThrows(IllegalArgumentException.class, () -> FluidRecipeEditor.longValue(changed, original, "amount"));
    }
}
