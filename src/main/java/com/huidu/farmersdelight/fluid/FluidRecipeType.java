package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.api.recipe.RecipeEditor;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.gui.recipebook.RecipeBookGui;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FluidRecipeType implements RecipeType {
    private final FluidRecipeManager manager;
    private final String type;
    private final FluidRecipeEditor editor;
    private volatile List<FluidRecipeSpec> cachedSource;
    private volatile List<ViewableRecipe> cachedRecipes = List.of();

    public FluidRecipeType(FluidRecipeManager manager, String type) {
        this.manager = java.util.Objects.requireNonNull(manager);
        this.type = java.util.Objects.requireNonNull(type);
        this.editor = new FluidRecipeEditor(manager, type);
    }

    @Override public String id() { return "farmersdelight:" + type; }
    @Override public Component title() { return I18n.getComponent("fluid.type." + type); }
    @Override public ItemStack icon() {
        return new ItemStack(type.equals("fluid_filling") ? Material.WATER_BUCKET
                : type.equals("fluid_emptying") ? Material.BUCKET : Material.CAULDRON);
    }
    @Override public RecipeEditor editor() { return editor; }
    @Override public List<ViewableRecipe> recipes() {
        List<FluidRecipeSpec> current = manager.recipes();
        if (current != cachedSource) {
            synchronized (this) {
                if (current != cachedSource) {
                    List<ViewableRecipe> values = new ArrayList<>();
                    for (FluidRecipeSpec spec : current) if (type.equals(spec.type())) values.add(new View(spec));
                    cachedRecipes = List.copyOf(values);
                    cachedSource = current;
                }
            }
        }
        return cachedRecipes;
    }
    @Override public ViewableRecipe recipe(String id) {
        FluidRecipeSpec spec = manager.recipe(id);
        return spec != null && type.equals(spec.type()) ? new View(spec) : null;
    }

    private final class View implements ViewableRecipe {
        private final FluidRecipeSpec spec;
        View(FluidRecipeSpec spec) { this.spec = spec; }
        @Override public String id() { return spec.id(); }
        @Override public List<ItemStack> inputs() {
            return List.of(FluidRecipeEditor.ingredientIcon(spec.ingredient()), fluidIcon());
        }
        @Override public ItemStack result() {
            return spec.result().isEmpty() ? null : FluidResultCodec.deserialize(spec.result());
        }
        @Override public ItemStack icon() {
            ItemStack result = result();
            return result == null ? FluidRecipeType.this.icon() : result;
        }
        @Override public List<Component> infoLines(Player viewer) {
            List<Component> lines = new ArrayList<>();
            lines.add(I18n.getComponent("fluid.editor.fluid", viewer).append(Component.text(": "
                    + FluidExpression.display(spec.fluidExpression()))));
            lines.add(I18n.getComponent("fluid.editor.amount", viewer).append(Component.text(": " + spec.amount() + " mB")));
            lines.add(I18n.getComponent("fluid.editor.ingredient", viewer)
                    .append(Component.text(": " + RecipeSerializer.serializeIngredient(spec.ingredient()))));
            if (spec.type().equals("soaking")) {
                lines.add(I18n.getComponent("fluid.editor.time", viewer).append(Component.text(": " + spec.timeTicks() + " "))
                        .append(I18n.getComponent("fluid.editor.tick_unit", viewer)));
                lines.add(I18n.getComponent("fluid.editor.consume_fluid", viewer)
                        .append(Component.text(": ")).append(I18n.getComponent(
                                spec.consumeFluid() ? "fluid.editor.enabled" : "fluid.editor.disabled", viewer)));
            }
            if (spec.result().isEmpty()) lines.add(I18n.getComponent("fluid.editor.native_container", viewer));
            return List.copyOf(lines);
        }
        @Override public Map<String, List<ItemStack>> displaySlots() {
            return Map.of("fluid", List.of(fluidIcon()));
        }
        private ItemStack fluidIcon() {
            ItemStack icon = new ItemStack(Material.WATER_BUCKET);
            RecipeBookGui.rename(icon, Component.text(FluidExpression.display(spec.fluidExpression())));
            RecipeBookGui.applyLore(icon, List.of(Component.text(spec.amount() + " mB")));
            return icon;
        }
    }
}
