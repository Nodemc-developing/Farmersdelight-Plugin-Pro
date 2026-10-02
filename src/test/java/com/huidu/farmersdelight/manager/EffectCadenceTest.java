package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EffectCadenceTest {
    @Test
    void everyFixedTickerPhaseEmitsInsteadOfPermanentlySilencingSomeBlocks() {
        for (int phase = 0; phase < 4; phase++) {
            EffectCadence cadence = new EffectCadence();
            int emitted = 0;
            for (int tick = phase; tick < phase + 32; tick += 4) {
                if (cadence.tryAcquire(tick, 8)) emitted++;
            }
            assertEquals(4, emitted, "ticker phase " + phase);
        }
    }

    @Test
    void independentBlocksAndRegionsDoNotShareTheirClockOrSuppressEachOther() {
        EffectCadence first = new EffectCadence();
        EffectCadence second = new EffectCadence();
        assertTrue(first.tryAcquire(1000, 12));
        assertTrue(second.tryAcquire(7, 12));
        assertFalse(first.tryAcquire(1004, 12));
        assertFalse(second.tryAcquire(11, 12));
        assertTrue(first.tryAcquire(1012, 12));
        assertTrue(second.tryAcquire(19, 12));
    }

    @Test
    void irregularVisitsAndReloadedIntervalsRespectElapsedTicks() {
        EffectCadence cadence = new EffectCadence();
        assertTrue(cadence.tryAcquire(1, 9));
        assertFalse(cadence.tryAcquire(5, 9));
        assertFalse(cadence.tryAcquire(9, 9));
        assertTrue(cadence.tryAcquire(13, 9));
        assertFalse(cadence.tryAcquire(17, 12));
        assertTrue(cadence.tryAcquire(21, 4));
        assertTrue(cadence.tryAcquire(45, 4));
    }

    @Test
    void clockRollbackStartsAFreshCadenceAndDuplicateSameTickDoesNotEmitAgain() {
        EffectCadence cadence = new EffectCadence();
        assertTrue(cadence.tryAcquire(Integer.MAX_VALUE - 1L, 8));
        assertTrue(cadence.tryAcquire(Integer.MIN_VALUE, 8));
        assertFalse(cadence.tryAcquire(Integer.MIN_VALUE, 8));
        assertTrue(cadence.tryAcquire(Integer.MIN_VALUE + 8L, 8));
    }
}
