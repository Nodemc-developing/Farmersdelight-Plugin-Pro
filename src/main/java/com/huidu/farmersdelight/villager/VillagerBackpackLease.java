package com.huidu.farmersdelight.villager;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** The displayed slots are never an escrow and are never copied back on close. */
final class VillagerBackpackLease implements InventoryHolder {
    final Player player;
    final Villager villager;
    final UUID playerId;
    final UUID villagerId;
    final boolean editable;
    final long generation;
    final AtomicBoolean released = new AtomicBoolean();
    final AtomicBoolean validationPending = new AtomicBoolean();
    Inventory view;
    ItemStack[] shown;
    ScheduledTask heartbeat;

    VillagerBackpackLease(Player player, Villager villager, boolean editable, long generation) {
        this.player = player;
        this.villager = villager;
        this.playerId = player.getUniqueId();
        this.villagerId = villager.getUniqueId();
        this.editable = editable;
        this.generation = generation;
    }

    @Override public @NotNull Inventory getInventory() { return Objects.requireNonNull(view); }

    static ItemStack copy(ItemStack item) {
        return empty(item) ? null : item.clone();
    }

    static boolean empty(ItemStack item) {
        return item == null || item.getAmount() <= 0 || item.getType() == org.bukkit.Material.AIR
                || item.getType() == org.bukkit.Material.CAVE_AIR || item.getType() == org.bukkit.Material.VOID_AIR;
    }

    static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copied = new ItemStack[items.length];
        for (int slot = 0; slot < items.length; slot++) copied[slot] = copy(items[slot]);
        return copied;
    }

    static boolean same(ItemStack left, ItemStack right) {
        if (empty(left) || empty(right)) return empty(left) && empty(right);
        return left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    static boolean same(ItemStack[] left, ItemStack[] right) {
        if (left.length != right.length) return false;
        for (int slot = 0; slot < left.length; slot++) if (!same(left[slot], right[slot])) return false;
        return true;
    }

    record SlotChange(ItemStack slot, ItemStack cursor) { }

    /** Pure pickup/place plan; preserves actual item metadata and never mutates the arguments. */
    static SlotChange click(ItemStack present, ItemStack held, boolean right, int capacity) {
        ItemStack slot = copy(present), cursor = copy(held);
        if (cursor == null) {
            if (slot == null) return new SlotChange(null, null);
            int taken = right ? slot.getAmount() / 2 + slot.getAmount() % 2 : slot.getAmount();
            cursor = amount(slot, taken);
            slot = amount(slot, slot.getAmount() - taken);
        } else if (slot == null) {
            int put = Math.min(right ? 1 : cursor.getAmount(), Math.min(capacity, cursor.getMaxStackSize()));
            if (put > 0) {
                slot = amount(cursor, put);
                cursor = amount(cursor, cursor.getAmount() - put);
            }
        } else if (slot.isSimilar(cursor)) {
            int space = Math.max(0, Math.min(capacity, slot.getMaxStackSize()) - slot.getAmount());
            int put = Math.min(space, right ? 1 : cursor.getAmount());
            if (put > 0) {
                slot.setAmount(slot.getAmount() + put);
                cursor = amount(cursor, cursor.getAmount() - put);
            }
        } else if (fits(cursor, capacity)) {
            ItemStack previous = slot;
            slot = cursor;
            cursor = previous;
        }
        return new SlotChange(slot, cursor);
    }

    record Transfer(ItemStack source, ItemStack[] destination, int moved) { }

    /** Merge before filling empty slots, using each item's component-defined stack limit. */
    static Transfer transfer(ItemStack present, ItemStack[] destination, int capacity) {
        ItemStack source = copy(present);
        ItemStack[] result = copy(destination);
        if (source == null) return new Transfer(null, result, 0);
        int remaining = source.getAmount();
        for (int slot = 0; slot < result.length && remaining > 0; slot++) {
            ItemStack existing = result[slot];
            if (existing == null || !existing.isSimilar(source)) continue;
            int space = Math.max(0, Math.min(capacity, existing.getMaxStackSize()) - existing.getAmount());
            int put = Math.min(space, remaining);
            existing.setAmount(existing.getAmount() + put);
            remaining -= put;
        }
        for (int slot = 0; slot < result.length && remaining > 0; slot++) {
            if (result[slot] != null) continue;
            int put = Math.min(remaining, Math.min(capacity, source.getMaxStackSize()));
            if (put <= 0) continue;
            result[slot] = amount(source, put);
            remaining -= put;
        }
        return new Transfer(amount(source, remaining), result, source.getAmount() - remaining);
    }

    static boolean fits(ItemStack item, int capacity) {
        return empty(item) || item.getAmount() <= Math.min(capacity, item.getMaxStackSize());
    }

    private static ItemStack amount(ItemStack item, int amount) {
        if (amount <= 0) return null;
        ItemStack result = item.clone();
        result.setAmount(amount);
        return result;
    }
}
