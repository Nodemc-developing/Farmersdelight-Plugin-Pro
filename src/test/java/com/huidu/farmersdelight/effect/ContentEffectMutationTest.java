package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.api.event.FarmersDelightBuffChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContentEffectMutationTest {
    private final List<FarmersDelightBuffChangeEvent> transitions = new ArrayList<>();
    private final Map<String, CustomBuff> previous = new HashMap<>();
    private Field serverField;
    private Server originalServer;
    private Player player;
    private boolean previousEnabled;

    @BeforeEach void ownerFixture() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
        originalServer = (Server) serverField.get(null);
        PluginManager manager = proxy(PluginManager.class, (method, args) -> {
            if (method.getName().equals("callEvent") && args[0] instanceof FarmersDelightBuffChangeEvent transition) transitions.add(transition);
            return value(method.getReturnType());
        });
        serverField.set(null, proxy(Server.class, (method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            case "getPluginManager" -> manager;
            default -> value(method.getReturnType());
        }));
        UUID id = UUID.randomUUID();
        player = proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "effect-fixture";
            case "getActivePotionEffects" -> List.of();
            default -> value(method.getReturnType());
        });
        previousEnabled = CustomBuffRegistry.isSystemEnabled();
        CustomBuffRegistry.setSystemEnabled(true);
        EffectManager.configure(null);
    }

    @AfterEach void restoreFixture() throws Exception {
        EffectManager.clearPlayer(player);
        EffectListener.untrackPlayer(player.getUniqueId());
        CustomBuffRegistry.forget(player);
        for (var entry : previous.entrySet()) {
            CustomBuffRegistry.unregister(entry.getKey());
            if (entry.getValue() != null) CustomBuffRegistry.register(entry.getValue());
        }
        CustomBuffRegistry.setSystemEnabled(previousEnabled);
        serverField.set(null, originalServer);
    }

    @Test void preciseAliasesRemoveOnlyTheirOwnActiveCustomEffectAndSyncTheTransition() {
        install(new CustomBuff() {
            public String id() { return "farmersdelight:comfort"; }
            public boolean isActive(Player ignored) { return EffectManager.hasComfort(player); }
            public int level(Player ignored) { return EffectManager.comfortLevel(player); }
            public int remainingSeconds(Player ignored) { return EffectManager.comfortRemainingSeconds(player); }
            public void remove(Player ignored) { EffectManager.removeComfort(player); }
        });
        TestBuff unrelated = install(new TestBuff("fixture:discomfort", false, false));
        EffectManager.applyComfort(player, 30, 3);
        CustomBuffRegistry.syncState(player, "farmersdelight:comfort");
        mutate(ContentEffectFunction.Action.REMOVE, "farmersdelight:comfort_effect", 0, 0, 0, false);
        assertFalse(EffectManager.hasComfort(player));
        assertEquals(2, unrelated.level);
        assertEquals(2, transitions.size());
        assertTrue(transitions.getLast().isLost());
        assertEquals("farmersdelight:comfort", transitions.getLast().getBuffId());
        assertEquals("fixture:nourishment_effect", ContentEffectFunction.canonicalEffectId("fixture:nourishment_effect"));
        assertEquals("farmersdelight:nourishment", ContentEffectFunction.canonicalEffectId("farmersdelight:nourishment_effect"));
    }

    @Test void builtinUpgradeKeepsExactTicksAndCanReplaceWithALowerLevelOrShorterDuration() {
        EffectManager.applyComfort(player, 30, 3);
        mutate(ContentEffectFunction.Action.UPGRADE, "farmersdelight:comfort_effect", 1, 4, 0, false);
        assertEquals(4, EffectManager.comfortLevel(player));
        assertEquals(600, EffectManager.effectTicks(player, "farmersdelight:comfort"));
        mutate(ContentEffectFunction.Action.UPGRADE, "farmersdelight:comfort", Integer.MIN_VALUE, 4, 41, false);
        assertEquals(1, EffectManager.comfortLevel(player));
        assertEquals(41, EffectManager.effectTicks(player, "farmersdelight:comfort"));
        EffectManager.removeComfort(player);
        mutate(ContentEffectFunction.Action.UPGRADE, "farmersdelight:comfort", 1, 4, 200, false);
        assertFalse(EffectManager.hasComfort(player), "An upgrade must not create an absent effect");
    }

    @Test void addonUpgradeUsesItsPublicSecondsContractWithoutChangingOtherRegistrations() {
        TestBuff buff = install(new TestBuff("fixture:upgrade", false, false));
        TestBuff other = install(new TestBuff("fixture:other", false, false));
        mutate(ContentEffectFunction.Action.UPGRADE, buff.id, 1, 4, 21, false);
        assertEquals(3, buff.level);
        assertEquals(2, buff.seconds);
        mutate(ContentEffectFunction.Action.UPGRADE, buff.id, 1, 4, 0, false);
        assertEquals(4, buff.level);
        assertEquals(2, buff.seconds);
        assertEquals(2, other.level);
        assertEquals(9, other.seconds);
    }

    @Test void harmfulOnlySelectsTheActualCustomHarmfulFlagAndIsolatesBrokenAddonReporters() {
        TestBuff harmful = install(new TestBuff("fixture:harmful", true, false));
        TestBuff beneficial = install(new TestBuff("fixture:beneficial", false, false));
        install(new TestBuff("fixture:broken", false, false) { @Override public boolean isHarmful() { throw new IllegalStateException("bad addon"); } });
        mutate(ContentEffectFunction.Action.REMOVE_RANDOM, "", 0, 0, 0, true);
        assertEquals(0, harmful.level);
        assertEquals(2, beneficial.level);
    }

    @Test void lowPriorityCustomEffectsWaitForOrdinaryCandidatesButRemainACleanserFallback() {
        TestBuff ordinary = install(new TestBuff("fixture:ordinary", false, false));
        TestBuff low = install(new TestBuff("fixture:low", false, true));
        mutate(ContentEffectFunction.Action.REMOVE_RANDOM, "", 0, 0, 0, false);
        assertEquals(0, ordinary.level);
        assertEquals(2, low.level);
        mutate(ContentEffectFunction.Action.REMOVE_RANDOM, "", 0, 0, 0, false);
        assertEquals(0, low.level);
    }

    @Test void largePositiveDurationCannotOverflowAnActiveEffectIntoNegativeTicks() {
        EffectManager.applyComfort(player, Integer.MAX_VALUE, 2);
        assertEquals(Integer.MAX_VALUE, EffectManager.effectTicks(player, "farmersdelight:comfort"));
        assertEquals(2, EffectManager.comfortLevel(player));
    }

    @Test void asynchronousTeleportOriginRejectsMovementOrWorldChangesAndAllowsLookingAround() {
        World original = world(UUID.randomUUID()), other = world(UUID.randomUUID());
        Location start = new Location(original, 1, 65, -2);
        var origin = ContentEffectFunction.TeleportOrigin.capture(start);
        assertTrue(origin.matches(start.clone()));
        assertTrue(origin.matches(new Location(original, 1, 65, -2, 90, 30)));
        assertFalse(origin.matches(start.clone().add(.01, 0, 0)));
        assertFalse(origin.matches(start.clone().add(0, -1, 0)));
        assertFalse(origin.matches(new Location(other, 1, 65, -2)));
        assertFalse(origin.matches(new Location(null, 1, 65, -2)));
        assertFalse(ContentEffectFunction.safeTeleportMaterial("CAMPFIRE"));
        assertFalse(ContentEffectFunction.safeTeleportMaterial("SOUL_CAMPFIRE"));
    }

    private void mutate(ContentEffectFunction.Action action, String id, int increment, int maximum, int ticks, boolean harmfulOnly) {
        ContentEffectFunction.mutateEffects(player, action, id, increment, maximum, ticks, harmfulOnly);
    }
    private <T extends CustomBuff> T install(T buff) {
        previous.putIfAbsent(buff.id(), CustomBuffRegistry.byId(buff.id()));
        CustomBuffRegistry.register(buff); return buff;
    }
    private static class TestBuff implements CustomBuff {
        final String id; final boolean harmful, low;
        int level = 2, seconds = 9;
        TestBuff(String id, boolean harmful, boolean low) { this.id = id; this.harmful = harmful; this.low = low; }
        public String id() { return id; }
        public boolean isActive(Player ignored) { return level > 0; }
        public void remove(Player ignored) { level = 0; }
        public int level(Player ignored) { return level; }
        public int remainingSeconds(Player ignored) { return seconds; }
        public boolean isHarmful() { return harmful; }
        public boolean isLowPriority() { return low; }
        public boolean apply(Player ignored, int newLevel, int durationSeconds) { level = newLevel; seconds = durationSeconds; return true; }
    }
    private static World world(UUID id) { return proxy(World.class, (method, args) -> method.getName().equals("getUID") ? id : value(method.getReturnType())); }
    private interface Handler { Object invoke(java.lang.reflect.Method method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> handler.invoke(method, args));
    }
    private static Object value(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
