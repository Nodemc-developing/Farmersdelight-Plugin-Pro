package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.api.recipe.EditableRecipe;
import com.huidu.farmersdelight.api.recipe.NumericField;
import com.huidu.farmersdelight.api.recipe.RecipeEditor;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Editors whose persistence completes outside the player's owning thread. */
public interface AsyncRecipeEditor extends RecipeEditor {
    CompletableFuture<Boolean> saveAsync(EditableRecipe draft);
    CompletableFuture<Boolean> deleteAsync(String id);

    record TextField(String key, String labelKey, String promptKey) { }

    default List<TextField> textFields() { return List.of(); }
    default String text(EditableRecipe draft, String key) { return ""; }
    default void setText(EditableRecipe draft, String key, String value) {
        throw new IllegalArgumentException("Unknown editor field: " + key);
    }
    default boolean requiresResult() { return true; }
    default boolean canCreate(String id) { return true; }
    default Component numericLabel(NumericField field, Player viewer) { return Component.text(field.label()); }
    default String numericValue(EditableRecipe draft, NumericField field) {
        double value = draft.number(field.key(), field.min());
        return field.decimals() <= 0 ? String.valueOf((long) value)
                : String.format(java.util.Locale.ROOT, "%." + field.decimals() + "f", value);
    }
    default Component itemSlotLabel(int index, Player viewer) { return Component.text(itemSlotLabels().get(index)); }
    default List<Component> hints(Player viewer) { return List.of(); }

    @Override default boolean save(EditableRecipe draft) {
        throw new UnsupportedOperationException("Use saveAsync for this editor");
    }
    @Override default boolean delete(String id) {
        throw new UnsupportedOperationException("Use deleteAsync for this editor");
    }
}
