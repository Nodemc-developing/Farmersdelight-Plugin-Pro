package com.huidu.farmersdelight.fluid;

import com.ydxc20091.fluidcore.ce.FluidTankController;
import com.ydxc20091.fluidcore.ce.TankProcessor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owner-native recipe work sleeps after a failed match and wakes on slot/fluid/neighbor changes. */
final class TankRecipeProcessor implements TankProcessor {
    private record Pending(FluidRecipeSpec recipe, ItemStack input, long generation, long elapsed) {}
    private final FluidRecipeManager recipes;
    private final TypedFluidCoreAccess adapter;
    private final Map<FluidTankController, Pending> pending = new ConcurrentHashMap<>();
    TankRecipeProcessor(FluidRecipeManager recipes, TypedFluidCoreAccess adapter) { this.recipes = recipes; this.adapter = adapter; }
    @Override public boolean interact(FluidTankController tank, Player player, int slot) {
        if (tank.hasProtectedData() || !adapter.canUseHeld(player, tank.location())) return false;
        if (slot < 0 || slot >= player.getInventory().getSize()) return false;
        return HeldFluidRecipes.process(recipes.candidates(player.getInventory().getItem(slot)),
                recipe -> adapter.process(player, slot, tank.location(), recipe, false));
    }
    @Override public boolean tick(FluidTankController tank, int elapsedTicks) {
        if (tank.hasProtectedData()) { resetPending(tank); return false; }
        ItemStack input = tank.inventory().input();
        if (input == null) { resetPending(tank); return false; }
        TankInputRecipes.Decision decision = TankInputRecipes.select(recipes.candidates(input),
                recipe -> adapter.processTank(tank, recipe, false),
                () -> adapter.processGenericTank(tank, true, false).success(),
                () -> adapter.processGenericTank(tank, false, false).success(),
                recipe -> adapter.processTank(tank, recipe, true).ready());
        if (decision.completed()) { resetPending(tank); return tank.inventory().input() != null; }
        if (decision.soaking() == null) { resetPending(tank); return false; }
        Pending operation = pending.get(tank);
        if (operation == null || operation.generation != recipes.generation() || !operation.input.equals(input)
                || !operation.recipe.equals(decision.soaking())) {
            resetPending(tank);
            operation = new Pending(decision.soaking(), input, recipes.generation(), 0);
        }
        long elapsed = Math.addExact(operation.elapsed, elapsedTicks);
        if (elapsed < operation.recipe.timeTicks()) {
            pending.put(tank, new Pending(operation.recipe, operation.input, operation.generation, elapsed));
            if (operation.elapsed == 0 || elapsed / 5 != operation.elapsed / 5) tank.progressChanged();
            return true;
        }
        resetPending(tank);
        return adapter.processTank(tank, operation.recipe, false).success() && tank.inventory().input() != null;
    }
    private void resetPending(FluidTankController tank) { if (pending.remove(tank) != null) tank.progressChanged(); }
    @Override public void retired(FluidTankController tank) { pending.remove(tank); }
    @Override public Progress progress(FluidTankController tank) {
        Pending operation = pending.get(tank);
        return operation == null ? null : new Progress(operation.recipe.id(), operation.elapsed, operation.recipe.timeTicks());
    }
    void clear() { pending.clear(); }
}
