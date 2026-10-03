package com.huidu.farmersdelight.fluid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeTransactionTest {
    @Test void foreignFluidRecordsRemainProtectedWhileNativeCoreRecordsAreAllowed() {
        assertTrue(FluidCoreBridge.foreignFluidKey("libuid:saved_jug"));
        assertTrue(FluidCoreBridge.foreignFluidKey("papersdelight:jug"));
        assertTrue(FluidCoreBridge.foreignFluidKey("another:fluid_data"));
        assertTrue(FluidCoreBridge.foreignFluidKey("another:LIQUID_CONTENTS"));
        assertFalse(FluidCoreBridge.foreignFluidKey("fluidcore:item_fluid"));
        assertFalse(FluidCoreBridge.foreignFluidKey("craftengine:id"));
    }

    @Test void savedTankIdentityIsProtectedForRecipesWithoutBeingBlockedByTheForeignPlacementGuard() {
        assertTrue(FluidCoreBridge.hasPreservedTankData(probeStack("fluidcore", "tank_data")));
        assertFalse(FluidCoreBridge.foreignFluidKey("fluidcore:tank_data"));
        assertFalse(FluidCoreBridge.hasPreservedTankData(probeStack("fluidcore", "container_data")));
        assertFalse(FluidCoreBridge.hasPreservedTankData(probeStack("other", "tank_data")));
        assertTrue(FluidCoreBridge.foreignFluidKey("other:tank_data"));
        assertFalse(FluidCoreBridge.hasPreservedTankData(null));
    }

    private static org.bukkit.inventory.ItemStack probeStack(String namespace, String path) {
        org.bukkit.NamespacedKey present = new org.bukkit.NamespacedKey(namespace, path);
        var pdc = (org.bukkit.persistence.PersistentDataContainer) java.lang.reflect.Proxy.newProxyInstance(
                FluidRecipeTransactionTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.persistence.PersistentDataContainer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("has") && args.length == 1) return present.equals(args[0]);
                    throw new UnsupportedOperationException(method.getName());
                });
        var meta = (org.bukkit.inventory.meta.ItemMeta) java.lang.reflect.Proxy.newProxyInstance(
                FluidRecipeTransactionTest.class.getClassLoader(), new Class<?>[]{org.bukkit.inventory.meta.ItemMeta.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPersistentDataContainer")) return pdc;
                    throw new UnsupportedOperationException(method.getName());
                });
        return new ProbeStack(meta);
    }

    private static final class ProbeStack extends org.bukkit.inventory.ItemStack {
        private final org.bukkit.inventory.meta.ItemMeta meta;
        ProbeStack(org.bukkit.inventory.meta.ItemMeta meta) { super(); this.meta = meta; }
        @Override public org.bukkit.Material getType() {
            throw new AssertionError("Saved tank classification must only inspect persistent data");
        }
        @Override public org.bukkit.inventory.meta.ItemMeta getItemMeta() { return meta; }
        @Override public io.papermc.paper.persistence.PersistentDataContainerView getPersistentDataContainer() { return meta.getPersistentDataContainer(); }
    }

    @Test void simulationRollsFluidBackWithoutEverWritingInventory() {
        FakeOperation operation = new FakeOperation();
        assertEquals(FluidCoreBridge.Outcome.READY, run(operation, false, true));
        assertEquals(2000, operation.fluid);
        assertEquals(3, operation.input);
        assertEquals(0, operation.output);
        assertEquals(0, operation.replacements);
        assertFalse(operation.transaction.committed);
    }

    @Test void completeAmountAndReplacementCommitTogether() {
        FakeOperation operation = new FakeOperation();
        assertEquals(FluidCoreBridge.Outcome.SUCCESS, run(operation, false, false));
        assertEquals(1000, operation.fluid);
        assertEquals(2, operation.input);
        assertEquals(1, operation.output);
        assertTrue(operation.transaction.committed);
    }

    @Test void partialExtractionIsRolledBackRatherThanProducingAWholeResult() {
        FakeOperation operation = new FakeOperation();
        operation.maximumTransfer = 250;
        assertEquals(FluidCoreBridge.Outcome.NO_FLUID, run(operation, false, false));
        assertEquals(2000, operation.fluid);
        assertEquals(3, operation.input);
        assertEquals(0, operation.replacements);
    }

    @Test void partialInsertionIsRolledBackWithoutConsumingTheFilledContainer() {
        FakeOperation operation = new FakeOperation();
        operation.maximumTransfer = 250;
        operation.insertion = true;
        assertEquals(FluidCoreBridge.Outcome.NO_CAPACITY, run(operation, true, false));
        assertEquals(2000, operation.fluid);
        assertEquals(3, operation.input);
    }

    @Test void fullInventoryPreventsEvenOpeningAFluidTransaction() {
        FakeOperation operation = new FakeOperation();
        operation.space = false;
        assertEquals(FluidCoreBridge.Outcome.NO_INVENTORY_SPACE, run(operation, false, false));
        assertNull(operation.transaction);
        assertEquals(2000, operation.fluid);
    }

    @Test void inventoryRevalidationFailureRollsBackTheTentativeFluidTransfer() {
        FakeOperation operation = new FakeOperation();
        operation.rejectReplacement = true;
        assertEquals(FluidCoreBridge.Outcome.NO_INVENTORY_SPACE, run(operation, false, false));
        assertEquals(2000, operation.fluid);
        assertEquals(3, operation.input);
    }

    @Test void commitValidationFailureRollsBackFluidAndInventory() {
        FakeOperation operation = new FakeOperation();
        operation.commitFailure = true;
        assertEquals(FluidCoreBridge.Outcome.TRANSACTION_FAILED, run(operation, false, false));
        assertEquals(2000, operation.fluid);
        assertEquals(3, operation.input);
        assertEquals(0, operation.output);
    }

    @Test void committedNotificationFailureRemainsSuccessfulAndIsNeverRetried() {
        FakeOperation operation = new FakeOperation();
        operation.notificationFailure = true;
        List<String> warnings = new ArrayList<>();
        assertEquals(FluidCoreBridge.Outcome.SUCCESS,
                FluidRecipeTransaction.apply(operation, 1000, false, false, warnings::add));
        assertEquals(1000, operation.fluid);
        assertEquals(2, operation.input);
        assertEquals(1, operation.output);
        assertEquals(1, operation.transfers);
        assertEquals(1, operation.replacements);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("committed"));
    }

    private static FluidCoreBridge.Outcome run(FakeOperation operation, boolean insertion, boolean simulate) {
        return FluidRecipeTransaction.apply(operation, 1000, insertion, simulate, ignored -> { });
    }

    private static final class FakeOperation implements FluidRecipeTransaction.Operation {
        long fluid = 2000;
        int input = 3, output, transfers, replacements;
        long maximumTransfer = 1000;
        boolean space = true, insertion, rejectReplacement, commitFailure, notificationFailure;
        FakeTransaction transaction;
        @Override public boolean inventoryFits() { return space; }
        @Override public FluidRecipeTransaction.Transaction open() {
            transaction = new FakeTransaction(this);
            return transaction;
        }
        @Override public long transfer(FluidRecipeTransaction.Transaction tx) {
            transfers++;
            fluid += insertion ? maximumTransfer : -maximumTransfer;
            return maximumTransfer;
        }
        @Override public boolean replace(FluidRecipeTransaction.Transaction tx) {
            replacements++;
            if (rejectReplacement) return false;
            input--;
            output++;
            return true;
        }
    }

    private static final class FakeTransaction implements FluidRecipeTransaction.Transaction {
        final FakeOperation operation;
        final long fluid;
        final int input, output;
        boolean committed;
        FakeTransaction(FakeOperation operation) {
            this.operation = operation;
            fluid = operation.fluid; input = operation.input; output = operation.output;
        }
        @Override public void commit() {
            if (operation.commitFailure) throw new IllegalStateException("Validation rejected");
            committed = true;
            if (operation.notificationFailure) throw new IllegalStateException("Notification rejected");
        }
        @Override public boolean committed() { return committed; }
        @Override public void close() {
            if (!committed) {
                operation.fluid = fluid;
                operation.input = input;
                operation.output = output;
            }
        }
    }
}
