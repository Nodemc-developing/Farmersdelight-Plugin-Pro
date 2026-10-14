package com.huidu.farmersdelight.listener;

import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

final class CakeSliceRules {
    private static final Map<Material, Material> CANDLES = candles();
    record Click(UUID world, int x, int y, int z, int tick, EquipmentSlot hand) { }
    private CakeSliceRules() { }
    static boolean permitsInteraction(Event.Result block, Event.Result item) {
        return block != Event.Result.DENY && item != Event.Result.DENY;
    }
    static boolean target(Material material, boolean customCarrier) {
        return !customCarrier && (material == Material.CAKE || CANDLES.containsKey(material));
    }
    static Material candleDrop(Material material) { return CANDLES.get(material); }
    static int nextBites(int bites) {
        if (bites < 0 || bites > 6) throw new IllegalArgumentException("cake bites must be in 0..6");
        return bites == 6 ? -1 : bites + 1;
    }
    static boolean duplicateHand(Click previous, Click current) {
        return previous != null && previous.tick == current.tick && previous.world.equals(current.world)
                && previous.x == current.x && previous.y == current.y && previous.z == current.z
                && previous.hand != current.hand;
    }
    private static Map<Material, Material> candles() {
        EnumMap<Material, Material> result = new EnumMap<>(Material.class);
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.equals("CANDLE_CAKE") || name.endsWith("_CANDLE_CAKE")) {
                result.put(material, Material.valueOf(name.substring(0, name.length() - "_CAKE".length())));
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
