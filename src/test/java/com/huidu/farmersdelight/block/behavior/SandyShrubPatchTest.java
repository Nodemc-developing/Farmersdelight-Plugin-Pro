package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SandyShrubPatchTest {
    private static final class Cell implements OwnedBlockPairTransaction.Cell<String> {
        String state = "air";
        boolean resident = true;
        boolean failIfRead = false;
        Function<String, Boolean> writer;
        List<String> writes = new ArrayList<>();
        @Override public boolean resident() { return resident; }
        @Override public String read() {
            if (failIfRead) throw new AssertionError("read outside the owned loaded area");
            return state;
        }
        @Override public boolean write(String state) {
            writes.add(state);
            if (writer != null) return writer.apply(state);
            this.state = state;
            return true;
        }
        SandyShrubPatch.Change<String> change() { return new SandyShrubPatch.Change<>(this, "air", "shrub"); }
    }

    @Test void defaultPatchHasThirtyTwoAttemptsAndUsesASmallAreaAboveTheOrigin() {
        var options = SandyShrubPatch.Options.parse(Map.of());
        assertEquals(new SandyShrubPatch.Options(32, 2, 3), options);
        List<SandyShrubPatch.Offset> inspected = new ArrayList<>();
        var changes = SandyShrubPatch.prepare(options, new Random(273L), offset -> {
            inspected.add(offset);
            return new Cell().change();
        });
        assertTrue(changes.size() > 1);
        assertTrue(changes.size() <= 32);
        assertEquals(inspected.size(), inspected.stream().distinct().count());
        for (var offset : inspected) {
            assertTrue(Math.abs(offset.x()) <= 2 && Math.abs(offset.z()) <= 2);
            assertTrue(offset.y() >= -2 && offset.y() <= 4);
        }
    }
    @Test void emptyCandidatePositionsAreNotInspectedAgainInTheSamePatch() {
        List<SandyShrubPatch.Offset> inspected = new ArrayList<>();
        var changes = SandyShrubPatch.prepare(new SandyShrubPatch.Options(32, 0, 0), new Random(8L), offset -> {
            inspected.add(offset);
            return null;
        });
        assertTrue(changes.isEmpty());
        assertEquals(List.of(new SandyShrubPatch.Offset(0, 1, 0)), inspected);
    }
    @Test void groupedOptionsOverrideTheDefaultsWithoutAHiddenDensityLimit() {
        var options = SandyShrubPatch.Options.parse(Map.of("patch", Map.of("attempts", 64, "horizontal-range", 4, "vertical-range", 2), "spread-limit", 1));
        assertEquals(new SandyShrubPatch.Options(64, 4, 2), options);
        assertThrows(IllegalArgumentException.class, () -> new SandyShrubPatch.Options(257, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new SandyShrubPatch.Options(32, 9, 3));
        assertThrows(IllegalArgumentException.class, () -> new SandyShrubPatch.Options(32, 2, -1));
    }
    @Test void anUnloadedOrForeignCellIsRejectedWithoutReadingItsState() {
        Cell first = new Cell(), foreign = new Cell();
        foreign.resident = false;
        foreign.failIfRead = true;
        var result = SandyShrubPatch.commit(List.of(first.change(), foreign.change()), () -> true);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertTrue(first.writes.isEmpty());
        assertTrue(foreign.writes.isEmpty());
    }
    @Test void cancellationAndAnEmptyPatchMakeNoWorldChanges() {
        Cell cell = new Cell();
        assertFalse(SandyShrubPatch.commit(List.of(cell.change()), () -> false).committed());
        assertFalse(SandyShrubPatch.commit(List.<SandyShrubPatch.Change<String>>of(), () -> true).committed());
        assertTrue(cell.writes.isEmpty());
    }
    @Test void aChangedDestinationAbortsTheEntirePatchBeforeTheFirstPlacement() {
        Cell first = new Cell(), changed = new Cell();
        changed.state = "external-block";
        assertFalse(SandyShrubPatch.commit(List.of(first.change(), changed.change()), () -> true).committed());
        assertTrue(first.writes.isEmpty());
        assertTrue(changed.writes.isEmpty());
    }
    @Test void aFailedLaterWriteRestoresTheEarlierPlantAndItsOwnPartialMutation() {
        Cell first = new Cell(), second = new Cell();
        second.writer = state -> { second.state = state; return !state.equals("shrub"); };
        var result = SandyShrubPatch.commit(List.of(first.change(), second.change()), () -> true);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("air", first.state);
        assertEquals("air", second.state);
        assertEquals(List.of("shrub", "air"), second.writes);
    }
    @Test void anEventChangingTheHeldItemDuringPlacementCompensatesInsteadOfFinishing() {
        Cell first = new Cell(), second = new Cell();
        AtomicBoolean itemMatches = new AtomicBoolean(true);
        first.writer = state -> { first.state = state; if (state.equals("shrub")) itemMatches.set(false); return true; };
        var result = SandyShrubPatch.commit(List.of(first.change(), second.change()), itemMatches::get);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("air", first.state);
        assertTrue(second.writes.isEmpty());
    }
    @Test void compensationDoesNotOverwriteAReplacementWrittenByAnotherHandler() {
        Cell first = new Cell(), second = new Cell();
        second.writer = state -> { first.state = "other-shrub"; second.state = "other-block"; return false; };
        var result = SandyShrubPatch.commit(List.of(first.change(), second.change()), () -> true);
        assertFalse(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("other-shrub", first.state);
        assertEquals("other-block", second.state);
    }
    @Test void anExceptionAfterAPartialNativeWriteRestoresThePatchAndPropagates() {
        Cell first = new Cell(), second = new Cell();
        second.writer = state -> {
            second.state = state;
            if (state.equals("shrub")) throw new IllegalStateException("injected placement failure");
            return true;
        };
        assertThrows(IllegalStateException.class, () -> SandyShrubPatch.commit(List.of(first.change(), second.change()), () -> true));
        assertEquals("air", first.state);
        assertEquals("air", second.state);
    }
    @Test void losingOwnershipDuringPlacementReportsAnIncompleteRollbackWithoutReadingTheForeignCell() {
        Cell first = new Cell(), second = new Cell();
        first.writer = state -> { first.state = state; first.resident = false; first.failIfRead = true; return true; };
        var result = SandyShrubPatch.commit(List.of(first.change(), second.change()), () -> true);
        assertFalse(result.committed());
        assertFalse(result.rollbackComplete());
        assertTrue(second.writes.isEmpty());
    }
    @Test void aSuccessfulPatchCommitsSeveralPlantsAsOneOperation() {
        Cell first = new Cell(), second = new Cell(), third = new Cell();
        var result = SandyShrubPatch.commit(List.of(first.change(), second.change(), third.change()), () -> true);
        assertTrue(result.committed());
        assertTrue(result.rollbackComplete());
        assertEquals("shrub", first.state);
        assertEquals("shrub", second.state);
        assertEquals("shrub", third.state);
    }
    @Test void oneRollbackExceptionDoesNotPreventRestoringTheOtherAttemptedPlants() {
        Cell first = new Cell(), second = new Cell(), third = new Cell();
        second.writer = state -> {
            if (state.equals("air")) throw new IllegalStateException("injected rollback failure");
            second.state = state;
            return true;
        };
        third.writer = state -> { third.state = state; return !state.equals("shrub"); };
        assertThrows(IllegalStateException.class, () -> SandyShrubPatch.commit(List.of(first.change(), second.change(), third.change()), () -> true));
        assertEquals("air", first.state);
        assertEquals("air", third.state);
        assertTrue(second.writes.contains("air"));
    }
}
