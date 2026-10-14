package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RopeVisualStateAliasesTest {
    private static final String OLD_POST = "farmersdelight:rope[appearance=east=false,north=false,south=false,west=false]";
    private static final String POST_ALIAS = "twisting_vines[id=farmersdelight:rope/appearance_0]";

    @Test void dryPostUsesTheExactOldAppearanceWithoutMutatingSavedMappings() {
        Map<String, String> cache = new LinkedHashMap<>(Map.of(OLD_POST, "old-vine", "other-pack", "other-state"));
        assertEquals(Map.of(POST_ALIAS, "old-vine"), RopeVisualStateAliases.choose(cache, value -> value.endsWith("vine")));
        assertEquals(Map.of(OLD_POST, "old-vine", "other-pack", "other-state"), cache);
    }

    @Test void anAlreadyPublishedExplicitAliasRetainsItsAssignedVisual() {
        Map<String, String> cache = Map.of(OLD_POST, "old-vine", POST_ALIAS, "new-vine");
        assertEquals(Map.of(POST_ALIAS, "new-vine"), RopeVisualStateAliases.choose(cache, value -> value.endsWith("vine")));
    }

    @Test void unrelatedBlocksOrVisualGroupsAreNeverMappedIntoRopeAliases() {
        Map<String, String> cache = Map.of(OLD_POST, "wrong-leaf", "another:rope[appearance=east=false,north=false,south=false,west=false]", "some-vine");
        assertTrue(RopeVisualStateAliases.choose(cache, value -> value.endsWith("vine")).isEmpty());
    }

    @Test void wetRopesReuseTheAlreadyAssignedFirstWetColliderWithoutChangingOldMappings() {
        Map<String, String> cache = Map.of("kelp[id=farmersdelight:rope/appearance_16]", "first-kelp",
                "kelp[id=farmersdelight:rope/appearance_17]", "second-kelp", "other:kelp", "other-kelp");
        assertEquals(Map.of("kelp[id=farmersdelight:rope/waterlogged]", "first-kelp"), RopeVisualStateAliases.chooseWet(cache, value -> value.endsWith("kelp")));
        assertEquals(3, cache.size());
        assertEquals("second-kelp", cache.get("kelp[id=farmersdelight:rope/appearance_17]"));
    }

    @Test void anAlreadyAssignedSharedWetColliderTakesPrecedenceOverTheEarlierSixteen() {
        Map<String, String> cache = Map.of("kelp[id=farmersdelight:rope/appearance_16]", "first-kelp",
                "kelp[id=farmersdelight:rope/waterlogged]", "shared-kelp");
        assertEquals(Map.of("kelp[id=farmersdelight:rope/waterlogged]", "shared-kelp"), RopeVisualStateAliases.chooseWet(cache, value -> value.endsWith("kelp")));
    }

    @Test void aFreshInstallationMakesNoHiddenAllocationBeforeTheContentExists() {
        assertTrue(RopeVisualStateAliases.chooseWet(Map.of(), value -> true).isEmpty());
        assertTrue(RopeVisualStateAliases.chooseWet(Map.of("another:kelp", "other-kelp"), value -> true).isEmpty());
    }
}
