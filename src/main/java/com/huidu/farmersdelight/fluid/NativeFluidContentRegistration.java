package com.huidu.farmersdelight.fluid;

import com.ydxc20091.fluidcore.ce.FluidContentFactories;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

/** Resolves the typed fluid API only when its separate plugin is installed. */
final class NativeFluidContentRegistration {
    private NativeFluidContentRegistration() { }

    static boolean register() {
        if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("fluidcore:container")) != null
                && BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("farmersdelight:jug_item")) == null) {
            ItemBehaviors.register(Key.of("farmersdelight:jug_item"), (pack, path, id, section) ->
                    FluidContentFactories.containerBehavior(section));
        }
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of("fluidcore:tank")) != null
                && BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of("farmersdelight:jug")) == null) {
            BlockBehaviors.register(Key.of("farmersdelight:jug"), (block, section) ->
                    FluidContentFactories.tank(block, section, FluidContentFormat.menuDefaults()));
            return true;
        }
        return false;
    }
}
