package com.huidu.farmersdelight.fluid;

import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifiers;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

/** Registers compatibility identifiers through CraftEngine's public factories. */
public final class PapersFluidAliases {
    private PapersFluidAliases() { }

    public static void register() {
        // Jug blocks need an explicit fluid-preserving loot entry before adopting fluidcore:tank.
        // A behavior factory cannot change the block's already-constructed loot table.
        var container = BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("fluidcore:container"));
        Key jugItem = Key.of("papersdelight:jug_item");
        if (container != null && BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(jugItem) == null) {
            ItemBehaviors.register(jugItem, container.factory());
        }
        var setting = BuiltInRegistries.ITEM_SETTINGS_TYPE.getValue(Key.of("fluidcore:container"));
        Key legacySetting = Key.of("libuid:fluid_container");
        if (setting != null && BuiltInRegistries.ITEM_SETTINGS_TYPE.getValue(legacySetting) == null) {
            ItemSettingsModifiers.register(legacySetting, setting.factory());
        }
    }
}
