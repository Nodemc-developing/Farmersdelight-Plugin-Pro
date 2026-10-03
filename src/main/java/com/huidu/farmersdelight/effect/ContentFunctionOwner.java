package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Keeps provider evaluation and its resulting mutation in the same current entity-owner callback. */
final class ContentFunctionOwner {
    interface Access {
        boolean available();
        long generation();
        boolean owned(Player player);
        void schedule(Player player, Runnable action);
    }
    private final Access access;
    ContentFunctionOwner(Access access) { this.access = access; }
    static ContentFunctionOwner forPlugin(FarmersDelightPlugin plugin) {
        return new ContentFunctionOwner(new Access() {
            public boolean available() { return plugin != null && plugin.isEnabled(); }
            public long generation() { return plugin.configurationGeneration(); }
            public boolean owned(Player player) { return plugin.scheduler().isFolia() ? Bukkit.isOwnedByCurrentRegion(player) : Bukkit.isPrimaryThread(); }
            public void schedule(Player player, Runnable action) { plugin.scheduler().runForEntity(player, action); }
        });
    }
    void run(Player player, Runnable action) {
        if (!access.available()) return;
        long generation = access.generation();
        if (access.owned(player)) {
            if (player.isOnline() && !player.isDead()) action.run();
            return;
        }
        access.schedule(player, () -> {
            if (access.available() && access.generation() == generation && access.owned(player)
                    && player.isOnline() && !player.isDead()) action.run();
        });
    }
}
