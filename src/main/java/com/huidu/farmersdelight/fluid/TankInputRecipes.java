package com.huidu.farmersdelight.fluid;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/** Orders native input work without conflating immediate container conversion and timed soaking. */
final class TankInputRecipes {
    record Decision(boolean completed, FluidRecipeSpec soaking) {
        private static final Decision COMPLETED = new Decision(true, null);
        private static final Decision NONE = new Decision(false, null);
    }
    private TankInputRecipes() { }
    static Decision select(List<FluidRecipeSpec> candidates,
                           Function<FluidRecipeSpec, FluidCoreBridge.Outcome> commitExplicit,
                           BooleanSupplier emptyContainer, BooleanSupplier fillContainer,
                           Predicate<FluidRecipeSpec> soakingReady) {
        if (HeldFluidRecipes.process(candidates, commitExplicit)
                || emptyContainer.getAsBoolean() || fillContainer.getAsBoolean()) return Decision.COMPLETED;
        for (FluidRecipeSpec recipe : candidates)
            if ("soaking".equals(recipe.type()) && soakingReady.test(recipe)) return new Decision(false, recipe);
        return Decision.NONE;
    }
}
