package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.listener.worlddata.ExternalVillagerTrades;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VillagerWorkRulesTest {
    @Test void foodUnitsStopAtThresholdWithoutLosingUnusedItems() {
        assertEquals(4, VillagerInventoryMath.unitsToReach(0, 3, 64, 12));
        assertEquals(1, VillagerInventoryMath.unitsToReach(11, 6, 64, 12));
        assertEquals(2, VillagerInventoryMath.unitsToReach(0, 3, 2, 12));
        assertEquals(0, VillagerInventoryMath.unitsToReach(12, 3, 64, 12));
        assertEquals(0, VillagerInventoryMath.unitsToReach(0, 0, 64, 12));
        assertEquals(1, VillagerInventoryMath.unitsToReach(0, Integer.MAX_VALUE, 1, 12));
    }

    @Test void featureSwitchesAndBudgetsAreIndependentAndBounded() {
        var config = new YamlConfiguration(); config.set("villager.harvest.enable", false);
        config.set("villager.harvest.block_budget", 100000); config.set("villager.pickup.scan_interval_ticks", 0);
        var settings = VillagerWorkSettings.read(config);
        assertFalse(settings.harvest()); assertTrue(settings.pickup()); assertTrue(settings.breed());
        assertEquals(128, settings.blockBudget()); assertEquals(5, settings.activeTicks());
        config.set("villager.enable", false); assertFalse(VillagerWorkSettings.read(config).anyWork());
    }

    @Test void tradePoolsAreImmutableIndexedSnapshotsAndRegistrationDoesNotMutateOldSnapshot() {
        String first = "unit-test:farmer-a", second = "unit-test:farmer-b";
        var offer = new WorldDataConfig.TradeOffer("farmer", 2, "minecraft:carrot", 8, "minecraft:emerald", 1, 16, 2, .05f, .1);
        try {
            ExternalVillagerTrades.register(first, offer);
            var before = ExternalVillagerTrades.villagerTradesFor("farmer", 2);
            assertTrue(before.contains(offer)); assertThrows(UnsupportedOperationException.class, () -> before.add(offer));
            ExternalVillagerTrades.register(second, offer);
            assertEquals(before.size() + 1, ExternalVillagerTrades.villagerTradesFor("farmer", 2).size());
            assertEquals(1, before.stream().filter(value -> value == offer).count());
            assertFalse(ExternalVillagerTrades.villagerTradesFor("butcher", 2).contains(offer));
        } finally { ExternalVillagerTrades.unregister(first); ExternalVillagerTrades.unregister(second); }
    }
}
