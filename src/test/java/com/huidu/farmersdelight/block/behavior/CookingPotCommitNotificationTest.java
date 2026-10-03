package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Isolated("Temporarily supplies known non-air Material queries and restores their registry suppliers")
@Execution(ExecutionMode.SAME_THREAD)
class CookingPotCommitNotificationTest {
    private static final int INPUT = CookingPotLayout.DEFAULT.inputSlots()[0];
    private static final int OUTPUT = CookingPotLayout.DEFAULT.outputSlots()[0];
    private static final int PENDING = CookingPotLayout.DEFAULT.pendingOutputSlots()[0];
    private static final int CONTAINER = CookingPotLayout.DEFAULT.containerSlots()[0];
    private static final Map<Material, Object> originalBlockTypes = new EnumMap<>(Material.class);

    @BeforeAll static void supplyOnlyTheFixtureMaterialsNonAirQuery() throws Exception {
        // Paper's Material.isAir consults a live registry. These unit-test stacks use three known
        // non-air materials; their temporary null block-type suppliers answer that query without
        // bootstrapping a server. No entity inventory or serving operation is replaced by this fixture.
        var field = Material.class.getDeclaredField("blockType"); field.setAccessible(true);
        try {
            for (Material material : List.of(Material.STONE, Material.BREAD, Material.BOWL)) {
                originalBlockTypes.put(material, field.get(material));
                field.set(material, (java.util.function.Supplier<Object>) () -> null);
            }
        } catch (Exception | Error failure) {
            try { restoreEveryOriginalRegistrySupplier(); }
            catch (Exception | Error restoration) { if (restoration != failure) failure.addSuppressed(restoration); }
            throw failure;
        }
    }

    @AfterAll static void restoreEveryOriginalRegistrySupplier() throws Exception {
        var field = Material.class.getDeclaredField("blockType"); field.setAccessible(true);
        for (var entry : originalBlockTypes.entrySet()) {
            field.set(entry.getKey(), entry.getValue()); assertSame(entry.getValue(), field.get(entry.getKey()));
        }
        originalBlockTypes.clear();
    }

    @Test void completedCookPublishesOneFinalInventoryAndProgressCommit() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 2), false);
        assertTrue(pot.finishCooking(null, null));
        assertEquals(1, pot.notifications.size());
        assertEquals(new Observation(15, 2, 0, 0, 0, true, true, true), pot.notifications.getFirst());
        assertEquals(1, pot.handler.applications);
    }

    @Test void servingConsumesOnlyContainersForPortionsThatFitAndPreservesThePendingRemainder() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 4), true);
        pot.setInventorySlot(OUTPUT, new Stack(Material.BREAD, 62));
        pot.setInventorySlot(CONTAINER, new Stack(Material.BOWL, 3));
        pot.notifications.clear();
        assertTrue(pot.finishCooking(null, null));
        assertEquals(List.of(new Observation(15, 64, 2, 1, 0, true, true, true)), pot.notifications);
        assertNotNull(pot.getMealContainer());
    }

    @Test void aFullOutputAndPendingBufferNeverDebitInputAndStillPublishTheProgressBoundary() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 1), false);
        pot.setInventorySlot(OUTPUT, new Stack(Material.BREAD, 64));
        pot.setInventorySlot(PENDING, new Stack(Material.BREAD, 64));
        pot.setCookingProgress(12); pot.notifications.clear();
        assertFalse(pot.finishCooking(null, null));
        assertEquals(List.of(new Observation(16, 64, 64, 0, 20, false, true, true)), pot.notifications);
        assertEquals(0, pot.handler.applications);
    }

    @Test void blockedServingRetainsTheWholeNewMealInTheBufferWithOneIngredientDebit() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 3), false);
        pot.setInventorySlot(OUTPUT, new Stack(Material.STONE, 64)); pot.notifications.clear();
        assertTrue(pot.finishCooking(null, null));
        assertEquals(1, pot.notifications.size());
        assertEquals(15, amount(pot, INPUT)); assertEquals(64, amount(pot, OUTPUT)); assertEquals(3, amount(pot, PENDING));
        assertEquals(1, pot.handler.applications);
    }

    @Test void aMealNeedingUnavailableContainersRemainsPendingAndIsStillPublished() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 2), true);
        assertTrue(pot.finishCooking(null, null));
        assertEquals(List.of(new Observation(15, 0, 2, 0, 0, true, true, true)), pot.notifications);
        assertNotNull(pot.getMealContainer());
    }

    @Test void publicServingNotifiesImmediatelyOnlyWhenItActuallyChangesTheBatch() throws Exception {
        CountingPot pot = new CountingPot();
        pot.setInventorySlot(PENDING, new Stack(Material.BREAD, 3));
        pot.setInventorySlot(CONTAINER, new Stack(Material.BOWL, 2));
        mealContainer(pot).set(new Stack(Material.BOWL, 1)); pot.notifications.clear();
        pot.tryMovePendingToOutput();
        assertEquals(List.of(new Observation(0, 2, 1, 0, 0, true, false, false)), pot.notifications);
        assertNotNull(pot.getMealContainer());
        pot.tryMovePendingToOutput();
        assertEquals(1, pot.notifications.size(), "An unchanged public serving call must not publish another notification");
    }

    @Test void publicServingClearsAndPublishesAStaleContainerWithoutPendingMeals() throws Exception {
        CountingPot pot = new CountingPot(); mealContainer(pot).set(new Stack(Material.BOWL, 1));
        pot.tryMovePendingToOutput();
        assertNull(pot.getMealContainer()); assertEquals(1, pot.notifications.size());
        pot.tryMovePendingToOutput(); assertEquals(1, pot.notifications.size());
    }

    @Test void aFailureAfterIngredientDebitStillPublishesTheActualPartialMutation() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 2), false);
        RuntimeException primary = new IllegalStateException("remainder delivery failed"); pot.handler.failure = primary;
        assertSame(primary, assertThrows(IllegalStateException.class, () -> pot.finishCooking(null, null)));
        assertEquals(List.of(new Observation(15, 2, 0, 0, 20, false, true, true)), pot.notifications);
        assertEquals(1, pot.handler.applications);
    }

    @Test void aFailureAfterStoringBeforeServingStillMarksThePendingMealDirty() throws Exception {
        RuntimeException primary = new IllegalStateException("serving snapshot failed");
        Stack result = new Stack(Material.BREAD, 2, new AtomicInteger(), 5, primary);
        CountingPot pot = pot(result, false);
        assertSame(primary, assertThrows(IllegalStateException.class, () -> pot.finishCooking(null, null)));
        assertEquals(List.of(new Observation(16, 0, 2, 0, 20, false, true, true)), pot.notifications);
        assertEquals(0, pot.handler.applications);
    }

    @Test void dirtyNotificationFailureDoesNotHideTheOriginalMutationFailure() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 2), false);
        RuntimeException primary = new IllegalStateException("remainder delivery failed");
        RuntimeException secondary = new IllegalArgumentException("dirty notification failed");
        pot.handler.failure = primary; pot.notificationFailure = secondary;
        assertSame(primary, assertThrows(IllegalStateException.class, () -> pot.finishCooking(null, null)));
        assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());
        assertEquals(1, pot.notifications.size()); assertEquals(15, amount(pot, INPUT)); assertEquals(2, amount(pot, OUTPUT));
    }

    @Test void dirtyNotificationFailureAfterSuccessPropagatesWithoutRepeatingTheInventoryCommit() throws Exception {
        CountingPot pot = pot(new Stack(Material.BREAD, 2), false);
        RuntimeException failure = new IllegalStateException("dirty notification failed"); pot.notificationFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> pot.finishCooking(null, null)));
        assertEquals(1, pot.notifications.size()); assertEquals(1, pot.handler.applications);
        assertEquals(15, amount(pot, INPUT)); assertEquals(2, amount(pot, OUTPUT));
        assertEquals(0, pot.getCookingProgress()); assertNull(pot.getCurrentRecipe());
    }

    private static CountingPot pot(Stack result, boolean container) throws Exception {
        CountingPot pot = new CountingPot();
        pot.setInventorySlot(INPUT, new Stack(Material.STONE, 16));
        CookingPotRecipe recipe = new CookingPotRecipe("test:commit", List.of(),
                container ? new Stack(Material.BOWL, 1) : null, container, result, 0, 20, "test", 0);
        reference(pot, "currentRecipe").set(recipe); pot.setCookingDuration(20); pot.setCookingProgress(20);
        // Matching has its own differential tests. This fixture supplies a prepared debit while the real
        // entity stores, serves and consumes it, so no running CE registry or copied transaction is needed.
        pot.handler = new PreparedHandler(pot);
        var field = CookingPotBlockEntity.class.getDeclaredField("craftingHandler"); field.setAccessible(true); field.set(pot, pot.handler);
        pot.notifications.clear(); return pot;
    }

    @SuppressWarnings("unchecked")
    private static <T> AtomicReference<T> reference(CountingPot pot, String name) throws Exception {
        var field = CookingPotBlockEntity.class.getDeclaredField(name); field.setAccessible(true);
        return (AtomicReference<T>) field.get(pot);
    }
    private static AtomicReference<ItemStack> mealContainer(CountingPot pot) throws Exception { return reference(pot, "mealContainerStack"); }
    private static int amount(CookingPotBlockEntity pot, int slot) { ItemStack item = pot.getInventory()[slot]; return item == null ? 0 : item.getAmount(); }
    private record Observation(int input, int output, int pending, int containers, int progress,
                               boolean recipeCleared, boolean inventoryLock, boolean cookingLock) { }

    private static final class CountingPot extends CookingPotBlockEntity {
        final List<Observation> notifications = new ArrayList<>();
        PreparedHandler handler;
        RuntimeException notificationFailure;
        CountingPot() { super(new BlockPosKey(0, 0, 0)); }
        @Override void syncWorldlyContainer() {
            boolean cookingHeld;
            try {
                var field = CookingPotBlockEntity.class.getDeclaredField("cookingLock"); field.setAccessible(true);
                cookingHeld = Thread.holdsLock(field.get(this));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            notifications.add(new Observation(amount(this, INPUT), amount(this, OUTPUT), amount(this, PENDING), amount(this, CONTAINER),
                    getCookingProgress(), getCurrentRecipe() == null, Thread.holdsLock(getLock()), cookingHeld));
            if (notificationFailure != null) throw notificationFailure;
        }
    }
    private static final class PreparedHandler extends CookingPotCraftingHandler {
        final CountingPot pot;
        int applications;
        RuntimeException failure;
        PreparedHandler(CountingPot pot) { super(pot); this.pot = pot; }
        @Override Consumption prepareConsumption(CookingPotRecipe recipe) {
            assertTrue(Thread.holdsLock(pot.getLock()));
            return new Consumption(new int[]{1, 0, 0, 0, 0, 0}, List.of());
        }
        @Override void consumeIngredientsInternal(Consumption consumption, World world, Location location) {
            applications++; super.consumeIngredientsInternal(consumption, world, location);
            if (failure != null) throw failure;
        }
    }
    private static class Stack extends ItemStack {
        private final Material material;
        private int amount;
        private final AtomicInteger copies;
        private final int failOnCopy;
        private final RuntimeException copyFailure;
        Stack(Material material, int amount) { this(material, amount, new AtomicInteger(), -1, null); }
        Stack(Material material, int amount, AtomicInteger copies, int failOnCopy, RuntimeException copyFailure) {
            super(); this.material = material; this.amount = amount; this.copies = copies;
            this.failOnCopy = failOnCopy; this.copyFailure = copyFailure;
        }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public int getMaxStackSize() { return 64; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack && other.getType() == material; }
        @Override public ItemStack clone() {
            if (copies.incrementAndGet() == failOnCopy) throw copyFailure;
            return new Stack(material, amount, copies, failOnCopy, copyFailure);
        }
    }
}
