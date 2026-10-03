package com.huidu.farmersdelight.manager;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HandheldSkewerSessionTest {
    private static final HandheldSkewerSpec SPEC = HandheldSkewerSpec.parse(Key.of("test:raw"), Map.of("cooking_proxy", "test:proxy", "result", "test:cooked"));
    @Test void aServingIsDebitedOnceForEitherHandWithoutChangingRemainingComponents() {
        for (EquipmentSlot hand : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND}) {
            int slot = hand == EquipmentSlot.HAND ? 3 : 40;
            Stack raw = new Stack("raw", 7, "custom-components");
            Map<Integer, ItemStack> items = new HashMap<>(Map.of(slot, raw));
            var inventory = inventory(items, 3);
            var session = session(hand, slot, raw);
            assertEquals(7, inventory.getItem(slot).getAmount());
            assertTrue(session.consume(inventory, false));
            assertEquals(6, inventory.getItem(slot).getAmount());
            assertEquals("custom-components", ((Stack) inventory.getItem(slot)).component);
            assertFalse(session.consume(inventory, false));
            assertEquals(6, inventory.getItem(slot).getAmount());
        }
    }
    @Test void replacingMovingOrEditingTheHeldStackCannotCommitAnOldServing() {
        for (int change = 0; change < 5; ++change) {
            Stack raw = new Stack("raw", 7, "original");
            Map<Integer, ItemStack> items = new HashMap<>(Map.of(3, raw));
            var session = session(EquipmentSlot.HAND, 3, raw);
            int selected = 3;
            switch (change) {
                case 0 -> items.remove(3);
                case 1 -> items.put(3, new Stack("other", 7, "original"));
                case 2 -> items.put(3, new Stack("raw", 6, "original"));
                case 3 -> items.put(3, new Stack("raw", 7, "changed"));
                case 4 -> selected = 4;
            }
            assertFalse(session.consume(inventory(items, selected), false));
            assertFalse(session.committed);
        }
    }
    @Test void theLastRawItemLeavesAnEmptySlotAndCreativeNeverDebits() {
        Stack raw = new Stack("raw", 1, "original");
        Map<Integer, ItemStack> items = new HashMap<>(Map.of(3, raw));
        assertTrue(session(EquipmentSlot.HAND, 3, raw).consume(inventory(items, 3), false));
        assertNull(items.get(3));
        items.put(3, raw);
        assertTrue(session(EquipmentSlot.HAND, 3, raw).consume(inventory(items, 3), true));
        assertSame(raw, items.get(3));
        assertEquals(1, raw.getAmount());
    }
    @Test void durationAndIdentifierValidationRejectAmbiguousOrUnboundedDefinitions() {
        assertEquals(120, SPEC.cookTicks());
        assertEquals(40, HandheldSkewerSpec.parse(Key.of("test:raw"), Map.of("cooking-proxy", "test:proxy", "result", "test:cooked", "cook-ticks", 40)).cookTicks());
        for (Object invalid : new Object[]{0, -1, 72_001, 1.5, Long.MAX_VALUE, "120"})
            assertThrows(IllegalArgumentException.class, () -> HandheldSkewerSpec.parse(Key.of("test:raw"), Map.of("cooking_proxy", "test:proxy", "result", "test:cooked", "cook_ticks", invalid)));
        assertThrows(IllegalArgumentException.class, () -> HandheldSkewerSpec.parse(Key.of("test:raw"), Map.of("result", "test:cooked")));
    }
    private static SkewerCookingService.Session session(EquipmentSlot hand, int slot, Stack raw) {
        return new SkewerCookingService.Session(null, hand, slot, 3, raw.clone(), new Stack("proxy", 1, "display"), new Stack("cooked", 1, "result"), SPEC);
    }
    private static PlayerInventory inventory(Map<Integer, ItemStack> items, int selected) {
        return (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHeldItemSlot" -> selected;
                    case "getItem" -> items.get((int) args[0]);
                    case "setItem" -> { items.put((int) args[0], (ItemStack) args[1]); yield null; }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private static final class Stack extends ItemStack {
        private final String id, component;
        private int amount;
        Stack(String id, int amount, String component) { super(); this.id = id; this.amount = amount; this.component = component; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount = value; }
        @Override public Material getType() { return Material.STONE; }
        @Override public boolean isSimilar(ItemStack item) { return item instanceof Stack other && id.equals(other.id) && component.equals(other.component); }
        @Override public Stack clone() { return new Stack(id, amount, component); }
    }
}
