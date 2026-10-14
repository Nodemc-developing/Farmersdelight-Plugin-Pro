package com.huidu.farmersdelight.block.behavior;

import java.util.List;

/** A growth attempt authorizes its complete change set before changing any cell. */
final class CropGrowthTransaction {
    interface Mutation {
        boolean current();
        boolean authorize();
        boolean apply();
        boolean installed();
        void restore();
    }

    private CropGrowthTransaction() { }

    static boolean commit(List<? extends Mutation> changes) {
        if (changes.isEmpty() || changes.stream().anyMatch(change -> !change.current())) return false;
        for (Mutation change : changes) if (!change.authorize()) return false;
        if (changes.stream().anyMatch(change -> !change.current())) return false;
        int attempted = 0;
        boolean committed = false;
        Throwable originalFailure = null;
        try {
            for (Mutation change : changes) {
                attempted++;
                if (!change.apply()) return false;
            }
            if (changes.stream().anyMatch(change -> !change.installed())) return false;
            committed = true;
            return true;
        } catch (RuntimeException | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            if (!committed) {
                Throwable compensationFailure = compensate(changes, attempted);
                if (compensationFailure != null) {
                    if (originalFailure != null) originalFailure.addSuppressed(compensationFailure);
                    else if (compensationFailure instanceof RuntimeException failure) throw failure;
                    else throw (Error) compensationFailure;
                }
            }
        }
    }

    private static Throwable compensate(List<? extends Mutation> changes, int attempted) {
        Throwable failure = null;
        for (int index = attempted - 1; index >= 0; --index) {
            try {
                changes.get(index).restore();
            } catch (RuntimeException | Error problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        return failure;
    }
}
