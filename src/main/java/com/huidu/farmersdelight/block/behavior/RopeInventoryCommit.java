package com.huidu.farmersdelight.block.behavior;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.Objects;

/** A one-slot exchange makes the item result observable even if an inventory writer fails. */
public final class RopeInventoryCommit {
    private RopeInventoryCommit() { }

    public static boolean write(Inventory inventory, int slot, ItemStack expected, ItemStack result) {
        if (!same(inventory.getItem(slot), expected)) return false;
        RuntimeException writeFailure = null;
        try { inventory.setItem(slot, result); }
        catch (RuntimeException failure) { writeFailure = failure; }
        ItemStack observed = inventory.getItem(slot);
        if (same(observed, result)) return true;
        if (same(observed, expected)) return false;
        throw new IllegalStateException("Rope exchange encountered a different inventory slot value", writeFailure);
    }

    public static boolean returnOne(Inventory inventory, ItemStack item) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; ++slot) {
            ItemStack before = contents[slot];
            if (before == null || before.getAmount() == 0 || before.getType().isAir()) {
                ItemStack result = item.clone(); result.setAmount(1);
                return write(inventory, slot, before, result);
            }
            if (before.isSimilar(item) && before.getAmount() < Math.min(before.getMaxStackSize(), inventory.getMaxStackSize())) {
                ItemStack result = before.clone(); result.setAmount(before.getAmount() + 1);
                return write(inventory, slot, before, result);
            }
        }
        return false;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        if (empty(left)) return empty(right);
        if (empty(right)) return false;
        return Objects.equals(left, right);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getAmount() == 0 || item.getType() == Material.AIR;
    }
}
