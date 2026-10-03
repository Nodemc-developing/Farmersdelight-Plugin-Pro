package com.huidu.farmersdelight.block.behavior;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class OwnedBlockPairTransactionTest {
    private static final class Cell implements OwnedBlockPairTransaction.Cell<String> {
        String state;
        boolean resident = true;
        final List<String> writes = new ArrayList<>();
        Function<String, Boolean> writer;
        Cell(String state) { this.state = state; }
        @Override public boolean resident() { return resident; }
        @Override public String read() { return state; }
        @Override public boolean write(String update) {
            writes.add(update);
            if (writer != null) return writer.apply(update);
            state = update; return true;
        }
    }
    @Test void staleSecondCellRejectsBothWrites() {
        Cell first = new Cell("left-unpaired"), second = new Cell("replacement");
        var outcome = OwnedBlockPairTransaction.update(first, "left-unpaired", "left-paired", second, "right-unpaired", "right-paired");
        assertFalse(outcome.committed()); assertTrue(outcome.rollbackComplete());
        assertTrue(first.writes.isEmpty()); assertTrue(second.writes.isEmpty());
    }
    @Test void failedSecondWriteRetractsTheFirstAndItsOwnPartialMutation() {
        Cell first = new Cell("left-unpaired"), second = new Cell("right-unpaired");
        second.writer = update -> { second.state = update; return !update.equals("right-paired"); };
        var outcome = OwnedBlockPairTransaction.update(first, "left-unpaired", "left-paired", second, "right-unpaired", "right-paired");
        assertFalse(outcome.committed()); assertTrue(outcome.rollbackComplete());
        assertEquals("left-unpaired", first.state); assertEquals("right-unpaired", second.state);
        assertEquals(List.of("right-paired", "right-unpaired"), second.writes);
    }
    @Test void failureNeverOverwritesAnExternalReplacementDuringTheWrite() {
        Cell first = new Cell("left-unpaired"), second = new Cell("right-unpaired");
        second.writer = update -> { first.state = "external-left"; second.state = "external-right"; return false; };
        var outcome = OwnedBlockPairTransaction.update(first, "left-unpaired", "left-paired", second, "right-unpaired", "right-paired");
        assertFalse(outcome.committed()); assertTrue(outcome.rollbackComplete());
        assertEquals("external-left", first.state); assertEquals("external-right", second.state);
        assertEquals(List.of("left-paired"), first.writes); assertEquals(List.of("right-paired"), second.writes);
    }
    @Test void losingOwnershipBeforeSecondWriteStopsAndReportsIncompleteCompensation() {
        Cell first = new Cell("left"), second = new Cell("right");
        first.writer = update -> { first.state = update; first.resident = false; return true; };
        var outcome = OwnedBlockPairTransaction.update(first, "left", "left-new", second, "right", "right-new");
        assertFalse(outcome.committed()); assertFalse(outcome.rollbackComplete());
        assertTrue(second.writes.isEmpty()); assertEquals(List.of("left-new"), first.writes);
    }
    @Test void aFailedRestoreCannotBeReportedAsACompleteRollback() {
        Cell first = new Cell("left"), second = new Cell("right");
        first.writer = update -> { if (update.equals("left")) return false; first.state = update; return true; };
        second.writer = update -> false;
        var outcome = OwnedBlockPairTransaction.update(first, "left", "left-new", second, "right", "right-new");
        assertFalse(outcome.committed()); assertFalse(outcome.rollbackComplete());
        assertEquals("left-new", first.state); assertEquals("right", second.state);
    }
    @Test void anExceptionAfterPartialWriteRestoresThenPropagates() {
        Cell first = new Cell("left"), second = new Cell("right");
        second.writer = update -> { second.state = update; if (update.equals("right-new")) throw new IllegalStateException("injected native write failure"); return true; };
        assertThrows(IllegalStateException.class, () -> OwnedBlockPairTransaction.update(first, "left", "left-new", second, "right", "right-new"));
        assertEquals("left", first.state); assertEquals("right", second.state);
    }
    @Test void theAlreadyPlacedHeadIsValidatedWithoutWritingItAgain() {
        Cell foot = new Cell("air"), head = new Cell("head");
        var outcome = OwnedBlockPairTransaction.update(foot, "air", "foot", head, "head", "head");
        assertTrue(outcome.committed()); assertEquals(List.of("foot"), foot.writes); assertTrue(head.writes.isEmpty());
    }
    @Test void partnerMustHaveTheOppositePartAndTheSameFacing() {
        assertTrue(DoubleBlockRugBlockBehavior.matchingPairParts(BlockFace.EAST, "head", BlockFace.EAST, "foot"));
        assertTrue(DoubleBlockRugBlockBehavior.matchingPairParts(BlockFace.EAST, "foot", BlockFace.EAST, "head"));
        assertFalse(DoubleBlockRugBlockBehavior.matchingPairParts(BlockFace.EAST, "head", BlockFace.WEST, "foot"));
        assertFalse(DoubleBlockRugBlockBehavior.matchingPairParts(BlockFace.EAST, "head", BlockFace.EAST, "head"));
        assertFalse(DoubleBlockRugBlockBehavior.matchingPairParts(BlockFace.EAST, null, BlockFace.EAST, "foot"));
    }
    @Test void refundRequiresOneNativeDebitAndUnchangedComponents() {
        assertTrue(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(1, 0, true));
        assertTrue(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(64, 63, true));
        assertFalse(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(64, 64, true));
        assertFalse(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(64, 62, true));
        assertFalse(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(64, 63, false));
        assertFalse(DoubleBlockRugBlockBehavior.nativeConsumptionConfirmed(0, 0, true));
    }
}
