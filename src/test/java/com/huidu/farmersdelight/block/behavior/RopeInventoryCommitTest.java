package com.huidu.farmersdelight.block.behavior;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RopeInventoryCommitTest {
    @Test void aWriterThatFailsBeforeApplyingTheItemIsUncommitted() {
        Stack before = new Stack(4), after = new Stack(3);
        AtomicReference<ItemStack> slot = new AtomicReference<>(before);
        assertFalse(RopeInventoryCommit.write(inventory(slot, false), 0, before, after));
        assertSame(before, slot.get());
    }

    @Test void anExceptionAfterAnAppliedWriteDoesNotReturnAnExtraRope() {
        Stack before = new Stack(4), after = new Stack(3);
        AtomicReference<ItemStack> slot = new AtomicReference<>(before);
        assertTrue(RopeInventoryCommit.write(inventory(slot, true), 0, before, after));
        assertSame(after, slot.get());
    }

    @Test void anEmptyAirItemAndNullRepresentTheSameEmptySlot() {
        AtomicReference<ItemStack> slot = new AtomicReference<>(null);
        Inventory inventory = inventory(slot, true);
        assertTrue(RopeInventoryCommit.write(inventory, 0, new Stack(0), new Stack(1)));
        assertEquals(1, slot.get().getAmount());
    }

    private static Inventory inventory(AtomicReference<ItemStack> slot, boolean apply) {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> slot.get();
                    case "setItem" -> {
                        if (apply) slot.set((ItemStack) args[1]);
                        throw new IllegalStateException("fixture inventory writer failed");
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Stack extends ItemStack {
        private final int count;
        @SuppressWarnings("deprecation") Stack(int count) { super(); this.count = count; }
        @Override public int getAmount() { return count; }
        @Override public Material getType() { return count == 0 ? Material.AIR : Material.PAPER; }
        @Override public boolean equals(Object other) { return other instanceof Stack stack && count == stack.count; }
        @Override public int hashCode() { return count; }
    }
}
