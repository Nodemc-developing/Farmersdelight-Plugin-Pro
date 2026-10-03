package com.huidu.farmersdelight.gui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class InventoryClosureTest {
    @Test
    void queuedClosureLeavesReplacementWindowOpen() {
        Window window = new Window();
        AtomicReference<Inventory> top = new AtomicReference<>(window.inventory);
        AtomicInteger closes = new AtomicInteger();
        Runnable queued = window.viewerCloseAction(top::get, closes::incrementAndGet);
        window.close();
        top.set(inventory());

        queued.run();

        assertEquals(0, closes.get());
    }

    @Test
    void queuedClosureClosesItsRetiredWindow() {
        Window window = new Window();
        AtomicReference<Inventory> top = new AtomicReference<>(window.inventory);
        AtomicInteger closes = new AtomicInteger();
        Runnable queued = window.viewerCloseAction(top::get, closes::incrementAndGet);
        window.close();

        queued.run();

        assertEquals(1, closes.get());
    }

    @Test
    void oldClosureCannotCloseReopenedAndRetiredInstance() {
        Window window = new Window();
        AtomicInteger closes = new AtomicInteger();
        window.open();
        Runnable previous = window.viewerCloseAction(() -> window.inventory, closes::incrementAndGet);
        window.close();
        window.open();
        window.close();

        previous.run();
        assertEquals(0, closes.get());
        window.viewerCloseAction(() -> window.inventory, closes::incrementAndGet).run();
        assertEquals(1, closes.get());
    }

    @Test
    void captureDoesNotReadViewerInventoryOnCallingThread() {
        Window window = new Window();
        AtomicInteger reads = new AtomicInteger();
        Runnable queued = window.viewerCloseAction(() -> {
            reads.incrementAndGet();
            return window.inventory;
        }, () -> { });

        assertEquals(0, reads.get());
        window.close();
        queued.run();
        assertEquals(1, reads.get());
    }

    @Test
    void stalePotWindowRemovalPreservesReplacementRoute() throws ReflectiveOperationException {
        // Only the routing hook is under test; constructing the live menu needs a running Bukkit server.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        var singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object allocator = singleton.get(null);
        var allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        CookingPotGui previous = (CookingPotGui) allocate.invoke(allocator, CookingPotGui.class);
        CookingPotGui replacement = (CookingPotGui) allocate.invoke(allocator, CookingPotGui.class);
        UUID playerId = UUID.randomUUID();
        try {
            CookingPotGui.activeGuis.put(playerId, previous);
            CookingPotGui.activeGuis.put(playerId, replacement);

            previous.removeFromActiveGuis(playerId);

            assertSame(replacement, CookingPotGui.activeGuis.get(playerId));
            replacement.removeFromActiveGuis(playerId);
            assertFalse(CookingPotGui.activeGuis.containsKey(playerId));
        } finally {
            CookingPotGui.activeGuis.remove(playerId);
        }
    }

    private static Inventory inventory() {
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
    }

    private static final class Window extends AbstractInventoryGui {
        Window() {
            super(null, player());
            inventory = inventory();
        }
        void open() { doOpen(null); }
        @Override protected AbstractInventoryGui findExistingGui(UUID playerId) { return null; }
        @Override protected void putActiveGui(UUID playerId, AbstractInventoryGui gui) { }
        @Override protected void removeFromActiveGuis(UUID playerId) { }
        @Override protected void ensureListenerRegistered() { }
    }
}
