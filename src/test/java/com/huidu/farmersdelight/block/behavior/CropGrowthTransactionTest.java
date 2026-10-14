package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CropGrowthTransactionTest {
    private static class Cell implements CropGrowthTransaction.Mutation {
        int age = 2, events, writes;
        boolean cancelled, valid = true, fails;
        @Override public boolean current() { return valid && age == 2; }
        @Override public boolean authorize() { events++; return !cancelled; }
        @Override public boolean apply() { if (fails) return false; writes++; age = 3; return true; }
        @Override public boolean installed() { return age == 3; }
        @Override public void restore() { if (age == 3) age = 2; }
    }

    @Test void cancellingTheUpperGrowthPreservesBothCellsAndBoneMeal() {
        Cell lower = new Cell(), upper = new Cell();
        upper.cancelled = true;
        int boneMeal = 5;
        if (CropGrowthTransaction.commit(List.of(lower, upper))) boneMeal--;
        assertEquals(2, lower.age);
        assertEquals(2, upper.age);
        assertEquals(0, lower.writes);
        assertEquals(0, upper.writes);
        assertEquals(1, lower.events);
        assertEquals(1, upper.events);
        assertEquals(5, boneMeal);
    }

    @Test void aListenerChangingTheWorldPreventsAllWrites() {
        Cell upper = new Cell();
        var lower = new CropGrowthTransaction.Mutation() {
            @Override public boolean current() { return true; }
            @Override public boolean authorize() { upper.valid = false; return true; }
            @Override public boolean apply() { fail("Growth must recheck the full change set"); return false; }
            @Override public boolean installed() { return false; }
            @Override public void restore() { fail("Nothing was written"); }
        };
        assertFalse(CropGrowthTransaction.commit(List.of(lower, upper)));
        assertEquals(0, upper.writes);
    }

    @Test void aFailedSecondPlacementRestoresTheFirst() {
        Cell lower = new Cell(), upper = new Cell();
        upper.fails = true;
        assertFalse(CropGrowthTransaction.commit(List.of(lower, upper)));
        assertEquals(2, lower.age);
        assertEquals(2, upper.age);
    }

    @Test void successfulGrowthAuthorizesEachCellOnceAndConsumesOnce() {
        Cell lower = new Cell(), upper = new Cell();
        int boneMeal = 5;
        if (CropGrowthTransaction.commit(List.of(lower, upper))) boneMeal--;
        assertEquals(3, lower.age);
        assertEquals(3, upper.age);
        assertEquals(1, lower.events);
        assertEquals(1, upper.events);
        assertEquals(4, boneMeal);
    }

    @Test void staleOrEmptyGrowthNeverEmitsEvents() {
        Cell cell = new Cell();
        cell.valid = false;
        assertFalse(CropGrowthTransaction.commit(List.of(cell)));
        assertFalse(CropGrowthTransaction.commit(List.of()));
        assertEquals(0, cell.events);
    }

    @Test void failedPlacementThatAlreadyWroteItsOwnCellIsCompensated() {
        Cell lower = new Cell();
        Cell upper = new Cell() {
            @Override public boolean apply() { super.apply(); return false; }
        };
        assertFalse(CropGrowthTransaction.commit(List.of(lower, upper)));
        assertEquals(2, lower.age);
        assertEquals(2, upper.age);
    }

    @Test void anExceptionAfterWritingCompensatesTheAttemptedCellAndPreservesTheException() {
        Cell lower = new Cell();
        IllegalStateException reason = new IllegalStateException("writer failed after installing its block");
        Cell upper = new Cell() {
            @Override public boolean apply() { super.apply(); throw reason; }
        };
        assertSame(reason, assertThrows(IllegalStateException.class, () -> CropGrowthTransaction.commit(List.of(lower, upper))));
        assertEquals(2, lower.age);
        assertEquals(2, upper.age);
    }

    @Test void aLaterWriterChangingTheFirstCellRejectsSuccessAndPreservesThatForeignChange() {
        Cell lower = new Cell();
        Cell upper = new Cell() {
            @Override public boolean apply() { super.apply(); lower.age = 9; return true; }
        };
        int boneMeal = 5;
        if (CropGrowthTransaction.commit(List.of(lower, upper))) boneMeal--;
        assertEquals(9, lower.age);
        assertEquals(2, upper.age);
        assertEquals(5, boneMeal);
    }

    @Test void compensationContinuesAfterOneRestoreThrowsWithoutReplacingTheWriteFailure() {
        Cell lower = new Cell();
        IllegalStateException writeFailure = new IllegalStateException("write");
        IllegalArgumentException restoreFailure = new IllegalArgumentException("restore");
        Cell upper = new Cell() {
            @Override public boolean apply() { super.apply(); throw writeFailure; }
            @Override public void restore() { throw restoreFailure; }
        };
        assertSame(writeFailure, assertThrows(IllegalStateException.class, () -> CropGrowthTransaction.commit(List.of(lower, upper))));
        assertEquals(2, lower.age);
        assertEquals(3, upper.age);
        assertArrayEquals(new Throwable[]{restoreFailure}, writeFailure.getSuppressed());
    }

    @Test void aProtectionChangeDuringAuthorizationPreventsEveryWrite() {
        boolean[] allowed = {true};
        Cell lower = new Cell() {
            @Override public boolean current() { return super.current() && allowed[0]; }
            @Override public boolean authorize() { super.authorize(); allowed[0] = false; return true; }
        };
        Cell upper = new Cell() {
            @Override public boolean current() { return super.current() && allowed[0]; }
        };
        assertFalse(CropGrowthTransaction.commit(List.of(lower, upper)));
        assertEquals(0, lower.writes);
        assertEquals(0, upper.writes);
    }

    @Test void inputChangedByTheFinalWriterRestoresBothCellsInsteadOfGrantingFreeGrowth() {
        boolean[] inputMatches = {true};
        Cell lower = new Cell() {
            @Override public boolean installed() { return super.installed() && inputMatches[0]; }
        };
        Cell upper = new Cell() {
            @Override public boolean apply() { super.apply(); inputMatches[0] = false; return true; }
            @Override public boolean installed() { return super.installed() && inputMatches[0]; }
        };
        int boneMeal = 5;
        if (CropGrowthTransaction.commit(List.of(lower, upper))) boneMeal--;
        assertEquals(2, lower.age);
        assertEquals(2, upper.age);
        assertEquals(5, boneMeal);
    }
}
