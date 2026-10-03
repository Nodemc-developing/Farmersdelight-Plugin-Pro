package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HydrationSamplerTest {
    private record Chunk(int x, int z) { }
    private record Cell(int x, int y, int z) { }
    private record Job(String owner, Runnable action) { }
    private static final class World implements HydrationSampler.Access {
        final Map<Chunk, String> owners = new HashMap<>();
        final Set<Chunk> unloaded = new HashSet<>();
        final Set<Cell> water = new HashSet<>();
        final Set<Cell> read = new HashSet<>();
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        final List<Boolean> applied = new ArrayList<>();
        String thread = "soil";
        boolean enabled = true, stateUnchanged = true, definitionUnchanged = true, raining;
        boolean reject, failRead;
        int generation, expectedGeneration;
        int dispatches;
        String owner(int cx, int cz) { return owners.getOrDefault(new Chunk(cx, cz), "soil"); }
        @Override public boolean available() { return enabled && generation == expectedGeneration; }
        @Override public boolean current() {
            return available() && thread.equals("soil") && stateUnchanged && definitionUnchanged && loaded(0, 0);
        }
        @Override public boolean raining() { assertEquals("soil", thread); return raining; }
        @Override public boolean loaded(int cx, int cz) { return !unloaded.contains(new Chunk(cx, cz)); }
        @Override public boolean owned(int cx, int cz) { return thread.equals(owner(cx, cz)); }
        @Override public boolean water(int x, int y, int z) {
            assertTrue(loaded(x >> 4, z >> 4), "Read must not load an absent chunk");
            assertTrue(owned(x >> 4, z >> 4), "Read must execute on the actual chunk owner");
            if (failRead && !thread.equals("soil")) throw new IllegalStateException("Snapshot unavailable");
            read.add(new Cell(x, y, z));
            return water.contains(new Cell(x, y, z));
        }
        @Override public void at(int cx, int cz, Runnable action) {
            if (reject) throw new IllegalStateException("Shutdown");
            dispatches++;
            jobs.add(new Job(owner(cx, cz), action));
        }
        @Override public void back(Runnable action) {
            if (reject) throw new IllegalStateException("Shutdown");
            jobs.add(new Job("soil", action));
        }
        void apply(boolean wet) { assertEquals("soil", thread); assertTrue(current()); applied.add(wet); }
        Job next() {
            Job job = jobs.remove();
            thread = job.owner;
            try { job.action.run(); } finally { thread = "soil"; }
            return job;
        }
        void drain() { int guard = 0; while (!jobs.isEmpty()) { assertTrue(++guard < 30); next(); } }
        void sample(HydrationSampler<String> sampler, String key) { sampler.sample(key, 15, 64, 8, this, this::apply); }
        void foreignEast() { owners.put(new Chunk(1, 0), "east"); }
    }

    private HydrationSampler<String> sampler() { return new HydrationSampler<>(256, 2_000_000_000L); }

    @Test void rainAndLocalWaterDoNotDispatchForeignReads() {
        for (boolean rain : new boolean[]{true, false}) {
            World world = new World(); world.foreignEast(); world.raining = rain;
            if (!rain) world.water.add(new Cell(14, 64, 8));
            world.sample(sampler(), "soil");
            assertEquals(List.of(true), world.applied);
            assertEquals(0, world.dispatches);
            assertTrue(world.jobs.isEmpty());
            assertTrue(world.read.stream().noneMatch(c -> c.x >= 16));
        }
    }

    @Test void fullyLocalDrySearchCoversExactBoxAndBothHeights() {
        World world = new World();
        sampler().sample("negative", -16, 70, -16, world, world::apply);
        assertEquals(List.of(false), world.applied);
        assertEquals(162, world.read.size());
        assertTrue(world.read.contains(new Cell(-20, 70, -20)));
        assertTrue(world.read.contains(new Cell(-12, 71, -12)));
        assertTrue(world.read.stream().allMatch(c -> c.x >= -20 && c.x <= -12 && c.z >= -20 && c.z <= -12 && (c.y == 70 || c.y == 71)));
        assertEquals(0, world.dispatches);
    }

    @Test void actualForeignWaterHydratesOnlyAfterReturningToSoilOwner() {
        World world = new World(); world.foreignEast(); world.water.add(new Cell(19, 65, 12));
        HydrationSampler<String> sampler = sampler();
        world.sample(sampler, "soil");
        assertTrue(world.applied.isEmpty()); assertEquals(1, sampler.pendingCount());
        world.next(); assertTrue(world.applied.isEmpty());
        world.next(); assertEquals(List.of(true), world.applied); assertEquals(0, sampler.pendingCount());
    }

    @Test void allForeignChunksAreGroupedAndDrynessRequiresEverySnapshot() {
        World world = new World();
        world.owners.put(new Chunk(1, 0), "east"); world.owners.put(new Chunk(0, 1), "south");
        world.owners.put(new Chunk(1, 1), "diagonal");
        HydrationSampler<String> sampler = sampler();
        sampler.sample("corner", 15, 64, 15, world, world::apply);
        assertEquals(3, world.dispatches);
        world.next(); world.next(); assertTrue(world.applied.isEmpty());
        world.drain(); assertEquals(List.of(false), world.applied); assertEquals(162, world.read.size());
        assertEquals(0, sampler.pendingCount());
    }

    @Test void unloadingOrFailedForeignReadDoesNotTurnUnknownIntoDryness() {
        for (boolean unload : new boolean[]{true, false}) {
            World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
            world.sample(sampler, "soil");
            if (unload) world.unloaded.add(new Chunk(1, 0)); else world.failRead = true;
            world.drain(); assertTrue(world.applied.isEmpty()); assertEquals(0, sampler.pendingCount());
            assertTrue(world.read.stream().noneMatch(c -> c.x >= 16));
            // A later complete sample is evaluated afresh; there is no permanent optimistic wet cache.
            world.unloaded.clear(); world.failRead = false;
            world.sample(sampler, "soil"); world.drain(); assertEquals(List.of(false), world.applied);
        }
    }

    @Test void waterAddedLocallyWhileSamplingPreventsAStaleDryCommit() {
        World world = new World(); world.foreignEast();
        world.sample(sampler(), "soil"); world.next();
        world.water.add(new Cell(11, 65, 8));
        world.drain(); assertEquals(List.of(true), world.applied);
    }

    @Test void duplicateRandomTicksAndDuplicateCallbackApplyOneTick() {
        World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
        world.sample(sampler, "soil"); world.sample(sampler, "soil");
        assertEquals(1, world.dispatches);
        Job completed = world.next();
        world.thread = completed.owner; completed.action.run(); world.thread = "soil";
        world.sample(sampler, "soil"); assertEquals(1, world.dispatches);
        world.drain(); assertEquals(List.of(false), world.applied); assertEquals(0, sampler.pendingCount());
        world.water.add(new Cell(16, 64, 8));
        world.sample(sampler, "soil"); world.drain(); assertEquals(List.of(false, true), world.applied);
    }

    @Test void changedStateDefinitionGenerationDisabledOrRetiredOwnerCancelsLateCommit() {
        for (int change = 0; change < 5; change++) {
            World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
            world.sample(sampler, "soil"); world.next();
            switch (change) {
                case 0 -> world.stateUnchanged = false;
                case 1 -> world.definitionUnchanged = false;
                case 2 -> world.generation++;
                case 3 -> world.enabled = false;
                case 4 -> world.unloaded.add(new Chunk(0, 0));
            }
            world.drain(); assertTrue(world.applied.isEmpty()); assertEquals(0, sampler.pendingCount());
        }
    }

    @Test void capacityIsBoundedAndExpiredCallbacksCannotCompleteAReplacement() {
        AtomicLong clock = new AtomicLong(); HydrationSampler<String> sampler = new HydrationSampler<>(2, 100, clock::get);
        World world = new World(); world.foreignEast();
        world.sample(sampler, "world-a"); world.sample(sampler, "world-b"); world.sample(sampler, "world-c");
        assertEquals(2, sampler.pendingCount()); assertEquals(2, world.dispatches);
        clock.set(100); world.sample(sampler, "world-a");
        assertEquals(1, sampler.pendingCount()); assertEquals(3, world.dispatches);
        world.next(); world.next(); assertTrue(world.applied.isEmpty());
        world.drain(); assertEquals(List.of(false), world.applied); assertEquals(0, sampler.pendingCount());
    }

    @Test void schedulingRejectionReleasesPendingWithoutAWorldMutation() {
        for (boolean returnRejected : new boolean[]{false, true}) {
            World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
            if (!returnRejected) world.reject = true;
            world.sample(sampler, "soil");
            if (returnRejected) { world.reject = true; world.next(); }
            assertTrue(world.applied.isEmpty()); assertEquals(0, sampler.pendingCount());
        }
    }

    @Test void waterSnapshotIsNotReusedAfterSourceRemoval() {
        World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
        world.water.add(new Cell(16, 64, 8)); world.sample(sampler, "soil"); world.drain();
        world.water.clear(); world.sample(sampler, "soil"); world.drain();
        assertEquals(List.of(true, false), world.applied);
    }

    @Test void newlyForeignLocalChunkIsUnknownUntilANewOwnerSample() {
        World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
        world.sample(sampler, "soil"); world.next();
        world.owners.put(new Chunk(0, 0), "new-region");
        world.drain(); assertTrue(world.applied.isEmpty()); assertEquals(0, sampler.pendingCount());
    }

    @Test void initiallyAbsentNeighborChunkCannotProveDrynessAndIsNotLoaded() {
        World world = new World(); world.unloaded.add(new Chunk(1, 0));
        HydrationSampler<String> sampler = sampler();
        world.sample(sampler, "soil");
        assertTrue(world.applied.isEmpty()); assertEquals(0, world.dispatches);
        assertTrue(world.read.stream().noneMatch(c -> c.x >= 16));
        assertTrue(world.unloaded.contains(new Chunk(1, 0)));
        world.water.add(new Cell(12, 64, 8));
        world.sample(sampler, "soil"); assertEquals(List.of(true), world.applied);
    }

    @Test void initiallyLocalNeighborUnloadingBeforeCommitCannotProveDryness() {
        World world = new World(); world.foreignEast(); HydrationSampler<String> sampler = sampler();
        sampler.sample("corner", 15, 64, 15, world, world::apply);
        world.next();
        world.unloaded.add(new Chunk(0, 1));
        world.drain(); assertTrue(world.applied.isEmpty()); assertEquals(0, sampler.pendingCount());
        world.unloaded.clear();
        sampler.sample("corner", 15, 64, 15, world, world::apply); world.drain();
        assertEquals(List.of(false), world.applied);
    }

    @Test void configuredRadiusZeroThroughSixteenReadsExactlyItsOwnBox() {
        for (int radius : new int[]{0, 1, 4, 16}) {
            World world = new World();
            sampler().sample("radius", 15, 64, 15, radius, world, world::apply);
            assertEquals(List.of(false), world.applied);
            assertEquals((radius * 2 + 1) * (radius * 2 + 1) * 2, world.read.size());
            assertTrue(world.read.stream().allMatch(c -> c.x >= 15 - radius && c.x <= 15 + radius
                    && c.z >= 15 - radius && c.z <= 15 + radius && (c.y == 64 || c.y == 65)));
        }
    }

    @Test void configuredRadiusIncludesBoundaryWaterAndExcludesWaterOutsideRange() {
        for (int radius : new int[]{0, 4, 16}) {
            World world = new World(); HydrationSampler<String> sampler = sampler();
            world.water.add(new Cell(15 + radius + 1, 64, 15));
            sampler.sample("radius", 15, 64, 15, radius, world, world::apply);
            assertEquals(List.of(false), world.applied);
            world.water.add(new Cell(15 + radius, 65, 15));
            sampler.sample("radius", 15, 64, 15, radius, world, world::apply);
            assertEquals(List.of(false, true), world.applied);
        }
    }

    @Test void maximumRadiusSamplesAllEightForeignChunksOnTheirOwners() {
        World world = new World(); HydrationSampler<String> sampler = sampler();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) world.owners.put(new Chunk(x, z), "owner-" + x + "," + z);
        }
        sampler.sample("radius", 15, 64, 15, 16, world, world::apply);
        assertEquals(8, world.dispatches); assertTrue(world.applied.isEmpty());
        world.drain(); assertEquals(List.of(false), world.applied); assertEquals(2178, world.read.size());
        assertEquals(0, sampler.pendingCount());
        world.water.add(new Cell(-1, 65, 31));
        sampler.sample("radius", 15, 64, 15, 16, world, world::apply); world.drain();
        assertEquals(List.of(false, true), world.applied);
    }

    @Test void invalidRadiusIsRejectedBeforeAnyWorldAccess() {
        World world = new World();
        assertThrows(IllegalArgumentException.class, () -> sampler().sample("bad", 0, 64, 0, -1, world, world::apply));
        assertThrows(IllegalArgumentException.class, () -> sampler().sample("bad", 0, 64, 0, 17, world, world::apply));
        assertTrue(world.read.isEmpty()); assertEquals(0, world.dispatches); assertTrue(world.applied.isEmpty());
    }
}
