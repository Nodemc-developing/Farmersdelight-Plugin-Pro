package com.huidu.farmersdelight.villager;

import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.item.setting.ItemSettings;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillagerFoodSettingTest {
    @Test void nutritionLivesOnTheDefinitionAndDoesNotCollideWithOtherSimpleSettings() throws Exception {
        // Exercise the real custom-data methods without a global CraftEngine plugin/config instance.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var instance = unsafeType.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        ItemSettings settings = (ItemSettings) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), ItemSettings.class);
        var customData = ItemSettings.class.getDeclaredField("customData");
        customData.setAccessible(true); customData.set(settings, new java.util.IdentityHashMap<>());
        CustomItemSettingType<String> unrelated = CustomItemSettingType.simple();
        settings.addCustomData(unrelated, "preserved");
        assertNull(VillagerFoodSetting.points(settings));
        VillagerFoodSetting.modifier(ConfigValue.of("settings.villager_food_point", 2)).apply(settings);
        assertEquals(2, VillagerFoodSetting.points(settings));
        assertEquals("preserved", settings.getCustomData(unrelated));
        VillagerFoodSetting.modifier(ConfigValue.of("settings.villager_food_point", 0)).apply(settings);
        assertEquals(0, VillagerFoodSetting.points(settings));
    }

    @Test void invalidPointsRejectTheDefinitionRatherThanSilentlyChangingItsNutrition() {
        assertThrows(IllegalArgumentException.class, () -> VillagerFoodSetting.modifier(ConfigValue.of("settings.villager_food_point", -1)));
        assertThrows(IllegalArgumentException.class, () -> VillagerFoodSetting.modifier(ConfigValue.of("settings.villager_food_point", 4097)));
    }
}
