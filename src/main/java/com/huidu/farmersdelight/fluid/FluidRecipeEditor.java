package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.api.recipe.EditableRecipe;
import com.huidu.farmersdelight.api.recipe.NumericField;
import com.huidu.farmersdelight.gui.editor.AsyncRecipeEditor;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeParsingSupport;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** Keeps selectors and item snapshots while the generic editor changes numeric and item templates. */
public final class FluidRecipeEditor implements AsyncRecipeEditor {
    private final FluidRecipeManager manager;
    private final String type;
    private final Map<EditableRecipe, Context> contexts = Collections.synchronizedMap(new WeakHashMap<>());

    private static final class Context {
        final FluidRecipeSpec original;
        final ItemStack originalResult;
        RecipeIngredient ingredient;
        ItemStack inputPreview;
        String fluid;
        Context(FluidRecipeSpec original, RecipeIngredient ingredient, ItemStack inputPreview, ItemStack result, String fluid) {
            this.original = original;
            this.ingredient = ingredient;
            this.inputPreview = inputPreview == null ? null : inputPreview.clone();
            this.originalResult = result == null ? null : result.clone();
            this.fluid = fluid;
        }
    }

    public FluidRecipeEditor(FluidRecipeManager manager, String type) {
        this.manager = java.util.Objects.requireNonNull(manager);
        this.type = java.util.Objects.requireNonNull(type);
    }

    @Override public List<String> itemSlotLabels() { return List.of(I18n.get("fluid.editor.ingredient")); }
    @Override public Component itemSlotLabel(int index, Player viewer) {
        return I18n.getComponent("fluid.editor.ingredient", viewer);
    }
    @Override public List<NumericField> numericFields() {
        List<NumericField> fields = new ArrayList<>();
        fields.add(new NumericField("amount", "mB", 100, 1, 9_007_199_254_740_991D, 0));
        if (type.equals("soaking")) {
            fields.add(new NumericField("time", "ticks", 20, 0, 9_007_199_254_740_991D, 0));
            fields.add(new NumericField("consume_fluid", "consume", 1, 0, 1, 0));
        }
        fields.add(new NumericField("priority", "priority", 1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
        return List.copyOf(fields);
    }

    @Override public Component numericLabel(NumericField field, Player viewer) {
        return I18n.getComponent("fluid.editor." + field.key(), viewer);
    }
    @Override public String numericValue(EditableRecipe draft, NumericField field) {
        FluidRecipeSpec original = context(draft).original;
        Long oldValue = originalNumber(original, field.key());
        if (oldValue != null && draft.number(field.key(), field.min()) == oldValue.doubleValue()) {
            return Long.toString(oldValue);
        }
        return AsyncRecipeEditor.super.numericValue(draft, field);
    }

    static Long originalNumber(FluidRecipeSpec original, String field) {
        if (original == null) return null;
        return switch (field) {
            case "amount" -> Long.valueOf(original.amount());
            case "time" -> Long.valueOf(original.timeTicks());
            default -> null;
        };
    }
    @Override public boolean canCreate(String id) { return manager.recipe(id) == null; }

    @Override public List<TextField> textFields() {
        return List.of(new TextField("fluid", "fluid.editor.fluid", "fluid.editor.fluid_prompt"),
                new TextField("ingredient", "fluid.editor.ingredient", "fluid.editor.ingredient_prompt"));
    }

    @Override public EditableRecipe load(String id) {
        FluidRecipeSpec spec = manager.recipe(id);
        if (spec != null && !type.equals(spec.type())) return null;
        EditableRecipe draft = new EditableRecipe(id, 1);
        RecipeIngredient ingredient = spec == null ? null : spec.ingredient();
        ItemStack input = ingredient == null ? null : ingredientIcon(ingredient);
        ItemStack result = spec == null || spec.result().isEmpty() ? null : FluidResultCodec.deserialize(spec.result());
        draft.setItem(0, input);
        draft.setResult(result);
        draft.setResultCount(result == null ? 1 : result.getAmount());
        draft.setNumber("amount", spec == null ? 1000 : spec.amount());
        draft.setNumber("time", spec == null ? 0 : spec.timeTicks());
        draft.setNumber("consume_fluid", spec == null || spec.consumeFluid() ? 1 : 0);
        draft.setNumber("priority", spec == null ? 0 : spec.priority());
        contexts.put(draft, new Context(spec, ingredient, input, result,
                spec == null ? "minecraft:water" : FluidExpression.display(spec.fluidExpression())));
        return draft;
    }

    @Override public String text(EditableRecipe draft, String key) {
        Context context = context(draft);
        return switch (key) {
            case "fluid" -> context.fluid;
            case "ingredient" -> context.ingredient == null ? "" : RecipeSerializer.serializeIngredient(context.ingredient);
            default -> "";
        };
    }

    @Override public void setText(EditableRecipe draft, String key, String value) {
        Context context = context(draft);
        String input = value == null ? "" : value.trim();
        if (key.equals("fluid")) {
            expression(input);
            context.fluid = input;
        } else if (key.equals("ingredient")) {
            RecipeIngredient parsed = RecipeParsingSupport.parseIngredientValue(input);
            ItemStack preview = ingredientIcon(parsed);
            context.ingredient = parsed;
            context.inputPreview = preview.clone();
            draft.setItem(0, preview);
        } else throw new IllegalArgumentException("Unknown fluid recipe field");
    }

    @Override public boolean requiresResult() { return type.equals("soaking"); }
    @Override public List<Component> hints(Player viewer) {
        return type.equals("soaking") ? List.of(I18n.getComponent("fluid.editor.consume_hint", viewer))
                : List.of(I18n.getComponent("fluid.editor.native_container", viewer));
    }

    @Override public CompletableFuture<Boolean> saveAsync(EditableRecipe draft) {
        // Construct the immutable spec on the owner before enqueueing plain-file persistence.
        Context context = context(draft);
        ItemStack input = draft.item(0);
        if (input == null || input.getType().isAir()) throw new IllegalArgumentException("Ingredient cannot be empty");
        RecipeIngredient ingredient = context.ingredient != null && similar(input, context.inputPreview)
                ? context.ingredient : RecipeIngredient.Item.fromStack(input);
        Map<String, Object> result = new LinkedHashMap<>();
        ItemStack output = draft.result();
        if (output != null && !output.getType().isAir()) {
            if (context.original != null && similar(output, context.originalResult)) result.putAll(context.original.result());
            else {
                Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(output);
                if (snapshot != null) result.putAll(snapshot);
                else result.put("item", RecipeSerializer.itemIdString(output));
            }
            result.put("count", draft.resultCount());
        }
        long amount = longValue(draft.number("amount", 1000), context.original == null ? null : context.original.amount(), "amount");
        long time = longValue(draft.number("time", 0), context.original == null ? null : context.original.timeTicks(), "time");
        boolean consume = !type.equals("soaking") || draft.number("consume_fluid", 1) >= 1;
        Object fluidExpression = expression(context.fluid);
        String primary = FluidExpression.primary(fluidExpression);
        FluidRecipeSpec edited = new FluidRecipeSpec(draft.id(), type, ingredient, result,
                primary.startsWith("#") ? primary.substring(1) : primary,
                primary.startsWith("#"), amount, time, consume,
                Math.toIntExact(longValue(draft.number("priority", 0), null, "priority")), fluidExpression);
        return manager.saveAsync(edited);
    }

    @Override public CompletableFuture<Boolean> deleteAsync(String id) { return manager.deleteAsync(id); }

    private static Object expression(String input) {
        if (!input.startsWith("{") && !input.startsWith("[")) return FluidExpression.capture(input);
        try {
            return FluidExpression.capture(com.huidu.farmersdelight.config.PlainYamlDocuments.parse("fluid: " + input, true).get("fluid"));
        } catch (org.bukkit.configuration.InvalidConfigurationException invalid) { throw new IllegalArgumentException("Invalid fluid expression YAML", invalid); }
    }

    private Context context(EditableRecipe draft) {
        Context context = contexts.get(draft);
        if (context == null) throw new IllegalArgumentException("Fluid editor draft has expired");
        return context;
    }

    static long longValue(double value, Long original, String field) {
        if (original != null && value == original.doubleValue()) return original;
        if (!Double.isFinite(value) || value != Math.rint(value) || Math.abs(value) > 9_007_199_254_740_991D) {
            throw new IllegalArgumentException(field + " must be an exactly representable whole number");
        }
        return (long) value;
    }

    static ItemStack ingredientIcon(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            if (stack != null && !stack.getType().isAir()) return stack;
        }
        try {
            List<ItemStack> candidates = ItemUtils.createSlotItems(RecipeSerializer.serializeIngredient(ingredient));
            if (!candidates.isEmpty()) return candidates.getFirst().clone();
        } catch (RuntimeException ignored) { }
        return new ItemStack(Material.PAPER);
    }

    private static boolean similar(ItemStack first, ItemStack second) {
        return first != null && second != null && first.isSimilar(second);
    }
}
