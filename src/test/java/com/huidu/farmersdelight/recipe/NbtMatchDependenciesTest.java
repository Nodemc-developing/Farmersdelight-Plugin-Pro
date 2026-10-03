package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NbtMatchDependenciesTest {
    private CookingPotRecipe recipe(String id, RecipeIngredient ingredient) {
        return new CookingPotRecipe(id, List.of(ingredient), null, false, null, 0, 20, null, 0);
    }

    @Test void unrelatedItemsAndGroupsRetainTheirCompactCache() {
        var ordinary = recipe("ordinary", new RecipeIngredient.Item(Key.of("minecraft:carrot")));
        var special = recipe("special", new RecipeIngredient.Item(Key.of("minecraft:apple"), "snapshot"));
        var dependencies = NbtMatchDependencies.compile(List.of(ordinary), Map.of("seasonal", Map.of("special", special)));
        assertTrue(dependencies.any());
        assertFalse(dependencies.any(null));
        assertFalse(dependencies.any("other"));
        assertTrue(dependencies.any("seasonal"));
        assertFalse(dependencies.dependsOn("minecraft:carrot", "seasonal"));
        assertFalse(dependencies.dependsOn("minecraft:apple", null));
        assertFalse(dependencies.dependsOn("minecraft:apple", "other"));
        assertTrue(dependencies.dependsOn("Minecraft:Apple", "seasonal"));
    }

    @Test void defaultFallbackAndEveryChoiceBranchRemainDependencies() {
        var choice = new RecipeIngredient.Choice(List.of(new RecipeIngredient.Item(Key.of("minecraft:carrot")),
                new RecipeIngredient.Choice(List.of(new RecipeIngredient.Item(Key.of("minecraft:apple"), "nbt")))));
        var dependencies = NbtMatchDependencies.compile(List.of(recipe("choice", choice)), Map.of());
        assertTrue(dependencies.any(null));
        assertTrue(dependencies.any("any_custom_group"));
        assertTrue(dependencies.dependsOn("minecraft:apple", "any_custom_group"));
        assertFalse(dependencies.dependsOn("minecraft:carrot", null));
    }
}
