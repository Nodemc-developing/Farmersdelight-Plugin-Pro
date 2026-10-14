package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CakeSliceTransactionTest {
    private static final class Cell implements CakeSliceTransaction.Cell<String> {
        String state = "cake0";
        boolean resident = true;
        boolean failOnRead;
        final List<String> writes = new ArrayList<>();
        Function<String, Boolean> writer;
        @Override public boolean resident() { return resident; }
        @Override public String read() {
            if (failOnRead) throw new AssertionError("foreign region must not be read");
            return state;
        }
        @Override public boolean write(String update) {
            writes.add(update);
            if (writer != null) return writer.apply(update);
            state = update;
            return true;
        }
    }
    private static class Drops implements CakeSliceTransaction.Output {
        int live;
        int calls;
        @Override public boolean deliver() { calls++; live = 1; return true; }
        @Override public boolean retract() { live = 0; return true; }
    }

    @Test void aCancelledOrDeniedInteractionDoesNotChangeCakeOrProduceItems() {
        Cell cell = new Cell();
        Drops drops = new Drops();
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> false, drops);
        assertFalse(result.committed());
        assertEquals("cake0", cell.state);
        assertTrue(cell.writes.isEmpty());
        assertEquals(0, drops.calls);
    }
    @Test void noCakeOrDropReadsOccurWhenTheAreaIsUnloadedOrUnowned() {
        Cell cell = new Cell();
        cell.resident = false;
        cell.failOnRead = true;
        Drops drops = new Drops();
        assertFalse(CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops).committed());
        assertEquals(0, drops.calls);
    }
    @Test void aListenerReplacingTheCakeBeforeCommitPreventsTheSlice() {
        Cell cell = new Cell();
        cell.state = "external-cake";
        Drops drops = new Drops();
        assertFalse(CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops).committed());
        assertEquals("external-cake", cell.state);
        assertEquals(0, drops.calls);
    }
    @Test void aFailedNativeWriteCannotDropASliceAndItsPartialMutationIsRestored() {
        Cell cell = new Cell();
        cell.writer = state -> { cell.state = state; return !state.equals("cake1"); };
        Drops drops = new Drops();
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("cake0", cell.state);
        assertEquals(0, drops.calls);
    }
    @Test void cancellingOneDropRestoresCakeAndRetractsEarlierDrops() {
        Cell cell = new Cell();
        cell.state = "candle-cake";
        Drops drops = new Drops() {
            @Override public boolean deliver() { live = 1; calls++; return false; }
        };
        var result = CakeSliceTransaction.commit(cell, "candle-cake", "cake1", () -> true, drops);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("candle-cake", cell.state);
        assertEquals(0, drops.live);
        assertEquals(1, drops.calls);
    }
    @Test void changingTheKnifeDuringItemSpawnRestoresCakeInsteadOfGivingAFinishedSlice() {
        Cell cell = new Cell();
        AtomicBoolean heldKnife = new AtomicBoolean(true);
        Drops drops = new Drops() {
            @Override public boolean deliver() { super.deliver(); heldKnife.set(false); return true; }
        };
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", heldKnife::get, drops);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("cake0", cell.state);
        assertEquals(0, drops.live);
    }
    @Test void rollbackNeverOverwritesAnExternalBlockReplacement() {
        Cell cell = new Cell();
        Drops drops = new Drops() {
            @Override public boolean deliver() { cell.state = "new-block"; live = 1; return false; }
        };
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("new-block", cell.state);
        assertEquals(0, drops.live);
    }
    @Test void aSpawnExceptionRetractsItsPartialOutputAndRestoresCake() {
        Cell cell = new Cell();
        Drops drops = new Drops() {
            @Override public boolean deliver() { live = 1; throw new IllegalStateException("injected spawn error"); }
        };
        assertThrows(IllegalStateException.class, () -> CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops));
        assertEquals("cake0", cell.state);
        assertEquals(0, drops.live);
    }
    @Test void theLastServingRemovesCakeOnlyWhenTheSliceCanBeDelivered() {
        Cell cell = new Cell();
        cell.state = "cake6";
        Drops drops = new Drops();
        var result = CakeSliceTransaction.commit(cell, "cake6", "air", () -> true, drops);
        assertTrue(result.committed());
        assertEquals("air", cell.state);
        assertEquals(1, drops.live);
    }
    @Test void ownerLossIsReportedAndNeverFollowedByAForeignWorldRead() {
        Cell cell = new Cell();
        Drops drops = new Drops() {
            @Override public boolean deliver() { cell.resident = false; cell.failOnRead = true; live = 1; return false; }
        };
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops);
        assertFalse(result.committed());
        assertFalse(result.rollbackComplete());
        assertEquals(0, drops.live);
    }
    @Test void aNormalSliceDoesNotNeedAnInventorySlotOrConsumeTheKnife() {
        Cell cell = new Cell();
        AtomicBoolean knifeUnchanged = new AtomicBoolean(true);
        Drops drops = new Drops();
        var result = CakeSliceTransaction.commit(cell, "cake0", "cake1", knifeUnchanged::get, drops);
        assertTrue(result.committed());
        assertTrue(knifeUnchanged.get());
        assertEquals(1, drops.live);
        assertEquals("cake1", cell.state);
    }
    @Test void aFailingEntityCleanupDoesNotPreventRestoringTheSourceCake() {
        Cell cell = new Cell();
        Drops drops = new Drops() {
            @Override public boolean deliver() { return false; }
            @Override public boolean retract() { throw new IllegalStateException("injected entity cleanup error"); }
        };
        assertThrows(IllegalStateException.class, () -> CakeSliceTransaction.commit(cell, "cake0", "cake1", () -> true, drops));
        assertEquals("cake0", cell.state);
    }
    @Test void oneItemRemovalExceptionDoesNotLeaveTheOtherProducedItemsUntouched() {
        List<String> removed = new ArrayList<>();
        var failure = assertThrows(IllegalStateException.class, () -> CakeSliceTransaction.retractAll(List.of("first-slice", "candle", "last-slice"), item -> {
            removed.add(item);
            if (item.equals("first-slice")) throw new IllegalStateException("injected removal failure");
            if (item.equals("candle")) throw new IllegalArgumentException("injected second removal failure");
            return true;
        }));
        assertEquals(List.of("first-slice", "candle", "last-slice"), removed);
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalArgumentException.class, failure.getSuppressed()[0]);
    }
    @Test void anUnownedOutputDoesNotSkipRetractionOfTheRemainingOwnedOutputs() {
        List<String> attempted = new ArrayList<>();
        assertFalse(CakeSliceTransaction.retractAll(List.of("unowned", "owned"), item -> {
            attempted.add(item);
            return item.equals("owned");
        }));
        assertEquals(List.of("unowned", "owned"), attempted);
    }
}
