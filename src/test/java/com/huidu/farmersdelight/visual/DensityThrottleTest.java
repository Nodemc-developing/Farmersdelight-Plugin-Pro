package com.huidu.farmersdelight.visual;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DensityThrottleTest {
    @Test void densityOnlyThrottlesAboveThresholdAndNeverBelowTheConfiguredFloor() {
        assertEquals(1D, DensityThrottle.rate(4, 4, .05));
        assertEquals(.5D, DensityThrottle.rate(8, 4, .05));
        assertEquals(.05D, DensityThrottle.rate(1000, 4, .05));
        assertEquals(1D, DensityThrottle.rate(1000, 0, .05));
    }
    @Test void staleSourcesDoNotKeepQuietChunksThrottled() {
        var throttle = new DensityThrottle<String>();
        assertEquals(1D, throttle.observe("first", 1L, 1, .1));
        assertEquals(.5D, throttle.observe("second", 2L, 1, .1));
        assertEquals(1D, throttle.observe("third", 5_000_000_000L, 1, .1));
    }
}
