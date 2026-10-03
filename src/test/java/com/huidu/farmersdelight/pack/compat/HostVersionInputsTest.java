package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.util.VersionHelper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HostVersionInputsTest {
    @Test void nestedSelectorsResolveTheActualHostVersionAndKeepOriginalContainers() {
        assertEquals(260300, VersionHelper.version);
        Map<String, Object> feature = ordered("$$>=26.3", Map.of("type", "minecraft:simple_block",
                "to_place", Map.of("state", "farmersdelight:cabbage")),
                "$$26.1~26.2", Map.of("type", "old", "config", Map.of("to_place", "old")),
                "$$fallback", Map.of("type", "fallback"));
        Map<String, Object> input = Map.of("definition", Map.of("feature", feature,
                "placement", Map.of("$$26.3", List.of(Map.of("type", "minecraft:offset")),
                        "$$fallback", List.of("old"))));
        var result = HostVersionInputs.expand(null, input);
        var definition = (Map<?, ?>) result.get("definition");
        assertEquals("minecraft:simple_block", ((Map<?, ?>) definition.get("feature")).get("type"));
        assertEquals(List.of(Map.of("type", "minecraft:offset")), definition.get("placement"));
        assertEquals(3, feature.size());
        assertTrue(feature.containsKey("$$26.1~26.2"));
        assertNotSame(input.get("definition"), definition);
    }

    @Test void hostRangeBoundariesFallbackAndLastMatchingBranchStayIntact() {
        Map<String, Object> input = Map.of("range", ordered("$$26.2~26.3", "included", "$$fallback", "wrong"),
                "fallback", ordered("$$<26.3", "wrong", "$$>26.3", "wrong", "$$fallback", "kept"),
                "last", ordered("$$>=26.1", "earlier", "$$26.3", "last", "$$fallback", "wrong"));
        var result = HostVersionInputs.expand(null, input);
        assertEquals("included", result.get("range"));
        assertEquals("kept", result.get("fallback"));
        assertEquals("last", result.get("last"));
    }

    @Test void mixedBlocksMergeThroughTheHostAndKeepUnknownFieldsAndTypedScalars() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        BigDecimal decimal = new BigDecimal("1.000000000000000000000001");
        Object opaque = new Object();
        Map<String, Object> input = ordered("metadata", Map.of("uuid", uuid, "bytes", bytes,
                "decimal", decimal, "opaque", opaque),
                "$$>=26.3", Map.of("metadata", Map.of("extra", true), "values", List.of(1, 2L, (byte) 3)),
                "$$26.1~26.2", Map.of("wrong", true), "unknown", Map.of("future_schema", 7));
        var result = HostVersionInputs.expand(null, input);
        Map<?, ?> metadata = (Map<?, ?>) result.get("metadata");
        assertSame(uuid, metadata.get("uuid"));
        assertSame(bytes, metadata.get("bytes"));
        assertSame(decimal, metadata.get("decimal"));
        assertSame(opaque, metadata.get("opaque"));
        assertEquals(true, metadata.get("extra"));
        assertEquals(List.of(1, 2L, (byte) 3), result.get("values"));
        assertEquals(Map.of("future_schema", 7), result.get("unknown"));
        assertFalse(result.containsKey("wrong"));
        assertFalse(((Map<?, ?>) input.get("metadata")).containsKey("extra"));
    }

    @Test void selectorsInsideListsResolveWithoutFlatteningUnrelatedSchema() {
        var input = Map.of("layers", List.of(Map.of("provider",
                ordered("$$>=26.3", Map.of("Name", "farmersdelight:rice"), "$$fallback", "old"))),
                "unknown", Map.of("type", "future:provider", "config", Map.of("x", 1)));
        var result = HostVersionInputs.expand(null, input);
        assertEquals(List.of(Map.of("provider", Map.of("Name", "farmersdelight:rice"))), result.get("layers"));
        assertEquals(input.get("unknown"), result.get("unknown"));
    }

    @Test void unsupportedVersionSyntaxIsExplicitlyRejectedWithoutChangingSource() {
        Map<String, Object> input = Map.of("feature", Map.of("$$", Map.of("type", "future:feature")));
        var failure = assertThrows(IllegalArgumentException.class, () -> HostVersionInputs.expand(null, input));
        assertTrue(failure.getMessage().contains("CraftEngine version conditions"));
        assertEquals(Map.of("$$", Map.of("type", "future:feature")), input.get("feature"));
    }

    private static Map<String, Object> ordered(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
