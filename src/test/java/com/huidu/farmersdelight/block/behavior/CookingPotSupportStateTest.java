package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CookingPotSupportStateTest {
    @Test void integerSupportMatchesContentPackVariantsAndRoundTrips() {
        assertEquals("none", CookingPotSupportState.fromInteger(0));
        assertEquals("tray", CookingPotSupportState.fromInteger(1));
        assertEquals("handle", CookingPotSupportState.fromInteger(2));
        for (int value = 0; value < 3; value++) {
            assertEquals(value, CookingPotSupportState.toInteger(CookingPotSupportState.fromInteger(value)));
        }
    }

    @Test void invalidStatesCannotSilentlySelectAnotherRenderer() {
        assertNull(CookingPotSupportState.fromInteger(null));
        assertNull(CookingPotSupportState.fromInteger(3));
        assertThrows(IllegalArgumentException.class, () -> CookingPotSupportState.toInteger("unsupported"));
    }
}
