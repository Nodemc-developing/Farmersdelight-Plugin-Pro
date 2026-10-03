package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkewerUseLeaseDurationTest {
    @Test void nonConsumableSourceStillGetsACompleteCookingBudget() {
        assertEquals(27, SkewerUseLease.durationTicks(6, 0));
        assertEquals(141, SkewerUseLease.durationTicks(120, 0));
        assertEquals(72_021, SkewerUseLease.durationTicks(72_000, 0));
    }
    @Test void existingNativeDurationIsNeverShortened() {
        assertEquals(32, SkewerUseLease.durationTicks(6, 32));
        assertEquals(1_200, SkewerUseLease.durationTicks(120, 1_200));
        assertEquals(Integer.MAX_VALUE, SkewerUseLease.durationTicks(1, Integer.MAX_VALUE));
    }
    @Test void floatRoundTripCannotTakeTicksAwayFromTheNativeBudget() {
        for (int cooking = 1; cooking <= 72_000; cooking++) {
            int ticks = SkewerUseLease.durationTicks(cooking, 0);
            float seconds = Math.nextUp(ticks / 20f);
            assertTrue((int) (seconds * 20f) >= ticks, "lost ticks at " + cooking);
        }
    }
    @Test void invalidDurationsCannotProduceAUsableLease() {
        assertThrows(IllegalArgumentException.class, () -> SkewerUseLease.durationTicks(0, 0));
        assertThrows(IllegalArgumentException.class, () -> SkewerUseLease.durationTicks(72_001, 0));
        assertThrows(IllegalArgumentException.class, () -> SkewerUseLease.durationTicks(1, -1));
    }
}
