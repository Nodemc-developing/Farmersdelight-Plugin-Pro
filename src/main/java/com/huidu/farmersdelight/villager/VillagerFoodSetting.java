package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.compat.OtherDelightIds;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.item.setting.ItemSettings;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifier;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifiers;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

/** Villager nutrition is independent of player FOOD components and stays with the loaded definition. */
public final class VillagerFoodSetting {
    // The unique processor identity also separates this type from other addons' data-only setting types.
    private static final CustomItemSettingType<Integer> TYPE = CustomItemSettingType.newType((points, output) -> { }, null);
    private VillagerFoodSetting() { }

    public static void register() {
        for (String namespace : java.util.List.of("farmersdelight", OtherDelightIds.NAMESPACE)) {
            Key id = Key.of(namespace + ":villager_food_point");
            if (BuiltInRegistries.ITEM_SETTINGS_TYPE.getValue(id) == null) ItemSettingsModifiers.register(id, VillagerFoodSetting::modifier);
        }
    }

    static ItemSettingsModifier modifier(ConfigValue configured) {
        int points = configured.getAsInt();
        if (points < 0 || points > 4096) throw new IllegalArgumentException(configured.path() + " must be between 0 and 4096");
        return settings -> settings.addCustomData(TYPE, points);
    }

    /** Null means not declared; an explicit zero prevents the player-food fallback. */
    public static Integer points(ItemSettings settings) { return settings == null ? null : settings.getCustomData(TYPE); }
}
