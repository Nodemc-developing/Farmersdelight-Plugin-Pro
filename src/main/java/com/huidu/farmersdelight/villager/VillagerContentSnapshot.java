package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

record VillagerContentSnapshot(VillagerWorkSettings settings, Map<String, Integer> foodPoints,
                               Map<String, List<ManagedCropBlockBehavior>> plants, Set<String> pickupItems) {
    static VillagerContentSnapshot read(FarmersDelightPlugin plugin) {
        var config = plugin.getConfig();
        Map<String, Integer> food = new LinkedHashMap<>();
        Set<String> pickup = new LinkedHashSet<>();
        var engine = BukkitCraftEngine.instance();
        for (var entry : engine.itemManager().loadedItems().entrySet()) {
            var definition = entry.getValue();
            Integer declared = VillagerFoodSetting.points(definition.settings());
            int points = declared == null ? (definition.settings().foodData() == null ? 0 : definition.settings().foodData().nutrition()) : declared;
            if (declared == null && points <= 0) {
                var stack = ItemUtils.createItem(entry.getKey().toString());
                if (stack != null && stack.hasItemMeta() && stack.getItemMeta().hasFood())
                    points = stack.getItemMeta().getFood().getNutrition();
            }
            if (points > 0) food.put(entry.getKey().toString(), Math.min(4096, points));
            if (definition.settings().tags().contains(Key.of("minecraft:villager_plantable_seeds"))) pickup.add(entry.getKey().toString());
        }
        var overrides = config.getConfigurationSection("villager.breed.food_points");
        if (overrides != null) for (String id : overrides.getKeys(false)) {
            int points = overrides.getInt(id);
            if (points <= 0) food.remove(id); else food.put(id, Math.min(4096, points));
        }
        Map<String, List<ManagedCropBlockBehavior>> plants = new LinkedHashMap<>();
        for (var definition : BuiltInRegistries.BLOCK) {
            var behavior = ManagedCropBlockBehavior.byId(definition.id());
            if (behavior == null) continue;
            Key seed = behavior.plantingState().settings().itemId();
            if (seed != null) plants.computeIfAbsent(seed.toString(), ignored -> new ArrayList<>()).add(behavior);
        }
        var explicit = config.getConfigurationSection("villager.harvest.planting");
        if (explicit != null) for (String id : explicit.getKeys(false)) {
            var behavior = ManagedCropBlockBehavior.byId(Key.of(explicit.getString(id, "")));
            if (behavior == null) throw new IllegalArgumentException("villager.harvest.planting." + id + " refers to an unknown managed crop");
            plants.put(id, List.of(behavior));
        }
        Map<String, List<ManagedCropBlockBehavior>> frozen = new LinkedHashMap<>();
        plants.forEach((id, values) -> frozen.put(id, List.copyOf(values)));
        pickup.addAll(plants.keySet()); pickup.addAll(food.keySet());
        return new VillagerContentSnapshot(VillagerWorkSettings.read(config), Map.copyOf(food), Map.copyOf(frozen), Set.copyOf(pickup));
    }
}
