package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockAttemptPlaceEvent;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockInteractEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Delayed soaking rechecks the world, player, recipe and input before opening a fresh transaction. */
public final class FluidRecipeListener implements Listener, AutoCloseable {
    private record Pending(FluidRecipeSpec recipe, int slot, Location location, ItemStack input, long generation) { }
    private final FarmersDelightPlugin plugin;
    private final FluidRecipeManager manager;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public FluidRecipeListener(FarmersDelightPlugin plugin, FluidRecipeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttemptPlace(CustomBlockAttemptPlaceEvent event) {
        if (!manager.bridge().available()) return;
        Player player = event.getPlayer();
        ItemStack held = event.hand() == InteractionHand.MAIN_HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        if (FluidCoreBridge.hasForeignFluidData(held)) {
            event.setCancelled(true);
            report(player, FluidCoreBridge.Outcome.PROTECTED_DATA);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getHand() == null || !manager.bridge().available()) return;
        Player player = event.getPlayer();
        Location location = event.getClickedBlock().getLocation();
        if (!canUse(player, location)) return;
        // CE fires its cancellable block event before either block or item behavior processes the click.
        if (CraftEngineBlocks.isCustomBlock(event.getClickedBlock())) return;
        handleInteraction(player, location, event.getHand() == EquipmentSlot.OFF_HAND, () -> claim(event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCustomInteract(CustomBlockInteractEvent event) {
        if (event.action() != CustomBlockInteractEvent.Action.RIGHT_CLICK || !manager.bridge().available()) return;
        Player player = event.player();
        Location location = event.location();
        if (!canUse(player, location)) return;
        handleInteraction(player, location, event.hand() == InteractionHand.OFF_HAND, () -> event.setCancelled(true));
    }

    private void handleInteraction(Player player, Location location, boolean offHand, Runnable claim) {
        int slot = offHand ? 40 : player.getInventory().getHeldItemSlot();
        ItemStack input = player.getInventory().getItem(slot);
        List<FluidRecipeSpec> candidates = manager.candidates(input);
        if (candidates.isEmpty() || !manager.bridge().storageAt(location)) return;
        Pending active = pending.get(player.getUniqueId());
        if (active != null) {
            claim.run();
            return;
        }
        FluidCoreBridge.Outcome failure = null;
        for (FluidRecipeSpec recipe : candidates) {
            FluidCoreBridge.Outcome probe = manager.bridge().process(player, slot, location, recipe, true);
            if (!probe.ready()) {
                if (probe != FluidCoreBridge.Outcome.NO_FLUID && probe != FluidCoreBridge.Outcome.NO_MATCH) failure = probe;
                continue;
            }
            claim.run();
            if (recipe.timeTicks() > 0) {
                Pending operation = new Pending(recipe, slot, location.clone(), input.clone(), manager.generation());
                if (pending.putIfAbsent(player.getUniqueId(), operation) != null) return;
                player.sendMessage(I18n.getComponent("fluid.soaking_started", player,
                        Map.of("ticks", Long.toString(recipe.timeTicks()))));
                try {
                    plugin.scheduler().runLaterForEntity(player, () -> complete(player, operation), recipe.timeTicks());
                } catch (RuntimeException rejected) {
                    pending.remove(player.getUniqueId(), operation);
                    player.sendMessage(I18n.getComponent("fluid.cancelled", player));
                }
            } else {
                report(player, manager.bridge().process(player, slot, location, recipe));
            }
            return;
        }
        if (failure != null) {
            claim.run();
            report(player, failure);
        }
    }

    private static void claim(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private boolean canUse(Player player, Location location) {
        return plugin.isEnabled() && player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR && player.hasPermission("farmersdelight.use.fluids")
                && plugin.scheduler().isOwnedByCurrentRegion(location)
                && player.getWorld().equals(location.getWorld())
                && player.getLocation().distanceSquared(location.clone().add(0.5, 0.5, 0.5)) <= 25
                && ProtectionCompat.canUse(player, location, "farmersdelight-fluids")
                && BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag.OPEN_CONTAINER, location);
    }

    private void complete(Player player, Pending operation) {
        if (!pending.remove(player.getUniqueId(), operation)) return;
        ItemStack input = player.getInventory().getItem(operation.slot());
        if (manager.generation() != operation.generation() || !canUse(player, operation.location())
                || !operation.input().equals(input)
                || (operation.slot() != 40 && player.getInventory().getHeldItemSlot() != operation.slot())) {
            if (player.isOnline()) player.sendMessage(I18n.getComponent("fluid.cancelled", player));
            return;
        }
        report(player, manager.bridge().process(player, operation.slot(), operation.location(), operation.recipe()));
    }

    private static void report(Player player, FluidCoreBridge.Outcome outcome) {
        player.sendMessage(I18n.getComponent("fluid.outcome." + outcome.name().toLowerCase(java.util.Locale.ROOT), player));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { pending.remove(event.getEntity().getUniqueId()); }
    public void cancelPending() {
        for (var entry : pending.entrySet()) {
            if (!pending.remove(entry.getKey(), entry.getValue())) continue;
            Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) plugin.scheduler().runForEntity(player, () -> {
                if (player.isOnline()) player.sendMessage(I18n.getComponent("fluid.cancelled", player));
            });
        }
    }
    @Override public void close() { pending.clear(); }
}
