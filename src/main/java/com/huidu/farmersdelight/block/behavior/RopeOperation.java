package com.huidu.farmersdelight.block.behavior;

import java.util.function.BooleanSupplier;

/** Checks and commits an owner-thread rope edit before consuming or returning an item. */
public final class RopeOperation {
    private RopeOperation() { }

    public static boolean commit(BooleanSupplier allowed, BooleanSupplier unchanged, BooleanSupplier edit, Runnable afterSuccess) {
        if (!allowed.getAsBoolean() || !unchanged.getAsBoolean() || !edit.getAsBoolean()) return false;
        afterSuccess.run();
        return true;
    }

    public static boolean commitWithCompensation(BooleanSupplier allowed, BooleanSupplier unchanged,
                                                 BooleanSupplier edit, BooleanSupplier itemCommit, BooleanSupplier restore) {
        if (!allowed.getAsBoolean() || !unchanged.getAsBoolean() || !edit.getAsBoolean()) return false;
        if (itemCommit.getAsBoolean()) return true;
        if (!restore.getAsBoolean()) throw new IllegalStateException("Rope exchange could not restore its world edit after an unsuccessful inventory write");
        return false;
    }
}
