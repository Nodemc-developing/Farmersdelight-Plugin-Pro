package com.huidu.farmersdelight.manager;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GroupedEntryIndexTest {
    @Test void retiringOneChunkDoesNotScanOrRemoveAnotherChunk() {
        var index = new GroupedEntryIndex<String, String, String, Object>();
        Object first = new Object(), other = new Object();
        index.put("pot", "chunk1", Set.of("heat1"), first);
        index.put("other", "chunk2", Set.of("heat2"), other);
        index.group("chunk1").forEach(index::remove);
        assertTrue(index.affected("heat1").isEmpty());
        assertSame(other, index.group("chunk2").get("other"));
    }
    @Test void delayedRetirementCannotRemoveReplacementAndDependenciesAreRebuilt() {
        var index = new GroupedEntryIndex<String, String, String, Object>();
        Object old = new Object(), replacement = new Object();
        index.put("pot", "chunk", Set.of("old_heat"), old);
        index.put("pot", "chunk", Set.of("new_heat"), replacement);
        assertFalse(index.remove("pot", old));
        assertTrue(index.affected("old_heat").isEmpty());
        assertSame(replacement, index.affected("new_heat").get("pot"));
        assertTrue(index.remove("pot", replacement));
        assertTrue(index.groups().isEmpty());
        assertTrue(index.affected("new_heat").isEmpty());
    }
}
