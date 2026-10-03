package com.huidu.farmersdelight.statistics;

import com.huidu.farmersdelight.api.util.ShutdownBudget;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StatisticsService {
    private record Batch(UUID id, Map<StatisticKey, Long> changes, Set<UUID> players) { }
    private static final class Profile {
        final UUID player;
        final Map<String, Activity> activities = new HashMap<>();
        boolean loaded, queued, online;
        int dirtyRows;
        String name;
        Profile(UUID player) { this.player = player; }
    }
    private static final class Activity {
        final Row total;
        final String sourceActivity;
        final Map<String, Row> items = new HashMap<>();
        Activity(Profile profile, String activity) { sourceActivity = activity; total = new Row(profile, activity, "*"); }
    }
    private static final class Row {
        final Profile profile;
        final StatisticKey key;
        long total, delta;
        boolean dirty;
        Row(Profile profile, String activity, String item) {
            this.profile = profile;
            key = new StatisticKey(profile.player, activity, item);
        }
    }
    private static final int MAX_QUEUED_WARMUPS = 256;
    private final Object lock = new Object();
    private final Set<Row> pending = new HashSet<>();
    private final Map<String, UUID> names = new HashMap<>();
    private final LinkedHashMap<UUID, Profile> profiles = new LinkedHashMap<>(16, .75f, true);
    private int queuedWarmups;
    private int loadedProfiles;
    private final ScheduledExecutorService writer;
    private final SqliteStatisticStore store;
    private final Logger logger;
    private volatile boolean enabled;
    private volatile boolean accepting = true;
    private volatile Batch retry;
    private volatile int profileLimit = 2048;
    private java.util.concurrent.ScheduledFuture<?> flushTask;
    private long flushInterval;

    public StatisticsService(Path file, boolean enabled, long flushSeconds, Logger logger) {
        this.enabled = enabled;
        this.logger = logger;
        this.store = new SqliteStatisticStore(file);
        this.writer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Farmersdelight-statistics");
            thread.setDaemon(true);
            return thread;
        });
        configure(enabled, flushSeconds);
    }

    public boolean enabled() { return enabled; }

    public synchronized void configure(boolean enabled, long flushSeconds) {
        if (!accepting) return;
        long interval = Math.max(1L, flushSeconds);
        if (flushTask != null && this.enabled == enabled && flushInterval == interval) return;
        this.enabled = enabled;
        if (flushTask != null) flushTask.cancel(false);
        if (enabled) preload(StatisticKey.SERVER, null);
        flushInterval = interval;
        flushTask = writer.scheduleWithFixedDelay(this::flush, interval, interval, TimeUnit.SECONDS);
    }

    public void cachePlayerLimit(int value) { profileLimit = Math.max(64, Math.min(50000, value)); }

    public synchronized void online(UUID player, String name) {
        if (!accepting || !enabled || player == null) return;
        synchronized (lock) {
            Profile profile = profile(player);
            profile.online = true;
            preload(profile, name);
        }
    }

    public void offline(UUID player) {
        synchronized (lock) {
            Profile profile = profiles.get(player);
            if (profile != null) profile.online = false;
        }
    }

    public synchronized void preload(UUID player, String name) {
        if (!enabled || player == null || !accepting) return;
        synchronized (lock) {
            Profile profile = profiles.get(player);
            if (profile == null) {
                if (queuedWarmups >= MAX_QUEUED_WARMUPS) return;
                profile = profile(player);
            }
            preload(profile, name);
        }
    }

    private void preload(Profile profile, String name) {
        if (!profile.loaded && !profile.queued && queuedWarmups >= MAX_QUEUED_WARMUPS) return;
        if (name != null) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (!normalized.equals(profile.name)) {
                if (profile.name != null) names.remove(profile.name, profile.player);
                profile.name = normalized;
            }
            if (!profile.player.equals(names.get(normalized))) names.put(normalized, profile.player);
        }
        if (profile.loaded || profile.queued) return;
        profile.queued = true;
        ++queuedWarmups;
        queueWarmup(profile);
    }

    private void queueWarmup(Profile profile) {
        writer.execute(() -> {
            try {
                ensureLoaded(profile.player);
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Statistics cache could not be loaded for " + profile.player, failure);
            } finally {
                synchronized (lock) {
                    if (profile.queued) { profile.queued = false; --queuedWarmups; }
                }
            }
        });
    }

    private void ensureLoaded(UUID player) throws Exception {
        synchronized (lock) { if (profile(player).loaded) return; }
        Map<StatisticKey, Long> persisted = store.read(player);
        synchronized (lock) {
            Profile profile = profile(player);
            // Validate the whole warm merge before changing totals, so a failed load cannot merge twice.
            for (var entry : persisted.entrySet()) Math.addExact(row(profile, entry.getKey().activity(), entry.getKey().item()).total, entry.getValue());
            persisted.forEach((key, amount) -> {
                Row row = row(profile, key.activity(), key.item());
                row.total += amount;
            });
            profile.loaded = true;
            ++loadedProfiles;
        }
        trimOfflineProfiles();
    }

    /** Call after the operation commits on its owner thread. */
    public synchronized void committed(UUID player, String activity, String item, long amount) {
        if (!enabled || !accepting || amount <= 0 || item == null || "*".equals(item)) return;
        String normalizedActivity = normalized(activity), normalizedItem = normalized(item);
        synchronized (lock) {
            Profile server = profile(StatisticKey.SERVER);
            Activity serverActivity = activity(server, normalizedActivity, activity);
            Row serverTotal = serverActivity.total, serverItem = item(serverActivity, normalizedItem, item);
            Profile participant = player == null || StatisticKey.SERVER.equals(player) ? null : profile(player);
            Activity playerActivity = participant == null ? null : activity(participant, normalizedActivity, activity);
            Row playerTotal = playerActivity == null ? null : playerActivity.total;
            Row playerItem = playerActivity == null ? null : item(playerActivity, normalizedItem, item);
            checkedAdd(serverTotal, amount); checkedAdd(serverItem, amount);
            if (participant != null) { checkedAdd(playerTotal, amount); checkedAdd(playerItem, amount); }
            add(serverTotal, amount); add(serverItem, amount);
            if (participant != null) {
                add(playerTotal, amount); add(playerItem, amount);
                if (!participant.loaded && !participant.queued) preload(participant, null);
            }
        }
    }

    private static String normalized(String value) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException("Invalid statistic dimension");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() > 256) throw new IllegalArgumentException("Invalid normalized statistic dimension");
        return normalized;
    }

    private Profile profile(UUID player) {
        Profile profile = profiles.get(player);
        if (profile == null) { profile = new Profile(player); profiles.put(player, profile); }
        return profile;
    }

    private static Activity activity(Profile profile, String name, String source) {
        Activity activity = profile.activities.get(name);
        if (activity == null) { activity = new Activity(profile, source); profile.activities.put(name, activity); }
        return activity;
    }

    private static Row item(Activity activity, String name, String source) {
        Row row = activity.items.get(name);
        if (row == null) {
            row = new Row(activity.total.profile, activity.sourceActivity, source);
            activity.items.put(name, row);
        }
        return row;
    }

    private static Row row(Profile profile, String activity, String item) {
        Activity dimension = activity(profile, activity, activity);
        return "*".equals(item) ? dimension.total : item(dimension, item, item);
    }

    private static void checkedAdd(Row row, long amount) {
        Math.addExact(row.total, amount);
        Math.addExact(row.delta, amount);
    }

    private void add(Row row, long amount) {
        row.total += amount;
        row.delta += amount;
        if (!row.dirty) { row.dirty = true; pending.add(row); ++row.profile.dirtyRows; }
    }

    public OptionalLong cached(UUID player, String activity, String item) {
        if (!enabled || player == null) return OptionalLong.empty();
        synchronized (lock) {
            Profile profile = profiles.get(player);
            if (profile == null || !profile.loaded) return OptionalLong.empty();
            String normalizedActivity = normalized(activity), normalizedItem = normalized(item);
            Activity dimension = profile.activities.get(normalizedActivity);
            if (dimension == null) return OptionalLong.of(0L);
            Row row = "*".equals(normalizedItem) ? dimension.total : dimension.items.get(normalizedItem);
            return OptionalLong.of(row == null ? 0L : row.total);
        }
    }

    public UUID cachedPlayer(String nameOrUuid) {
        Objects.requireNonNull(nameOrUuid);
        int separators = 0;
        if (nameOrUuid.length() <= 36) {
            for (int index = 0; index < nameOrUuid.length(); ++index) if (nameOrUuid.charAt(index) == '-') ++separators;
        }
        if (separators == 4) {
            try { return UUID.fromString(nameOrUuid); }
            catch (IllegalArgumentException invalid) { /* A non-UUID registered name can also contain hyphens. */ }
        }
        synchronized (lock) { return names.get(nameOrUuid.toLowerCase(Locale.ROOT)); }
    }

    void flush() {
        try {
            if (retry == null) {
                synchronized (lock) {
                    if (pending.isEmpty()) { trimOfflineProfiles(); return; }
                    var changes = new HashMap<StatisticKey, Long>();
                    var players = new HashSet<UUID>();
                    for (Row row : pending) { changes.put(row.key, row.delta); players.add(row.profile.player); }
                    retry = new Batch(UUID.randomUUID(), Map.copyOf(changes), Set.copyOf(players));
                    for (Row row : pending) { row.delta = 0L; row.dirty = false; --row.profile.dirtyRows; }
                    pending.clear();
                }
            }
            for (UUID player : retry.players()) {
                ensureLoaded(player);
            }
            store.write(retry.id(), retry.changes());
            retry = null;
            trimOfflineProfiles();
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Statistics batch remains queued for retry", failure);
        }
    }

    private void trimOfflineProfiles() {
        synchronized (lock) {
            if (loadedProfiles <= profileLimit + 1) return;
            Batch failed = retry;
            var oldest = profiles.entrySet().iterator();
            while (loadedProfiles > profileLimit + 1 && oldest.hasNext()) {
                Profile profile = oldest.next().getValue();
                if (!profile.loaded || StatisticKey.SERVER.equals(profile.player) || profile.online || profile.dirtyRows != 0 || profile.queued
                        || failed != null && failed.players().contains(profile.player)) continue;
                oldest.remove();
                --loadedProfiles;
                if (profile.name != null) names.remove(profile.name, profile.player);
            }
        }
    }

    public synchronized void shutdown(ShutdownBudget budget) {
        if (!accepting) return;
        accepting = false;
        if (flushTask != null) flushTask.cancel(false);
        writer.execute(() -> {
            // A retry must finish before the newer pending changes can become a separate batch.
            flush();
            if (retry == null) flush();
            try { store.close(); }
            catch (Exception failure) { logger.log(Level.WARNING, "Statistics database close failed", failure); }
        });
        boolean drained = budget.awaitTermination("statistics", writer);
        synchronized (lock) {
            if (!drained || retry != null || !pending.isEmpty()) logger.warning("Statistics shutdown did not persist all queued changes");
        }
    }
}
