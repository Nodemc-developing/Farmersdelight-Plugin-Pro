package com.huidu.farmersdelight.listener;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CakeSliceRulesTest {
    @Test void eitherItemOrBlockCancellationPreventsCuttingAndOrdinaryDefaultsPermitIt() {
        assertTrue(CakeSliceRules.permitsInteraction(Event.Result.DEFAULT, Event.Result.DEFAULT));
        assertTrue(CakeSliceRules.permitsInteraction(Event.Result.ALLOW, Event.Result.DEFAULT));
        assertTrue(CakeSliceRules.permitsInteraction(Event.Result.DEFAULT, Event.Result.ALLOW));
        assertFalse(CakeSliceRules.permitsInteraction(Event.Result.DENY, Event.Result.DEFAULT));
        assertFalse(CakeSliceRules.permitsInteraction(Event.Result.DEFAULT, Event.Result.DENY));
        assertFalse(CakeSliceRules.permitsInteraction(Event.Result.ALLOW, Event.Result.DENY));
        assertFalse(CakeSliceRules.permitsInteraction(Event.Result.DENY, Event.Result.ALLOW));
    }
    @Test void aWholeCakeProducesSevenSingleServingTransitions() {
        int bites = 0, servings = 0;
        while (bites >= 0) { bites = CakeSliceRules.nextBites(bites); servings++; }
        assertEquals(7, servings);
        assertThrows(IllegalArgumentException.class, () -> CakeSliceRules.nextBites(-1));
        assertThrows(IllegalArgumentException.class, () -> CakeSliceRules.nextBites(7));
    }
    @Test void allSeventeenNativeCandleCakesReturnTheMatchingCandle() {
        int count = 0;
        for (Material type : Material.values()) {
            if (!type.name().equals("CANDLE_CAKE") && !type.name().endsWith("_CANDLE_CAKE")) continue;
            count++;
            assertTrue(CakeSliceRules.target(type, false));
            assertEquals(type.name().substring(0, type.name().length() - "_CAKE".length()), CakeSliceRules.candleDrop(type).name());
        }
        assertEquals(17, count);
        assertNull(CakeSliceRules.candleDrop(Material.CAKE));
    }
    @Test void customCakeCarriersAndOtherBlocksAreAlwaysExcluded() {
        assertTrue(CakeSliceRules.target(Material.CAKE, false));
        assertFalse(CakeSliceRules.target(Material.CAKE, true));
        assertFalse(CakeSliceRules.target(Material.RED_CANDLE_CAKE, true));
        assertFalse(CakeSliceRules.target(Material.CANDLE, false));
        assertFalse(CakeSliceRules.target(Material.PUMPKIN_PIE, false));
        assertFalse(CakeSliceRules.target(Material.STONE, false));
    }
    @Test void mainAndOffHandForTheSameClickDoNotTakeTwoServings() {
        UUID world = UUID.randomUUID();
        var main = new CakeSliceRules.Click(world, 1, 64, 2, 333, EquipmentSlot.HAND);
        var off = new CakeSliceRules.Click(world, 1, 64, 2, 333, EquipmentSlot.OFF_HAND);
        assertTrue(CakeSliceRules.duplicateHand(main, off));
        assertTrue(CakeSliceRules.duplicateHand(off, main));
        assertFalse(CakeSliceRules.duplicateHand(main, main));
        assertFalse(CakeSliceRules.duplicateHand(null, off));
        assertFalse(CakeSliceRules.duplicateHand(main, new CakeSliceRules.Click(world, 1, 64, 2, 334, EquipmentSlot.OFF_HAND)));
        assertFalse(CakeSliceRules.duplicateHand(main, new CakeSliceRules.Click(world, 1, 64, 3, 333, EquipmentSlot.OFF_HAND)));
        assertFalse(CakeSliceRules.duplicateHand(main, new CakeSliceRules.Click(UUID.randomUUID(), 1, 64, 2, 333, EquipmentSlot.OFF_HAND)));
    }
}
