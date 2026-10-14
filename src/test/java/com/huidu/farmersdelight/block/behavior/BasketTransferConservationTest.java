package com.huidu.farmersdelight.block.behavior;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily supplies the fixture's non-air Material query and restores its registry supplier")
class BasketTransferConservationTest {
    private static Object originalBlockType;

    @BeforeAll static void supplyFixtureMaterial() throws Exception {
        var field = Material.class.getDeclaredField("blockType");
        field.setAccessible(true);
        originalBlockType = field.get(Material.STONE);
        field.set(Material.STONE, (java.util.function.Supplier<Object>) () -> null);
    }

    @AfterAll static void restoreFixtureMaterial() throws Exception {
        var field = Material.class.getDeclaredField("blockType");
        field.setAccessible(true);
        field.set(Material.STONE, originalBlockType);
        assertSame(originalBlockType, field.get(Material.STONE));
    }
    @Test void aPartialCollectionPreservesRemainderComponentsAndCountsAsACompletedTransfer() {
        Storage basket = new Storage(60, 64);
        Drop drop = new Drop(new Stack(10, "custom-data"));

        assertTrue(BasketVacuumController.collectItem(basket.inventory(), drop.item()));
        assertEquals(64, basket.stored);
        assertEquals(6, drop.stack.getAmount());
        assertEquals("custom-data", ((Stack) drop.stack).components);
        assertFalse(drop.removed);
        assertEquals(70, basket.stored + drop.stack.getAmount());
    }

    @Test void aFullBasketDoesNotRewriteOrRemoveTheDroppedStack() {
        Storage basket = new Storage(64, 64);
        Drop drop = new Drop(new Stack(10, "custom-data"));
        ItemStack original = drop.stack;

        assertFalse(BasketVacuumController.collectItem(basket.inventory(), drop.item()));
        assertSame(original, drop.stack);
        assertEquals(10, drop.stack.getAmount());
        assertFalse(drop.removed);
        assertEquals(0, drop.writes);
    }

    @Test void aFullyAcceptedDropIsRemovedAfterItsExactAmountWasInserted() {
        Storage basket = new Storage(2, 64);
        Drop drop = new Drop(new Stack(10, "custom-data"));

        assertTrue(BasketVacuumController.collectItem(basket.inventory(), drop.item()));
        assertEquals(12, basket.stored);
        assertEquals("custom-data", ((Stack) basket.lastReceived).components);
        assertTrue(drop.removed);
        assertEquals(0, drop.writes);
    }

    @Test void anOptionalOutputTransferDoesNotDebitWhenTheDestinationIsFull() {
        Storage source = new Storage(7, 64);
        Storage target = new Storage(64, 64);

        assertFalse(BasketVacuumController.ejectOneItem(source.inventory(), target.inventory()));
        assertEquals(7, source.stored);
        assertEquals(64, target.stored);
        assertEquals(0, source.writes);
    }

    @Test void anOptionalOutputTransferMovesOnlyOneItemAndKeepsBothStacksComponents() {
        Storage source = new Storage(7, 64);
        Storage target = new Storage(0, 64);

        assertTrue(BasketVacuumController.ejectOneItem(source.inventory(), target.inventory()));
        assertEquals(6, source.stored);
        assertEquals(1, target.stored);
        assertEquals("custom-data", ((Stack) source.stack).components);
        assertEquals("custom-data", ((Stack) target.lastReceived).components);
        assertEquals(7, source.stored + target.stored);
    }

    private static final class Storage {
        int stored, writes;
        final int capacity;
        ItemStack stack, lastReceived;
        Storage(int stored, int capacity) {
            this.stored = stored;
            this.capacity = capacity;
            this.stack = new Stack(stored, "custom-data");
        }
        Inventory inventory() {
            return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(), new Class<?>[]{Inventory.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getStorageContents" -> new ItemStack[]{stack};
                        case "setItem" -> {
                            ++writes;
                            stack = (ItemStack) args[1];
                            stored = stack == null ? 0 : stack.getAmount();
                            yield null;
                        }
                        case "addItem" -> {
                            ItemStack given = ((ItemStack[]) args[0])[0];
                            lastReceived = given.clone();
                            int accepted = Math.min(capacity - stored, given.getAmount());
                            stored += accepted;
                            given.setAmount(given.getAmount() - accepted);
                            yield given.getAmount() == 0 ? new HashMap<Integer, ItemStack>() : new HashMap<>(Map.of(0, given));
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class Drop {
        ItemStack stack;
        int writes;
        boolean removed;
        Drop(ItemStack stack) { this.stack = stack; }
        Item item() {
            return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getItemStack" -> stack;
                        case "setItemStack" -> { ++writes; stack = (ItemStack) args[0]; yield null; }
                        case "remove" -> { removed = true; yield null; }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class Stack extends ItemStack {
        int amount;
        final String components;
        Stack(int amount, String components) { super(); this.amount = amount; this.components = components; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public Stack clone() { return new Stack(amount, components); }
    }
}
