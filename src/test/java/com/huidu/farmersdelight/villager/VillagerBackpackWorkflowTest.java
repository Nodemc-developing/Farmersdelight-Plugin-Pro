package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises real click/close handlers with inventory probes that reject every off-owner access. */
class VillagerBackpackWorkflowTest {
    private Field serverField;
    private Object originalServer;
    private Object originalLocale;
    private FarmersDelightPlugin plugin;
    private VillagerBackpackService service;
    private Player player;
    private Villager villager;
    private World world;
    private PlayerInventory playerInventory;
    private Inventory realInventory;
    private Inventory topInventory;
    private InventoryView view;
    private VillagerBackpackLease lease;
    private final ItemStack[] real = new ItemStack[8], storage = new ItemStack[36], shown = new ItemStack[9];
    private final List<Runnable> deferred = new ArrayList<>();
    private ItemStack cursor, offhand;
    private boolean playerOwned = true, villagerOwned = true, opened = true;
    private int liveReads, liveWrites, playerWrites, wakes;
    private final UUID playerId = UUID.randomUUID(), villagerId = UUID.randomUUID();

    @BeforeEach void fixture() throws Exception {
        Field localeField = field(I18n.class, "state"); originalLocale = localeField.get(null);
        var locale = new YamlConfiguration();
        locale.set("villager.backpack.changed", "Inventory changed");
        locale.set("villager.backpack.closed", "Inventory closed");
        var localeConstructor = originalLocale.getClass().getDeclaredConstructor(Map.class, YamlConfiguration.class, String.class);
        localeConstructor.setAccessible(true);
        localeField.set(null, localeConstructor.newInstance(Map.of("zh_cn", locale), locale, "zh_cn"));
        serverField = field(Bukkit.class, "server"); originalServer = serverField.get(null);
        serverField.set(null, proxy(Server.class, (method, args) -> switch (method.getName()) {
            case "isOwnedByCurrentRegion" -> args[0] == player ? playerOwned : args[0] == villager && villagerOwned;
            default -> defaultValue(method.getReturnType());
        }));
        plugin = allocate(FarmersDelightPlugin.class);
        field(JavaPlugin.class, "isEnabled").set(plugin, true);
        SchedulerAdapter scheduler = allocate(SchedulerAdapter.class);
        field(SchedulerAdapter.class, "folia").set(scheduler, true);
        field(SchedulerAdapter.class, "plugin").set(scheduler, plugin);
        field(FarmersDelightPlugin.class, "scheduler").set(plugin, scheduler);
        service = new VillagerBackpackService(plugin, ignored -> wakes++);
        field(VillagerBackpackService.class, "running").set(service, true);
        field(VillagerBackpackService.class, "settings").set(service,
                new VillagerBackpackService.Settings(true, true, true, 6));
        Plugin cleanupOwner = proxy(Plugin.class, (method, args) -> switch (method.getName()) {
            case "isEnabled" -> true;
            default -> defaultValue(method.getReturnType());
        });
        field(VillagerBackpackService.class, "cleanupOwner").set(service, cleanupOwner);
        world = proxy(World.class, (method, args) -> defaultValue(method.getReturnType()));
        ScheduledTask task = proxy(ScheduledTask.class, (method, args) -> defaultValue(method.getReturnType()));
        EntityScheduler entityScheduler = proxy(EntityScheduler.class, (method, args) -> {
            if (method.getName().equals("execute")) { deferred.add((Runnable) args[1]); return true; }
            if (method.getName().equals("run")) {
                @SuppressWarnings("unchecked") Consumer<ScheduledTask> callback = (Consumer<ScheduledTask>) args[1];
                deferred.add(() -> callback.accept(task)); return task;
            }
            return defaultValue(method.getReturnType());
        });
        playerInventory = inventory(PlayerInventory.class, storage, false, false);
        realInventory = inventory(Inventory.class, real, true, false);
        topInventory = inventory(Inventory.class, shown, false, true);
        player = proxy(Player.class, (method, args) -> {
            if (method.getName().equals("getUniqueId")) return playerId;
            if (method.getName().equals("getScheduler")) return entityScheduler;
            owner(false);
            return switch (method.getName()) {
                case "getInventory" -> playerInventory;
                case "getItemOnCursor" -> cursor;
                case "setItemOnCursor" -> { cursor = VillagerBackpackLease.copy((ItemStack) args[0]); playerWrites++; yield null; }
                case "getLocation" -> new Location(world, 0, 64, 0);
                case "hasPermission", "isOnline" -> true;
                case "isDead" -> false;
                case "getOpenInventory" -> view;
                case "closeInventory" -> { opened = false; service.closed(new InventoryCloseEvent(view)); yield null; }
                case "sendMessage" -> null;
                default -> defaultValue(method.getReturnType());
            };
        });
        villager = proxy(Villager.class, (method, args) -> {
            if (method.getName().equals("getUniqueId")) return villagerId;
            if (method.getName().equals("getScheduler")) return entityScheduler;
            owner(true);
            return switch (method.getName()) {
                case "getInventory" -> realInventory;
                case "getLocation" -> new Location(world, 1, 64, 0);
                case "isValid" -> true;
                case "isDead" -> false;
                default -> defaultValue(method.getReturnType());
            };
        });
        view = proxy(InventoryView.class, (method, args) -> switch (method.getName()) {
            case "getTopInventory" -> topInventory;
            case "getBottomInventory" -> playerInventory;
            case "getPlayer" -> player;
            case "getType" -> InventoryType.CHEST;
            case "countSlots" -> 45;
            case "convertSlot" -> (int) args[0] < 36 ? (int) args[0] : (int) args[0] - 36;
            case "getInventory" -> (int) args[0] < 0 ? null : (int) args[0] < 9 ? topInventory : playerInventory;
            default -> defaultValue(method.getReturnType());
        });
        install(true);
    }

    @AfterEach void restore() throws Exception {
        serverField.set(null, originalServer);
        field(I18n.class, "state").set(null, originalLocale);
    }

    @Test void withdrawingUpdatesLiveInventoryImmediatelyAndCloseNeverCopiesDisplayBack() {
        real[0] = food(7); sync();
        click(0, ClickType.LEFT, -1);
        assertNull(real[0]); assertEquals(7, cursor.getAmount()); assertNull(shown[0]);
        assertEquals(1, liveWrites); assertTrue(service.isLocked(villagerId));
        service.closed(new InventoryCloseEvent(view));
        assertFalse(service.isLocked(villagerId)); assertNull(real[0]);
        assertEquals(1, liveWrites); assertEquals(7, cursor.getAmount()); assertEquals(1, wakes);
    }

    @Test void shiftDepositAndHotbarSwapConserveRealItemsBeforeQuitOrReload() {
        storage[9] = food(5); sync();
        click(9, ClickType.SHIFT_LEFT, -1);
        assertNull(storage[9]); assertEquals(5, real[0].getAmount());
        storage[2] = new Stack("knife", 1);
        click(0, ClickType.NUMBER_KEY, 2);
        assertEquals("knife", ((Stack) real[0]).id); assertEquals(5, storage[2].getAmount());
        service.close();
        assertEquals("knife", ((Stack) real[0]).id); assertEquals(5, storage[2].getAmount());
        assertFalse(service.isLocked(villagerId));
    }

    @Test void externalMutationRejectsStaleClickAndOnlyRefreshesChangedSlots() {
        real[0] = food(8); sync();
        real[0] = food(3);
        click(0, ClickType.LEFT, -1);
        assertNull(cursor); assertEquals(3, real[0].getAmount()); assertEquals(3, shown[0].getAmount());
        assertEquals(0, liveWrites); assertEquals(0, playerWrites);
    }

    @Test void readOnlyTopCannotWithdrawOrDepositButOwnPlayerSlotsCanBeArrangedAcrossRegions() throws Exception {
        real[0] = food(8); install(false); sync();
        villagerOwned = false;
        click(0, ClickType.LEFT, -1);
        storage[9] = food(4);
        click(9, ClickType.SHIFT_LEFT, -1);
        assertEquals(8, real[0].getAmount()); assertEquals(4, storage[9].getAmount()); assertNull(cursor);
        click(9, ClickType.RIGHT, -1);
        assertEquals(2, storage[9].getAmount()); assertEquals(2, cursor.getAmount());
        assertEquals(0, liveReads); assertEquals(0, liveWrites);
    }

    @Test void losingSharedRegionCancelsBeforeAnyLiveInventoryReadAndDefersMenuClosure() throws Exception {
        real[0] = food(6); sync();
        // Suppress wake scheduling to isolate the cleanup callback, as during a disable transition.
        field(JavaPlugin.class, "isEnabled").set(plugin, false);
        villagerOwned = false;
        click(0, ClickType.LEFT, -1);
        assertEquals(0, liveReads); assertEquals(0, liveWrites); assertNull(cursor);
        assertTrue(opened); assertFalse(service.isLocked(villagerId)); assertEquals(1, deferred.size());
        deferred.removeFirst().run();
        assertFalse(opened); assertEquals(6, real[0].getAmount()); assertNull(cursor);
    }

    @Test void disableKeepsSnapshotsProtectedUntilPlayerOwnedCleanupAndPreservesRealCursor() throws Exception {
        real[0] = food(8); sync();
        cursor = new Stack("player-original", 3);
        field(JavaPlugin.class, "isEnabled").set(plugin, false);
        playerOwned = false; villagerOwned = false;
        service.close();
        assertTrue(opened); assertEquals(0, liveReads); assertEquals(0, liveWrites); assertEquals(0, playerWrites);
        assertEquals(1, deferred.size());
        playerOwned = true;
        InventoryClickEvent nativeClick = event(0, ClickType.LEFT, -1);
        Object guard = field(VillagerBackpackService.class, "guard").get(service);
        Method cancel = guard.getClass().getDeclaredMethod("click", InventoryClickEvent.class); cancel.setAccessible(true);
        cancel.invoke(guard, nativeClick);
        assertTrue(nativeClick.isCancelled());
        deferred.removeFirst().run();
        assertFalse(opened); assertEquals(8, real[0].getAmount());
        assertEquals("player-original", ((Stack) cursor).id); assertEquals(3, cursor.getAmount());
        assertEquals(0, playerWrites); assertEquals(0, liveWrites);
    }

    @Test void dropDoubleClickCreativeCloneAndFillerCannotCreateTransactions() {
        real[0] = food(8); sync();
        for (ClickType click : List.of(ClickType.DROP, ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.MIDDLE))
            click(0, click, -1);
        click(8, ClickType.LEFT, -1);
        assertEquals(8, real[0].getAmount()); assertNull(cursor);
        assertEquals(0, liveWrites); assertEquals(0, playerWrites);
    }

    @Test void anotherPluginCancellationPreventsManualTransfer() {
        real[0] = food(8); sync();
        InventoryClickEvent event = event(0, ClickType.LEFT, -1); event.setCancelled(true);
        service.clicked(event);
        assertEquals(8, real[0].getAmount()); assertNull(cursor);
        assertEquals(0, liveReads); assertEquals(0, liveWrites);
    }

    private void install(boolean editable) throws Exception {
        lease = new VillagerBackpackLease(player, villager, editable, 0);
        lease.view = topInventory; lease.shown = VillagerBackpackLease.copy(real);
        map("viewers").put(playerId, lease); map("editors").clear();
        if (editable) map("editors").put(villagerId, lease);
        @SuppressWarnings("unchecked") Set<VillagerBackpackLease> outstanding = (Set<VillagerBackpackLease>)
                field(VillagerBackpackService.class, "outstandingViews").get(service);
        outstanding.clear(); outstanding.add(lease);
    }

    @SuppressWarnings("unchecked") private Map<UUID, VillagerBackpackLease> map(String name) throws Exception {
        return (Map<UUID, VillagerBackpackLease>) field(VillagerBackpackService.class, name).get(service);
    }

    private void sync() {
        lease.shown = VillagerBackpackLease.copy(real);
        for (int slot = 0; slot < 8; slot++) shown[slot] = VillagerBackpackLease.copy(real[slot]);
    }

    private InventoryClickEvent event(int raw, ClickType click, int hotbar) {
        return new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, raw, click, InventoryAction.UNKNOWN, hotbar);
    }

    private void click(int raw, ClickType click, int hotbar) {
        InventoryClickEvent event = event(raw, click, hotbar);
        service.clicked(event); assertTrue(event.isCancelled());
    }

    private <T> T inventory(Class<T> type, ItemStack[] items, boolean live, boolean top) {
        return proxy(type, (method, args) -> {
            owner(live);
            return switch (method.getName()) {
                case "getHolder" -> top ? lease : null;
                case "getStorageContents" -> { if (live) liveReads++; yield VillagerBackpackLease.copy(items); }
                case "getItem" -> items[(int) args[0]];
                case "setItem" -> {
                    items[(int) args[0]] = VillagerBackpackLease.copy((ItemStack) args[1]);
                    if (live) liveWrites++; else if (!top) playerWrites++; yield null;
                }
                case "clear" -> { java.util.Arrays.fill(items, null); yield null; }
                case "getItemInOffHand" -> offhand;
                case "setItemInOffHand" -> { offhand = VillagerBackpackLease.copy((ItemStack) args[0]); playerWrites++; yield null; }
                case "getSize" -> items.length;
                default -> defaultValue(method.getReturnType());
            };
        });
    }

    private void owner(boolean entity) { assertTrue(entity ? villagerOwned : playerOwned, "Off-owner inventory/entity access"); }
    private static Stack food(int amount) { return new Stack("actual-ce-food-with-nbt", amount); }
    private static final class Stack extends ItemStack {
        final String id;
        private int amount;
        Stack(String id, int amount) { super(); this.id = id; this.amount = amount; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack stack && id.equals(stack.id); }
        @Override public ItemStack clone() { return new Stack(id, amount); }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object instance = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance, type));
    }
    private static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> {
            if (method.getName().equals("equals")) return instance == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
            return calls.invoke(method, args);
        }));
    }
    @FunctionalInterface private interface Calls { Object invoke(Method method, Object[] args) throws Throwable; }
}
