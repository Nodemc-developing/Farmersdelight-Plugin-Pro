package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.compat.OtherDelightIds;
import com.ydxc20091.fluidcore.ce.FluidContentFactories;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifiers;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

/** Linked only after the optional library is present; existing foreign factories retain ownership. */
final class TypedFluidContentAliases {
    private TypedFluidContentAliases() {}
    static boolean register() {
        if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("fluidcore:container")) != null
                && BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of(OtherDelightIds.JUG_ITEM)) == null)
            ItemBehaviors.register(Key.of(OtherDelightIds.JUG_ITEM), (pack, path, id, section) -> FluidContentFactories.containerBehavior(section));
        if (BuiltInRegistries.ITEM_SETTINGS_TYPE.getValue(Key.of("fluidcore:container")) != null
                && BuiltInRegistries.ITEM_SETTINGS_TYPE.getValue(Key.of("libuid:fluid_container")) == null)
            ItemSettingsModifiers.register(Key.of("libuid:fluid_container"), FluidContentFactories::containerSetting);
        if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of("fluidcore:tank")) != null
                && BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(OtherDelightIds.JUG)) == null) {
            BlockBehaviors.register(Key.of(OtherDelightIds.JUG), (block, section) ->
                    FluidContentFactories.tank(block, section, FluidContentFormat.menuDefaults())); return true;
        }
        return false;
    }
}
