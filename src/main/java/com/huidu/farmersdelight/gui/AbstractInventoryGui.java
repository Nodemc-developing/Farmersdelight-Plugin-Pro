package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class AbstractInventoryGui implements InventoryHolder {

    /** Closes this retired window on its viewer's owner without disturbing a newer window. */
    protected final void closeViewerInventory() {
        Player viewer = player;
        if (viewer == null) return;
        try {
            plugin.scheduler().runForEntity(viewer,
                    viewerCloseAction(() -> viewer.getOpenInventory().getTopInventory(), viewer::closeInventory));
        } catch (RuntimeException stopped) {
            if (plugin.isEnabled()) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not schedule inventory closure", stopped);
            }
        }
    }

    protected final FarmersDelightPlugin plugin;
    protected UUID playerId;
    protected Player player;
    protected Inventory inventory;
    protected volatile boolean closed = false;
    private volatile long openGeneration;
    protected final Consumer<Void> tickCallback;

    protected AbstractInventoryGui(FarmersDelightPlugin plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.playerId = player != null ? player.getUniqueId() : null;
        this.tickCallback = v -> {
            try (var ignored = com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.readScope()) { onTick(); }
        };
    }

    @Override
    @Nonnull
    public Inventory getInventory() {
        return inventory;
    }

    protected void onTick() {
    }

    protected boolean requiresTicking() {
        return false;
    }

    protected final void doOpen(Runnable afterRefresh) {
        ++openGeneration;
        closed = false;

        AbstractInventoryGui existingGui = findExistingGui(playerId);
        if (existingGui != null && !existingGui.closed) {
            existingGui.close();
        }

        ensureListenerRegistered();
        putActiveGui(playerId, this);

        if (afterRefresh != null) {
            try (var ignored = com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.readScope()) { afterRefresh.run(); }
        }
        player.openInventory(inventory);

        if (requiresTicking()) {
            GuiTickManager.getInstance(plugin).registerCallback(player, tickCallback);
        }
    }

    // ---- Required subclass hooks ----

    protected abstract AbstractInventoryGui findExistingGui(UUID playerId);

    protected abstract void putActiveGui(UUID playerId, AbstractInventoryGui gui);

    protected abstract void removeFromActiveGuis(UUID playerId);

    protected abstract void ensureListenerRegistered();

    // ---- Shared lifecycle ----

    final Runnable viewerCloseAction(Supplier<Inventory> currentTop, Runnable closeInventory) {
        Inventory expected = inventory;
        long generation = openGeneration;
        return () -> {
            if (closed && generation == openGeneration && expected != null && currentTop.get() == expected) {
                closeInventory.run();
            }
        };
    }

    public void close() {
        if (closed) return;
        closed = true;
        if (requiresTicking()) {
            GuiTickManager.getInstance(plugin).unregisterCallback(tickCallback);
        }
    }

    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() != this) return;
        if (closed) return;
        close();
        removeFromActiveGuis(event.getPlayer().getUniqueId());
    }

    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() != this) return;
        // Cancel all drags into the top inventory by default; subclasses may allow specific drag behavior.
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < event.getView().getTopInventory().getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

}
