package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvancedRecipeTagsTest {
    @AfterEach void clear() {
        AdvancedRecipeTags.unregisterSource("test");
        AdvancedRecipeTags.unregisterSource("another");
        AdvancedRecipeTags.clearFoodGroups();
    }

    @Test void separateTypeAndStableKeyPreventNativeTagAlias() {
        RecipeIngredient parsed = RecipeParsingSupport.parseIngredientValue(" ADVTag:Example:Vegetables ");
        assertEquals(new RecipeIngredient.AdvancedTag(Key.of("example:vegetables")), parsed);
        assertNotEquals(new RecipeIngredient.Tag(Key.of("example:vegetables")).stableKey(), parsed.stableKey());
        assertEquals("advtag:example:vegetables", RecipeSerializer.serializeIngredientValue(parsed));
    }

    @Test void membersUseExactItemIdentityAndUnknownGroupsNeverMatch() {
        AdvancedRecipeTags.registerSource("test", Map.of("example:paper", List.of("example:custom_paper")));
        assertTrue(AdvancedRecipeTags.matchesItemId("example:custom_paper", Key.of("example:paper")));
        assertFalse(AdvancedRecipeTags.matchesItemId("minecraft:paper", Key.of("example:paper")));
        assertFalse(AdvancedRecipeTags.matchesItemId("minecraft:paper", Key.of("minecraft:planks")));
        assertEquals(java.util.Set.of("example:paper"), AdvancedRecipeTags.tagsForItemId("example:custom_paper"));
    }

    @Test void registryPublishesDetachedImmutableSnapshots() {
        List<String> items = new ArrayList<>(List.of("minecraft:carrot"));
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("example:vegetables", items);
        AdvancedRecipeTags.registerSource("test", groups);
        var old = AdvancedRecipeTags.members("example:vegetables");
        items.add("minecraft:potato");
        groups.clear();
        assertEquals(java.util.Set.of("minecraft:carrot"), old);
        assertThrows(UnsupportedOperationException.class, () -> old.add("minecraft:potato"));
        AdvancedRecipeTags.registerSource("test", Map.of("example:vegetables", List.of("minecraft:potato")));
        assertEquals(java.util.Set.of("minecraft:carrot"), old);
        assertEquals(java.util.Set.of("minecraft:potato"), AdvancedRecipeTags.members("example:vegetables"));
        assertTrue(AdvancedRecipeTags.tagsForItemId("minecraft:carrot").isEmpty());
    }

    @Test void explicitLocalGroupsOverrideAddonGroupsAndRemovalRestoresThem() {
        AdvancedRecipeTags.registerSource("test", Map.of("example:vegetables", List.of("minecraft:potato")));
        AdvancedRecipeTags.publishFoodGroups(List.of(new FoodGroupSnapshot.Group("example:vegetables",
                FoodGroupSnapshot.Kind.EQUIVALENT, List.of("minecraft:carrot"))));
        assertEquals(java.util.Set.of("minecraft:carrot"), AdvancedRecipeTags.members("example:vegetables"));
        AdvancedRecipeTags.clearFoodGroups();
        assertEquals(java.util.Set.of("minecraft:potato"), AdvancedRecipeTags.members("example:vegetables"));
    }

    @Test void unknownAdvancedOptionRejectsTheRecipeEvenWithUsableAlternatives() {
        RecipeIngredient parsed = RecipeParsingSupport.parseIngredientValue(Map.of("items",
                List.of("minecraft:carrot", Map.of("items", List.of("advtag:example:missing")))));
        var error = assertThrows(IllegalArgumentException.class, () -> AdvancedRecipeTags.requireDefined(parsed));
        assertTrue(error.getMessage().contains("advtag:example:missing"));
    }

    @Test void definitionsCannotSilentlyContainNestedNativeTags() {
        assertThrows(IllegalArgumentException.class, () -> AdvancedRecipeTags.registerSource("test",
                Map.of("example:vegetables", List.of("#minecraft:planks"))));
        assertThrows(IllegalArgumentException.class, () -> AdvancedRecipeTags.registerSource("test",
                Map.of("example:vegetables", List.of("advtag:example:other"))));
        assertTrue(AdvancedRecipeTags.members("example:vegetables").isEmpty());
    }

    @Test void advancedToolSerializationKeepsItsIndependentIdentity() {
        var tool = new CuttingBoardRecipe.ToolRequirement(Key.of("example:tools"), false,
                java.util.Set.of(), java.util.Set.of(), true);
        assertInstanceOf(RecipeIngredient.AdvancedTag.class, tool.asIngredient());
        assertEquals("advtag:example:tools", RecipeSerializer.serializeTool(tool));
    }
}
