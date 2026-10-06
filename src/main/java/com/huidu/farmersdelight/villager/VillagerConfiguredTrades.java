package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.listener.worlddata.ExternalVillagerTrades;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration contributes to the existing weighted offers; addon registrations are retained. */
final class VillagerConfiguredTrades implements AutoCloseable {
    private final List<String> registered = new ArrayList<>();

    void reload(ConfigurationSection config) {
        Map<String, WorldDataConfig.TradeOffer> incoming = new LinkedHashMap<>();
        if (config.getBoolean("villager.enable", true)) {
            if (config.getBoolean("villager.farmers-buy-crops.enable", true)) {
                List<Map<?, ?>> rows = config.getMapList("villager.farmers-buy-crops.trades");
                for (int index = 0; index < rows.size(); index++) {
                    Map<?, ?> row = rows.get(index);
                    String item = string(row, "item", "");
                    validateItem(item, "villager.farmers-buy-crops.trades[" + index + "].item");
                    int level = integer(row, "level", 1, 1, 5);
                    incoming.put("farmersdelight:configured_farmer_buy_" + index, new WorldDataConfig.TradeOffer("farmer", level,
                            item, integer(row, "amount", 16, 1, 64), "minecraft:emerald", 1,
                            integer(row, "max_uses", 16, 1, 10000), integer(row, "villager_xp", 2, 0, 10000),
                            (float) decimal(row, "price_multiplier", .05, 0, 1), decimal(row, "chance", .35 / Math.max(1, rows.size()), 0, 1)));
                }
            }
            if (config.getBoolean("villager.wandering-trader-sells.enable", true)) {
                List<?> rows = config.getList("villager.wandering-trader-sells.items", List.of());
                for (int index = 0; index < rows.size(); index++) {
                    Object raw = rows.get(index);
                    Map<?, ?> row = raw instanceof Map<?, ?> map ? map : Map.of("item", String.valueOf(raw));
                    String item = string(row, "item", "");
                    validateItem(item, "villager.wandering-trader-sells.items[" + index + "].item");
                    incoming.put("farmersdelight:configured_wandering_sell_" + index, new WorldDataConfig.TradeOffer(null, 0,
                            "minecraft:emerald", integer(row, "emerald_cost", 1, 1, 64), item,
                            integer(row, "amount", 1, 1, 64), integer(row, "max_uses", 12, 1, 10000),
                            integer(row, "villager_xp", 1, 0, 10000), (float) decimal(row, "price_multiplier", .05, 0, 1),
                            decimal(row, "chance", .35 / Math.max(1, rows.size()), 0, 1)));
                }
            }
        }
        close();
        incoming.forEach((id, offer) -> { ExternalVillagerTrades.register(id, offer); registered.add(id); });
    }

    @Override public void close() { registered.forEach(ExternalVillagerTrades::unregister); registered.clear(); }

    private static void validateItem(String item, String path) {
        if (item.isBlank() || ItemUtils.createItem(item) == null) throw new IllegalArgumentException(path + " refers to an unknown item: " + item);
    }
    private static String string(Map<?, ?> row, String key, String fallback) {
        Object value = row.get(key); return value == null ? fallback : String.valueOf(value).trim();
    }
    private static int integer(Map<?, ?> row, String key, int fallback, int minimum, int maximum) {
        String value = string(row, key, String.valueOf(fallback));
        int parsed;
        try { parsed = Integer.parseInt(value); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid villager trade " + key + ": " + value); }
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException("Villager trade " + key + " outside " + minimum + ".." + maximum);
        return parsed;
    }
    private static double decimal(Map<?, ?> row, String key, double fallback, double minimum, double maximum) {
        String value = string(row, key, String.valueOf(fallback));
        double parsed;
        try { parsed = Double.parseDouble(value); } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid villager trade " + key + ": " + value); }
        if (!Double.isFinite(parsed) || parsed < minimum || parsed > maximum) throw new IllegalArgumentException("Invalid villager trade " + key + ": " + value);
        return parsed;
    }
}
