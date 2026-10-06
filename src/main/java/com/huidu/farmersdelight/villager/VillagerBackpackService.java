package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CompatItemMeta;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Owner-thread live edits. A view is only a display: close, retirement and reload never copy it back.
 * Folia edits require simultaneous ownership of both entities; cross-region inspection is read-only.
 */
public final class VillagerBackpackService implements Listener, AutoCloseable {
    public static final String VIEW_PERMISSION = "farmersdelight.villager.inventory";
    public static final String EDIT_PERMISSION = "farmersdelight.villager.inventory.edit";
    private static final int SLOTS = 8;
    private final FarmersDelightPlugin plugin;
    private final Consumer<Villager> wake;
    private final Map<UUID, VillagerBackpackLease> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, VillagerBackpackLease> editors = new ConcurrentHashMap<>();
    private final Set<VillagerBackpackLease> outstandingViews = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> recentInteractions = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final SnapshotGuard guard = new SnapshotGuard();
    private volatile Settings settings;
    private volatile boolean running;
    private volatile boolean guardRegistered;
    private Plugin cleanupOwner;

    public VillagerBackpackService(FarmersDelightPlugin plugin, Consumer<Villager> wake) {
        this.plugin = plugin;
        this.wake = wake == null ? ignored -> { } : wake;
    }

    public synchronized void start() {
        if (running) return;
        settings = Settings.read(plugin.getConfig());
        cleanupOwner = Bukkit.getPluginManager().getPlugin("CraftEngine");
        // CE is a mandatory dependency. Its guard outlives FD's onDisable long enough to close
        // Folia menus on their players' owning threads after FD can no longer schedule tasks.
        if (cleanupOwner == null || !cleanupOwner.isEnabled()) {
            throw new IllegalStateException("CraftEngine must be enabled before villager backpacks start");
        }
        Bukkit.getPluginManager().registerEvents(guard, cleanupOwner);
        guardRegistered = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        running = true;
    }

    public synchronized void reload() {
        settings = Settings.read(plugin.getConfig());
        generation.incrementAndGet();
        for (VillagerBackpackLease lease : Set.copyOf(outstandingViews)) closeOnPlayer(lease, true);
    }

    public boolean isLocked(UUID villagerId) { return editors.containsKey(villagerId); }

    public double maximumDistance() {
        Settings current = settings;
        return current == null ? 6 : current.maximumDistance();
    }

    /** Safe when called from commands or interactions; all player API calls run on the player owner. */
    public void open(Player player, Villager villager) {
        execute(player, () -> requestOnPlayer(player, villager), () -> { });
    }

    private void requestOnPlayer(Player player, Villager villager) {
        Settings current = settings;
        if (!running || current == null || !current.enabled()) { message(player, "disabled"); return; }
        if (!player.hasPermission(VIEW_PERMISSION)) {
            player.sendMessage(I18n.getComponent("general.no_permission", player)); return;
        }
        if (!player.isOnline() || player.isDead()) return;
        if (owned(villager)) {
            if (!available(villager)) { message(player, "closed"); return; }
            showOnPlayer(player, villager, live(villager), villager.getLocation(), false, generation.get());
            return;
        }
        long requestGeneration = generation.get();
        execute(villager, () -> {
            if (!running || generation.get() != requestGeneration || !available(villager)) {
                execute(player, () -> message(player, "closed"), () -> { }); return;
            }
            ItemStack[] snapshot = live(villager);
            Location location = villager.getLocation().clone();
            execute(player, () -> showOnPlayer(player, villager, snapshot, location, true, requestGeneration), () -> { });
        }, () -> execute(player, () -> message(player, "closed"), () -> { }));
    }

    private void showOnPlayer(Player player, Villager villager, ItemStack[] snapshot,
                              Location location, boolean remote, long requestGeneration) {
        Settings current = settings;
        if (!running || generation.get() != requestGeneration || !player.isOnline() || player.isDead()) return;
        if (current == null || !current.enabled()) { message(player, "disabled"); return; }
        if (!player.hasPermission(VIEW_PERMISSION)) {
            player.sendMessage(I18n.getComponent("general.no_permission", player)); return;
        }
        if (!withinDistance(player, location)) { message(player, "too_far"); return; }
        boolean editable = !remote && current.editable() && player.hasPermission(EDIT_PERMISSION) && owned(villager);
        if (editable) {
            // Revalidation and live read happen immediately before acquiring the editor lease.
            if (!available(villager) || !withinDistance(player, villager.getLocation())) { message(player, "closed"); return; }
            snapshot = live(villager);
        }
        VillagerBackpackLease previous = viewers.get(player.getUniqueId());
        if (previous != null) cleanupOnPlayer(previous, false);
        VillagerBackpackLease lease = new VillagerBackpackLease(player, villager, editable, requestGeneration);
        if (editable && editors.putIfAbsent(lease.villagerId, lease) != null) { message(player, "busy"); return; }
        try {
            lease.view = Bukkit.createInventory(lease, 9,
                    I18n.getComponent(editable ? "villager.backpack.title" : "villager.backpack.title_read_only", player));
            lease.shown = VillagerBackpackLease.copy(snapshot);
            for (int slot = 0; slot < SLOTS; slot++) lease.view.setItem(slot, VillagerBackpackLease.copy(snapshot[slot]));
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            var meta = filler.getItemMeta();
            meta.displayName(I18n.getComponent("villager.backpack.filler", player));
            CompatItemMeta.setItemModel(meta, new NamespacedKey("minecraft", "air"));
            meta.setHideTooltip(true);
            filler.setItemMeta(meta);
            lease.view.setItem(SLOTS, filler);
            if (!publish(lease)) { cleanupOnPlayer(lease, false); return; }
            player.openInventory(lease.view);
            if (player.getOpenInventory().getTopInventory().getHolder() != lease) {
                cleanupOnPlayer(lease, false); return;
            }
            ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                    ignored -> validateOnPlayer(lease), () -> retiredPlayer(lease), 1L, 5L);
            lease.heartbeat = task;
            if (task == null || lease.released.get() || !current(lease)) {
                if (task != null) task.cancel();
                cleanupOnPlayer(lease, false); return;
            }
            message(player, editable ? "editing_hint" : "read_only");
        } catch (RuntimeException failure) {
            cleanupOnPlayer(lease, false);
            message(player, "closed");
            plugin.getLogger().log(Level.WARNING, "Could not open villager backpack " + lease.villagerId, failure);
        }
    }

    private synchronized boolean publish(VillagerBackpackLease lease) {
        // Linearize publication with global reload/close. An in-flight opening must not publish a
        // snapshot after close has observed an empty set and retired the persistent guard.
        if (!running || settings == null || !settings.enabled() || lease.generation != generation.get()) return false;
        viewers.put(lease.playerId, lease);
        outstandingViews.add(lease);
        return true;
    }

    private void validateOnPlayer(VillagerBackpackLease lease) {
        if (!current(lease) || !lease.player.isOnline() || lease.player.isDead()
                || !lease.player.hasPermission(VIEW_PERMISSION)
                || lease.player.getOpenInventory().getTopInventory().getHolder() != lease) {
            cleanupOnPlayer(lease, true); return;
        }
        if (lease.editable) {
            if (!editableNow(lease)) { cleanupOnPlayer(lease, true); return; }
            refresh(lease, live(lease.villager));
            return;
        }
        if (!lease.validationPending.compareAndSet(false, true)) return;
        execute(lease.villager, () -> {
            if (!available(lease.villager)) {
                lease.validationPending.set(false); closeOnPlayer(lease, true); return;
            }
            Location location = lease.villager.getLocation().clone();
            ItemStack[] snapshot = live(lease.villager);
            execute(lease.player, () -> {
                lease.validationPending.set(false);
                if (!current(lease) || !withinDistance(lease.player, location)) cleanupOnPlayer(lease, true);
                else refresh(lease, snapshot);
            }, () -> retiredPlayer(lease));
        }, () -> { lease.validationPending.set(false); closeOnPlayer(lease, true); });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void clicked(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof VillagerBackpackLease lease)) return;
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled || event.getWhoClicked() != lease.player || !current(lease)) return;
        if (!lease.player.hasPermission(VIEW_PERMISSION)) { closeAfterEvent(lease); return; }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot == SLOTS || rawSlot >= event.getView().countSlots()) return;
        boolean top = rawSlot < SLOTS;
        if (top && !lease.editable) return;
        if (lease.editable && !editableNow(lease)) { closeAfterEvent(lease); return; }
        ItemStack[] real = lease.editable ? live(lease.villager) : null;
        if (lease.editable && !VillagerBackpackLease.same(real, lease.shown)) {
            refresh(lease, real); message(lease.player, "changed"); return;
        }
        ItemStack[] storage = VillagerBackpackLease.copy(lease.player.getInventory().getStorageContents());
        int slot = top ? rawSlot : event.getView().convertSlot(rawSlot);
        if (!top && (slot < 0 || slot >= storage.length)) return;
        ItemStack[] oldReal = real == null ? null : VillagerBackpackLease.copy(real);
        ItemStack[] oldStorage = VillagerBackpackLease.copy(storage);
        ItemStack oldCursor = VillagerBackpackLease.copy(lease.player.getItemOnCursor());
        ItemStack cursor = VillagerBackpackLease.copy(oldCursor);
        ItemStack oldOffhand = VillagerBackpackLease.copy(lease.player.getInventory().getItemInOffHand());
        ItemStack offhand = VillagerBackpackLease.copy(oldOffhand);
        ClickType click = event.getClick();
        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            var change = VillagerBackpackLease.click(top ? real[slot] : storage[slot], cursor,
                    click == ClickType.RIGHT, 64);
            if (top) real[slot] = change.slot(); else storage[slot] = change.slot();
            cursor = change.cursor();
        } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            if (!lease.editable) return;
            var transfer = VillagerBackpackLease.transfer(top ? real[slot] : storage[slot], top ? storage : real, 64);
            if (top) { real[slot] = transfer.source(); storage = transfer.destination(); }
            else { storage[slot] = transfer.source(); real = transfer.destination(); }
        } else if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            if (!VillagerBackpackLease.empty(cursor)) return;
            int hotbar = event.getHotbarButton();
            if (click == ClickType.NUMBER_KEY && (hotbar < 0 || hotbar > 8)) return;
            ItemStack replacement = click == ClickType.SWAP_OFFHAND ? offhand : storage[hotbar];
            if (!VillagerBackpackLease.fits(replacement, 64)) return;
            ItemStack previous = top ? real[slot] : storage[slot];
            if (click == ClickType.SWAP_OFFHAND) offhand = previous;
            else storage[hotbar] = previous;
            if (top) real[slot] = replacement; else storage[slot] = replacement;
        } else {
            // Drag, double-click collect, drop, creative-clone and unknown clicks have no transaction.
            return;
        }
        commit(lease, oldReal, real, oldStorage, storage, oldCursor, cursor, oldOffhand, offhand);
    }

    private void commit(VillagerBackpackLease lease, ItemStack[] oldReal, ItemStack[] real,
                        ItemStack[] oldStorage, ItemStack[] storage, ItemStack oldCursor,
                        ItemStack cursor, ItemStack oldOffhand, ItemStack offhand) {
        // No task is deferred and no event is called between these writes. Ownership cannot change
        // halfway through one owner-thread callback. The live slots are updated before the display.
        try {
            if (real != null) writeChanges(lease.villager.getInventory(), oldReal, real);
            writeChanges(lease.player.getInventory(), oldStorage, storage);
            if (!VillagerBackpackLease.same(oldOffhand, offhand)) lease.player.getInventory().setItemInOffHand(offhand);
            if (!VillagerBackpackLease.same(oldCursor, cursor)) lease.player.setItemOnCursor(cursor);
            if (real != null) refresh(lease, real);
        } catch (RuntimeException failure) {
            // Both inventories still have the same owner here. Restore every potentially touched slot.
            if (oldReal != null) restore(lease.villager.getInventory(), oldReal, failure);
            restore(lease.player.getInventory(), oldStorage, failure);
            try {
                lease.player.getInventory().setItemInOffHand(oldOffhand);
                lease.player.setItemOnCursor(oldCursor);
            } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            closeAfterEvent(lease);
            plugin.getLogger().log(Level.SEVERE, "Villager backpack transaction failed for " + lease.villagerId, failure);
        }
    }

    private static void writeChanges(Inventory inventory, ItemStack[] before, ItemStack[] after) {
        for (int slot = 0; slot < after.length; slot++)
            if (!VillagerBackpackLease.same(before[slot], after[slot])) inventory.setItem(slot, VillagerBackpackLease.copy(after[slot]));
    }

    private static void restore(Inventory inventory, ItemStack[] before, RuntimeException failure) {
        try {
            for (int slot = 0; slot < before.length; slot++) inventory.setItem(slot, VillagerBackpackLease.copy(before[slot]));
        } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
    }

    private void refresh(VillagerBackpackLease lease, ItemStack[] snapshot) {
        if (lease.released.get()) return;
        for (int slot = 0; slot < SLOTS; slot++)
            if (!VillagerBackpackLease.same(lease.shown[slot], snapshot[slot]))
                lease.view.setItem(slot, VillagerBackpackLease.copy(snapshot[slot]));
        lease.shown = VillagerBackpackLease.copy(snapshot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void dragged(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof VillagerBackpackLease) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void closed(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof VillagerBackpackLease lease) {
            release(lease);
            lease.view.clear();
            outstandingViews.remove(lease);
            retireGuardIfFinished();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interacted(PlayerInteractEntityEvent event) { interact(event); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactedAt(PlayerInteractAtEntityEvent event) { interact(event); }

    private void interact(PlayerInteractEntityEvent event) {
        Settings current = settings;
        if (!running || current == null || !current.enabled() || !current.openOnSneak()
                || !(event.getRightClicked() instanceof Villager villager) || !event.getPlayer().isSneaking()) return;
        Player player = event.getPlayer();
        Integer previous = recentInteractions.get(player.getUniqueId());
        int tick = player.getTicksLived();
        if (event.getHand() != EquipmentSlot.HAND) {
            if (previous != null && previous == tick) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        recentInteractions.put(player.getUniqueId(), tick);
        if (previous == null || previous != tick) open(player, villager);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickedUp(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Villager villager && isLocked(villager.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void entityDied(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager villager) invalidate(villager.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void entitiesUnloaded(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof Villager) invalidate(entity.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) {
        VillagerBackpackLease lease = viewers.get(event.getPlayer().getUniqueId());
        if (lease != null) cleanupOnPlayer(lease, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void playerDied(PlayerDeathEvent event) {
        VillagerBackpackLease lease = viewers.get(event.getEntity().getUniqueId());
        if (lease != null) cleanupOnPlayer(lease, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        recentInteractions.remove(id);
        VillagerBackpackLease lease = viewers.get(id);
        if (lease != null) cleanupOnPlayer(lease, false);
    }

    private void invalidate(UUID villagerId) {
        for (VillagerBackpackLease lease : viewers.values())
            if (lease.villagerId.equals(villagerId)) closeOnPlayer(lease, true);
    }

    private boolean current(VillagerBackpackLease lease) {
        Settings current = settings;
        return running && current != null && current.enabled() && !lease.released.get()
                && lease.generation == generation.get() && viewers.get(lease.playerId) == lease;
    }

    private boolean editableNow(VillagerBackpackLease lease) {
        return owned(lease.player) && owned(lease.villager) && current(lease) && settings.editable()
                && editors.get(lease.villagerId) == lease && lease.player.hasPermission(EDIT_PERMISSION)
                && available(lease.villager) && withinDistance(lease.player, lease.villager.getLocation());
    }

    private boolean withinDistance(Player player, Location location) {
        Location origin = player.getLocation();
        return origin.getWorld() == location.getWorld()
                && origin.distanceSquared(location) <= maximumDistance() * maximumDistance();
    }

    private static boolean available(Villager villager) { return villager.isValid() && !villager.isDead(); }

    private static ItemStack[] live(Villager villager) {
        ItemStack[] contents = villager.getInventory().getStorageContents();
        if (contents.length != SLOTS) throw new IllegalStateException("Unexpected villager inventory size: " + contents.length);
        return VillagerBackpackLease.copy(contents);
    }

    private boolean owned(Entity entity) {
        return !plugin.scheduler().isFolia() ? Bukkit.isPrimaryThread() : Bukkit.isOwnedByCurrentRegion(entity);
    }

    private void execute(Entity entity, Runnable task, Runnable retired) {
        if (owned(entity)) { task.run(); return; }
        try { plugin.scheduler().runForEntity(entity, task, retired); }
        catch (RuntimeException rejected) { retired.run(); }
    }

    private void closeAfterEvent(VillagerBackpackLease lease) {
        release(lease);
        // Closing a menu from an InventoryClickEvent is prohibited by Bukkit's event contract.
        Plugin owner = plugin.isEnabled() ? plugin : cleanupOwner;
        if (owner == null || !owner.isEnabled()) return;
        try {
            ScheduledTask task = lease.player.getScheduler().run(owner,
                    ignored -> cleanupOnPlayer(lease, true), () -> retiredPlayer(lease));
            if (task == null) retiredPlayer(lease);
        } catch (RuntimeException rejected) {
            // The active service/CE guard continues cancelling native clicks until another lifecycle
            // callback can clean up. Never close the inventory inside the current click event.
            plugin.getLogger().log(Level.WARNING, "Could not defer villager backpack cleanup for " + lease.playerId, rejected);
        }
    }

    private void closeOnPlayer(VillagerBackpackLease lease, boolean notify) {
        release(lease);
        if (owned(lease.player)) { cleanupOnPlayer(lease, notify); return; }
        Plugin owner = plugin.isEnabled() ? plugin : cleanupOwner;
        if (owner == null || !owner.isEnabled()) return; // Retain the guard and displayed holder.
        try {
            boolean accepted = lease.player.getScheduler().execute(owner,
                    () -> cleanupOnPlayer(lease, notify), () -> retiredPlayer(lease), 1L);
            if (!accepted) retiredPlayer(lease);
        } catch (RuntimeException rejected) {
            // Retain CE's guard: an unclosed snapshot must never fall through to native clicks.
            plugin.getLogger().log(Level.WARNING, "Could not schedule villager backpack cleanup for " + lease.playerId, rejected);
        }
    }

    private void cleanupOnPlayer(VillagerBackpackLease lease, boolean notify) {
        release(lease);
        if (lease.player.getOpenInventory().getTopInventory().getHolder() == lease) {
            lease.player.closeInventory();
            if (notify && lease.player.isOnline() && running) message(lease.player, "closed");
        }
        if (lease.view != null) lease.view.clear();
        outstandingViews.remove(lease);
        retireGuardIfFinished();
    }

    private void retiredPlayer(VillagerBackpackLease lease) {
        release(lease);
        // A retired player no longer exposes this transient menu to a client. No inventory access here.
        outstandingViews.remove(lease);
        retireGuardIfFinished();
    }

    private void release(VillagerBackpackLease lease) {
        if (!lease.released.compareAndSet(false, true)) return;
        viewers.remove(lease.playerId, lease);
        boolean edited = editors.remove(lease.villagerId, lease);
        ScheduledTask task = lease.heartbeat;
        if (task != null) task.cancel();
        if (edited && running && plugin.isEnabled()) execute(lease.villager, () -> {
            if (available(lease.villager)) wake.accept(lease.villager);
        }, () -> { });
    }

    private synchronized void retireGuardIfFinished() {
        if (!running && outstandingViews.isEmpty() && guardRegistered) {
            HandlerList.unregisterAll(guard);
            guardRegistered = false;
        }
    }

    @Override public synchronized void close() {
        running = false;
        generation.incrementAndGet();
        // Disable the service before removing its events. The CE-owned guard now blocks every menu.
        HandlerList.unregisterAll(this);
        for (VillagerBackpackLease lease : Set.copyOf(outstandingViews)) closeOnPlayer(lease, false);
        recentInteractions.clear();
        retireGuardIfFinished();
    }

    private static void message(Player player, String suffix) {
        player.sendMessage(I18n.getComponent("villager.backpack." + suffix, player));
    }

    record Settings(boolean enabled, boolean editable, boolean openOnSneak, double maximumDistance) {
        static Settings read(FileConfiguration config) {
            double distance = config.getDouble("villager.backpack.maximum-distance-blocks", 6);
            if (!Double.isFinite(distance)) distance = 6;
            return new Settings(config.getBoolean("villager.enable", true)
                    && config.getBoolean("villager.backpack.enabled", true),
                    config.getBoolean("villager.backpack.editable", true),
                    config.getBoolean("villager.backpack.open-on-sneak", true), Math.max(1, Math.min(32, distance)));
        }
    }

    /** Remains registered under CE only while inactive FD still has displayed snapshot inventories. */
    private final class SnapshotGuard implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void click(InventoryClickEvent event) {
            if ((!running || !plugin.isEnabled())
                    && event.getView().getTopInventory().getHolder() instanceof VillagerBackpackLease)
                event.setCancelled(true);
        }
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void drag(InventoryDragEvent event) {
            if ((!running || !plugin.isEnabled())
                    && event.getView().getTopInventory().getHolder() instanceof VillagerBackpackLease)
                event.setCancelled(true);
        }
    }
}
