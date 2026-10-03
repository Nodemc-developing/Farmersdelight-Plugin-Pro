package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FluidRecipeCandidatesTest {
    @Test void exactAndNbtInputsDoNotForceAnUnrelatedGlobalScan() {
        List<FluidRecipeSpec> recipes = new ArrayList<>();
        for (int i = 0; i < 10000; i++) recipes.add(recipe("test:r" + i, new RecipeIngredient.Item(Key.of("test:item" + i), i == 9 ? "snapshot" : null)));
        var index = FluidRecipeCandidates.compile(recipes, unused -> Set.of());
        assertEquals(List.of(recipes.get(9)), index.select("test:item9", Set.of(), Set.of()));
        assertTrue(index.select("minecraft:stone", Set.of(), Set.of()).isEmpty());
        assertEquals(0, index.fallbackRecipes());
    }

    @Test void choiceExpandedTagAndLiveGroupsAreDeduplicatedInPublishedPriorityOrder() {
        var first = recipe("test:z", new RecipeIngredient.Choice(List.of(
                new RecipeIngredient.Item(Key.of("test:apple")), new RecipeIngredient.Tag(Key.of("c:fruit")))));
        var second = recipe("test:a", new RecipeIngredient.AdvancedTag(Key.of("test:foods")));
        var index = FluidRecipeCandidates.compile(List.of(first, second), tag -> Set.of("test:apple"));
        assertEquals(List.of(first, second), index.select("test:apple", Set.of("c:fruit"), Set.of("test:foods")));
        // A tag updated after publication is selected by the live reverse tag bucket.
        assertEquals(List.of(first), index.select("test:new_fruit", Set.of("c:fruit"), Set.of()));
        assertEquals(List.of(second), index.select("test:new_member", Set.of(), Set.of("test:foods")));
    }

    @Test void unknownTagsStayCheckedAndAnUnrelatedBaseMaterialIdentityIsNotAnExactHit() {
        var unknown = recipe("test:unknown", new RecipeIngredient.Tag(Key.of("test:future")));
        var vanilla = recipe("test:vanilla", new RecipeIngredient.Item(Key.of("minecraft:apple")));
        var index = FluidRecipeCandidates.compile(List.of(unknown, vanilla), unused -> Set.of());
        assertEquals(List.of(unknown), index.select("test:custom_apple", Set.of(), Set.of()));
        assertEquals(1, index.fallbackRecipes());
        assertTrue(index.select(null, Set.of(), Set.of()).isEmpty());
    }

    private static FluidRecipeSpec recipe(String id, RecipeIngredient input) {
        return new FluidRecipeSpec(id, "fluid_filling", input, Map.of(), "minecraft:water", false, 250, 0, true, 0);
    }
}
