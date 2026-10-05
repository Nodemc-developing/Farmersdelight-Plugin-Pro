package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

/** Translates native content-pack recipes into the internal gameplay representation. */
public final class RecipeSchemaAdapter {
    private RecipeSchemaAdapter() { }

    public static ConfigurationSection normalizePot(ConfigurationSection source) {
        return NativeRecipeSchema.isNative(source) ? NativeRecipeSchema.normalizePot(source) : internalCopy(source);
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source) {
        return normalizeBoard(source, List.of("#farmersdelight:tools/knives"));
    }

    public static ConfigurationSection normalizeBoard(ConfigurationSection source, List<String> defaultTools) {
        return NativeRecipeSchema.isNative(source) ? NativeRecipeSchema.normalizeBoard(source, defaultTools) : internalCopy(source);
    }

    public static Map<String, Object> formatPot(Map<String, Object> source, boolean nativeFormat) {
        return nativeFormat ? NativeRecipeSchema.formatPot(source) : NativeRecipeSchema.copy(source);
    }

    public static Map<String, Object> formatBoard(Map<String, Object> source, boolean nativeFormat) {
        return nativeFormat ? NativeRecipeSchema.formatBoard(source) : NativeRecipeSchema.copy(source);
    }

    // Internal API callers already use gameplay field names; external files are admitted by the native pack root.
    private static ConfigurationSection internalCopy(ConfigurationSection source) {
        YamlConfiguration result = new YamlConfiguration();
        result.options().pathSeparator('\u0001');
        NativeRecipeSchema.copy(source.getValues(false)).forEach((key, value) ->
                com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(result, key, value));
        return result;
    }
}
