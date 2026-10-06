package com.huidu.farmersdelight.fluid;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Optional adapter boundary: this interface never links FluidCore classes. */
interface FluidCoreAccess {
    boolean storageAt(Location location);
    default boolean isNativeTank(Location location) { return false; }
    default boolean isNativeTankState(ImmutableBlockState state) { return false; }
    default boolean protectedNativeRecord(ItemStack item) { return true; }
    void validate(FluidRecipeSpec recipe);
    void attach(FluidRecipeManager recipes);
    void close();
    FluidCoreBridge.Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate);
}
