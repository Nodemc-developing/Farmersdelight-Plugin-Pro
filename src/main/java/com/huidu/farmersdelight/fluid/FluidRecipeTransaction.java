package com.huidu.farmersdelight.fluid;

import java.util.function.Consumer;

/** Applies a complete recipe in one native storage and inventory transaction. */
final class FluidRecipeTransaction {
    interface Transaction extends AutoCloseable {
        void commit();
        boolean committed();
        @Override void close();
    }

    interface Operation {
        boolean inventoryFits();
        Transaction open();
        long transfer(Transaction transaction);
        boolean replace(Transaction transaction);
    }

    static FluidCoreBridge.Outcome apply(Operation operation, long amount, boolean fillingTank,
                                        boolean simulate, Consumer<String> warning) {
        if (!operation.inventoryFits()) return FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
        Transaction transaction = null;
        FluidCoreBridge.Outcome outcome;
        try {
            transaction = operation.open();
            if (operation.transfer(transaction) != amount) {
                outcome = fillingTank ? FluidCoreBridge.Outcome.NO_CAPACITY : FluidCoreBridge.Outcome.NO_FLUID;
            } else if (simulate) {
                outcome = FluidCoreBridge.Outcome.READY;
            } else if (!operation.replace(transaction)) {
                outcome = FluidCoreBridge.Outcome.NO_INVENTORY_SPACE;
            } else {
                transaction.commit();
                outcome = FluidCoreBridge.Outcome.SUCCESS;
            }
        } catch (RuntimeException failure) {
            if (transaction != null && transaction.committed()) {
                warning.accept("Fluid recipe committed, but a notification failed: " + failure.getMessage());
                outcome = FluidCoreBridge.Outcome.SUCCESS;
            } else {
                warning.accept("Fluid recipe transaction failed: " + failure.getMessage());
                outcome = FluidCoreBridge.Outcome.TRANSACTION_FAILED;
            }
        }
        if (transaction != null) {
            try { transaction.close(); }
            catch (RuntimeException failure) {
                warning.accept("Fluid recipe transaction close failed: " + failure.getMessage());
                if (!transaction.committed()) outcome = FluidCoreBridge.Outcome.TRANSACTION_FAILED;
            }
        }
        return outcome;
    }

    private FluidRecipeTransaction() { }
}
