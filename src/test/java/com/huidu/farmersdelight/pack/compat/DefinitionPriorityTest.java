package com.huidu.farmersdelight.pack.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefinitionPriorityTest {
    @TempDir Path directory;

    @Test void identicalRepeatedSourceKeepsTheFirstLoadingValueWithoutReportingAConflict() throws Exception {
        for (boolean bundled : List.of(false, true)) {
            Object firstLocation = new Object(), repeatedLocation = new Object();
            var first = new DefinitionPriority.Definition<>("default:model/cube_all", "models.yml#templates#models#block",
                    bundled, firstLocation, Map.of("nested", List.of(Map.of("uv", new int[]{1, 2, 3}))));
            var repeated = new DefinitionPriority.Definition<>(first.id(), first.source(), bundled,
                    repeatedLocation, Map.of("nested", List.of(Map.of("uv", new int[]{1, 2, 3}))));
            var independent = new DefinitionPriority.Definition<>("default:model/cross", first.source(), bundled,
                    new Object(), Map.of("parent", "minecraft:block/cross"));
            var report = report();
            assertEquals(List.of(first, independent), DefinitionPriority.select("templates", List.of(first, repeated, independent), report));
            report.save();
            assertEquals("[]", Files.readString(directory.resolve("conflicts.json")));
        }
    }

    @Test void differentFilesRemainConflictingEvenWithIdenticalContents() {
        for (boolean bundled : List.of(false, true)) {
            var first = new DefinitionPriority.Definition<>("default:model/cube_all", "first.yml#templates", bundled, Map.of("parent", "cube_all"));
            var second = new DefinitionPriority.Definition<>(first.id(), "second.yml#templates", bundled, first.value());
            assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("templates", List.of(first, second), report()));
        }
    }

    @Test void changedPayloadFromTheSameSourceIsRejectedAndReported() throws Exception {
        for (boolean bundled : List.of(false, true)) {
            var first = new DefinitionPriority.Definition<>("default:model/cube_all", "models.yml#templates", bundled,
                    Map.of("textures", List.of(Map.of("uv", new int[]{1, 2, 3}))));
            var changed = new DefinitionPriority.Definition<>(first.id(), first.source(), bundled,
                    Map.of("textures", List.of(Map.of("uv", new int[]{1, 2, 4}))));
            var report = report();
            assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("templates", List.of(first, changed), report));
            report.save();
            assertTrue(Files.readString(directory.resolve("conflicts.json")).contains("same source produced different definitions"));
        }
    }

    @Test void shadowedBundledSourceStillRejectsAChangedRepeat() {
        var own = new DefinitionPriority.Definition<>("food:plate", "own.yml", true, "default");
        var external = new DefinitionPriority.Definition<>(own.id(), "external.yml", false, "operator");
        var repeat = new DefinitionPriority.Definition<>(own.id(), own.source(), true, "default");
        assertEquals(List.of(external), DefinitionPriority.select("items", List.of(own, external, repeat), report()));
        var changed = new DefinitionPriority.Definition<>(own.id(), own.source(), true, "changed");
        assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("items", List.of(own, external, changed), report()));
    }

    @Test void identicalContentsDoNotHideInconsistentSourceOwnership() {
        var own = new DefinitionPriority.Definition<>("food:plate", "same.yml", true, "value");
        var external = new DefinitionPriority.Definition<>(own.id(), own.source(), false, "value");
        assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("items", List.of(own, external), report()));
    }

    @Test void scalarTypesAndListOrderArePartOfThePayload() {
        var first = new DefinitionPriority.Definition<Object>("food:plate", "same.yml", false, Map.of("data", List.of(1, 2)));
        for (Object changed : List.of(Map.of("data", List.of(2, 1)), Map.of("data", List.of(1L, 2L)),
                Map.of("data", new int[]{1, 2}))) {
            var repeated = new DefinitionPriority.Definition<>(first.id(), first.source(), false, changed);
            assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("items", List.of(first, repeated), report()));
        }
    }

    private ContentConflicts report() { return new ContentConflicts(directory.resolve("conflicts.json")); }
}
