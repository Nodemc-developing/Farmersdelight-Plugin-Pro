package com.huidu.farmersdelight.villager;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VillagerBackpackTransactionsTest {
    @Test void rightPickupUsesRoundedUpHalfAndLeavesOriginalUntouched() {
        Stack original = new Stack("rice", "custom-data", 9, 64);
        var change = VillagerBackpackLease.click(original, null, true, 64);
        assertEquals(5, change.cursor().getAmount());
        assertEquals(4, change.slot().getAmount());
        assertEquals(9, original.getAmount());
        assertEquals("custom-data", ((Stack) change.cursor()).payload);
        assertNotSame(original, change.cursor());
    }

    @Test void leftPickupAndPlacementPreserveAllActualItemData() {
        Stack food = new Stack("onion", "ce-id,name,nbt", 32, 64);
        var pickup = VillagerBackpackLease.click(food, null, false, 64);
        assertNull(pickup.slot());
        assertEquals(32, pickup.cursor().getAmount());
        var put = VillagerBackpackLease.click(null, pickup.cursor(), false, 64);
        assertNull(put.cursor());
        assertTrue(food.isSimilar(put.slot()));
        assertNotSame(pickup.cursor(), put.slot());
    }

    @Test void rightPlaceMovesExactlyOneAndMergeHonorsComponentStackLimit() {
        Stack held = new Stack("food", "a", 8, 16);
        var right = VillagerBackpackLease.click(null, held, true, 64);
        assertEquals(1, right.slot().getAmount());
        assertEquals(7, right.cursor().getAmount());
        var merged = VillagerBackpackLease.click(new Stack("food", "a", 15, 16), held, false, 64);
        assertEquals(16, merged.slot().getAmount());
        assertEquals(7, merged.cursor().getAmount());
        assertEquals(8, held.getAmount());
    }

    @Test void sameMaterialWithDifferentIdentityOrMetadataSwapsWithoutBlending() {
        Stack existing = new Stack("cabbage", "name-a", 12, 64);
        Stack held = new Stack("cabbage", "name-b", 3, 64);
        var swapped = VillagerBackpackLease.click(existing, held, false, 64);
        assertEquals("name-b", ((Stack) swapped.slot()).payload);
        assertEquals("name-a", ((Stack) swapped.cursor()).payload);
        assertEquals(3, swapped.slot().getAmount());
        assertEquals(12, swapped.cursor().getAmount());
        assertEquals(12, existing.getAmount());
    }

    @Test void overCapacitySwapAndFullMergeAreNoOps() {
        Stack slot = new Stack("first", "a", 64, 64);
        Stack cursor = new Stack("second", "b", 65, 64);
        var noSwap = VillagerBackpackLease.click(slot, cursor, false, 64);
        assertTrue(VillagerBackpackLease.same(slot, noSwap.slot()));
        assertTrue(VillagerBackpackLease.same(cursor, noSwap.cursor()));
        var full = VillagerBackpackLease.click(slot, new Stack("first", "a", 1, 64), false, 64);
        assertEquals(64, full.slot().getAmount());
        assertEquals(1, full.cursor().getAmount());
    }

    @Test void shiftTransferMergesBeforeUsingEmptySlotsAndNeverMutatesInputs() {
        Stack source = new Stack("rice", "real-data", 22, 64);
        ItemStack[] destination = {null, new Stack("rice", "real-data", 60, 64), new Stack("rice", "other", 60, 64)};
        var transfer = VillagerBackpackLease.transfer(source, destination, 64);
        assertNull(transfer.source());
        assertEquals(22, transfer.moved());
        assertEquals(18, transfer.destination()[0].getAmount());
        assertEquals(64, transfer.destination()[1].getAmount());
        assertEquals(60, transfer.destination()[2].getAmount());
        assertNull(destination[0]);
        assertEquals(60, destination[1].getAmount());
        assertEquals(22, source.getAmount());
        assertEquals("real-data", ((Stack) transfer.destination()[0]).payload);
    }

    @Test void shiftTransferLeavesExactRemainderWhenInventoryCannotFitEverything() {
        Stack source = new Stack("soup", "bowl", 17, 16);
        var transfer = VillagerBackpackLease.transfer(source,
                new ItemStack[]{new Stack("soup", "bowl", 15, 16), null}, 16);
        assertNull(transfer.source());
        assertEquals(16, transfer.destination()[1].getAmount());
        var partial = VillagerBackpackLease.transfer(new Stack("soup", "bowl", 20, 16),
                new ItemStack[]{new Stack("soup", "bowl", 15, 16), null}, 16);
        assertEquals(3, partial.source().getAmount());
        assertEquals(17, partial.moved());
        var full = VillagerBackpackLease.transfer(source,
                new ItemStack[]{new Stack("other", "bowl", 16, 16)}, 16);
        assertEquals(0, full.moved());
        assertEquals(17, full.source().getAmount());
    }

    @Test void randomClickAndShiftPlansConserveEveryItemIdentity() {
        Random random = new Random(1782);
        for (int trial = 0; trial < 1000; trial++) {
            Stack slot = stack(random), cursor = stack(random);
            var click = VillagerBackpackLease.click(slot, cursor, random.nextBoolean(), 64);
            for (String id : new String[]{"a", "b"}) assertEquals(count(id, slot, cursor), count(id, click.slot(), click.cursor()));
            ItemStack[] target = {stack(random), stack(random), stack(random), stack(random)};
            var transfer = VillagerBackpackLease.transfer(cursor, target, 64);
            for (String id : new String[]{"a", "b"})
                assertEquals(count(id, cursor) + count(id, target), count(id, transfer.source()) + count(id, transfer.destination()));
            for (ItemStack item : transfer.destination()) if (item != null) assertTrue(item.getAmount() <= item.getMaxStackSize());
        }
    }

    @Test void snapshotsAreClonedAndConflictDetectionIncludesIdentityMetadataAndCount() {
        ItemStack[] real = {new Stack("a", "payload", 2, 64), null};
        ItemStack[] shown = VillagerBackpackLease.copy(real);
        assertTrue(VillagerBackpackLease.same(real, shown));
        assertNotSame(real[0], shown[0]);
        real[0].setAmount(1);
        assertFalse(VillagerBackpackLease.same(real, shown));
        real[0] = new Stack("a", "different-payload", 2, 64);
        assertFalse(VillagerBackpackLease.same(real, shown));
        real[0] = new Stack("b", "payload", 2, 64);
        assertFalse(VillagerBackpackLease.same(real, shown));
        assertEquals(2, shown[0].getAmount());
    }

    @Test void masterSwitchAndBackpackSwitchAreIndependentAndDistanceIsFiniteAndBounded() {
        var config = new YamlConfiguration();
        var defaults = VillagerBackpackService.Settings.read(config);
        assertTrue(defaults.enabled()); assertTrue(defaults.editable()); assertTrue(defaults.openOnSneak());
        assertEquals(6, defaults.maximumDistance());
        config.set("villager.backpack.editable", false);
        config.set("villager.backpack.open-on-sneak", false);
        var readonly = VillagerBackpackService.Settings.read(config);
        assertTrue(readonly.enabled()); assertFalse(readonly.editable()); assertFalse(readonly.openOnSneak());
        config.set("villager.backpack.enabled", false);
        assertFalse(VillagerBackpackService.Settings.read(config).enabled());
        config.set("villager.backpack.enabled", true);
        config.set("villager.enable", false);
        assertFalse(VillagerBackpackService.Settings.read(config).enabled());
        config.set("villager.backpack.maximum-distance-blocks", Double.NaN);
        assertEquals(6, VillagerBackpackService.Settings.read(config).maximumDistance());
        config.set("villager.backpack.maximum-distance-blocks", -100);
        assertEquals(1, VillagerBackpackService.Settings.read(config).maximumDistance());
        config.set("villager.backpack.maximum-distance-blocks", 10000);
        assertEquals(32, VillagerBackpackService.Settings.read(config).maximumDistance());
    }

    private static Stack stack(Random random) {
        if (random.nextInt(4) == 0) return null;
        int max = random.nextBoolean() ? 16 : 64;
        return new Stack(random.nextBoolean() ? "a" : "b", "payload", 1 + random.nextInt(max), max);
    }

    private static int count(String id, ItemStack... items) {
        int count = 0;
        for (ItemStack item : items) if (item != null && ((Stack) item).id.equals(id)) count += item.getAmount();
        return count;
    }

    private static final class Stack extends ItemStack {
        final String id, payload;
        final int maximum;
        private int amount;
        Stack(String id, String payload, int amount, int maximum) {
            super(); this.id = id; this.payload = payload; this.amount = amount; this.maximum = maximum;
        }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return maximum; }
        @Override public boolean isSimilar(ItemStack other) {
            return other instanceof Stack stack && id.equals(stack.id) && payload.equals(stack.payload) && maximum == stack.maximum;
        }
        @Override public ItemStack clone() { return new Stack(id, payload, amount, maximum); }
    }
}
