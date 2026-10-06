package com.huidu.farmersdelight.fluid;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

/** Optional adapter boundary: this interface never links FluidCore classes. */
interface FluidCoreAccess {
    boolean storageAt(Location location);
    default boolean isNativeTank(Location location) { return false; }
    default boolean isNativeTankState(ImmutableBlockState state) { return false; }
    void validate(FluidRecipeSpec recipe);
    void attach(FluidRecipeManager recipes);
    void close();
    FluidCoreBridge.Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate);
}
