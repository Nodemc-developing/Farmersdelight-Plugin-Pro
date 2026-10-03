package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.CommonTagResolver;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CuttingBoardCommonTagDisplayTest {
    @Test void registeredCommonInputsProduceARealDisplayWithoutNeedingANativeCeTag() throws Exception {
        Key member = Key.of("local_test:dough");
        Stack stack = new Stack("dough");
        try {
            RecipePublicationTransaction.run(() -> CommonTagResolver.registerSource("board-common-display",
                    Map.of("local_test:foods/dough", List.of(member.toString()))));
            ItemStack display = CuttingBoardRecipeManager.commonDisplayItem(new RecipeIngredient.Tag(Key.of("local_test:foods/dough")),
                    id -> { assertEquals(member.toString(), id); return stack.clone(); },
                    (item, tag) -> { throw new AssertionError("No exclusions were declared"); });
            assertInstanceOf(Stack.class, display);
            assertNotSame(stack, display, "display consumers receive a clone of the cached registered item");
            assertEquals("dough", ((Stack) display).identity);
        } finally {
            RecipePublicationTransaction.run(() -> CommonTagResolver.unregisterSource("board-common-display"));
        }
    }

    @Test void excludedRegisteredMemberIsNotChosenAsTheBoardInputDisplay() throws Exception {
        Key first = Key.of("local_test:excluded_dough"), second = Key.of("local_test:allowed_dough");
        try {
            RecipePublicationTransaction.run(() -> CommonTagResolver.registerSource("board-common-exclusion",
                    Map.of("local_test:foods/dough", List.of(first.toString(), second.toString()))));
            ItemStack display = CuttingBoardRecipeManager.commonDisplayItem(
                    new RecipeIngredient.Tag(Key.of("local_test:foods/dough"), Set.of(first), Set.of()),
                    id -> { assertEquals(second.toString(), id, "Excluded definitions must never be built as display items"); return new Stack("allowed"); },
                    (item, tag) -> { throw new AssertionError("No tag exclusions were declared"); });
            assertEquals("allowed", ((Stack) display).identity);
        } finally {
            RecipePublicationTransaction.run(() -> CommonTagResolver.unregisterSource("board-common-exclusion"));
        }
    }

    /** A cached definition's returned stack; registry/world work is deliberately unavailable in this fixture. */
    private static final class Stack extends ItemStack {
        private final String identity;
        private Stack(String identity) { super(); this.identity = identity; }
        @Override public Material getType() { return Material.PAPER; }
        @Override public int getAmount() { return 1; }
        @Override public Stack clone() { return new Stack(identity); }
    }
}
