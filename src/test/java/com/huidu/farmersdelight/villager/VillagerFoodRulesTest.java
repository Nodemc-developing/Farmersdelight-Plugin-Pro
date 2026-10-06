package com.huidu.farmersdelight.villager;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VillagerFoodRulesTest {
    private final VillagerFoodRules rules = new VillagerFoodRules(Map.of("rice", 2, "onion", 1, "panicle", 0), Set.of("rice", "onion"), 32);
    @Test void farmerReserveUsesTheCombinedInventoryRatherThanEachStack() {
        assertEquals(8, rules.consumable("onion", 20 + 20, 12, true));
        assertEquals(6, rules.consumable("rice", 20 + 20, 12, true));
        assertEquals(0, rules.consumable("rice", 16 + 16, 12, true));
        assertEquals(1, rules.consumable("rice", 33, 12, true));
    }
    @Test void professionsAndPickupOnlyFoodKeepTheirOwnRules() {
        assertEquals(6, rules.consumable("rice", 16, 12, false));
        assertEquals(0, rules.consumable("panicle", 64, 12, false));
        assertEquals(0, rules.shareable("panicle", 64, true));
        assertEquals(8, rules.shareable("rice", 40, true));
        assertEquals(40, rules.shareable("rice", 40, false));
        assertEquals(0, rules.consumable("rice", 64, 0, true));
    }
    @Test void compostingLeavesBothPlantingAndConfiguredFoodReservesIntact() {
        assertEquals(8, VillagerCompostService.expendable(40, 32, 16));
        assertEquals(8, VillagerCompostService.expendable(40, 16, 32));
        assertEquals(0, VillagerCompostService.expendable(31, 32, 0));
    }
    @Test void invalidRulesAndNonFiniteCompostChancesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VillagerFoodRules(Map.of("rice", 13), Set.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new VillagerFoodRules(Map.of("rice", -1), Set.of(), 0));
        var config = new YamlConfiguration(); config.set("villager.compost.default-chance", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> VillagerCompostService.Settings.read(config));
    }
    @Test void everyNewFeatureCanBeDisabledWithoutStartingBackgroundWork() {
        var config = new YamlConfiguration();
        config.set("villager.harvest.enable", false); config.set("villager.pickup.enable", false);
        config.set("villager.breed.enable", false); config.set("villager.breed.share-food", false);
        config.set("villager.compost.enabled", false); config.set("villager.bonemeal.enabled", false);
        assertFalse(VillagerWorkSettings.read(config).anyWork());
        config.set("villager.compost.enabled", true); assertTrue(VillagerWorkSettings.read(config).compost());
        config.set("villager.enable", false); assertFalse(VillagerWorkSettings.read(config).anyWork());
    }
}
