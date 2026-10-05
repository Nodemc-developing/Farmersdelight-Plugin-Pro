package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.compat.CraftEngineItemComponents;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Grants genuine always-eat food components while keeping their original override state recoverable. */
@SuppressWarnings("UnstableApiUsage")
public final class NourishmentFoodListener implements Listener {
    private static final NamespacedKey MARKER = new NamespacedKey("farmersdelight", "nourishment_food_override");
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, OwnerRestoration> cleanupTasks = new ConcurrentHashMap<>();
    private static volatile NourishmentFoodListener active;
    private final FarmersDelightPlugin plugin;
    private final Plugin cleanupHost;
    private final AtomicBoolean closed = new AtomicBoolean();
    public NourishmentFoodListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        cleanupHost = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) cleanupTasks.put(player.getUniqueId(), new OwnerRestoration(player));
        active = this;
    }

    public static void effectChanged(Player player) {
        NourishmentFoodListener listener = active;
        if (listener != null) listener.defer(player);
    }
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (active == this) active = null;
        org.bukkit.event.HandlerList.unregisterAll(this);
        queued.clear();
        java.util.Map<UUID, OwnerRestoration> closing = new java.util.HashMap<>(cleanupTasks);
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) closing.putIfAbsent(player.getUniqueId(), new OwnerRestoration(player));
        for (OwnerRestoration cleanup : closing.values()) {
            Player player = cleanup.player;
            if (!plugin.scheduler().isFolia() || org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) cleanup.accept(null);
            else if (cleanupHost != null && cleanupHost.isEnabled()) {
                try { player.getScheduler().run(cleanupHost, cleanup, cleanup); }
                catch (RuntimeException retired) { cleanup.run(); }
            } else cleanup.run();
        }
        cleanupTasks.clear();
    }
    public static void configurationChanged() {
        NourishmentFoodListener listener = active;
        if (listener != null) for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) listener.defer(player);
    }
    private boolean enabled(Player player) {
        return EffectManager.hasNourishment(player) && plugin.getConfigBoolean(true, "buff.nourishment.always-eat", "buff.nourishment.always_eat");
    }
    private void defer(Player player) {
        UUID id = player.getUniqueId();
        if (closed.get() || !plugin.isEnabled() || !queued.add(id)) return;
        if (!cleanupTasks.containsKey(id)) cleanupTasks.putIfAbsent(id, new OwnerRestoration(player));
        if (closed.get()) { queued.remove(id); cleanupTasks.remove(id); return; }
        try {
            plugin.scheduler().runLaterForEntity(player, () -> {
                queued.remove(id);
                if (!player.isOnline() || active != this || closed.get()) return;
                if (enabled(player)) refreshHeld(player); else restoreInventory(player);
            }, 1);
        } catch (RuntimeException stopped) { queued.remove(id); }
    }
    private void refreshHeld(Player player) {
        int selected = player.getInventory().getHeldItemSlot();
        for (int slot : new int[]{selected, 40}) {
            ItemStack present = player.getInventory().getItem(slot);
            ItemStack prepared = prepare(present);
            if (prepared != present) player.getInventory().setItem(slot, prepared);
        }
    }
    static ItemStack prepare(ItemStack original) {
        if (original == null || original.getType().isAir()) return original;
        var food = CraftEngineItemComponents.food(original);
        if (food == null || Boolean.TRUE.equals(food.get("can_always_eat"))) return original;
        Number nutrition = food.get("nutrition") instanceof Number number ? number : 0;
        Number saturation = food.get("saturation") instanceof Number number ? number : 0F;
        ItemStack copy = original.clone();
        int[] saved = new int[]{nutrition.intValue(), Float.floatToIntBits(saturation.floatValue()),
                BukkitAdaptor.adapt(original).hasNonDefaultComponent(DataComponentKeys.FOOD) ? 1 : 0};
        CraftEngineItemComponents.setAlwaysEat(copy, true);
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(MARKER, PersistentDataType.INTEGER_ARRAY, saved);
        copy.setItemMeta(meta);
        return copy;
    }
    static ItemStack restore(ItemStack current) {
        if (current == null || current.getType().isAir()) return current;
        int[] saved = current.getItemMeta().getPersistentDataContainer().get(MARKER, PersistentDataType.INTEGER_ARRAY);
        if (saved == null) return current;
        ItemStack copy = current.clone();
        var food = CraftEngineItemComponents.food(copy);
        int nutrition = food != null && food.get("nutrition") instanceof Number number ? number.intValue() : -1;
        float saturation = food != null && food.get("saturation") instanceof Number number ? number.floatValue() : Float.NaN;
        // Only retract the exact component this service installed; later edits by another plugin win.
        if (canRestore(saved, nutrition, saturation, food != null && Boolean.TRUE.equals(food.get("can_always_eat")))) {
            if (saved[2] == 0) {
                var wrapped = BukkitAdaptor.adapt(copy);
                var defaults = wrapped.copy();
                defaults.resetComponent(DataComponentKeys.FOOD);
                if (sameExceptAlwaysEat(food, CraftEngineItemComponents.getMap(defaults, DataComponentKeys.FOOD))) {
                    wrapped.resetComponent(DataComponentKeys.FOOD);
                    copy.setItemMeta(ItemStackUtils.getBukkitStack(wrapped).getItemMeta());
                } else CraftEngineItemComponents.setAlwaysEat(copy, false);
            } else CraftEngineItemComponents.setAlwaysEat(copy, false);
        }
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().remove(MARKER);
        copy.setItemMeta(meta);
        return copy;
    }
    static boolean sameExceptAlwaysEat(java.util.Map<String, Object> current, java.util.Map<String, Object> defaults) {
        if (current == null || defaults == null) return false;
        java.util.Map<String, Object> first = new java.util.HashMap<>(current), second = new java.util.HashMap<>(defaults);
        first.remove("can_always_eat");
        second.remove("can_always_eat");
        return first.equals(second);
    }
    static boolean canRestore(int[] saved, int nutrition, float saturation, boolean alwaysEat) {
        return saved != null && saved.length == 3 && (saved[2] == 0 || saved[2] == 1) && alwaysEat
                && nutrition == saved[0] && Float.floatToIntBits(saturation) == saved[1];
    }
    private static void restoreInventory(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); ++slot) {
            ItemStack original = player.getInventory().getItem(slot), restored = restore(original);
            if (restored != original) player.getInventory().setItem(slot, restored);
        }
        ItemStack cursor = player.getItemOnCursor(), restored = restore(cursor);
        if (restored != cursor) player.setItemOnCursor(restored);
    }
    private static final class OwnerRestoration implements Consumer<ScheduledTask>, Runnable {
        private final Player player;
        private final AtomicBoolean completed = new AtomicBoolean();
        OwnerRestoration(Player player) { this.player = player; }
        @Override public void accept(ScheduledTask ignored) {
            if (completed.compareAndSet(false, true) && player.isOnline()) restoreInventory(player);
        }
        // A retired entity has already left the world; its inventory is never accessed here.
        @Override public void run() { completed.set(true); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHeld(PlayerItemHeldEvent event) {
        ItemStack before = event.getPlayer().getInventory().getItem(event.getPreviousSlot());
        ItemStack restored = restore(before);
        if (restored != before) event.getPlayer().getInventory().setItem(event.getPreviousSlot(), restored);
        defer(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent event) {
        ItemStack main = event.getMainHandItem(), restoredMain = restore(main);
        if (restoredMain != main) event.setMainHandItem(restoredMain);
        ItemStack off = event.getOffHandItem(), restoredOff = restore(off);
        if (restoredOff != off) event.setOffHandItem(restoredOff);
        defer(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getHotbarButton() >= 0) {
            ItemStack source = player.getInventory().getItem(event.getHotbarButton()), clean = restore(source);
            if (clean != source) player.getInventory().setItem(event.getHotbarButton(), clean);
        }
        if (event.getClickedInventory() == player.getInventory()) {
            ItemStack current = event.getCurrentItem(), restoredCurrent = restore(current);
            if (restoredCurrent != current) event.setCurrentItem(restoredCurrent);
        }
        ItemStack cursor = player.getItemOnCursor(), restored = restore(cursor);
        if (restored != cursor) player.setItemOnCursor(restored);
        defer(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean marked = event.getNewItems().values().stream().anyMatch(item -> restore(item) != item);
        if (marked) {
            event.setCancelled(true);
            player.setItemOnCursor(restore(event.getOldCursor()));
        }
        defer(player);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) {
        var dropped = event.getItemDrop();
        ItemStack current = dropped.getItemStack(), restored = restore(current);
        if (restored != current) dropped.setItemStack(restored);
        defer(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack current = event.getItem(), restored = restore(current);
        if (restored != current) event.setItem(restored);
        defer(event.getPlayer());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        event.getDrops().replaceAll(NourishmentFoodListener::restore);
        if (event.getKeepInventory()) restoreInventory(event.getEntity());
        queued.remove(event.getEntity().getUniqueId());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        var pickedUp = event.getItem();
        ItemStack current = pickedUp.getItemStack(), restored = restore(current);
        if (restored != current) pickedUp.setItemStack(restored);
        defer(player);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent event) { queued.remove(event.getPlayer().getUniqueId()); restoreInventory(event.getPlayer()); defer(event.getPlayer()); }
    @EventHandler(priority = EventPriority.LOWEST) public void onQuit(PlayerQuitEvent event) { restoreInventory(event.getPlayer()); queued.remove(event.getPlayer().getUniqueId()); cleanupTasks.remove(event.getPlayer().getUniqueId()); }
}
