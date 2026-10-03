package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import org.bukkit.World;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityChunkIndexTest {

    @ParameterizedTest
    @CsvSource({"536, 512", "0, 0", "-1, -1", "-537, 512", "536, -513"})
    void potsRetireOnlyTheirOwnChunkAndWorld(int chunkX, int chunkZ) throws Exception {
        verifyChunkLifecycle(true, chunkX, chunkZ);
    }

    @ParameterizedTest
    @CsvSource({"536, 512", "0, 0", "-1, -1", "-537, 512", "536, -513"})
    void boardsRetireOnlyTheirOwnChunkAndWorld(int chunkX, int chunkZ) throws Exception {
        verifyChunkLifecycle(false, chunkX, chunkZ);
    }

    private static void verifyChunkLifecycle(boolean pots, int chunkX, int chunkZ) throws Exception {
        World world = world(UUID.randomUUID());
        World otherWorld = world(UUID.randomUUID());
        BlockPosKey first = new BlockPosKey(chunkX * 16 + 1, 64, chunkZ * 16 + 1);
        BlockPosKey last = new BlockPosKey(chunkX * 16 + 15, 127, chunkZ * 16 + 15);
        BlockPosKey neighbour = new BlockPosKey((chunkX + 1) * 16, 64, chunkZ * 16 + 2);
        Class<?> behavior = pots ? CookingPotBlockBehavior.class : CuttingBoardBlockBehavior.class;
        try {
            Object firstEntity = register(pots, world, first);
            Object lastEntity = register(pots, world, last);
            Object neighbourEntity = register(pots, world, neighbour);
            Object otherEntity = register(pots, otherWorld, first);

            Map<BlockPosKey, ?> loaded = inChunk(pots, world, chunkX, chunkZ);
            assertEquals(Set.of(first, last), loaded.keySet());
            assertSame(firstEntity, loaded.get(first));
            assertSame(lastEntity, loaded.get(last));
            assertSame(neighbourEntity, inChunk(pots, world, chunkX + 1, chunkZ).get(neighbour));
            assertSame(otherEntity, inChunk(pots, otherWorld, chunkX, chunkZ).get(first));

            remove(pots, world, first);
            assertEquals(Set.of(last), inChunk(pots, world, chunkX, chunkZ).keySet());
            assertFalse(authoritative(behavior).get(world.getUID()).containsKey(first));
            remove(pots, world, last);
            assertTrue(inChunk(pots, world, chunkX, chunkZ).isEmpty());
            assertSame(neighbourEntity, inChunk(pots, world, chunkX + 1, chunkZ).get(neighbour));
            assertSame(otherEntity, inChunk(pots, otherWorld, chunkX, chunkZ).get(first));
            assertEquals(Set.of(neighbour), authoritative(behavior).get(world.getUID()).keySet());

            Map<Long, Set<BlockPosKey>> remainingIndex = chunkIndexes(behavior).get(world.getUID());
            assertEquals(1, remainingIndex.size(), "Retiring a chunk must remove its index bucket.");
            remove(pots, world, neighbour);
            assertFalse(chunkIndexes(behavior).containsKey(world.getUID()), "The last chunk must retire its world index.");
            remove(pots, otherWorld, first);
            assertFalse(chunkIndexes(behavior).containsKey(otherWorld.getUID()));
        } finally {
            for (World ownWorld : new World[]{world, otherWorld}) {
                authoritative(behavior).remove(ownWorld.getUID());
                chunkIndexes(behavior).remove(ownWorld.getUID());
            }
        }
    }

    private static Object register(boolean pots, World world, BlockPosKey position) throws Exception {
        if (!pots) {
            CuttingBoardBlockEntity board = new CuttingBoardBlockEntity(null, position, world);
            CuttingBoardBlockBehavior.putBlockEntity(world, position, board);
            return board;
        }
        CookingPotBlockEntity pot = new CookingPotBlockEntity(null, position, world, CookingPotLayout.DEFAULT, null);
        // Native controller loading is outside this unit test; exercise the actual registration index and public removal.
        authoritative(CookingPotBlockBehavior.class).computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>())
                .put(position, pot);
        Method indexAdd = CookingPotBlockBehavior.class.getDeclaredMethod("indexAdd", UUID.class, BlockPosKey.class);
        indexAdd.setAccessible(true);
        indexAdd.invoke(null, world.getUID(), position);
        return pot;
    }

    private static Map<BlockPosKey, ?> inChunk(boolean pots, World world, int chunkX, int chunkZ) {
        return pots ? CookingPotBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ)
                : CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, chunkX, chunkZ);
    }

    private static void remove(boolean pots, World world, BlockPosKey position) {
        if (pots) CookingPotBlockBehavior.removeBlockEntity(world, position, false);
        else CuttingBoardBlockBehavior.removeBlockEntity(world, position, false);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Map<BlockPosKey, Object>> authoritative(Class<?> behavior) throws Exception {
        Field field = behavior.getDeclaredField("worldBlockEntities");
        field.setAccessible(true);
        return (Map<UUID, Map<BlockPosKey, Object>>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Map<Long, Set<BlockPosKey>>> chunkIndexes(Class<?> behavior) throws Exception {
        Field field = behavior.getDeclaredField("chunkIndex");
        field.setAccessible(true);
        return (Map<UUID, Map<Long, Set<BlockPosKey>>>) field.get(null);
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "ChunkIndexWorld[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
