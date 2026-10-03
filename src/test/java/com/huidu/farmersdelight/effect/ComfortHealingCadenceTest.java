package com.huidu.farmersdelight.effect;

import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class ComfortHealingCadenceTest {
    @Test void alignedAndExactReplacementDurationsBothApplyTwoActualHeals() {
        Fixture aligned = new Fixture(), replacement = new Fixture();
        aligned.runDuration(160, 4); replacement.runDuration(161, 4);
        assertEquals(3d, aligned.health); assertEquals(3d, replacement.health);
        assertEquals(2, aligned.heals); assertEquals(2, replacement.heals);
        assertEquals(160, aligned.firstRemaining); assertEquals(161, replacement.firstRemaining);
    }
    @Test void aVisitIntervalThatDoesNotDivideTheHealPeriodCannotSkipTheHealthChanges() {
        Fixture fixture = new Fixture(); fixture.runDuration(161, 7);
        assertEquals(3d, fixture.health); assertEquals(2, fixture.heals);
        fixture = new Fixture(); fixture.runDuration(160, 7);
        assertEquals(3d, fixture.health); assertEquals(2, fixture.heals);
    }
    @Test void regenerationAndSaturationStillBlockTheActualHealthMutation() {
        Fixture regeneration = new Fixture(); regeneration.regenerating = true;
        regeneration.runDuration(161, 7);
        assertEquals(1d, regeneration.health); assertEquals(0, regeneration.heals);
        Fixture saturated = new Fixture(); saturated.saturation = 1f;
        saturated.runDuration(161, 7);
        assertEquals(1d, saturated.health); assertEquals(0, saturated.heals);
    }
    @Test void expirationHasNoZeroBoundaryHealAndAStalledVisitStillRespectsTheMaximumHealth() {
        Fixture shortEffect = new Fixture(); shortEffect.runDuration(79, 7);
        assertEquals(1d, shortEffect.health); assertEquals(0, shortEffect.heals);
        EffectManager.tickComfort(shortEffect.player, 0, 7, false);
        assertEquals(1d, shortEffect.health); assertEquals(0, shortEffect.heals);
        Fixture stalled = new Fixture(); stalled.health = 19.5;
        EffectManager.tickComfort(stalled.player, 161, 200, false);
        assertEquals(20d, stalled.health); assertEquals(1, stalled.heals);
    }
    private static final class Fixture {
        double health = 1d; float saturation; int heals, firstRemaining; boolean regenerating;
        final AttributeInstance maximum = proxy(AttributeInstance.class, (method, args) -> method.getName().equals("getValue") ? 20d : value(method.getReturnType()));
        final Player player = proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getHealth" -> health;
            case "setHealth" -> { health = (double) args[0]; heals++; yield null; }
            case "getSaturation" -> saturation;
            case "getAttribute" -> maximum;
            case "hasPotionEffect" -> regenerating;
            default -> value(method.getReturnType());
        });
        void runDuration(int remaining, int elapsed) {
            firstRemaining = remaining;
            while (remaining > 0) {
                // The production owner supplies this same observed regeneration boolean. No Bukkit
                // global registries or artificial potion implementation is needed to test healing.
                EffectManager.tickComfort(player, remaining, elapsed, player.hasPotionEffect(null));
                remaining = Math.max(0, remaining - elapsed);
            }
        }
    }
    private interface Handler { Object invoke(Method method, Object[] args); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> handler.invoke(method, args));
    }
    private static Object value(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false; if (type == int.class) return 0;
        if (type == long.class) return 0L; if (type == double.class) return 0d;
        if (type == float.class) return 0f; if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0; if (type == char.class) return '\0';
        return null;
    }
}
