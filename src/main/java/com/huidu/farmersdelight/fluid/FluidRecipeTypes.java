package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.api.FarmersDelightApi;

import java.util.List;

/** Publishes the three editable fluid recipe categories in the existing recipe book and editor menus. */
public final class FluidRecipeTypes {
    private static final List<String> TYPES = List.of("fluid_filling", "fluid_emptying", "soaking");
    private FluidRecipeTypes() { }

    public static void register(FluidRecipeManager manager) {
        for (String type : TYPES) FarmersDelightApi.get().registerRecipeType(new FluidRecipeType(manager, type));
    }

    public static void unregister() {
        for (String type : TYPES) FarmersDelightApi.get().unregisterRecipeType("farmersdelight:" + type);
    }
}
