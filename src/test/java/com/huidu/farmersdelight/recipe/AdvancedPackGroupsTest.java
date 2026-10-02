package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AdvancedPackGroupsTest {
    @Test void expandsForwardAndCrossGroupReferencesWithoutLosingConcreteItemIdentity() {
        Map<String, List<String>> definitions = new LinkedHashMap<>();
        definitions.put("c:foods/raw_meat", List.of("advtag:c:foods/raw_pork", "advtag:c:foods/raw_beef"));
        definitions.put("c:foods/raw_pork", List.of("minecraft:porkchop", "advtag:c:foods/raw_bacon"));
        definitions.put("c:foods/raw_bacon", List.of("farmersdelight:bacon", "minecraft:porkchop"));
        definitions.put("c:foods/raw_beef", List.of("minecraft:beef"));
        var issues = new ArrayList<String>();
        var groups = AdvancedPackGroups.resolve(definitions, issues::add);
        assertEquals(List.of("minecraft:porkchop", "farmersdelight:bacon", "minecraft:beef"), groups.get("c:foods/raw_meat"));
        assertTrue(issues.isEmpty());
    }

    @Test void cyclesAndMissingDependenciesRejectOnlyAffectedGroups() {
        var definitions = Map.of("addon:a", List.of("advtag:addon:b"), "addon:b", List.of("advtag:addon:a"),
                "addon:missing", List.of("advtag:addon:absent"), "addon:valid", List.of("minecraft:carrot"));
        var issues = new ArrayList<String>();
        var groups = AdvancedPackGroups.resolve(definitions, issues::add);
        assertEquals(Map.of("addon:valid", List.of("minecraft:carrot")), groups);
        assertEquals(3, issues.size());
    }

    @Test void ordinaryTagReferencesNeverBecomeAdvancedGroupsImplicitly() {
        var issues = new ArrayList<String>();
        var groups = AdvancedPackGroups.resolve(Map.of("addon:bad", List.of("#minecraft:logs")), issues::add);
        assertTrue(groups.isEmpty());
        assertEquals(1, issues.size());
    }
}
