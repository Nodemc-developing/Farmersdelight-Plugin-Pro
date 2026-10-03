package com.huidu.farmersdelight.manager;

/** A jump can change the food face only once, after the player has actually left the ground. */
final class HandheldFlipState {
    private enum Phase { READY, JUMP_REQUESTED, AIRBORNE }
    private Phase phase = Phase.READY;
    private boolean flipped;
    void jump() { if (phase == Phase.READY) phase = Phase.JUMP_REQUESTED; }
    boolean sample(boolean grounded) {
        if (!grounded && phase == Phase.JUMP_REQUESTED) phase = Phase.AIRBORNE;
        else if (grounded && phase == Phase.AIRBORNE) {
            phase = Phase.READY;
            flipped = !flipped;
            return true;
        }
        return false;
    }
    boolean flipped() { return flipped; }
    void reset() { phase = Phase.READY; flipped = false; }
}
