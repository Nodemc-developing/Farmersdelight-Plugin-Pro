package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CookingPotRecipeManager;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.BlockPosKey;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CookingPotFinishValidationTest {
    @Test void invalidCachedRecipeRunsOneLockedPlanAndNeverClonesOutputsOrDebitsInputs() throws Exception {
        TestPlugin plugin = allocate(TestPlugin.class);
        CookingPotBlockEntity pot = new CookingPotBlockEntity(plugin, new BlockPosKey(0, 0, 0), null, CookingPotLayout.DEFAULT, null);
        RejectingManager manager = new RejectingManager(pot); plugin.manager = manager;
        StubStack input = new StubStack(16), output = new StubStack(1);
        pot.setInventorySlot(0, input);
        CookingPotRecipe recipe = new CookingPotRecipe("test:invalid", List.of(new RecipeIngredient.Item(Key.of("minecraft:stone"))),
                null, false, output, 0, 20, "test", 0);
        var field = CookingPotBlockEntity.class.getDeclaredField("currentRecipe"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var current = (AtomicReference<CookingPotRecipe>) field.get(pot); current.set(recipe);
        input.clones.set(0); output.clones.set(0);
        long version = pot.getInventoryVersion(); pot.setCookingProgress(20);

        assertFalse(pot.finishCooking(null, null));
        assertEquals(1, manager.preparations);
        assertNull(pot.getCurrentRecipe());
        assertEquals(0, input.clones.get(), "Finish must not duplicate an input snapshot before planning its debit");
        assertEquals(0, output.clones.get(), "Invalid recipes must be rejected before cloning a result");
        assertEquals(version, pot.getInventoryVersion());
        assertEquals(16, pot.getInventory()[0].getAmount());
        assertNull(pot.getInventory()[6]); assertNull(pot.getInventory()[8]);
        assertEquals(20, pot.getCookingProgress());
    }

    @Test void aCachedRecipeWithoutAnAvailableManagerIsClearedWithoutProducing() throws Exception {
        CookingPotBlockEntity pot = new CookingPotBlockEntity(new BlockPosKey(0, 0, 0));
        StubStack input = new StubStack(16); pot.setInventorySlot(0, input);
        CookingPotRecipe recipe = new CookingPotRecipe("test:invalid", List.of(), null, false, new StubStack(1), 0, 20, "test", 0);
        var field = CookingPotBlockEntity.class.getDeclaredField("currentRecipe"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var current = (AtomicReference<CookingPotRecipe>) field.get(pot); current.set(recipe);
        long version = pot.getInventoryVersion();
        assertFalse(pot.finishCooking(null, null)); assertNull(pot.getCurrentRecipe());
        assertEquals(version, pot.getInventoryVersion()); assertEquals(16, pot.getInventory()[0].getAmount());
        assertNull(pot.getInventory()[6]); assertNull(pot.getInventory()[8]);
    }

    private static final class TestPlugin extends FarmersDelightPlugin {
        CookingPotRecipeManager manager;
        @Override public CookingPotRecipeManager getCookingPotRecipes() { return manager; }
    }
    private static final class RejectingManager extends CookingPotRecipeManager {
        final CookingPotBlockEntity pot;
        int preparations;
        RejectingManager(CookingPotBlockEntity pot) { super(null); this.pot = pot; }
        @Override public int[] prepareConsumption(CookingPotRecipe recipe, List<ItemStack> inputs) {
            assertTrue(Thread.holdsLock(pot.getLock()));
            try {
                var field = CookingPotBlockEntity.class.getDeclaredField("cookingLock"); field.setAccessible(true);
                assertTrue(Thread.holdsLock(field.get(pot)));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            preparations++;
            assertEquals(6, inputs.size()); assertEquals(16, inputs.getFirst().getAmount());
            return null;
        }
        @Override public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs) {
            throw new AssertionError("Finish must use one strict plan instead of separate revalidation");
        }
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static final class StubStack extends ItemStack {
        private int amount;
        final AtomicInteger clones;
        StubStack(int amount) { this(amount, new AtomicInteger()); }
        private StubStack(int amount, AtomicInteger clones) { super(); this.amount = amount; this.clones = clones; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public Material getType() { return Material.STONE; }
        @Override public boolean hasItemMeta() { return false; }
        @Override public ItemMeta getItemMeta() { return null; }
        @Override public ItemStack clone() { clones.incrementAndGet(); return new StubStack(amount, clones); }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof StubStack; }
    }
}
