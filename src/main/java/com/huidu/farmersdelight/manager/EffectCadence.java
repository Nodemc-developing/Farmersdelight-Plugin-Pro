package com.huidu.farmersdelight.manager;

/** Per-block emission cadence, read and updated only by that block's owning region. */
final class EffectCadence {
    private long previous = Long.MIN_VALUE;

    boolean tryAcquire(long currentTick, int intervalTicks) {
        if (previous == Long.MIN_VALUE || currentTick < previous
                || currentTick - previous >= Math.max(1, intervalTicks)) {
            previous = currentTick;
            return true;
        }
        return false;
    }
}
