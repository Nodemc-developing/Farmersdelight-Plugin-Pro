package com.huidu.farmersdelight.api;

import com.huidu.farmersdelight.api.recipe.JumpTarget;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.recipe.RecipePublicationTransaction;
import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeResultIndexTest {

    private static final String TYPE_ID = "test:linked_recipes";

    @Test
    void buildsStableImmutableSnapshots() {
        MutableRecipeType type = new MutableRecipeType(List.of(recipe("first"), recipe("second")));
        Map<String, List<JumpTarget>> initial = FarmersDelightApi.buildRecipeResultIndex(
                List.of(type), recipe -> "minecraft:carrot");
        assertEquals(List.of(new JumpTarget(TYPE_ID, "first"), new JumpTarget(TYPE_ID, "second")),
                initial.get("minecraft:carrot"));
        assertThrows(UnsupportedOperationException.class,
                () -> initial.get("minecraft:carrot").add(new JumpTarget(TYPE_ID, "third")));
        assertThrows(UnsupportedOperationException.class,
                () -> initial.put("minecraft:potato", List.of()));

        type.recipes = List.of(recipe("replacement"));
        Map<String, List<JumpTarget>> refreshed = FarmersDelightApi.buildRecipeResultIndex(
                List.of(type), recipe -> "minecraft:potato");
        assertEquals(List.of(), refreshed.getOrDefault("minecraft:carrot", List.of()));
        assertEquals(List.of(new JumpTarget(TYPE_ID, "replacement")), refreshed.get("minecraft:potato"));
        assertEquals(2, initial.get("minecraft:carrot").size());
    }

    @Test void removedRuntimeRecipeCannotSurviveTheCompleteResultIndexPublication() {
        Object recipeOwner = new Object(), indexOwner = new Object();
        List<ViewableRecipe> initial = List.of(recipe("removed"), recipe("retained"));
        List<ViewableRecipe> remaining = List.of(initial.getLast());
        RuntimeSnapshotPublication.publish(recipeOwner, initial, initial);
        RecipeType type = new RecipeType() {
            public String id() { return TYPE_ID; }
            public Component title() { return Component.empty(); }
            public ItemStack icon() { return null; }
            public List<ViewableRecipe> recipes() { return RuntimeSnapshotPublication.get(recipeOwner, initial); }
        };
        var previousIndex = FarmersDelightApi.buildRecipeResultIndex(List.of(type), recipe -> "minecraft:carrot");
        RuntimeSnapshotPublication.publish(indexOwner, previousIndex, previousIndex);
        try {
            try (var oldRead = RuntimeSnapshotPublication.readScope()) {
                RecipePublicationTransaction.run(() -> {
                    RuntimeSnapshotPublication.publish(recipeOwner, initial, remaining);
                    var rebuilt = FarmersDelightApi.buildRecipeResultIndex(List.of(type), recipe -> "minecraft:carrot");
                    assertEquals(List.of(new JumpTarget(TYPE_ID, "retained")), rebuilt.get("minecraft:carrot"));
                    RuntimeSnapshotPublication.publish(indexOwner, previousIndex, rebuilt);
                });
                assertEquals(initial, type.recipes());
                assertEquals(previousIndex, RuntimeSnapshotPublication.get(indexOwner, Map.of()));
            }
            try (var newRead = RuntimeSnapshotPublication.readScope()) {
                assertEquals(remaining, type.recipes());
                Map<String, List<JumpTarget>> actual = RuntimeSnapshotPublication.get(indexOwner, Map.of());
                assertEquals(List.of(new JumpTarget(TYPE_ID, "retained")), actual.get("minecraft:carrot"));
            }
            assertEquals(2, previousIndex.get("minecraft:carrot").size(), "Existing readers retain their complete old generation");
        } finally {
            RuntimeSnapshotPublication.remove(recipeOwner);
            RuntimeSnapshotPublication.remove(indexOwner);
        }
    }

    private static ViewableRecipe recipe(String id) {
        return new ViewableRecipe() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<ItemStack> inputs() {
                return List.of();
            }

            @Override
            public ItemStack result() {
                return null;
            }
        };
    }

    private static final class MutableRecipeType implements RecipeType {
        private List<ViewableRecipe> recipes;

        private MutableRecipeType(List<ViewableRecipe> recipes) {
            this.recipes = recipes;
        }

        @Override
        public String id() {
            return TYPE_ID;
        }

        @Override
        public Component title() {
            return Component.empty();
        }

        @Override
        public ItemStack icon() {
            return null;
        }

        @Override
        public List<ViewableRecipe> recipes() {
            return recipes;
        }
    }
}
