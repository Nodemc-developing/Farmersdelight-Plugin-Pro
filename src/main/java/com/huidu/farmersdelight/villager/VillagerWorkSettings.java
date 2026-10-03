package com.huidu.farmersdelight.villager;

import org.bukkit.configuration.ConfigurationSection;

record VillagerWorkSettings(boolean harvest, boolean pickup, boolean breed, boolean share,
                            int activeTicks, int idleTicks, int blockBudget, double pickupRadius) {
    static VillagerWorkSettings read(ConfigurationSection config) {
        boolean enabled = config.getBoolean("villager.enable", true);
        int pickup = config.getInt("villager.pickup.scan_interval_ticks", 20);
        int breed = config.getInt("villager.breed.scan_interval_ticks", 40);
        return new VillagerWorkSettings(enabled && config.getBoolean("villager.harvest.enable", true),
                enabled && config.getBoolean("villager.pickup.enable", true),
                enabled && config.getBoolean("villager.breed.enable", true),
                enabled && config.getBoolean("villager.breed.share_food", true),
                Math.max(5, Math.min(200, Math.min(pickup, breed))),
                Math.max(40, Math.min(1200, config.getInt("villager.idle_interval_ticks", 200))),
                Math.max(1, Math.min(128, config.getInt("villager.harvest.block_budget", 64))),
                Math.max(.5, Math.min(4, config.getDouble("villager.pickup.radius", 2.5))));
    }
    boolean anyWork() { return harvest || pickup || breed; }
}
