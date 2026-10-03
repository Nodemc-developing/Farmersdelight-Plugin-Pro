package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class RecipeParsingSupport {

    private RecipeParsingSupport() {
    }

    static RecipeIngredient parseSimpleItemOrTag(String str) {
        str = str.trim();
        if (str.regionMatches(true, 0, "advtag:", 0, 7)) {
            String group = str.substring(7).trim().toLowerCase(Locale.ROOT);
            if (group.isEmpty()) throw new IllegalArgumentException("Advanced tag cannot be empty");
            return new RecipeIngredient.AdvancedTag(Key.of(group));
        }
        if (!str.startsWith("#")) {
            return new RecipeIngredient.Item(Key.of(str));
        }
        return new RecipeIngredient.Tag(Key.of(str.substring(1)));
    }

    // Splits a choice ingredient on '|', trims each option, drops empties, and collapses a single-option
    // choice to that option; a string without '|' is passed through to leafParser as-is (each caller
    // applies its own single-token trimming). The leaf parser turns one option token into a RecipeIngredient.
    static RecipeIngredient parseChoice(String str, Function<String, RecipeIngredient> leafParser) {
        String[] choiceParts = str.split("\\|");
        if (choiceParts.length > 1) {
            List<RecipeIngredient> options = new ArrayList<>();
            for (String choicePart : choiceParts) {
                String trimmed = choicePart.trim();
                if (!trimmed.isEmpty()) {
                    options.add(leafParser.apply(trimmed));
                }
            }
            if (options.isEmpty()) {
                throw new IllegalArgumentException("Choice ingredient must contain at least one option");
            }
            if (options.size() == 1) {
                return options.getFirst();
            }
            return new RecipeIngredient.Choice(options);
        }
        return leafParser.apply(str);
    }

    static RecipeIngredient parseIngredientChoice(String str) {
        return parseChoice(str, option -> {
            String trimmed = option.trim();
            return trimmed.startsWith("#")
                    ? parseTagIngredientWithExclusions(trimmed)
                    : parseSimpleItemOrTag(trimmed);
        });
    }

    /** Parses both the legacy string form and editor-generated item/choice maps. */
    public static RecipeIngredient parseIngredientValue(Object value) {
        return parseIngredientValue(value, 0);
    }

    static void requireKnownSemantics(org.bukkit.configuration.ConfigurationSection section, Set<String> supported) {
        for (String field : section.getKeys(false)) {
            String key = field.toLowerCase(Locale.ROOT).replace('_', '-');
            if (supported.contains(key)) continue;
            if (key.startsWith("match") || key.startsWith("consume") || key.startsWith("condition")
                    || key.equals("components") || key.equals("exact-components") || key.equals("nbt")) {
                throw new IllegalArgumentException("Unsupported recipe condition or consumption field: " + field);
            }
        }
    }

    private static RecipeIngredient parseIngredientValue(Object value, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Ingredient alternatives are nested too deeply");
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            value = section.getValues(false);
        }
        if (value instanceof Map<?, ?> raw) {
            for (Object field : raw.keySet()) {
                String key = String.valueOf(field);
                if (!java.util.Set.of("item", "nbt", "items", "choice", "extensions", "metadata").contains(key)
                        && !key.startsWith("x-")) throw new IllegalArgumentException("Unsupported ingredient field: " + key);
            }
            Object choice = raw.containsKey("items") ? raw.get("items") : raw.get("choice");
            if (raw.containsKey("items") && raw.containsKey("choice")
                    || choice != null && (raw.containsKey("item") || raw.containsKey("nbt"))) {
                throw new IllegalArgumentException("Ingredient must use one item or one alternative list");
            }
            if (choice instanceof List<?> options) {
                Map<String, RecipeIngredient> parsed = new java.util.LinkedHashMap<>();
                for (Object option : options) {
                    RecipeIngredient ingredient = parseIngredientValue(option, depth + 1);
                    if (ingredient instanceof RecipeIngredient.Choice nested) {
                        for (RecipeIngredient leaf : nested.options()) parsed.putIfAbsent(leaf.stableKey(), leaf);
                    } else {
                        parsed.putIfAbsent(ingredient.stableKey(), ingredient);
                    }
                }
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("Choice ingredient must contain at least one option");
                }
                List<RecipeIngredient> optionsParsed = List.copyOf(parsed.values());
                return optionsParsed.size() == 1 ? optionsParsed.getFirst() : new RecipeIngredient.Choice(optionsParsed);
            }
            if (raw.containsKey("items") || raw.containsKey("choice")) {
                throw new IllegalArgumentException("Ingredient items or choice must be a list");
            }
            Object item = raw.get("item");
            if (item != null) {
                Object nbt = raw.get("nbt");
                return new RecipeIngredient.Item(Key.of(item.toString()),
                        nbt == null || nbt.toString().isBlank() ? null : nbt.toString());
            }
            throw new IllegalArgumentException("Ingredient map must contain item, items or choice");
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Ingredient cannot be empty");
        }
        return parseIngredientChoice(value.toString());
    }

    static RecipeIngredient.Tag parseTagIngredientWithExclusions(String str) {
        ParsedKey parsed = parseKeyWithExclusions(str, "ingredient");
        return new RecipeIngredient.Tag(parsed.key(), parsed.excludedItems(), parsed.excludedTags());
    }

    static ParsedKey parseKeyWithExclusions(String str, String contextName) {
        String[] parts = str.split(",");
        String baseToken = parts[0].trim();
        if (baseToken.isEmpty()) {
            throw new IllegalArgumentException(contextName + " cannot be empty");
        }

        Key baseKey = baseToken.startsWith("#")
                ? Key.of(baseToken.substring(1))
                : Key.of(baseToken);
        Set<Key> excludedItems = new HashSet<>();
        Set<Key> excludedTags = new HashSet<>();

        for (int i = 1; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isEmpty()) {
                continue;
            }
            if (!token.startsWith("!")) {
                throw new IllegalArgumentException("Invalid exclusion token '" + token + "' in " + contextName + ": " + str);
            }

            String exclusion = token.substring(1).trim();
            if (exclusion.isEmpty()) {
                continue;
            }
            if (exclusion.startsWith("#")) {
                excludedTags.add(Key.of(exclusion.substring(1)));
            } else {
                excludedItems.add(Key.of(exclusion));
            }
        }

        return new ParsedKey(baseKey, baseToken.startsWith("#"),
                Set.copyOf(excludedItems), Set.copyOf(excludedTags));
    }

    record ParsedKey(Key key, boolean tag, Set<Key> excludedItems, Set<Key> excludedTags) {
    }
}
