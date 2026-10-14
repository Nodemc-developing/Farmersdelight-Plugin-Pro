package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RopeOperationTest {
    @Test void cancelledEditDoesNotConsumeTheHeldRopeOrReturnAnExtraRope() {
        AtomicInteger inventory = new AtomicInteger(4);
        AtomicInteger worldRopes = new AtomicInteger(7);
        assertFalse(RopeOperation.commit(() -> false, () -> true,
                () -> { worldRopes.decrementAndGet(); return true; }, inventory::incrementAndGet));
        assertEquals(4, inventory.get());
        assertEquals(7, worldRopes.get());
    }

    @Test void anEventThatReplacesTheTargetInvalidatesTheOriginalEdit() {
        AtomicInteger edits = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        assertFalse(RopeOperation.commit(() -> true, () -> false,
                () -> { edits.incrementAndGet(); return true; }, consumed::incrementAndGet));
        assertEquals(0, edits.get());
        assertEquals(0, consumed.get());
    }

    @Test void aFailedWorldWriteDoesNotReturnAReward() {
        AtomicInteger returned = new AtomicInteger();
        assertFalse(RopeOperation.commit(() -> true, () -> true, () -> false, returned::incrementAndGet));
        assertEquals(0, returned.get());
    }

    @Test void theItemCommitFollowsTheWorldCommitOnce() {
        List<String> steps = new ArrayList<>();
        assertTrue(RopeOperation.commit(() -> { steps.add("permissions-and-events"); return true; },
                () -> { steps.add("target-and-hand-version"); return true; },
                () -> { steps.add("world-edit"); return true; }, () -> steps.add("item-edit")));
        assertEquals(List.of("permissions-and-events", "target-and-hand-version", "world-edit", "item-edit"), steps);
    }

    @Test void anUnsuccessfulItemDeliveryRestoresTheRemovedRope() {
        AtomicInteger worldRopes = new AtomicInteger(7);
        assertFalse(RopeOperation.commitWithCompensation(() -> true, () -> true,
                () -> { worldRopes.decrementAndGet(); return true; }, () -> false,
                () -> { worldRopes.incrementAndGet(); return true; }));
        assertEquals(7, worldRopes.get());
    }

    @Test void aCompletedItemExchangeIsNotCompensatedAgain() {
        AtomicInteger worldRopes = new AtomicInteger(7);
        AtomicInteger inventory = new AtomicInteger(4);
        assertTrue(RopeOperation.commitWithCompensation(() -> true, () -> true,
                () -> { worldRopes.decrementAndGet(); return true; },
                () -> { inventory.incrementAndGet(); return true; },
                () -> { worldRopes.incrementAndGet(); return true; }));
        assertEquals(6, worldRopes.get());
        assertEquals(5, inventory.get());
    }
}
