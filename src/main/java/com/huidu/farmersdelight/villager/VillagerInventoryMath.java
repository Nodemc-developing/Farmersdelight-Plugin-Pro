package com.huidu.farmersdelight.villager;

import org.bukkit.inventory.ItemStack;

/** Pure capacity calculations allow pickup events to run before either inventory is changed. */
final class VillagerInventoryMath {
    static int capacity(ItemStack[] contents, ItemStack offered) {
        int space = 0;
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) space += offered.getMaxStackSize();
            else if (stack.isSimilar(offered)) space += Math.max(0, stack.getMaxStackSize() - stack.getAmount());
            if (space >= offered.getAmount()) return offered.getAmount();
        }
        return Math.min(space, offered.getAmount());
    }
    static int unitsToReach(int current, int pointsPerItem, int available, int target) {
        if (pointsPerItem <= 0 || available <= 0 || current >= target) return 0;
        long missing = (long) target - current;
        return (int) Math.min(available, (missing + pointsPerItem - 1) / pointsPerItem);
    }
}
