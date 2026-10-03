package com.huidu.farmersdelight.statistics;

import com.huidu.farmersdelight.api.util.ShutdownBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class SqliteStatisticStoreTest {
    @TempDir Path folder;

    @Test void uncertainCommitRetryDoesNotDoubleCountAndRestartKeepsTotals() throws Exception {
        UUID player = UUID.randomUUID(), batch = UUID.randomUUID();
        var key = new StatisticKey(player, "cutting_board", "farmersdelight:tomato");
        Path file = folder.resolve("stats.sqlite");
        try (var store = new SqliteStatisticStore(file)) {
            store.write(batch, Map.of(key, 3L));
            store.write(batch, Map.of(key, 3L));
            assertEquals(3L, store.read(player).get(key));
        }
        try (var reopened = new SqliteStatisticStore(file)) {
            assertEquals(3L, reopened.read(player).get(key));
        }
    }

    @Test void failedBatchRollsBackReceiptAndEveryDimension() throws Exception {
        UUID player = UUID.randomUUID(), batch = UUID.randomUUID();
        var key = new StatisticKey(player, "skillet", "minecraft:cooked_beef");
        try (var store = new SqliteStatisticStore(folder.resolve("stats.sqlite"))) {
            assertThrows(IllegalArgumentException.class, () -> store.write(batch, Map.of(key, -1L)));
            assertTrue(store.read(player).isEmpty());
            store.write(batch, Map.of(key, 2L));
            assertEquals(2L, store.read(player).get(key));
        }
    }

    @Test void latePlayerWarmupDoesNotMergePreviouslyCommittedDeltasTwice() throws Exception {
        Path file = folder.resolve("stats.sqlite");
        UUID player = UUID.randomUUID();
        Logger log = Logger.getAnonymousLogger();
        var first = new StatisticsService(file, true, 30L, log);
        first.committed(player, "skillet", "minecraft:cooked_beef", 2L);
        first.shutdown(ShutdownBudget.ofMillis(5000L, log));
        var second = new StatisticsService(file, true, 30L, log);
        second.committed(player, "skillet", "minecraft:cooked_beef", 3L);
        // Flushing also warms affected player caches, before committing their new deltas.
        second.shutdown(ShutdownBudget.ofMillis(5000L, log));
        assertEquals(5L, second.cached(player, "skillet", "*").orElseThrow());
        assertEquals(5L, second.cached(StatisticKey.SERVER, "skillet", "*").orElseThrow());
        assertEquals("-", StatisticPlaceholders.resolve(second, null, "stats_player__unknown__skillet"));
    }

    @Test void concurrentOwnerCommitsAndShutdownNeverLeaveAcceptedCacheTotalsUnwritten() throws Exception {
        Path file = folder.resolve("concurrent.sqlite");
        Logger log = Logger.getAnonymousLogger();
        UUID player = UUID.randomUUID();
        var service = new StatisticsService(file, true, 30L, log);
        service.committed(player, "cutting_board", "minecraft:carrot", 1L);
        var start = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int owner = 0; owner < 3; owner++) tasks.add(workers.submit(() -> {
            try { start.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            for (int i = 0; i < 100; i++) service.committed(player, "cutting_board", "minecraft:carrot", 1L);
        }));
        tasks.add(workers.submit(() -> {
            try { start.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            service.shutdown(ShutdownBudget.ofMillis(5000L, log));
        }));
        start.countDown();
        try { for (var task : tasks) task.get(10L, java.util.concurrent.TimeUnit.SECONDS); }
        finally { workers.shutdownNow(); }
        long accepted = service.cached(player, "cutting_board", "*").orElseThrow();
        try (var stored = new SqliteStatisticStore(file)) {
            assertEquals(accepted, stored.read(player).get(new StatisticKey(player, "cutting_board", "*")));
        }
    }

    @Test void oldOfflineProfilesAreEvictedWithoutDeletingStatisticsOrEvictingOnlinePlayers() throws Exception {
        Path file = folder.resolve("bounded.sqlite");
        var players = new java.util.ArrayList<UUID>();
        var seed = new java.util.LinkedHashMap<StatisticKey, Long>();
        for (int i = 0; i < 80; i++) {
            UUID player = UUID.randomUUID(); players.add(player);
            seed.put(new StatisticKey(player, "eat", "*"), 7L);
        }
        try (var store = new SqliteStatisticStore(file)) { store.write(UUID.randomUUID(), seed); }
        Logger log = Logger.getAnonymousLogger();
        var service = new StatisticsService(file, true, 30L, log);
        service.cachePlayerLimit(64);
        service.online(players.getFirst(), "PresentPlayer");
        for (int i = 1; i < players.size(); i++) service.preload(players.get(i), "FormerPlayer" + i);
        var writerField = StatisticsService.class.getDeclaredField("writer"); writerField.setAccessible(true);
        ((java.util.concurrent.ExecutorService) writerField.get(service)).submit(() -> {}).get(10L, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(7L, service.cached(players.getFirst(), "eat", "*").orElseThrow());
        assertTrue(service.cached(players.get(1), "eat", "*").isEmpty());
        assertNull(service.cachedPlayer("FormerPlayer1"));
        assertEquals(7L, service.cached(players.getLast(), "eat", "*").orElseThrow());
        service.shutdown(ShutdownBudget.ofMillis(5000L, log));
        try (var stored = new SqliteStatisticStore(file)) {
            assertEquals(7L, stored.read(players.get(1)).get(new StatisticKey(players.get(1), "eat", "*")));
        }
    }
}
