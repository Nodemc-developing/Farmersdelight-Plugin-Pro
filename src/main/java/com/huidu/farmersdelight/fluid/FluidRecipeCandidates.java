package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RecipeIngredient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Immutable identity prefilter. The caller still checks the complete ingredient predicate. */
final class FluidRecipeCandidates {
    private final Map<String, List<FluidRecipeSpec>> items;
    private final Map<String, List<FluidRecipeSpec>> tags;
    private final Map<String, List<FluidRecipeSpec>> advancedTags;
    private final Map<String, Integer> order;
    private final List<FluidRecipeSpec> fallback;

    private FluidRecipeCandidates(Map<String, List<FluidRecipeSpec>> items,
                                  Map<String, List<FluidRecipeSpec>> tags,
                                  Map<String, List<FluidRecipeSpec>> advancedTags,
                                  Map<String, Integer> order, List<FluidRecipeSpec> fallback) {
        this.items = items; this.tags = tags; this.advancedTags = advancedTags;
        this.order = Map.copyOf(order); this.fallback = List.copyOf(fallback);
    }

    static FluidRecipeCandidates compile(List<FluidRecipeSpec> ordered,
                                         Function<String, Set<String>> tagMembers) {
        Map<String, LinkedHashSet<FluidRecipeSpec>> items = new LinkedHashMap<>();
        Map<String, LinkedHashSet<FluidRecipeSpec>> tags = new LinkedHashMap<>();
        Map<String, LinkedHashSet<FluidRecipeSpec>> advanced = new LinkedHashMap<>();
        Map<String, Integer> order = new HashMap<>();
        LinkedHashSet<FluidRecipeSpec> fallback = new LinkedHashSet<>();
        for (int index = 0; index < ordered.size(); index++) {
            FluidRecipeSpec recipe = ordered.get(index); order.put(recipe.id(), index);
            index(recipe.ingredient(), recipe, items, tags, advanced, fallback, tagMembers);
        }
        return new FluidRecipeCandidates(freeze(items), freeze(tags), freeze(advanced), order, List.copyOf(fallback));
    }

    private static void index(RecipeIngredient ingredient, FluidRecipeSpec recipe,
                              Map<String, LinkedHashSet<FluidRecipeSpec>> items,
                              Map<String, LinkedHashSet<FluidRecipeSpec>> tags,
                              Map<String, LinkedHashSet<FluidRecipeSpec>> advanced,
                              LinkedHashSet<FluidRecipeSpec> fallback,
                              Function<String, Set<String>> tagMembers) {
        switch (ingredient) {
            case RecipeIngredient.Item item -> add(items, item.key().toString(), recipe);
            case RecipeIngredient.AdvancedTag tag -> add(advanced, tag.key().toString(), recipe);
            case RecipeIngredient.Tag tag -> {
                String key = normalize(tag.key().toString()); add(tags, key, recipe);
                Set<String> members = tagMembers.apply(key);
                if (members.isEmpty()) fallback.add(recipe);
                else for (String member : members) add(items, member, recipe);
            }
            case RecipeIngredient.Choice choice -> {
                for (RecipeIngredient option : choice.options())
                    index(option, recipe, items, tags, advanced, fallback, tagMembers);
            }
        }
    }

    List<FluidRecipeSpec> select(String itemId, Collection<String> itemTags, Collection<String> groups) {
        if (itemId == null) return List.of();
        LinkedHashSet<FluidRecipeSpec> selected = new LinkedHashSet<>();
        selected.addAll(items.getOrDefault(normalize(itemId), List.of()));
        for (String tag : itemTags) selected.addAll(tags.getOrDefault(normalize(tag), List.of()));
        for (String group : groups) selected.addAll(advancedTags.getOrDefault(normalize(group), List.of()));
        selected.addAll(fallback);
        if (selected.isEmpty()) return List.of();
        List<FluidRecipeSpec> result = new ArrayList<>(selected);
        result.sort(Comparator.comparingInt(recipe -> order.get(recipe.id())));
        return List.copyOf(result);
    }

    int itemKeys() { return items.size(); }
    int fallbackRecipes() { return fallback.size(); }
    private static void add(Map<String, LinkedHashSet<FluidRecipeSpec>> index, String key, FluidRecipeSpec recipe) {
        index.computeIfAbsent(normalize(key), unused -> new LinkedHashSet<>()).add(recipe);
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static Map<String, List<FluidRecipeSpec>> freeze(Map<String, LinkedHashSet<FluidRecipeSpec>> values) {
        Map<String, List<FluidRecipeSpec>> result = new LinkedHashMap<>();
        values.forEach((key, recipes) -> result.put(key, List.copyOf(recipes))); return Map.copyOf(result);
    }
}
