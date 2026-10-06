package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.util.Key;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

record VillagerContentSnapshot(VillagerWorkSettings settings, VillagerFoodRules foodRules,
                               VillagerCropRegistry.Registry crops, Set<String> pickupItems,
                               VillagerCompostService.Settings compost) {
    Map<String, Integer> foodPoints() { return foodRules.points(); }
    Map<String, java.util.List<VillagerCrop>> plants() { return crops.bySeed(); }

    static VillagerContentSnapshot read(FarmersDelightPlugin plugin) {
        var config = plugin.getConfig();
        var crops = VillagerCropRegistry.read(plugin);
        Map<String, Integer> points = new LinkedHashMap<>();
        Set<String> seeds = new LinkedHashSet<>(crops.bySeed().keySet());
        var engine = BukkitCraftEngine.instance();
        Map<String, Integer> defaults = Map.of("farmersdelight:cabbage", 1, "farmersdelight:tomato", 1,
                "farmersdelight:onion", 1, "farmersdelight:rice", 2, "farmersdelight:rice_panicle", 0);
        defaults.forEach((id, value) -> { if (engine.itemManager().loadedItems().containsKey(Key.of(id))) points.put(id, value); });
        boolean discover = config.getBoolean("villager.breed.discover-foods", false);
        boolean discoverSeeds = config.getBoolean("villager.pickup.discover-plantable-seeds", true);
        for (var entry : engine.itemManager().loadedItems().entrySet()) {
            var definition = entry.getValue();
            Integer declared = VillagerFoodSetting.points(definition.settings());
            if (declared != null) points.put(entry.getKey().toString(), Math.max(0, Math.min(12, declared)));
            else if (discover && definition.settings().foodData() != null)
                points.put(entry.getKey().toString(), Math.max(0, Math.min(12, definition.settings().foodData().nutrition())));
            if (discoverSeeds && definition.settings().tags().contains(Key.of("minecraft:villager_plantable_seeds")))
                seeds.add(entry.getKey().toString());
        }
        var overrides = config.getConfigurationSection("villager.breed.food-points");
        if (overrides != null) for (var entry : overrides.getValues(false).entrySet()) {
            int value;
            try { value = Integer.parseInt(String.valueOf(entry.getValue())); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid villager food points for " + entry.getKey()); }
            if (value < 0 || value > 12) throw new IllegalArgumentException("Villager food points must be 0..12 for " + entry.getKey());
            points.put(entry.getKey(), value);
        }
        int reserve = config.getBoolean("villager.breed.protect-custom-seeds", true)
                ? Math.max(0, Math.min(512, config.getInt("villager.breed.minimum-kept-seeds", 32))) : 0;
        seeds.removeIf(id -> id.startsWith("minecraft:"));
        // Vanilla foods retain Minecraft's consumption logic and planting reserves.
        points.keySet().removeIf(id -> id.startsWith("minecraft:"));
        Set<String> pickup = new LinkedHashSet<>(crops.bySeed().keySet());
        pickup.addAll(seeds);
        pickup.addAll(config.getStringList("villager.pickup.foods"));
        pickup.addAll(config.getStringList("villager.pickup.extra-items"));
        if (config.getBoolean("villager.pickup.include-harvest-drops", true)) pickup.addAll(crops.pickupDrops());
        if (discover) pickup.addAll(points.keySet());
        pickup.removeIf(id -> id.startsWith("minecraft:"));
        return new VillagerContentSnapshot(VillagerWorkSettings.read(config), new VillagerFoodRules(points, seeds, reserve),
                crops, Set.copyOf(pickup), VillagerCompostService.Settings.read(config));
    }
}
