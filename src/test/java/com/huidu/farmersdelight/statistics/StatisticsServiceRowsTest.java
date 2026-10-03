package com.huidu.farmersdelight.statistics;

import com.huidu.farmersdelight.api.util.ShutdownBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsServiceRowsTest {
    @TempDir Path directory;
    private final Logger logger = Logger.getAnonymousLogger();

    @Test void repeatedOwnersReuseCanonicalRowsAndFlushAllFourDimensionsToSql() throws Exception {
        Path file = directory.resolve("canonical.sqlite");
        UUID player = UUID.randomUUID();
        var service = new StatisticsService(file, true, 60, logger);
        service.preload(player, "KnownPlayer"); barrier(service);
        service.committed(player, "SKILLET", "MINECRAFT:BEEF", 1);
        Object playerRow = row(service, player, "skillet", "minecraft:beef");
        Object serverRow = row(service, StatisticKey.SERVER, "skillet", "minecraft:beef");
        Object playerKey = field(playerRow, "key"), serverKey = field(serverRow, "key");
        for (int i = 0; i < 2000; ++i) service.committed(player, "skillet", "minecraft:beef", 1);
        assertSame(playerRow, row(service, player, "skillet", "minecraft:beef"));
        assertSame(serverRow, row(service, StatisticKey.SERVER, "skillet", "minecraft:beef"));
        assertSame(playerKey, field(playerRow, "key")); assertSame(serverKey, field(serverRow, "key"));
        assertEquals(2001, service.cached(player, "SKILLET", "MINECRAFT:BEEF").orElseThrow());
        assertEquals(0, service.cached(player, "skillet", "minecraft:unknown").orElseThrow());
        assertEquals(4, ((java.util.Set<?>) field(service, "pending")).size());
        service.shutdown(ShutdownBudget.ofMillis(5000, logger));
        try (var store = new SqliteStatisticStore(file)) {
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                Map<StatisticKey, Long> totals = store.read(actor);
                assertEquals(2001L, totals.get(new StatisticKey(actor, "skillet", "*")));
                assertEquals(2001L, totals.get(new StatisticKey(actor, "skillet", "minecraft:beef")));
            }
        }
    }

    @Test void nullAndServerActorsCountOnlyOnceAndInvalidDimensionsDoNotChangeAnyTotal() throws Exception {
        Path file = directory.resolve("validation.sqlite");
        var service = new StatisticsService(file, true, 60, logger); barrier(service);
        try {
            service.committed(StatisticKey.SERVER, "eat", "minecraft:apple", 2);
            service.committed(null, "eat", "minecraft:apple", 3);
            assertThrows(IllegalArgumentException.class, () -> service.committed(UUID.randomUUID(), " ", "minecraft:apple", 1));
            assertThrows(IllegalArgumentException.class, () -> service.committed(UUID.randomUUID(), "eat", "x".repeat(257), 1));
            service.committed(UUID.randomUUID(), "eat", "*", 500);
            service.committed(UUID.randomUUID(), "eat", null, 500);
            assertEquals(5, service.cached(StatisticKey.SERVER, "eat", "*").orElseThrow());
            assertEquals(5, service.cached(StatisticKey.SERVER, "eat", "minecraft:apple").orElseThrow());
            String expandingCase = "\u0130".repeat(256);
            assertThrows(IllegalArgumentException.class, () -> service.committed(null, expandingCase, "minecraft:apple", 1));
            assertThrows(IllegalArgumentException.class, () -> service.committed(null, "eat", expandingCase, 1));
            assertThrows(IllegalArgumentException.class, () -> new StatisticKey(StatisticKey.SERVER, expandingCase, "minecraft:apple"));
            assertThrows(IllegalArgumentException.class, () -> new StatisticKey(StatisticKey.SERVER, "eat", expandingCase));
            assertThrows(IllegalArgumentException.class, () -> service.cached(StatisticKey.SERVER,
                    expandingCase.toLowerCase(java.util.Locale.ROOT), "*"));
            assertEquals(5, service.cached(StatisticKey.SERVER, "eat", "*").orElseThrow());
            assertEquals(5, service.cached(StatisticKey.SERVER, "eat", "minecraft:apple").orElseThrow());
        } finally { service.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
        try (var sql = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var rows = sql.createStatement().executeQuery("SELECT COUNT(*) FROM totals")) {
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
        }
    }

    @Test void expandedUnicodeDimensionsAtTheCanonicalLimitSurviveRealSqlAndServiceRestart() throws Exception {
        Path file = directory.resolve("unicode-roundtrip.sqlite"); UUID player = UUID.randomUUID();
        String source = "\u0130".repeat(128), canonical = source.toLowerCase(java.util.Locale.ROOT);
        String invalid = "\u0130".repeat(256);
        assertEquals(256, canonical.length());
        var service = new StatisticsService(file, true, 60, logger);
        service.preload(player, "UnicodePlayer"); barrier(service);
        try {
            service.committed(player, source, source, 7);
            assertEquals(7, service.cached(player, source, "*").orElseThrow());
            assertEquals(7, service.cached(player, canonical, canonical).orElseThrow());
            assertThrows(IllegalArgumentException.class, () -> service.committed(player, invalid, source, 1));
            assertThrows(IllegalArgumentException.class, () -> service.committed(player, source, invalid, 1));
            assertThrows(IllegalArgumentException.class, () -> service.committed(player, "rejected_activity", invalid, 1));
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                assertEquals(7, service.cached(actor, source, "*").orElseThrow());
                assertEquals(7, service.cached(actor, source, source).orElseThrow());
                assertEquals(0, service.cached(actor, "rejected_activity", "*").orElseThrow());
            }
            assertEquals(4, ((java.util.Set<?>) field(service, "pending")).size());
            assertEquals(1, ((Map<?, ?>) field(((Map<?, ?>) field(service, "profiles")).get(player), "activities")).size());
        } finally { service.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
        try (var store = new SqliteStatisticStore(file)) {
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                Map<StatisticKey, Long> persisted = store.read(actor);
                assertEquals(2, persisted.size());
                assertEquals(7L, persisted.get(new StatisticKey(actor, source, "*")));
                assertEquals(7L, persisted.get(new StatisticKey(actor, canonical, canonical)));
            }
        }
        var restarted = new StatisticsService(file, true, 60, logger);
        restarted.preload(player, "UnicodePlayer"); barrier(restarted);
        try {
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                assertEquals(7, restarted.cached(actor, source, "*").orElseThrow());
                assertEquals(7, restarted.cached(actor, canonical, canonical).orElseThrow());
            }
            restarted.committed(player, canonical, canonical, 3);
            assertEquals(10, restarted.cached(player, source, source).orElseThrow());
            assertEquals(10, restarted.cached(StatisticKey.SERVER, source, "*").orElseThrow());
        } finally { restarted.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
        try (var store = new SqliteStatisticStore(file)) {
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                assertEquals(10L, store.read(actor).get(new StatisticKey(actor, source, "*")));
                assertEquals(10L, store.read(actor).get(new StatisticKey(actor, canonical, canonical)));
            }
        }
    }

    @Test void overflowInThePlayerDimensionCannotPartiallyIncrementTheServerDimension() throws Exception {
        Path file = directory.resolve("overflow.sqlite"); UUID player = UUID.randomUUID();
        try (var store = new SqliteStatisticStore(file)) {
            store.write(UUID.randomUUID(), Map.of(new StatisticKey(player, "eat", "*"), Long.MAX_VALUE,
                    new StatisticKey(player, "eat", "minecraft:apple"), Long.MAX_VALUE));
        }
        var service = new StatisticsService(file, true, 60, logger);
        service.preload(player, "LimitPlayer"); barrier(service);
        try {
            assertThrows(ArithmeticException.class, () -> service.committed(player, "eat", "minecraft:apple", 1));
            assertEquals(0, service.cached(StatisticKey.SERVER, "eat", "*").orElseThrow());
            assertEquals(0, service.cached(StatisticKey.SERVER, "eat", "minecraft:apple").orElseThrow());
            assertEquals(Long.MAX_VALUE, service.cached(player, "eat", "*").orElseThrow());
            assertTrue(((java.util.Set<?>) field(service, "pending")).isEmpty());
        } finally { service.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
        try (var store = new SqliteStatisticStore(file)) {
            assertTrue(store.read(StatisticKey.SERVER).isEmpty());
            assertEquals(Long.MAX_VALUE, store.read(player).get(new StatisticKey(player, "eat", "*")));
        }
    }

    @Test void failedSqlBatchRetriesBeforeLaterPrimitiveDeltasAndNeverReaddsPersistedTotals() throws Exception {
        Path file = directory.resolve("retry.sqlite"); UUID player = UUID.randomUUID();
        try (var store = new SqliteStatisticStore(file)) {
            store.write(UUID.randomUUID(), Map.of(new StatisticKey(player, "skillet", "*"), 7L,
                    new StatisticKey(player, "skillet", "minecraft:beef"), 7L,
                    new StatisticKey(StatisticKey.SERVER, "skillet", "*"), 11L,
                    new StatisticKey(StatisticKey.SERVER, "skillet", "minecraft:beef"), 11L));
        }
        var service = new StatisticsService(file, true, 60, logger); service.preload(player, "RetryPlayer"); barrier(service);
        try {
            service.committed(player, "skillet", "minecraft:beef", 2);
            try (var blocker = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var sql = blocker.createStatement()) {
                sql.execute("BEGIN IMMEDIATE");
                writer(service).submit(service::flush).get(5, TimeUnit.SECONDS);
                assertNotNull(field(service, "retry"));
                service.committed(player, "skillet", "minecraft:beef", 3);
                assertEquals(12, service.cached(player, "skillet", "*").orElseThrow());
                sql.execute("COMMIT");
            }
            writer(service).submit(service::flush).get(5, TimeUnit.SECONDS);
            assertNull(field(service, "retry"));
            writer(service).submit(service::flush).get(5, TimeUnit.SECONDS);
        } finally { service.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
        try (var store = new SqliteStatisticStore(file)) {
            assertEquals(12L, store.read(player).get(new StatisticKey(player, "skillet", "*")));
            assertEquals(12L, store.read(player).get(new StatisticKey(player, "skillet", "minecraft:beef")));
            assertEquals(16L, store.read(StatisticKey.SERVER).get(new StatisticKey(StatisticKey.SERVER, "skillet", "*")));
            assertEquals(16L, store.read(StatisticKey.SERVER).get(new StatisticKey(StatisticKey.SERVER, "skillet", "minecraft:beef")));
        }
    }

    @Test void parallelOwnersAndFlushesPreserveExactSqlAndCachedAmounts() throws Exception {
        Path file = directory.resolve("parallel.sqlite"); UUID player = UUID.randomUUID();
        var service = new StatisticsService(file, true, 60, logger); service.preload(player, "ParallelPlayer"); barrier(service);
        ExecutorService owners = Executors.newFixedThreadPool(3);
        var start = new CountDownLatch(1);
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int n = 0; n < 3; ++n) jobs.add(owners.submit(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                for (int i = 0; i < 500; ++i) service.committed(player, "cutting_board", "minecraft:carrot", 1);
            }));
            start.countDown();
            for (int i = 0; i < 20; ++i) writer(service).submit(service::flush).get(5, TimeUnit.SECONDS);
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
        } finally {
            owners.shutdownNow(); service.shutdown(ShutdownBudget.ofMillis(5000, logger));
        }
        assertEquals(1500, service.cached(player, "cutting_board", "*").orElseThrow());
        try (var store = new SqliteStatisticStore(file)) {
            for (UUID actor : java.util.List.of(player, StatisticKey.SERVER)) {
                assertEquals(1500L, store.read(actor).get(new StatisticKey(actor, "cutting_board", "*")));
                assertEquals(1500L, store.read(actor).get(new StatisticKey(actor, "cutting_board", "minecraft:carrot")));
            }
        }
    }

    @Test void unchangedAndReassignedNamesPreserveOwnershipWithoutExtraProfileRows() throws Exception {
        var service = new StatisticsService(directory.resolve("names.sqlite"), true, 60, logger);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        try {
            service.online(first, "SharedName"); service.preload(second, "SharedName"); barrier(service);
            assertEquals(second, service.cachedPlayer("SHAREDNAME"));
            service.preload(first, "RenamedPlayer");
            assertEquals(second, service.cachedPlayer("SharedName"));
            assertEquals(first, service.cachedPlayer("RenamedPlayer"));
            assertEquals(first, service.cachedPlayer(first.toString()));
            assertEquals(UUID.fromString("1-1-1-1-1"), service.cachedPlayer("1-1-1-1-1"));
            assertNull(service.cachedPlayer("unknown-name"));
            Object profile = ((Map<?, ?>) field(service, "profiles")).get(first);
            Object canonicalName = field(profile, "name");
            for (int i = 0; i < 100; ++i) service.preload(first, "RENAMEDPLAYER");
            assertSame(canonicalName, field(profile, "name"));
            assertEquals(2, ((Map<?, ?>) field(service, "names")).size());
            assertEquals(0, ((Map<?, ?>) field(profile, "activities")).size());
        } finally { service.shutdown(ShutdownBudget.ofMillis(5000, logger)); }
    }

    @Test void fullWarmupAdmissionDoesNotDropCommittedDeltasOrGrowIdleProfilePlaceholders() throws Exception {
        Path file = directory.resolve("warmup-limit.sqlite");
        var service = new StatisticsService(file, true, 60, logger); barrier(service);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        UUID overflowPlayer = UUID.randomUUID();
        var blocker = writer(service).submit(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 300; ++i) service.preload(UUID.randomUUID(), "WarmupPlayer" + i);
            assertEquals(256, field(service, "queuedWarmups"));
            assertEquals(257, ((Map<?, ?>) field(service, "profiles")).size());
            assertNull(service.cachedPlayer("WarmupPlayer256"));
            service.committed(overflowPlayer, "eat", "minecraft:apple", 3);
            assertEquals(256, field(service, "queuedWarmups"));
            release.countDown(); blocker.get(5, TimeUnit.SECONDS);
            barrier(service);
        } finally {
            release.countDown(); service.shutdown(ShutdownBudget.ofMillis(5000, logger));
        }
        try (var store = new SqliteStatisticStore(file)) {
            assertEquals(3L, store.read(overflowPlayer).get(new StatisticKey(overflowPlayer, "eat", "*")));
            assertEquals(3L, store.read(StatisticKey.SERVER).get(new StatisticKey(StatisticKey.SERVER, "eat", "*")));
        }
    }

    private static void barrier(StatisticsService service) throws Exception { writer(service).submit(() -> {}).get(5, TimeUnit.SECONDS); }
    private static ExecutorService writer(StatisticsService service) throws Exception { return (ExecutorService) field(service, "writer"); }
    private static Object row(StatisticsService service, UUID player, String activity, String item) throws Exception {
        Object profile = ((Map<?, ?>) field(service, "profiles")).get(player);
        Object dimension = ((Map<?, ?>) field(profile, "activities")).get(activity);
        return ((Map<?, ?>) field(dimension, "items")).get(item);
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
