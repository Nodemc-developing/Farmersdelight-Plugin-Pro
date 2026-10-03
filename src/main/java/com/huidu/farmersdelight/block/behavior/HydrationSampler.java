package com.huidu.farmersdelight.block.behavior;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** A hydration snapshot never reads a cell outside its owner or turns an incomplete sample into dryness. */
final class HydrationSampler<K> {
    interface Access {
        /** Lifecycle and configuration checks only; safe on any thread. */
        boolean available();
        /** On the soil owner: resident, owner, exact state and definition checks. */
        boolean current();
        boolean raining();
        boolean loaded(int chunkX, int chunkZ);
        boolean owned(int chunkX, int chunkZ);
        boolean water(int x, int y, int z);
        void at(int chunkX, int chunkZ, Runnable action);
        void back(Runnable action);
    }

    private enum Result { WET, DRY, UNKNOWN }
    private record Chunk(int x, int z) { }
    private final class Pending {
        final K key;
        final long started;
        final Result[] results;
        int remaining;
        boolean finished;
        Pending(K key, int count) {
            this.key = key;
            this.started = clock.getAsLong();
            this.results = new Result[count];
            this.remaining = count;
        }
    }

    private final int capacity;
    private final long timeoutNanos;
    private final LongSupplier clock;
    // Insertion order permits constant work for the usual expiry path. Completed samples are removed
    // immediately; this is a pending cache, never a reusable wet/dry cache after the water changes.
    private final LinkedHashMap<K, Pending> pending = new LinkedHashMap<>();

    HydrationSampler(int capacity, long timeoutNanos) {
        this(capacity, timeoutNanos, System::nanoTime);
    }

    HydrationSampler(int capacity, long timeoutNanos, LongSupplier clock) {
        if (capacity < 1 || timeoutNanos < 1) throw new IllegalArgumentException("Invalid hydration sample limits");
        this.capacity = capacity;
        this.timeoutNanos = timeoutNanos;
        this.clock = clock;
    }

    void sample(K key, int x, int y, int z, Access access, Consumer<Boolean> apply) {
        sample(key, x, y, z, 4, access, apply);
    }

    void sample(K key, int x, int y, int z, int radius, Access access, Consumer<Boolean> apply) {
        if (radius < 0 || radius > 16) throw new IllegalArgumentException("Hydration radius must be between 0 and 16");
        if (!access.available() || !access.current() || pending(key)) return;
        boolean unknown = false;
        try {
            if (access.raining()) { if (access.current()) apply.accept(true); return; }
        } catch (RuntimeException | LinkageError failedRead) { unknown = true; }
        List<Chunk> foreign = null;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                if (!access.loaded(cx, cz)) { unknown = true; continue; }
                if (!access.owned(cx, cz)) {
                    if (foreign == null) foreign = new ArrayList<>(3);
                    foreign.add(new Chunk(cx, cz));
                    continue;
                }
                Result result = read(access, cx, cz, x, y, z, radius);
                if (result == Result.WET) { if (access.current()) apply.accept(true); return; }
                unknown |= result == Result.UNKNOWN;
            }
        }
        if (foreign == null) {
            if (!unknown && access.current()) apply.accept(false);
            return;
        }
        Pending request = claim(key, foreign.size());
        if (request == null) return;
        List<Chunk> chunks = List.copyOf(foreign);
        boolean initiallyUnknown = unknown;
        for (int i = 0; i < chunks.size(); i++) {
            if (!live(request)) break;
            int index = i;
            Chunk chunk = chunks.get(i);
            try {
                access.at(chunk.x, chunk.z, () -> {
                    if (!live(request)) return;
                    Result result = access.available() ? read(access, chunk.x, chunk.z, x, y, z, radius) : Result.UNKNOWN;
                    complete(request, index, result, () -> {
                        try {
                            access.back(() -> {
                                try {
                                    if (!live(request) || !access.available() || !access.current()) return;
                                    Result finalResult = recheck(access, request, chunks, initiallyUnknown, x, y, z, radius);
                                    if (finalResult != Result.UNKNOWN && access.current()) apply.accept(finalResult == Result.WET);
                                } finally { release(request); }
                            });
                        } catch (RuntimeException | LinkageError rejected) { release(request); }
                    });
                });
            } catch (RuntimeException | LinkageError rejected) {
                release(request);
                break;
            }
        }
    }

    private static Result read(Access access, int cx, int cz, int x, int y, int z, int radius) {
        if (!access.available() || !access.loaded(cx, cz) || !access.owned(cx, cz)) return Result.UNKNOWN;
        try {
            int minX = Math.max(x - radius, cx << 4), maxX = Math.min(x + radius, (cx << 4) + 15);
            int minZ = Math.max(z - radius, cz << 4), maxZ = Math.min(z + radius, (cz << 4) + 15);
            for (int px = minX; px <= maxX; px++) {
                for (int pz = minZ; pz <= maxZ; pz++) {
                    for (int py = y; py <= y + 1; py++) {
                        if (access.water(px, py, pz)) return Result.WET;
                    }
                }
            }
            return Result.DRY;
        } catch (RuntimeException | LinkageError failedRead) { return Result.UNKNOWN; }
    }

    private static <K> Result recheck(Access access, HydrationSampler<K>.Pending request,
                                     List<Chunk> foreign, boolean initiallyUnknown, int x, int y, int z, int radius) {
        boolean unknown = initiallyUnknown;
        try { if (access.raining()) return Result.WET; }
        catch (RuntimeException | LinkageError failedRead) { unknown = true; }
        boolean wetSnapshot = false;
        for (int i = 0; i < foreign.size(); i++) {
            Chunk chunk = foreign.get(i);
            if (!access.loaded(chunk.x, chunk.z)) unknown = true;
            else wetSnapshot |= request.results[i] == Result.WET;
            unknown |= request.results[i] == Result.UNKNOWN;
        }
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) {
                if (!access.loaded(cx, cz)) { unknown = true; continue; }
                if (!access.owned(cx, cz)) {
                    // A previously local chunk may belong to another owner after a region split.
                    if (!foreign.contains(new Chunk(cx, cz))) unknown = true;
                    continue;
                }
                Result local = read(access, cx, cz, x, y, z, radius);
                if (local == Result.WET) return Result.WET;
                unknown |= local == Result.UNKNOWN;
            }
        }
        if (wetSnapshot) return Result.WET;
        return unknown ? Result.UNKNOWN : Result.DRY;
    }

    private void complete(Pending request, int index, Result result, Runnable dispatch) {
        synchronized (request) {
            if (request.finished || request.results[index] != null) return;
            request.results[index] = result;
            if (--request.remaining > 0) return;
            request.finished = true;
        }
        if (live(request)) dispatch.run();
    }

    private synchronized boolean pending(K key) {
        expire();
        return pending.containsKey(key);
    }

    private synchronized Pending claim(K key, int count) {
        expire();
        if (pending.containsKey(key) || pending.size() >= capacity) return null;
        Pending request = new Pending(key, count);
        pending.put(key, request);
        return request;
    }

    private synchronized boolean live(Pending request) {
        if (clock.getAsLong() - request.started >= timeoutNanos) { release(request); return false; }
        return pending.get(request.key) == request;
    }

    private synchronized void release(Pending request) { pending.remove(request.key, request); }

    private void expire() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<K, Pending>> entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            if (now - entries.next().getValue().started < timeoutNanos) break;
            entries.remove();
        }
    }

    synchronized int pendingCount() { expire(); return pending.size(); }
}
