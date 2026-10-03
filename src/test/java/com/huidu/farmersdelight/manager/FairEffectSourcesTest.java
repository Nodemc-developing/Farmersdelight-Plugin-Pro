package com.huidu.farmersdelight.manager;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class FairEffectSourcesTest {
    @Test void stableTickOrderCannotStarveTheSourcesAtTheEnd() {
        var budget = new FairEffectSources<Integer>();
        var admitted = new HashSet<Integer>();
        for (int tick = 0; tick < 10; tick++) {
            int count = 0;
            for (int pot = 0; pot < 100; pot++) if (budget.admit(pot, tick * 4L, 12)) {
                admitted.add(pot); count++;
            }
            assertEquals(12, count);
        }
        assertEquals(100, admitted.size());
    }
    @Test void staleSourcesRetireAndNewSourcesCanUseAvailableOpportunities() {
        var budget = new FairEffectSources<String>();
        assertTrue(budget.admit("old", 0, 1));
        assertTrue(budget.admit("new", 100, 1));
    }
    @Test void recentlySeenSourcesKeepTheirTurnAtTheExpiryBoundary() {
        var budget = new FairEffectSources<String>();
        assertTrue(budget.admit("first", 500, 1));
        assertFalse(budget.admit("second", 500, 1));
        assertTrue(budget.admit("first", 580, 1));
        assertFalse(budget.admit("second", 580, 1));
        assertFalse(budget.admit("first", 581, 1));
        assertTrue(budget.admit("second", 581, 1));
        assertTrue(budget.admit("new", 662, 1));
    }
}
