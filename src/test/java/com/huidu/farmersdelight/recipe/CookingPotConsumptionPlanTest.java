package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CookingPotConsumptionPlanTest {
    private record Input(String id, int amount, boolean nbt) { }
    private record Required(Set<String> choices, boolean nbt) { }
    private static Input input(String id, int amount) { return new Input(id, amount, true); }
    private static Required required(String... ids) { return new Required(Set.of(ids), false); }
    private static boolean empty(Input input) { return input == null || "air".equals(input.id()); }
    private static boolean matches(Input input, Required requirement) {
        return !empty(input) && input.id() != null && requirement.choices().contains(input.id()) && (!requirement.nbt() || input.nbt());
    }
    private static int[] plan(List<Input> inputs, List<Required> requirements, boolean fuzzy,
                              Map<String, Integer> counts, AtomicInteger resolutions) {
        return CookingPotRecipeManager.consumptionAssignment(inputs, requirements, fuzzy, counts,
                input -> empty(input) || fuzzy && input.amount() <= 0,
                input -> { resolutions.incrementAndGet(); return new Input(input.id(), input.amount(), input.nbt()); },
                Input::amount, Input::id, CookingPotConsumptionPlanTest::matches);
    }
    private static int[] plan(List<Input> inputs, List<Required> requirements) {
        return plan(inputs, requirements, false, null, new AtomicInteger());
    }

    @Test void prefersOneUnitPerFilledSlotInsteadOfDrainingASingleLargeStack() {
        int[] slots = plan(List.of(input("a", 64), input("a", 64)), List.of(required("a"), required("a")));
        assertNotNull(slots); assertEquals(Set.of(0, 1), Set.of(slots[0], slots[1]));
    }

    @Test void overlappingBroadAndNarrowRequirementsReassignRatherThanGreedilyFail() {
        assertArrayEquals(new int[]{1, 0}, plan(List.of(input("a", 1), input("b", 1)),
                List.of(required("a", "b"), required("a"))));
    }

    @Test void stackFallbackWorksOnlyAfterTheExistingFilledSlotGatePasses() {
        int[] slots = plan(List.of(input("a", 3), input("b", 1), input("b", 1)),
                List.of(required("a"), required("a"), required("b")));
        assertNotNull(slots); assertEquals(2L, Arrays.stream(slots).filter(slot -> slot == 0).count());
        assertNull(plan(List.of(input("a", 3), input("b", 1)),
                List.of(required("a"), required("a"), required("b"))));
    }

    @Test void anUnusableExtraFilledSlotCannotBeIgnoredByConsumption() {
        assertNull(plan(List.of(input("a", 64), input("junk", 1)), List.of(required("a"))));
        assertNull(plan(List.of(input("a", 64), input(null, 1)), List.of(required("a"))));
    }

    @Test void matchingIdentityDoesNotBypassTheNbtPredicate() {
        Required predicate = new Required(Set.of("a"), true);
        assertNull(plan(List.of(new Input("a", 64, false)), List.of(predicate)));
        assertArrayEquals(new int[]{0}, plan(List.of(new Input("a", 64, true)), List.of(predicate)));
    }

    @Test void assignmentMapsBackToOriginalSlotsAcrossNullAndAirInputs() {
        assertArrayEquals(new int[]{3, 1}, plan(Arrays.asList(null, input("a", 1), input("air", 64), input("b", 1)),
                List.of(required("a", "b"), required("a"))));
    }

    @Test void identityIsResolvedOncePerInputEvenWithOverlapsAndStackFallback() {
        AtomicInteger resolutions = new AtomicInteger();
        int[] result = plan(Arrays.asList(null, input("a", 64), input("air", 1), input("b", 64), input("b", 64)),
                List.of(required("a"), required("a"), required("b")), false, null, resolutions);
        assertNotNull(result); assertEquals(3, resolutions.get());
        assertEquals(2L, Arrays.stream(result).filter(slot -> slot == 1).count());
    }

    @Test void aLaterOperationResolvesTheCurrentIdentityAndNbtInsteadOfReusingACachedPlan() {
        AtomicInteger resolutions = new AtomicInteger(); Required predicate = new Required(Set.of("a"), true);
        assertNotNull(plan(List.of(new Input("a", 2, true)), List.of(predicate), false, null, resolutions));
        assertNull(plan(List.of(new Input("a", 2, false)), List.of(predicate), false, null, resolutions));
        assertNull(plan(List.of(input("b", 2)), List.of(predicate), false, null, resolutions));
        assertEquals(3, resolutions.get());
    }

    @Test void fuzzyCountsUseFilledSlotsAndConsumeOneFromEachWithOriginalSlotIndices() {
        AtomicInteger resolutions = new AtomicInteger();
        int[] result = plan(Arrays.asList(null, input("a", 64), input("air", 2), input("a", 2), input("b", 1)),
                List.of(), true, Map.of("a", 2, "b", 1), resolutions);
        assertArrayEquals(new int[]{1, 3, 4}, result); assertEquals(3, resolutions.get());
    }

    @Test void staleMissingUnknownOrExtraFuzzyInputsRejectWithoutPartialAssignment() {
        Map<String, Integer> expected = Map.of("a", 2, "b", 1);
        assertNull(plan(List.of(input("a", 64), input("b", 1)), List.of(), true, expected, new AtomicInteger()));
        assertNull(plan(List.of(input("a", 64), input("a", 1), input("junk", 1)), List.of(), true, expected, new AtomicInteger()));
        assertNull(plan(List.of(input("a", 64), input("a", 1), input(null, 1)), List.of(), true, expected, new AtomicInteger()));
        AtomicInteger resolutions = new AtomicInteger();
        assertNull(plan(List.of(input("a", 1)), List.of(), true, null, resolutions)); assertEquals(0, resolutions.get());
    }

    @Test void fuzzyZeroStacksAreExcludedAndDoNotTriggerIdentityReads() {
        AtomicInteger resolutions = new AtomicInteger();
        assertArrayEquals(new int[]{1}, plan(List.of(input("junk", 0), input("a", 64)), List.of(), true, Map.of("a", 1), resolutions));
        assertEquals(1, resolutions.get());
    }

    @Test void combinedPlanIsEquivalentToThePreviousValidationAndDebitFor1024Inventories() {
        List<Input> options = Arrays.asList(null, input("a", 1), input("b", 2), input("x", 0));
        List<Required> ingredients = List.of(required("a"), required("b"), required("a", "b"), required("x"));
        int checked = 0;
        for (Input a : options) for (Input b : options) for (Input c : options) {
            List<Input> inputs = Arrays.asList(a, b, c);
            List<Input> filled = new ArrayList<>(); for (Input input : inputs) if (!empty(input)) filled.add(input);
            for (Required first : ingredients) for (Required second : ingredients) {
                List<Required> required = List.of(first, second);
                boolean valid = IngredientMatching.matchesIngredients(required, filled, false,
                        CookingPotConsumptionPlanTest::matches, Input::amount);
                int[] previous = null;
                if (valid) {
                    previous = IngredientMatching.assignIngredients(required, inputs, CookingPotConsumptionPlanTest::matches,
                            input -> empty(input) || input.amount() <= 0 ? 0 : 1);
                    if (previous == null) previous = IngredientMatching.assignIngredients(required, inputs,
                            CookingPotConsumptionPlanTest::matches, input -> empty(input) ? 0 : input.amount());
                }
                assertArrayEquals(previous, plan(inputs, required), "Validation/debit semantics changed for " + inputs + " / " + required);
                checked++;
            }
        }
        assertEquals(1024, checked);
    }

    @Test void aSingleIngredientChecksAndResolvesEveryFilledSlotOnceAndChoosesTheFirstPositiveOriginalSlot() {
        AtomicInteger resolutions = new AtomicInteger(), predicates = new AtomicInteger();
        List<Input> inputs = Arrays.asList(input("a", 0), null, input("air", 64), input("a", 64), input("a", 2));
        int[] slots = CookingPotRecipeManager.consumptionAssignment(inputs, List.of(required("a")), false, null,
                CookingPotConsumptionPlanTest::empty,
                input -> { resolutions.incrementAndGet(); return input; }, Input::amount, Input::id,
                (input, ingredient) -> { predicates.incrementAndGet(); return matches(input, ingredient); });
        assertArrayEquals(new int[]{3}, slots);
        assertEquals(3, resolutions.get()); assertEquals(3, predicates.get());
        assertArrayEquals(new int[]{1}, plan(List.of(input("a", -1), input("a", 1), input("a", 0)), List.of(required("a"))));
        assertNull(plan(List.of(input("a", 0)), List.of(required("a"))));
    }

    @Test void aSingleIngredientStillRejectsUnknownJunkAndWrongNbtExtrasEvenWhenTheirAmountIsZero() {
        Required withNbt = new Required(Set.of("a"), true);
        assertNull(plan(List.of(input("a", 64), input("junk", 0)), List.of(required("a"))));
        assertNull(plan(List.of(input("a", 64), input(null, 0)), List.of(required("a"))));
        assertNull(plan(List.of(input("a", 64), new Input("a", 0, false)), List.of(withNbt)));
        assertArrayEquals(new int[]{0}, plan(List.of(input("a", 64), new Input("a", 0, true)), List.of(withNbt)));
    }

    @Test void singleIngredientPlanIsEquivalentToThePreviousValidationAndDebitFor256Inventories() {
        List<Input> options = Arrays.asList(null, input("a", 1), input("b", 2), input("x", 0));
        List<Required> ingredients = List.of(required("a"), required("b"), required("a", "b"), required("x"));
        int checked = 0;
        for (Input a : options) for (Input b : options) for (Input c : options) {
            List<Input> inputs = Arrays.asList(a, b, c);
            List<Input> filled = new ArrayList<>(); for (Input input : inputs) if (!empty(input)) filled.add(input);
            for (Required ingredient : ingredients) {
                List<Required> required = List.of(ingredient);
                boolean valid = IngredientMatching.matchesIngredients(required, filled, false,
                        CookingPotConsumptionPlanTest::matches, Input::amount);
                int[] previous = null;
                if (valid) {
                    previous = IngredientMatching.assignIngredients(required, inputs, CookingPotConsumptionPlanTest::matches,
                            input -> empty(input) || input.amount() <= 0 ? 0 : 1);
                    if (previous == null) previous = IngredientMatching.assignIngredients(required, inputs,
                            CookingPotConsumptionPlanTest::matches, input -> empty(input) ? 0 : input.amount());
                }
                assertArrayEquals(previous, plan(inputs, required), "Single-ingredient debit changed for " + inputs + " / " + required);
                checked++;
            }
        }
        assertEquals(256, checked);
    }
}
