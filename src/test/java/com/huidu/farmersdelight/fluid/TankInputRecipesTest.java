package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TankInputRecipesTest {
    @Test void explicitEmptyingCommitsImmediatelyAheadOfFillingAndGenericWorkEvenWithATimeField() {
        var filling = recipe("test:fill", "fluid_filling", 40);
        var soaking = recipe("test:soak", "soaking", 20);
        var emptying = recipe("test:empty", "fluid_emptying", 120);
        List<String> calls = new ArrayList<>();
        var decision = TankInputRecipes.select(List.of(filling, soaking, emptying), recipe -> {
            calls.add(recipe.id()); return FluidCoreBridge.Outcome.SUCCESS;
        }, () -> fail("Committed explicit work may not also empty generically"),
                () -> fail("Committed explicit work may not also fill generically"),
                recipe -> fail("Explicit container time is not a soaking delay"));
        assertTrue(decision.completed()); assertNull(decision.soaking());
        assertEquals(List.of(emptying.id()), calls);
    }

    @Test void genericEmptyingThenFillingPrecedeSoakingAndKeepFailureOrderingStable() {
        var soaking = recipe("test:soak", "soaking", 20);
        var filling = recipe("test:fill", "fluid_filling", 50);
        var emptying = recipe("test:empty", "fluid_emptying", 50);
        List<String> calls = new ArrayList<>();
        var decision = TankInputRecipes.select(List.of(soaking, filling, emptying), recipe -> {
            calls.add(recipe.id()); return FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
        }, () -> { calls.add("generic-empty"); return false; },
                () -> { calls.add("generic-fill"); return true; },
                recipe -> fail("A generic conversion already committed this input"));
        assertTrue(decision.completed()); assertNull(decision.soaking());
        assertEquals(List.of(emptying.id(), filling.id(), "generic-empty", "generic-fill"), calls);
    }

    @Test void onlyTheFirstCurrentlyReadySoakingRecipeReceivesItsExactConfiguredDuration() {
        var blocked = recipe("test:blocked", "soaking", 161);
        var ready = recipe("test:ready", "soaking", 27);
        List<String> calls = new ArrayList<>();
        var decision = TankInputRecipes.select(List.of(blocked, ready), recipe -> fail("Soaking must not enter an immediate explicit commit"),
                () -> { calls.add("generic-empty"); return false; },
                () -> { calls.add("generic-fill"); return false; },
                recipe -> { calls.add(recipe.id()); return recipe == ready; });
        assertFalse(decision.completed()); assertSame(ready, decision.soaking());
        assertEquals(27, decision.soaking().timeTicks());
        assertEquals(List.of("generic-empty", "generic-fill", blocked.id(), ready.id()), calls);
    }

    @Test void blockedOutputAndNoMatchingContainersDoNotCreateTimedWork() {
        var blocked = recipe("test:blocked", "soaking", 20);
        var decision = TankInputRecipes.select(List.of(blocked), recipe -> fail("Not a container recipe"),
                () -> false, () -> false, recipe -> false);
        assertFalse(decision.completed()); assertNull(decision.soaking());
    }

    private static FluidRecipeSpec recipe(String id, String type, long ticks) {
        return new FluidRecipeSpec(id, type, new RecipeIngredient.Item(Key.of("minecraft:glass_bottle")),
                Map.of("item", "minecraft:bread"), "minecraft:water", false, 250, ticks, true, 0);
    }
}
