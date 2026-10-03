package com.huidu.farmersdelight.fluid;

import java.util.List;
import java.util.function.Function;

/** Explicit container actions precede generic conversion; soaking belongs to the tank input. */
final class HeldFluidRecipes {
    private HeldFluidRecipes() { }
    static boolean process(List<FluidRecipeSpec> candidates, Function<FluidRecipeSpec, FluidCoreBridge.Outcome> commit) {
        for (int phase = 0; phase < 2; phase++) {
            String type = phase == 0 ? "fluid_emptying" : "fluid_filling";
            for (FluidRecipeSpec recipe : candidates)
                if (type.equals(recipe.type()) && commit.apply(recipe).success()) return true;
        }
        return false;
    }
}
