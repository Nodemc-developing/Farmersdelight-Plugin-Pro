package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackSupportTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @CsvSource({"1.21,48", "1.21.1-R0.1-SNAPSHOT,48", "1.21.2,57", "1.21.3-R0.1-SNAPSHOT,57",
            "1.21.4,61", "1.21.5,71", "1.21.6,80", "1.21.7,80", "1.21.8,80"})
    void usesTheCorrectLegacyDataPackFormat(String version, int format) {
        String metadata = DatapackSupport.renderPackMetadata("Verification", version);
        assertTrue(metadata.contains("\"pack_format\": " + format));
        assertFalse(metadata.contains("min_format"));
    }

    @ParameterizedTest
    @CsvSource({"1.21.9", "1.21.10-R0.1-SNAPSHOT", "1.21.11", "26.1", "26.1.2", "26.2", "26.3"})
    void newerVersionsUseARangeAndCannotMatchTheEarlyVersionPrefix(String version) {
        String metadata = DatapackSupport.renderPackMetadata("Verification", version);
        assertTrue(metadata.contains("\"min_format\": 88"));
        assertTrue(metadata.contains("\"max_format\": 150"));
        assertFalse(metadata.contains("\"pack_format\""));
    }

    @Test
    void findsNewDimensionWorldRootBeforeItsFirstSave() throws IOException {
        Path root = directory.resolve("world");
        Path overworld = Files.createDirectories(root.resolve("dimensions/minecraft/overworld"));
        Path custom = Files.createDirectories(root.resolve("dimensions/example/nested/test"));

        assertEquals(root, DatapackSupport.worldRoot(overworld));
        assertEquals(root, DatapackSupport.worldRoot(custom));
        assertFalse(Files.exists(root.resolve("level.dat")));
    }

    @Test
    void keepsLegacyWorldsIndependentAndFindsSavedDimensionRoots() throws IOException {
        Path root = Files.createDirectories(directory.resolve("world"));
        Files.createFile(root.resolve("level.dat"));
        Path nether = Files.createDirectories(root.resolve("DIM-1"));
        Path independent = Files.createDirectories(directory.resolve("world_nether"));
        Files.createFile(independent.resolve("level.dat"));

        assertEquals(root, DatapackSupport.worldRoot(nether));
        assertEquals(independent, DatapackSupport.worldRoot(independent));
    }

    @Test
    void keepsAnUnrecognizedUnsavedWorldFolder() throws IOException {
        Path root = Files.createDirectories(directory.resolve("fresh_world"));
        assertEquals(root, DatapackSupport.worldRoot(root));
    }

    @Test
    void recognizesSharedAndIndependentDatapackRoots() {
        Path primary = Path.of("world", "datapacks", "farmersdelight_enchant");

        assertTrue(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world", "dimensions", "minecraft", "overworld", "..", "..", "..", "datapacks", "farmersdelight_enchant")));
        assertFalse(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world_nether", "datapacks", "farmersdelight_enchant")));
    }
}
