package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExpandedItemLifecycleTest {
    @Test void transformedHostOrderAndUniqueDerivedDefinitionsReachPostProcess() {
        var first = section("first", "${unexpanded}");
        var failed = section("failed", "failed");
        var second = section("second", "old");
        var expandedFirst = section("first", "real first");
        var expandedSecond = section("second", "real second");
        var derived = section("derived", "real derived");
        var retained = ExpandedItemLifecycle.ordered(List.of(first, failed, second),
                List.of(second.id(), derived.id(), derived.id()), Map.of(first.id(), expandedFirst,
                        second.id(), expandedSecond, derived.id(), derived));
        assertEquals(List.of(expandedFirst, expandedSecond, derived), retained);
        assertEquals("${unexpanded}", first.section().get("name"));
        List<PendingConfigSection> delegateInputs = new ArrayList<>(List.of(first, failed));
        AtomicInteger postCalls = new AtomicInteger();
        ExpandedItemLifecycle.postProcess(delegateInputs, retained, () -> {
            postCalls.incrementAndGet();
            assertEquals(retained, delegateInputs);
            assertEquals("real first", delegateInputs.getFirst().section().get("name"));
        });
        assertEquals(1, postCalls.get());
        assertTrue(delegateInputs.isEmpty());
        assertEquals("${unexpanded}", first.section().get("name"));
    }

    @Test void postProcessFailureClearsBothExpandedAndOldRawEntriesBeforeTheNextLoad() {
        var raw = section("first", "old raw");
        var expanded = section("first", "new expanded");
        List<PendingConfigSection> inputs = new ArrayList<>(List.of(raw));
        var failure = assertThrows(IllegalStateException.class, () -> ExpandedItemLifecycle.postProcess(inputs,
                List.of(expanded), () -> { throw new IllegalStateException("allocator/future failure"); }));
        assertEquals("allocator/future failure", failure.getMessage());
        assertTrue(inputs.isEmpty());
        var next = section("next", "new generation");
        ExpandedItemLifecycle.postProcess(inputs, List.of(next), () -> assertEquals(List.of(next), inputs));
        assertTrue(inputs.isEmpty());
    }

    @Test void anEmptySuccessfulGenerationDoesNotReviveAnOldPendingDefinition() {
        List<PendingConfigSection> inputs = new ArrayList<>(List.of(section("stale", "old")));
        ExpandedItemLifecycle.postProcess(inputs, List.of(), () -> assertTrue(inputs.isEmpty()));
        assertTrue(inputs.isEmpty());
    }

    private static PendingConfigSection section(String id, String name) {
        Path file = Path.of("fixture-" + id + ".yml");
        var pack = new Pack(Path.of("."), new PackMeta("test", "test", "1", "food"), true, new String[0]);
        return new PendingConfigSection(pack, file, Key.of("food:" + id),
                ConfigSection.of("items.food:" + id, Map.of("name", name)));
    }
}
