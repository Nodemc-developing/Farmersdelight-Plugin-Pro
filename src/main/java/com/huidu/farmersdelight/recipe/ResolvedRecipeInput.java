package com.huidu.farmersdelight.recipe;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** An input's authoritative identity is read once within its owner's recipe operation. */
record ResolvedRecipeInput(ItemStack stack, String identity) {
    static List<ResolvedRecipeInput> resolve(List<ItemStack> stacks, Function<ItemStack, String> resolver) {
        List<ResolvedRecipeInput> inputs = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack != null && !isAir(stack.getType())) {
                inputs.add(new ResolvedRecipeInput(stack, resolver.apply(stack)));
            }
        }
        return inputs;
    }

    boolean matches(String expected) {
        return identity != null && expected != null && identity.equalsIgnoreCase(expected);
    }

    static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }
}
