package com.huidu.farmersdelight.recipe;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Identity dependencies are prepared once, without reading or serializing live stacks. */
record NbtMatchDependencies(Set<String> defaults, Map<String, Set<String>> groups) {
    NbtMatchDependencies {
        defaults = Set.copyOf(defaults);
        Map<String, Set<String>> frozen = new HashMap<>();
        groups.forEach((group, keys) -> frozen.put(group, Set.copyOf(keys)));
        groups = Map.copyOf(frozen);
    }

    static NbtMatchDependencies compile(Collection<CookingPotRecipe> defaults,
                                        Map<String, Map<String, CookingPotRecipe>> groups) {
        Map<String, Set<String>> custom = new HashMap<>();
        groups.forEach((group, recipes) -> custom.put(group, identities(recipes.values())));
        return new NbtMatchDependencies(identities(defaults), custom);
    }

    private static Set<String> identities(Collection<CookingPotRecipe> recipes) {
        Set<String> result = new HashSet<>();
        recipes.forEach(recipe -> recipe.ingredients().forEach(ingredient -> collect(ingredient, result)));
        return result;
    }

    private static void collect(RecipeIngredient ingredient, Set<String> result) {
        if (ingredient instanceof RecipeIngredient.Item item && item.nbt() != null) {
            result.add(item.key().toString().toLowerCase(Locale.ROOT));
        } else if (ingredient instanceof RecipeIngredient.Choice choice) {
            choice.options().forEach(option -> collect(option, result));
        }
    }

    boolean dependsOn(String identity, String group) {
        if (identity == null) return false;
        String key = identity.toLowerCase(Locale.ROOT);
        return defaults.contains(key) || (group != null && groups.getOrDefault(group, Set.of()).contains(key));
    }

    boolean any(String group) {
        return !defaults.isEmpty() || (group != null && !groups.getOrDefault(group, Set.of()).isEmpty());
    }

    boolean any() { return !defaults.isEmpty() || groups.values().stream().anyMatch(keys -> !keys.isEmpty()); }
}
