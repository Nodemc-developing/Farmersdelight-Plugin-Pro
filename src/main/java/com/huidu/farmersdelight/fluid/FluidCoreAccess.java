package com.huidu.farmersdelight.fluid;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Optional adapter boundary: this interface never links FluidCore classes. */
interface FluidCoreAccess {
    boolean storageAt(Location location);
    default boolean isNativeTank(Location location) { return false; }
    void validate(FluidRecipeSpec recipe);
    void attach(FluidRecipeManager recipes);
    void close();
    FluidCoreBridge.Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate);
}
