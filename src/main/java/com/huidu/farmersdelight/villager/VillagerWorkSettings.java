package com.huidu.farmersdelight.villager;

import org.bukkit.configuration.ConfigurationSection;

record VillagerWorkSettings(boolean harvest, boolean pickup, boolean breed, boolean share,
                            int activeTicks, int idleTicks, int blockBudget, double pickupRadius,
                            boolean planting, boolean bonemeal, boolean compost, boolean workHoursOnly,
                            int bonemealInterval, int shareInterval, int minimumFoodToShare,
                            boolean pauseWithExternal, int pickupInterval, int breedInterval) {
    static VillagerWorkSettings read(ConfigurationSection config) {
        boolean enabled = config.getBoolean("villager.enable", true);
        int pickup = scanInterval(config, "villager.pickup.scan-interval-ticks", 20);
        int breed = scanInterval(config, "villager.breed.scan-interval-ticks", 40);
        return new VillagerWorkSettings(enabled && config.getBoolean("villager.harvest.enable", true),
                enabled && config.getBoolean("villager.pickup.enable", true),
                enabled && config.getBoolean("villager.breed.enable", true),
                enabled && config.getBoolean("villager.breed.share-food", true),
                Math.max(5, Math.min(200, Math.min(pickup, breed))),
                Math.max(40, Math.min(1200, config.getInt("villager.idle-interval-ticks", 200))),
                Math.max(1, Math.min(128, config.getInt("villager.harvest.block-budget", 64))),
                Math.max(.5, Math.min(4, config.getDouble("villager.pickup.radius", 2.5))),
                enabled && config.getBoolean("villager.harvest.replant", true),
                enabled && config.getBoolean("villager.bonemeal.enabled", true),
                enabled && config.getBoolean("villager.compost.enabled", true),
                config.getBoolean("villager.harvest.work-hours-only", true),
                Math.max(20, Math.min(1200, config.getInt("villager.bonemeal.retry-delay-ticks", 40))),
                Math.max(20, Math.min(1200, config.getInt("villager.breed.share-interval-ticks", 100))),
                Math.max(12, Math.min(4096, config.getInt("villager.breed.minimum-food-to-share", 24))),
                config.getBoolean("villager.coexistence.pause-with-external-plugin", true), pickup, breed);
    }
    private static int scanInterval(ConfigurationSection config, String path, int fallback) {
        return Math.max(5, Math.min(1200, config.getInt(path, fallback)));
    }
    boolean anyWork() { return harvest || pickup || breed || share || bonemeal || compost; }
}
