package com.huidu.farmersdelight.pack.compat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedCategoriesTest {
    private static final String PREFIX = "farmersdelight:";

    @Test void theTwoStandardRootsBecomeOneVisibleRootWithNineGroups() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "gui", category(List.of(PREFIX + "0")));
        source.put(PREFIX + "item_display", category(List.of(PREFIX + "cooking_pot_handle")));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> false);
        assertEquals(UnifiedCategories.GROUPS.stream().map(group -> "#" + PREFIX + group).toList(), members(result, "default"));
        assertEquals(false, body(result, "default").get("hidden"));
        assertEquals(true, body(result, "categories").get("hidden"));
        assertEquals(true, body(result, "gui").get("hidden"));
        assertEquals(true, body(result, "item_display").get("hidden"));
        for (String group : UnifiedCategories.GROUPS) assertEquals(true, body(result, group).get("hidden"));
        assertEquals(1, result.entrySet().stream().filter(entry -> entry.getValue() instanceof Map<?, ?> map
                && Boolean.FALSE.equals(map.get("hidden"))).count());
        assertFalse(members(result, "default").contains("#" + PREFIX + "gui"));
    }

    @Test void membershipUnionKeepsTheExternalOrderAndDeduplicatesBothSources() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "tools", category(List.of(PREFIX + "iron_knife", PREFIX + "iron_knife", PREFIX + "skillet")));
        source.put(PREFIX + "weapon", category(List.of(PREFIX + "iron_knife", PREFIX + "diamond_knife")));
        source.put(PREFIX + "block", category(List.of(PREFIX + "skillet", PREFIX + "cooking_pot")));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> false);
        assertEquals(List.of(PREFIX + "iron_knife", PREFIX + "skillet", PREFIX + "cooking_pot", PREFIX + "diamond_knife"),
                members(result, "tools"));
    }

    @Test void explicitEmptyGroupsAreNotRefilledByLegacyItemsOrOptionalDefaults() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "decoration", category(List.of()));
        source.put(PREFIX + "foods", category(List.of()));
        source.put(PREFIX + "block", category(List.of(PREFIX + "basket")));
        source.put(PREFIX + "food", category(List.of(PREFIX + "barbecue_stick")));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> true);
        assertEquals(List.of(), members(result, "decoration"));
        assertEquals(List.of(), members(result, "foods"));
    }

    @Test void explicitEmptyRootStillWinsOverTheOtherStandardRoot() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "default", category(List.of()));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> true);
        assertEquals(List.of(), members(result, "default"));
        assertEquals(true, body(result, "categories").get("hidden"));
    }

    @Test void allFourMissingPlayableEntriesAreAddedOnlyWhenRegistered() {
        Map<String, Object> source = bothRoots();
        Set<String> available = Set.of(PREFIX + "glass_jug", PREFIX + "basket", PREFIX + "tray", PREFIX + "barbecue_stick");
        Map<String, Object> result = UnifiedCategories.merge(source, available::contains);
        assertEquals(List.of(PREFIX + "glass_jug", PREFIX + "basket", PREFIX + "tray"), members(result, "decoration"));
        assertEquals(List.of(PREFIX + "barbecue_stick"), members(result, "foods"));
        Map<String, Object> withoutModels = UnifiedCategories.merge(source, ignored -> false);
        assertEquals(List.of(), members(withoutModels, "decoration"));
        assertEquals(List.of(), members(withoutModels, "foods"));
    }

    @Test void currentAndLegacyTanksRemainDistinctAndOnlyTheLegacyPairUsesTheHiddenCompatibilityGroup() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "decoration", category(List.of(PREFIX + "jug")));
        Set<String> available = Set.of(PREFIX + "jug", PREFIX + "glass_jug", PREFIX + "fluid_jug", PREFIX + "fluid_tank");
        Map<String, Object> result = UnifiedCategories.merge(source, available::contains);
        assertEquals(List.of(PREFIX + "jug", PREFIX + "glass_jug"), members(result, "decoration"));
        assertEquals(List.of(PREFIX + "fluid_jug", PREFIX + "fluid_tank"), members(result, "compatibility"));
        assertEquals(true, body(result, "compatibility").get("hidden"));
        assertFalse(members(result, "default").contains("#" + PREFIX + "compatibility"));
    }

    @Test void withoutAnExternalPackTheBundledTreeContainsBothAvailableTankTypes() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(PREFIX + "categories", category(List.of("#" + PREFIX + "block", "#" + PREFIX + "food")));
        source.put(PREFIX + "block", category(List.of(PREFIX + "cooking_pot", PREFIX + "wild_rice", PREFIX + "basket")));
        source.put(PREFIX + "food", category(List.of(PREFIX + "cabbage", PREFIX + "raw_pasta", PREFIX + "apple_pie", PREFIX + "dog_food")));
        Set<String> available = Set.of(PREFIX + "fluid_jug", PREFIX + "fluid_tank");
        Map<String, Object> result = UnifiedCategories.merge(source, available::contains);
        assertEquals(9, members(result, "default").size());
        assertEquals(List.of(PREFIX + "basket", PREFIX + "fluid_jug", PREFIX + "fluid_tank"), members(result, "decoration"));
        assertEquals(List.of(PREFIX + "cooking_pot"), members(result, "tools"));
        assertEquals(List.of(PREFIX + "wild_rice"), members(result, "wild_plants"));
        assertEquals(List.of(PREFIX + "cabbage"), members(result, "crops"));
        assertEquals(List.of(PREFIX + "raw_pasta"), members(result, "processed"));
        assertEquals(List.of(PREFIX + "apple_pie"), members(result, "feast"));
        assertEquals(List.of(PREFIX + "dog_food"), members(result, "pet_foods"));
    }

    @Test void unknownMetadataAndOtherNamespacesSurviveWithoutMutatingImmutableSources() {
        Map<String, Object> metadata = Map.of("rows", List.of(Map.of("font", "custom:font", "offset", 7)));
        Map<String, Object> originalRoot = Map.of("name", "<white><font:custom:font>\ue001</font>农夫乐事</white>",
                "list", List.of("#" + PREFIX + "foods", "#other:extra"), "unknown", metadata);
        Map<String, Object> source = Map.of(PREFIX + "default", originalRoot,
                "other:extra", Map.of("hidden", false, "list", List.of("other:food"), "custom", metadata));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> false);
        assertEquals(metadata, body(result, "default").get("unknown"));
        assertEquals(source.get("other:extra"), result.get("other:extra"));
        assertEquals(originalRoot.get("list"), List.of("#" + PREFIX + "foods", "#other:extra"));
        assertTrue(members(result, "default").contains("#other:extra"));
        assertTrue(String.valueOf(body(result, "default").get("name")).contains("<font:custom:font>\ue001</font>"));
        assertNotSame(metadata, body(result, "default").get("unknown"));
    }

    @Test void arbitraryCategoriesDoNotBecomePartOfTheStandardTransformation() {
        Map<String, Object> source = Map.of(PREFIX + "default", category(List.of("#" + PREFIX + "custom")),
                PREFIX + "custom", category(List.of(PREFIX + "anything")), "unrelated:default", category(List.of("unrelated:item")));
        assertFalse(UnifiedCategories.recognized(source));
        assertEquals(source, UnifiedCategories.merge(source, ignored -> true));
    }

    @Test void anUnrelatedUserCategoryUsingTheCompatibilityIdIsNotRepurposed() {
        Map<String, Object> source = bothRoots();
        Map<String, Object> custom = Map.of("name", "我的分类", "hidden", false, "list", List.of("other:custom"), "metadata", 4);
        source.put(PREFIX + "compatibility", custom);
        Set<String> available = Set.of(PREFIX + "jug", PREFIX + "glass_jug", PREFIX + "fluid_jug", PREFIX + "fluid_tank");
        assertEquals(custom, UnifiedCategories.merge(source, available::contains).get(PREFIX + "compatibility"));
    }

    @Test void userSubcategoryReferencesKeepTheirBrowsingPathWhileTechnicalCategoriesStayHidden() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "block", category(List.of("#other:custom_blocks", "#" + PREFIX + "gui")));
        source.put(PREFIX + "food", category(List.of("#other:custom_foods", "#" + PREFIX + "item_display")));
        Map<String, Object> custom = Map.of("name", "自定义", "hidden", true, "list", List.of("other:food"), "metadata", 4);
        source.put("other:custom_foods", custom);
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> false);
        assertEquals(List.of("#other:custom_blocks"), members(result, "decoration"));
        assertEquals(List.of("#other:custom_foods"), members(result, "foods"));
        assertEquals(custom, result.get("other:custom_foods"));
        assertEquals(List.of("#other:custom_foods", "#" + PREFIX + "item_display"),
                ((Map<?, ?>) source.get(PREFIX + "food")).get("list"));
    }

    @Test void standardLabelsUseNormalTextWithGoldRootAndGrayChildren() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "foods", Map.of("name", "<italic><aqua>自定义食物</aqua></italic>", "list", List.of(PREFIX + "hamburger")));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> false);
        assertEquals("<!i><gold>农夫乐事</gold>", body(result, "default").get("name"));
        assertEquals("<!i><gray>自定义食物</gray>", body(result, "foods").get("name"));
        for (String group : UnifiedCategories.GROUPS)
            assertTrue(String.valueOf(body(result, group).get("name")).startsWith("<!i><gray>"));
    }

    @Test void unresolvedCustomListsRemainForTheHostToResolveInsteadOfBeingSilentlyReplaced() {
        Map<String, Object> source = bothRoots();
        source.put(PREFIX + "foods", Map.of("name", "食物", "list", "${members}", "custom", Map.of("key", 4)));
        source.put(PREFIX + "food", category(List.of(PREFIX + "barbecue_stick")));
        Map<String, Object> result = UnifiedCategories.merge(source, ignored -> true);
        assertEquals("${members}", body(result, "foods").get("list"));
        assertEquals(Map.of("key", 4), body(result, "foods").get("custom"));
    }

    private static Map<String, Object> bothRoots() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(PREFIX + "default", category(List.of("#" + PREFIX + "tools", "#" + PREFIX + "foods")));
        source.put(PREFIX + "categories", category(List.of("#" + PREFIX + "block", "#" + PREFIX + "food",
                "#" + PREFIX + "gui", "#" + PREFIX + "item_display")));
        return source;
    }

    private static Map<String, Object> category(List<?> members) { return Map.of("list", members); }
    @SuppressWarnings("unchecked") private static Map<String, Object> body(Map<String, Object> root, String name) {
        return (Map<String, Object>) root.get(PREFIX + name);
    }
    @SuppressWarnings("unchecked") private static List<Object> members(Map<String, Object> root, String name) {
        return (List<Object>) body(root, name).get("list");
    }
}
