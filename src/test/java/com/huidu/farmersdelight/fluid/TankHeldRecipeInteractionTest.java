package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TankHeldRecipeInteractionTest {
    @Test void emptyingPrecedesFillingAndACommittedRecipeStopsFurtherAttempts() {
        var filling = recipe("test:filling", "fluid_filling", 30);
        var first = recipe("test:emptying_first", "fluid_emptying", 0);
        var second = recipe("test:emptying_second", "fluid_emptying", 0);
        List<String> attempts = new ArrayList<>();
        assertTrue(HeldFluidRecipes.process(List.of(filling, first, second), recipe -> {
            attempts.add(recipe.id());
            return recipe == first ? FluidCoreBridge.Outcome.NO_CAPACITY : FluidCoreBridge.Outcome.SUCCESS;
        }));
        assertEquals(List.of(first.id(), second.id()), attempts);
    }

    @Test void allExplicitFailuresPermitGenericHandlingAndNeverAttemptSoaking() {
        var soaking = recipe("test:soak", "soaking", 20);
        var filling = recipe("test:fill", "fluid_filling", 0);
        var emptying = recipe("test:empty", "fluid_emptying", 0);
        List<String> attempts = new ArrayList<>();
        assertFalse(HeldFluidRecipes.process(List.of(soaking, filling, emptying), recipe -> {
            assertNotEquals("soaking", recipe.type()); attempts.add(recipe.id());
            return FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
        }));
        assertEquals(List.of(emptying.id(), filling.id()), attempts);
    }

    @Test void soakingOnlyOrEmptyCandidatesDoNotConsumeOrCreateDelayedWork() {
        assertFalse(HeldFluidRecipes.process(List.of(recipe("test:soak", "soaking", 0)), recipe -> {
            fail("Soaking must wait for the native tank input slot"); return FluidCoreBridge.Outcome.SUCCESS;
        }));
        assertFalse(HeldFluidRecipes.process(List.of(), recipe -> {
            fail("No matching recipe may not commit"); return FluidCoreBridge.Outcome.SUCCESS;
        }));
    }

    private static FluidRecipeSpec recipe(String id, String type, long time) {
        return new FluidRecipeSpec(id, type, new RecipeIngredient.Item(Key.of("minecraft:glass_bottle")),
                Map.of("id", "minecraft:stone"), "minecraft:water", false, 250, time, true, 0);
    }
}
