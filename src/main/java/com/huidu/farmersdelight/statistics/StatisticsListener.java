package com.huidu.farmersdelight.statistics;

import com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent;
import com.huidu.farmersdelight.api.event.FarmersDelightProduceEvent;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

public final class StatisticsListener implements Listener {
    private final StatisticsService statistics;
    private final com.huidu.farmersdelight.FarmersDelightPlugin plugin;
    public StatisticsListener(com.huidu.farmersdelight.FarmersDelightPlugin plugin, StatisticsService statistics) {
        this.plugin = plugin; this.statistics = statistics;
    }

    @EventHandler public void joined(PlayerJoinEvent event) {
        statistics.online(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler public void left(org.bukkit.event.player.PlayerQuitEvent event) { statistics.offline(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR) public void produced(FarmersDelightProduceEvent event) {
        count(event.getPlayerId(), event.getSource(), event.getResult());
    }

    @EventHandler(priority = EventPriority.MONITOR) public void cooked(ProfessionCookingExperienceEvent event) {
        // Pots publish ProduceEvent at production; collecting stored food only awards experience.
        if (!"cooking_pot".equals(event.getSource()) && !"cooking-pot".equals(event.getSource())) {
            count(event.getPlayerId(), event.getSource(), event.getResult());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void eaten(PlayerItemConsumeEvent event) {
        String custom = ItemUtils.getCustomItemId(event.getItem());
        if (custom != null) {
            var id = event.getPlayer().getUniqueId();
            plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> {
                if (!event.isCancelled()) statistics.committed(id, "eat", custom, 1L);
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void gained(FarmersDelightBuffChangeEvent event) {
        if (event.isGained()) statistics.committed(event.getPlayerId(), "effect", event.getBuffId(), 1L);
    }

    private void count(java.util.UUID player, String source, ItemStack result) {
        if (result == null || result.getType().isAir() || source == null) return;
        String key = com.huidu.farmersdelight.recipe.RecipeSerializer.itemIdString(result);
        if (key != null) statistics.committed(player, source.replace('-', '_'), key, result.getAmount());
    }
}
