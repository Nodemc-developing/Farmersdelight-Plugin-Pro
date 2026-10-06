package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.listener.worlddata.ExternalVillagerTrades;
import com.huidu.farmersdelight.listener.worlddata.WorldDataConfig;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VillagerWorkRulesTest {
    @Test void foodUnitsStopAtThresholdWithoutLosingUnusedItems() {
        assertEquals(4, VillagerInventoryMath.unitsToReach(0, 3, 64, 12));
        assertEquals(1, VillagerInventoryMath.unitsToReach(11, 6, 64, 12));
        assertEquals(2, VillagerInventoryMath.unitsToReach(0, 3, 2, 12));
        assertEquals(0, VillagerInventoryMath.unitsToReach(12, 3, 64, 12));
        assertEquals(0, VillagerInventoryMath.unitsToReach(0, 0, 64, 12));
        assertEquals(1, VillagerInventoryMath.unitsToReach(0, Integer.MAX_VALUE, 1, 12));
    }

    @Test void featureSwitchesAndBudgetsAreIndependentAndBounded() {
        var config = new YamlConfiguration(); config.set("villager.harvest.enable", false);
        config.set("villager.harvest.block-budget", 100000); config.set("villager.pickup.scan-interval-ticks", 0);
        var settings = VillagerWorkSettings.read(config);
        assertFalse(settings.harvest()); assertTrue(settings.pickup()); assertTrue(settings.breed());
        assertEquals(128, settings.blockBudget()); assertEquals(5, settings.activeTicks());
        assertEquals(5, settings.pickupInterval()); assertEquals(40, settings.breedInterval());
        config.set("villager.breed.scan-interval-ticks", Integer.MAX_VALUE);
        assertEquals(1200, VillagerWorkSettings.read(config).breedInterval());
        config.set("villager.enable", false); assertFalse(VillagerWorkSettings.read(config).anyWork());
    }

    @Test void actualWorkKeepsPickupAndFeedingCooldownsIndependentEvenWhenRepeatedlyWoken() throws Exception {
        var fastPickup = new WorkProbe(true, 20, 100);
        for (int tick : new int[]{0, 1, 5, 20, 40, 60, 80, 99, 100}) fastPickup.work(tick);
        assertEquals(6, fastPickup.pickupAttempts);
        assertEquals(2, fastPickup.nativeFood.reads,
                "Pickup ticks and extra wakeups must not bypass the 100-tick feeding interval");

        var fastFeeding = new WorkProbe(true, 100, 20);
        for (int tick : new int[]{0, 1, 5, 20, 40, 60, 80, 99, 100}) fastFeeding.work(tick);
        assertEquals(2, fastFeeding.pickupAttempts);
        assertEquals(6, fastFeeding.nativeFood.reads,
                "Feeding ticks must not bypass the 100-tick pickup interval");

        var pickupDisabled = new WorkProbe(false, 5, 100);
        for (int tick : new int[]{0, 5, 20, 40, 60, 80, 99, 100}) pickupDisabled.work(tick);
        assertEquals(0, pickupDisabled.pickupAttempts);
        assertEquals(2, pickupDisabled.nativeFood.reads,
                "A disabled pickup interval must not make feeding run more often");
    }

    @Test void tradePoolsAreImmutableIndexedSnapshotsAndRegistrationDoesNotMutateOldSnapshot() {
        String first = "unit-test:farmer-a", second = "unit-test:farmer-b";
        var offer = new WorldDataConfig.TradeOffer("farmer", 2, "minecraft:carrot", 8, "minecraft:emerald", 1, 16, 2, .05f, .1);
        try {
            ExternalVillagerTrades.register(first, offer);
            var before = ExternalVillagerTrades.villagerTradesFor("farmer", 2);
            assertTrue(before.contains(offer)); assertThrows(UnsupportedOperationException.class, () -> before.add(offer));
            ExternalVillagerTrades.register(second, offer);
            assertEquals(before.size() + 1, ExternalVillagerTrades.villagerTradesFor("farmer", 2).size());
            assertEquals(1, before.stream().filter(value -> value == offer).count());
            assertFalse(ExternalVillagerTrades.villagerTradesFor("butcher", 2).contains(offer));
        } finally { ExternalVillagerTrades.unregister(first); ExternalVillagerTrades.unregister(second); }
    }

    /** Drives the real owner work callback; native food is already full, so no server item factory is needed. */
    private static final class WorkProbe {
        private final VillagerAutomationService service;
        private final Object tracker;
        private final Method work;
        private final NativeFoodProbe nativeFood = new NativeFoodProbe();
        private int ticks, pickupAttempts;

        WorkProbe(boolean pickupEnabled, int pickupInterval, int breedInterval) throws Exception {
            var config = new YamlConfiguration();
            config.set("villager.harvest.enable", false);
            config.set("villager.bonemeal.enabled", false);
            config.set("villager.compost.enabled", false);
            config.set("villager.breed.share-food", false);
            config.set("villager.pickup.enable", pickupEnabled);
            config.set("villager.pickup.scan-interval-ticks", pickupInterval);
            config.set("villager.breed.scan-interval-ticks", breedInterval);
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Object unsafe = field(unsafeType, "theUnsafe").get(null);
            var plugin = (FarmersDelightPlugin) unsafeType.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, FarmersDelightPlugin.class);
            service = new VillagerAutomationService(plugin);
            World world = proxy(World.class, (method, args) -> method.getName().equals("getGameRuleValue")
                    ? true : defaultValue(method.getReturnType()));
            UUID id = UUID.randomUUID();
            Villager villager = proxy(Villager.class, (method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "isValid", "hasAI" -> true;
                case "getPose" -> Pose.STANDING;
                case "getWorld" -> world;
                case "getTicksLived" -> ticks;
                case "getNearbyEntities" -> { pickupAttempts++; yield List.of(); }
                default -> defaultValue(method.getReturnType());
            });
            var registry = new VillagerCropRegistry.Registry(Map.of(), Map.of(), Set.of(), null, null, Map.of());
            field(VillagerAutomationService.class, "snapshot").set(service, new VillagerContentSnapshot(
                    VillagerWorkSettings.read(config), new VillagerFoodRules(Map.of(), Set.of(), 0), registry,
                    Set.of(), new VillagerCompostService.Settings(Set.of(), 20, 32, .3, Map.of())));
            var lookup = MethodHandles.lookup();
            var handle = lookup.findVirtual(NativeFoodProbe.class, "access", MethodType.methodType(Object.class, Villager.class))
                    .bindTo(nativeFood);
            var counter = lookup.findVarHandle(NativeFoodProbe.class, "foodLevel", int.class);
            var constructor = VillagerFoodAccess.class.getDeclaredConstructor(java.lang.invoke.MethodHandle.class,
                    java.lang.invoke.VarHandle.class);
            constructor.setAccessible(true);
            field(VillagerAutomationService.class, "food").set(service, constructor.newInstance(handle, counter));
            field(VillagerAutomationService.class, "running").set(service, true);
            Class<?> trackerType = Class.forName(VillagerAutomationService.class.getName() + "$Tracker");
            var trackerConstructor = trackerType.getDeclaredConstructor(VillagerAutomationService.class, Villager.class);
            trackerConstructor.setAccessible(true);
            tracker = trackerConstructor.newInstance(service, villager);
            work = VillagerAutomationService.class.getDeclaredMethod("work", trackerType);
            work.setAccessible(true);
        }

        void work(int ticks) throws Exception { this.ticks = ticks; work.invoke(service, tracker); }
    }

    private static final class NativeFoodProbe {
        private int foodLevel = 12, reads;
        Object access(Villager ignored) { reads++; return this; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    private static <T> T proxy(Class<T> type, Calls calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> calls.invoke(method, args)));
    }
    @FunctionalInterface private interface Calls { Object invoke(Method method, Object[] args) throws Throwable; }
}
