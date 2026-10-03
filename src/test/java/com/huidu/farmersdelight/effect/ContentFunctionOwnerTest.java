package com.huidu.farmersdelight.effect;

import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ContentFunctionOwnerTest {
    @Test void asynchronousInvocationEvaluatesIntDoubleAndTheMutationOnlyOnTheCurrentOwner() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger providerReads = new AtomicInteger(), mutation = new AtomicInteger();
        NumberProvider provider = provider(fixture.owner, providerReads);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> fixture.gate.run(fixture.player, () -> {
                mutation.set(provider.getInt(null) + (int) provider.getDouble(null));
            })).get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, providerReads.get(), "A worker must not evaluate a provider backed by live player/world data");
        assertEquals(0, mutation.get());
        fixture.queue.remove().run();
        assertEquals(14, mutation.get());
        assertEquals(2, providerReads.get());
        fixture.gate.run(fixture.player, () -> mutation.set(provider.getInt(null)));
        assertEquals(7, mutation.get());
        assertTrue(fixture.queue.isEmpty(), "An already-owned invocation does not allocate another scheduler task");
    }

    @Test void staleReloadDisabledOfflineAndDeadCallbacksNeverEvaluateProviders() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger reads = new AtomicInteger();
        NumberProvider provider = provider(fixture.owner, reads);
        for (int condition = 0; condition < 4; condition++) {
            fixture.available.set(true); fixture.online.set(true); fixture.dead.set(false);
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> fixture.gate.run(fixture.player, () -> provider.getDouble(null))).get(2, TimeUnit.SECONDS);
            }
            switch (condition) {
                case 0 -> fixture.generation.incrementAndGet();
                case 1 -> fixture.available.set(false);
                case 2 -> fixture.online.set(false);
                case 3 -> fixture.dead.set(true);
            }
            fixture.queue.remove().run();
            assertEquals(0, reads.get(), "Cancelled lifecycle condition " + condition + " cannot query live numeric context");
        }
    }

    private static NumberProvider provider(Thread owner, AtomicInteger reads) {
        return new NumberProvider() {
            public float getFloat(Context context) { return (float) getDouble(context); }
            public double getDouble(Context context) { assertSame(owner, Thread.currentThread()); reads.incrementAndGet(); return 7; }
        };
    }
    private static final class Fixture {
        final Thread owner = Thread.currentThread();
        final ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(4);
        final AtomicBoolean available = new AtomicBoolean(true), online = new AtomicBoolean(true), dead = new AtomicBoolean();
        final AtomicLong generation = new AtomicLong();
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> {
            assertSame(owner, Thread.currentThread(), "Even lifecycle/player checks wait for the owner");
            return switch (method.getName()) {
                case "isOnline" -> online.get();
                case "isDead" -> dead.get();
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        final ContentFunctionOwner gate = new ContentFunctionOwner(new ContentFunctionOwner.Access() {
            public boolean available() { return available.get(); }
            public long generation() { return generation.get(); }
            public boolean owned(Player ignored) { return Thread.currentThread() == owner; }
            public void schedule(Player ignored, Runnable action) { queue.add(action); }
        });
    }
}
