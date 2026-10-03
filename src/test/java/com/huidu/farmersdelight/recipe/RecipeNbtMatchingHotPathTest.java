package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecipeNbtMatchingHotPathTest {
    @Test void manyOverlappingCandidatesOnlyResolveEachOccupiedSlotOnce() {
        AtomicInteger calls = new AtomicInteger();
        var inputs = ResolvedRecipeInput.resolve(Arrays.asList(null, new Stack("plain", 64),
                new Stack("named", 1), new Stack("air", 0)), stack -> {
            calls.incrementAndGet();
            return "minecraft:paper";
        });
        assertEquals(2, inputs.size());
        for (int candidate = 0; candidate < 32; ++candidate) {
            assertTrue(IngredientMatching.matchesIngredients(List.of("minecraft:paper", "minecraft:paper"),
                    inputs, true, ResolvedRecipeInput::matches, input -> 1));
        }
        assertEquals(2, calls.get(), "candidate comparisons must reuse each slot's authoritative identity");
        assertEquals(64, inputs.getFirst().stack().getAmount());
    }

    @Test void customAndStoredMmoIdentitiesCannotMatchTheirVanillaBase() {
        var custom = new ResolvedRecipeInput(new Stack("custom", 1), "farmersdelight:knife");
        var mmo = new ResolvedRecipeInput(new Stack("mmo", 1), "mmoitems:SWORD:CHEF");
        assertTrue(custom.matches("FarmersDelight:Knife"));
        assertTrue(mmo.matches("mmoitems:sword:chef"));
        assertFalse(custom.matches("minecraft:paper"));
        assertFalse(mmo.matches("minecraft:paper"));
        assertFalse(mmo.matches("farmersdelight:knife"));
    }

    @Test void snapshotComparisonsKeepAllDataAndAmountSemanticsWithoutCloningExpected() throws Exception {
        var expected = new Stack("full-custom-component", 1);
        Map<String, ItemStack> decoded = decoded();
        String encoded = "local-hot-path-snapshot";
        decoded.put(encoded, expected);
        try {
            for (int candidate = 0; candidate < 64; ++candidate) {
                assertTrue(RecipeItemCodec.matchesSnapshot(encoded, new Stack("full-custom-component", 64)));
                assertFalse(RecipeItemCodec.matchesSnapshot(encoded, new Stack("different-component", 1)));
            }
            assertEquals(0, expected.clones.get());
            assertEquals(1, expected.getAmount());
            ItemStack exposed = RecipeItemCodec.itemFromBase64(encoded);
            assertNotSame(expected, exposed);
            exposed.setAmount(13);
            assertEquals(1, expected.getAmount(), "the public decode API must keep returning an isolated clone");
            assertEquals(1, expected.clones.get());
            assertTrue(RecipeItemCodec.matchesSnapshot(encoded, new Stack("full-custom-component", 13)));
        } finally { decoded.remove(encoded); }
    }

    @Test void emptySnapshotsAndAirCannotSatisfyAnNbtIngredient() {
        assertFalse(RecipeItemCodec.matchesSnapshot(null, new Stack("data", 1)));
        assertFalse(RecipeItemCodec.matchesSnapshot("   ", new Stack("data", 1)));
        assertFalse(RecipeItemCodec.matchesSnapshot("unused", null));
        assertFalse(RecipeItemCodec.matchesSnapshot("unused", new Stack("air", 0)));
        assertNull(RecipeItemCodec.itemFromBase64("%%%%"));
        assertFalse(RecipeItemCodec.matchesSnapshot("%%%%", new Stack("data", 1)));
        assertTrue(ResolvedRecipeInput.isAir(Material.AIR));
        assertTrue(ResolvedRecipeInput.isAir(Material.CAVE_AIR));
        assertTrue(ResolvedRecipeInput.isAir(Material.VOID_AIR));
        assertFalse(ResolvedRecipeInput.isAir(Material.PAPER));
    }

    @Test void actualManagerCandidateMatchingUsesPreciseNbtInsideChoicesAndLenientSlots() throws Exception {
        var expected = new Stack("named", 1);
        String encoded = "local-manager-nbt-snapshot";
        Map<String, ItemStack> decoded = decoded();
        decoded.put(encoded, expected);
        var manager = new CookingPotRecipeManager(null);
        var method = CookingPotRecipeManager.class.getDeclaredMethod("matchRecipePrefiltered",
                CookingPotRecipe.class, List.class, boolean.class);
        method.setAccessible(true);
        var ingredient = new RecipeIngredient.Choice(List.of(
                new RecipeIngredient.Item(Key.of("example:other")),
                new RecipeIngredient.Item(Key.of("minecraft:paper"), encoded)));
        var recipe = new CookingPotRecipe("example:named", List.of(ingredient), null, false, null, 0, 20, null, 0);
        try {
            var valid = ResolvedRecipeInput.resolve(List.of(new Stack("named", 64)), ignored -> "minecraft:paper");
            var wrong = ResolvedRecipeInput.resolve(List.of(new Stack("other-name", 64)), ignored -> "minecraft:paper");
            var extra = ResolvedRecipeInput.resolve(List.of(new Stack("named", 64), new Stack("named", 1)), ignored -> "minecraft:paper");
            assertEquals(true, method.invoke(manager, recipe, valid, true));
            assertEquals(false, method.invoke(manager, recipe, wrong, true));
            assertEquals(false, method.invoke(manager, recipe, extra, true));
            assertEquals(true, method.invoke(manager, recipe, extra, false));
            assertEquals(0, expected.clones.get());
            assertEquals(64, valid.getFirst().stack().getAmount());
        } finally { decoded.remove(encoded); }
    }

    @Test void standaloneTagSourceReplacementCannotReuseAnswersFromAnEarlierReadScope() throws Exception {
        String source = "local-test:cache-tag-generation";
        Key tag = Key.of("local-test:cache-members");
        var manager = new CookingPotRecipeManager(null);
        var field = CookingPotRecipeManager.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        var snapshot = (CookingPotRecipeManager.Snapshot) field.get(manager);
        var cacheKey = CookingPotRecipeManager.class.getDeclaredMethod("buildCacheKey", List.class, ItemStack.class, String.class);
        cacheKey.setAccessible(true);
        var match = CookingPotRecipeManager.class.getDeclaredMethod("matchRecipePrefiltered", CookingPotRecipe.class, List.class, boolean.class);
        match.setAccessible(true);
        var recipe = new CookingPotRecipe("local-test:cache-recipe", List.of(new RecipeIngredient.AdvancedTag(tag)),
                null, false, null, 0, 20, null, 0);
        var inputs = List.of(new ResolvedRecipeInput(new Stack("data", 1), "local-test:apple"));
        try {
            AdvancedRecipeTags.registerSource(source, Map.of(tag.toString(), List.of("local-test:apple")));
            String oldKey;
            try (var scope = RuntimeSnapshotPublication.readScope()) {
                assertEquals(true, match.invoke(manager, recipe, inputs, true));
                oldKey = (String) cacheKey.invoke(manager, inputs, null, null);
                manager.cacheMatch(snapshot, 0, oldKey, recipe);
                assertSame(recipe, snapshot.recipeCache().get(oldKey));
                var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread replacement = new Thread(() -> {
                    try { AdvancedRecipeTags.registerSource(source, Map.of(tag.toString(), List.of("local-test:carrot"))); }
                    catch (Throwable error) { failure.set(error); }
                });
                replacement.start(); replacement.join(5000);
                assertFalse(replacement.isAlive()); assertNull(failure.get());
                assertEquals(oldKey, cacheKey.invoke(manager, inputs, null, null));
                assertEquals(true, match.invoke(manager, recipe, inputs, true), "the earlier read scope retains its own tags");
            }
            String nextKey = (String) cacheKey.invoke(manager, inputs, null, null);
            assertNotEquals(oldKey, nextKey);
            assertNull(snapshot.recipeCache().get(nextKey), "the old cached positive cannot answer the new tag generation");
            assertEquals(false, match.invoke(manager, recipe, inputs, true));
            manager.cacheMatch(snapshot, 0, nextKey, null);
            assertTrue(snapshot.recipeMisses().contains(nextKey));
            AdvancedRecipeTags.registerSource(source, Map.of(tag.toString(), List.of("local-test:apple")));
            String restoredKey = (String) cacheKey.invoke(manager, inputs, null, null);
            assertNotEquals(nextKey, restoredKey);
            assertFalse(snapshot.recipeMisses().contains(restoredKey), "an older negative also cannot suppress newly admitted members");
            assertEquals(true, match.invoke(manager, recipe, inputs, true));
        } finally { AdvancedRecipeTags.unregisterSource(source); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ItemStack> decoded() throws Exception {
        var field = RecipeItemCodec.class.getDeclaredField("DECODED");
        field.setAccessible(true);
        return (Map<String, ItemStack>) field.get(null);
    }

    private static final class Stack extends ItemStack {
        final String data;
        final AtomicInteger clones = new AtomicInteger();
        int amount;
        Stack(String data, int amount) { super(); this.data = data; this.amount = amount; }
        @Override public Material getType() { return data.equals("air") ? Material.AIR : Material.PAPER; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof Stack stack && data.equals(stack.data); }
        @Override public ItemStack clone() { clones.incrementAndGet(); return new Stack(data, amount); }
    }
}
