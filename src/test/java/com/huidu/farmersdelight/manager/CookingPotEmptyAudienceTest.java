package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CookingPotEmptyAudienceTest {
    @Test
    void initialEmptyAudienceIsQueriedOnceAndRefreshedNextTick() {
        UUID id = UUID.randomUUID();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger activityReads = new AtomicInteger();
        Chunk chunk = (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Chunk.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPlayersSeeingChunk")) {
                        reads.incrementAndGet();
                        return List.of();
                    }
                    throw new AssertionError("Unexpected chunk access: " + method.getName());
                });
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "isChunkLoaded" -> true;
                    case "getChunkAt" -> chunk;
                    default -> throw new AssertionError("Unexpected world access: " + method.getName());
                });
        CookingPotEffectManager effects = new CookingPotEffectManager(null);
        CookingPotBlockEntity active = new CookingPotBlockEntity(new BlockPosKey(0, 64, 0)) {
            @Override public boolean hasInput() { activityReads.incrementAndGet(); return true; }
        };
        for (int x = 0; x < 16; ++x) effects.emit(world, new BlockPosKey(x, 64, 0), active, true, null, 500L);
        assertEquals(1, reads.get());
        assertEquals(1, activityReads.get());
        effects.emit(world, new BlockPosKey(0, 64, 0), active, true, null, 501L);
        assertEquals(2, reads.get());
        assertEquals(2, activityReads.get());
        effects.cleanupChunk(id, ManagerSupport.chunkKey(0, 0));
        effects.emit(world, new BlockPosKey(0, 64, 0), active, true, null, 501L);
        assertEquals(3, reads.get());
        assertEquals(3, activityReads.get());
        effects.cleanupWorld(id);
        effects.emit(world, new BlockPosKey(0, 64, 0), active, true, null, 501L);
        assertEquals(4, reads.get());
        assertEquals(4, activityReads.get());
    }

    @Test
    void unloadedAudienceChunkIsNeverLoadedForParticles() {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> new UUID(0, 1);
                    case "isChunkLoaded" -> false;
                    default -> throw new AssertionError("An effect tried to access an unloaded chunk: " + method.getName());
                });
        new CookingPotEffectManager(null).emit(world, new BlockPosKey(0, 64, 0), activePot(), true, null, 500L);
    }

    private static CookingPotBlockEntity activePot() {
        return new CookingPotBlockEntity(new BlockPosKey(0, 64, 0)) {
            @Override public boolean hasInput() { return true; }
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyTrackedChunkSnapshotAvoidsRepeatedDensityAndAudienceWork() throws Exception {
        UUID id = UUID.randomUUID();
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return id;
                    throw new AssertionError("Unexpected repeated world lookup: " + method.getName());
                });
        CookingPotEffectManager effects = new CookingPotEffectManager(null);
        Class<?> contextType = Class.forName(CookingPotEffectManager.class.getName() + "$CookingPotFxContext");
        var constructor = contextType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object context = constructor.newInstance();
        var tick = contextType.getDeclaredField("budgetTick");
        tick.setAccessible(true);
        tick.setLong(context, 500L);
        var seeing = contextType.getDeclaredField("seeing");
        seeing.setAccessible(true);
        seeing.set(context, List.of());
        var chunks = CookingPotEffectManager.class.getDeclaredField("chunkFx");
        chunks.setAccessible(true);
        ((Map<UUID, Map<Long, Object>>) chunks.get(effects)).put(id,
                new ConcurrentHashMap<>(Map.of(ManagerSupport.chunkKey(0, 0), context)));
        CookingPotBlockEntity active = new CookingPotBlockEntity(new BlockPosKey(0, 64, 0)) {
            @Override public boolean hasInput() { throw new AssertionError("A known empty audience must not inspect the pot inventory"); }
        };
        for (int x = 0; x < 16; ++x) {
            effects.emit(world, new BlockPosKey(x, 64, 0), active, true, null, 500L);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void inactivePotDoesNotCreateAnyEffectContext() throws Exception {
        World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return new UUID(0, 1);
                    throw new AssertionError("An inactive pot must not inspect the audience: " + method.getName());
                });
        CookingPotEffectManager effects = new CookingPotEffectManager(null);
        effects.emit(world, new BlockPosKey(0, 64, 0), new CookingPotBlockEntity(new BlockPosKey(0, 64, 0)), true, null, 500L);
        var chunks = CookingPotEffectManager.class.getDeclaredField("chunkFx");
        chunks.setAccessible(true);
        assertEquals(0, ((Map<UUID, Map<Long, Object>>) chunks.get(effects)).size());
    }
}
