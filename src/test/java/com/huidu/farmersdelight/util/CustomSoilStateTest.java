package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CustomSoilStateTest {
    @Test void customNamespaceWithPropertiesLoadsWithoutBukkitMaterialResolution() {
        var rules = SoilRuleSupport.parseSoilRules(Map.of("bottom-blocks",
                List.of("farmersdelight:rich_soil_farmland[moisture=7]")));
        assertTrue(rules.isConfigured());
        assertTrue(rules.materials().isEmpty());
        assertEquals("farmersdelight:rich_soil_farmland", rules.customStates().getFirst().block().toString());
        assertEquals(Map.of("moisture", "7"), rules.customStates().getFirst().properties());
    }
    @Test void aCustomNamespaceCannotBecomeAVanillaMaterialWithTheSamePath() {
        var rules = SoilRuleSupport.parseSoilRules(Map.of("bottom-blocks", List.of("farmersdelight:farmland")));
        assertTrue(rules.materials().isEmpty());
        assertEquals(java.util.Set.of("farmersdelight:farmland"), rules.customBlockIds());
    }
    @Test void propertyOrderAndWhitespaceAreIndependentAndImmutable() {
        var state = CustomSoilState.parse("farmersdelight:soil[wet=true, moisture = 7]");
        assertEquals(Map.of("wet", "true", "moisture", "7"), state.properties());
        assertThrows(UnsupportedOperationException.class, () -> state.properties().put("moisture", "0"));
    }
    @Test void malformedAndDuplicatePropertiesAreRejectedAtLoading() {
        for (String value : List.of("test:soil[]", "test:soil[wet=true,wet=false]", "test:soil[wet]",
                "test:soil[wet=]", "test:soil[wet=true,]", "test:soil[wet=true]junk", "test:soil[wet=true][x=1]"))
            assertThrows(IllegalArgumentException.class, () -> CustomSoilState.parse(value), value);
    }
}
