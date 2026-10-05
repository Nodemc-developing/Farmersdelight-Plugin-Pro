package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.event.ProfessionCookingExperienceEvent;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.PlayerInventoryPackets;
import com.huidu.farmersdelight.util.compat.CraftEngineItemComponents;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.papermc.paper.event.player.PlayerStopUsingItemEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A serving is debited only after its result can be committed; cancellation has no escrow to recover. */
@SuppressWarnings("UnstableApiUsage")
public final class SkewerCookingService implements Listener {
    private final FarmersDelightPlugin plugin;
    private final Plugin cleanupHost;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile PluginTask ticker;
    private volatile boolean enabled;
    public SkewerCookingService(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        cleanupHost = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        reload();
    }
    public void reload() {
        if (closed.get()) return;
        enabled = plugin.getConfigBoolean(true, "skewer.handheld.enabled", "skewer-handheld.enabled");
        if (!enabled) for (Session session : sessions.values()) plugin.scheduler().runForEntity(session.player, () -> cancel(session.player));
    }
    public boolean use(Player player, EquipmentSlot hand, HandheldSkewerSpec spec) {
        if (closed.get() || !enabled || player.isDead() || player.isSneaking() || !player.isOnline()) return false;
        Session existing = sessions.get(player.getUniqueId());
        if (existing != null) {
            if (existing.hand == hand && existing.spec.equals(spec) && existing.matches(player.getInventory())) return true;
            cancel(player);
        }
        if (player.hasActiveItem() && player.getActiveItemHand() != hand || !hasHeat(player)) return false;
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        ItemStack source = player.getInventory().getItem(slot);
        if (source == null || source.getAmount() < 1 || !ItemUtils.matchesItemId(source, spec.source().toString())) return false;
        ItemStack proxy = ItemUtils.createItem(spec.cookingProxy());
        ItemStack result = ItemUtils.createItem(spec.result());
        if (proxy == null || result == null || result.getType().isAir()) return false;
        proxy.setAmount(source.getAmount());
        CraftEngineItemComponents.setAlwaysEat(proxy, true);
        SkewerUseLease use = SkewerUseLease.prepare(player, source, spec.cookTicks());
        CraftEngineItemComponents.setUseDuration(proxy, Math.nextUp(use.duration() / 20f));
        Session session = new Session(player, hand, slot, player.getInventory().getHeldItemSlot(), source.clone(), proxy, result.clone(), spec, use);
        if (player.hasActiveItem()) player.clearActiveItem();
        sessions.put(player.getUniqueId(), session);
        if (closed.get()) { cancel(player); return false; }
        try {
            player.getInventory().setItem(slot, use.prepared());
            player.startUsingItem(hand);
            if (!player.hasActiveItem() || player.getActiveItemHand() != hand) { cancel(player); return false; }
            player.setActiveItemRemainingTime(use.duration());
            var channel = BukkitNetworkManager.instance().getChannel(player);
            if (channel != null && channel.isOpen()) {
                session.display = new HandheldCookingDisplay(channel, slot, BukkitAdaptor.adapt(use.prepared().clone()).minecraftItem());
                updateDisplay(session);
            }
            ensureTicker();
            if (closed.get()) { cancel(player); return false; }
        } catch (RuntimeException | LinkageError failure) { cancel(player); throw failure; }
        return true;
    }
    private synchronized void ensureTicker() {
        if (closed.get()) return;
        if (ticker == null) ticker = plugin.scheduler().runRepeating(this::dispatch, 1, 1);
    }
    private void dispatch() {
        for (Session session : sessions.values()) {
            if (!plugin.scheduler().isFolia()) { advance(session); continue; }
            if (!session.scheduled.compareAndSet(false, true)) continue;
            try {
                plugin.scheduler().runForEntity(session.player, () -> {
                    try { if (sessions.get(session.player.getUniqueId()) == session) advance(session); }
                    finally { session.scheduled.set(false); }
                }, () -> retire(session));
            } catch (RuntimeException failure) { session.scheduled.set(false); retire(session); }
        }
        stopIdleTicker();
    }
    private void advance(Session session) {
        Player player = session.player;
        if (!enabled || !player.isOnline() || player.isDead() || !session.matches(player.getInventory())
                || !player.hasActiveItem() || player.getActiveItemHand() != session.hand) { cancel(player); return; }
        if (session.progress % 20 == 0 && !hasHeat(player)) { cancel(player); return; }
        if (++session.progress < session.spec.cookTicks()) {
            if (session.progress % 4 == 0 && session.display != null) updateDisplay(session);
            return;
        }
        finish(session);
    }
    private void finish(Session session) {
        Player player = session.player;
        if (!sessions.remove(player.getUniqueId(), session)) return;
        session.closeDisplay();
        player.clearActiveItem();
        ItemStack[] before = player.getInventory().getContents();
        for (int i = 0; i < before.length; ++i) if (before[i] != null) before[i] = before[i].clone();
        var drops = new ArrayList<Item>();
        try {
            if (!session.consume(player.getInventory(), player.getGameMode() == GameMode.CREATIVE)) return;
            for (ItemStack leftover : player.getInventory().addItem(session.result.clone()).values())
                drops.add(player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        } catch (RuntimeException failure) {
            drops.forEach(Item::remove);
            player.getInventory().setContents(before);
            throw failure;
        } finally { session.cleanup.accept(null); stopIdleTicker(); }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, .4f, 1.2f);
        Bukkit.getPluginManager().callEvent(new ProfessionCookingExperienceEvent(player.getUniqueId(), player.getName(),
                "skewer", session.result.clone(), 0, player.getLocation()));
    }
    public void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        session.closeDisplay();
        session.cleanup.accept(null);
        stopIdleTicker();
    }
    private void retire(Session session) { sessions.remove(session.player.getUniqueId(), session); session.closeDisplay(); stopIdleTicker(); }
    private synchronized void stopIdleTicker() {
        if (sessions.isEmpty() && ticker != null) { ticker.cancel(); ticker = null; }
    }
    private void updateDisplay(Session session) {
        ItemStack visual = SkilletHandheldCooking.createHandheldDisplay(session.proxy, null, session.progress, session.spec.cookTicks(), true);
        Object nativeItem = BukkitAdaptor.adapt(visual).minecraftItem();
        session.display.update(nativeItem, PlayerInventoryPackets.slot(session.slot, nativeItem));
    }
    private static void restoreSlot(Player player, int slot) {
        ItemStack present = player.getInventory().getItem(slot);
        var user = BukkitAdaptor.adapt(player);
        if (user != null) user.sendPacket(PlayerInventoryPackets.slot(slot,
                BukkitAdaptor.adapt(present == null ? new ItemStack(Material.AIR) : present.clone()).minecraftItem()), false);
    }
    private boolean hasHeat(Player player) {
        if (player.getFireTicks() > 0) return true;
        Location center = player.getLocation();
        var world = center.getWorld();
        for (int dx = -1; dx <= 1; ++dx) for (int dy = -1; dy <= 1; ++dy) for (int dz = -1; dz <= 1; ++dz) {
            int x = center.getBlockX() + dx, y = center.getBlockY() + dy, z = center.getBlockZ() + dz;
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location cell = new Location(world, x, y, z);
            if (!plugin.scheduler().isOwnedByCurrentRegion(cell)) continue;
            var block = world.getBlockAt(x, y, z);
            if (plugin.getHeatSourceConfig().isHeatSource(block, CraftEngineBlocks.getCustomBlockState(block))) return true;
        }
        return false;
    }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        enabled = false;
        org.bukkit.event.HandlerList.unregisterAll(this);
        Session[] closing = sessions.values().toArray(Session[]::new);
        sessions.clear();
        stopIdleTicker();
        for (Session session : closing) {
            session.closeDisplay();
            if (!plugin.scheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(session.player)) session.cleanup.accept(null);
            else if (cleanupHost != null && cleanupHost.isEnabled()) {
                try { session.player.getScheduler().run(cleanupHost, session.cleanup, session.cleanup); }
                catch (RuntimeException retired) { session.cleanup.run(); }
            } else session.cleanup.run();
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onStop(PlayerStopUsingItemEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHeld(PlayerItemHeldEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setMainHandItem(session.restoreUse(event.getMainHandItem()));
            event.setOffHandItem(session.restoreUse(event.getOffHandItem()));
        }
        cancel(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            Item item = event.getItemDrop();
            if (Bukkit.isOwnedByCurrentRegion(item)) item.setItemStack(session.restoreUse(item.getItemStack()));
            else plugin.scheduler().runForEntity(item, () -> {
                if (item.isValid()) item.setItemStack(session.restoreUse(item.getItemStack()));
            });
        }
        cancel(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) cancel(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) cancel(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onConsume(PlayerItemConsumeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        if (event.getHand() == session.hand && (session.use == null
                ? event.getItem().isSimilar(session.original) : session.use.owns(event.getItem()))) event.setCancelled(true);
        cancel(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer()); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Session session = sessions.get(event.getEntity().getUniqueId());
        if (session != null) {
            event.getDrops().replaceAll(session::restoreUse);
            event.getItemsToKeep().replaceAll(session::restoreUse);
        }
        cancel(event.getEntity());
    }
    static final class Session {
        final Player player;
        final EquipmentSlot hand;
        final int slot, selected;
        final ItemStack original, proxy, result;
        final HandheldSkewerSpec spec;
        final SkewerUseLease use;
        final OwnerCleanup cleanup;
        final AtomicBoolean scheduled = new AtomicBoolean();
        HandheldCookingDisplay display;
        int progress;
        boolean committed;
        Session(Player player, EquipmentSlot hand, int slot, int selected, ItemStack original, ItemStack proxy, ItemStack result, HandheldSkewerSpec spec) {
            this(player, hand, slot, selected, original, proxy, result, spec, null);
        }
        Session(Player player, EquipmentSlot hand, int slot, int selected, ItemStack original, ItemStack proxy, ItemStack result, HandheldSkewerSpec spec, SkewerUseLease use) {
            this.player = player; this.hand = hand; this.slot = slot; this.selected = selected;
            this.original = original; this.proxy = proxy; this.result = result; this.spec = spec;
            this.use = use;
            this.cleanup = new OwnerCleanup(player, hand, slot, original, use, use == null ? spec.cookTicks() + 20 : use.duration());
        }
        boolean matches(PlayerInventory inventory) {
            ItemStack present = inventory.getItem(slot);
            return inventory.getHeldItemSlot() == selected && present != null
                    && (use == null ? present.getAmount() == original.getAmount() && present.isSimilar(original) : use.matches(present));
        }
        boolean consume(PlayerInventory inventory, boolean creative) {
            if (committed || !matches(inventory)) return false;
            committed = true;
            if (creative && use == null) return true;
            ItemStack present = restoreUse(inventory.getItem(slot)).clone();
            if (!creative) {
                present.setAmount(present.getAmount() - 1);
            }
            inventory.setItem(slot, present.getAmount() == 0 ? null : present);
            return true;
        }
        ItemStack restoreUse(ItemStack current) { return use == null ? current : use.restore(current); }
        void closeDisplay() { if (display != null) display.close(); }
    }
    private static final class OwnerCleanup implements Consumer<ScheduledTask>, Runnable {
        private final Player player;
        private final EquipmentSlot hand;
        private final int slot, maximumRemaining;
        private final ItemStack original;
        private final SkewerUseLease use;
        private final AtomicBoolean completed = new AtomicBoolean();
        OwnerCleanup(Player player, EquipmentSlot hand, int slot, ItemStack original, SkewerUseLease use, int maximumRemaining) {
            this.player = player; this.hand = hand; this.slot = slot; this.original = original; this.use = use; this.maximumRemaining = maximumRemaining;
        }
        @Override public void accept(ScheduledTask ignored) {
            if (!completed.compareAndSet(false, true) || !player.isOnline()) return;
            ItemStack activeItem = player.getActiveItem();
            if (player.hasActiveItem() && player.getActiveItemHand() == hand
                    && activeItem != null && (use == null ? activeItem.isSimilar(original) : use.owns(activeItem))
                    && player.getActiveItemRemainingTime() <= maximumRemaining) player.clearActiveItem();
            if (use != null) {
                PlayerInventory inventory = player.getInventory();
                for (int index = 0; index < inventory.getSize(); index++) {
                    ItemStack current = inventory.getItem(index);
                    ItemStack restored = use.restore(current);
                    if (restored != current) inventory.setItem(index, restored);
                }
                ItemStack cursor = player.getItemOnCursor();
                ItemStack restored = use.restore(cursor);
                if (restored != cursor) player.setItemOnCursor(restored);
            }
            restoreSlot(player, slot);
        }
        @Override public void run() { completed.set(true); }
    }
}
