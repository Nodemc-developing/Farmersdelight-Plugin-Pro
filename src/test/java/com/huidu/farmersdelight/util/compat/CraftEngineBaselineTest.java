package com.huidu.farmersdelight.util.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineBaselineTest {
    @TempDir Path directory;

    @Test void remappedLocationsVerifyTheOriginalArtifactWithoutAcceptingTheRemappedBytes() throws Exception {
        Path original = directory.resolve("CraftEngine.jar");
        Path remapped = directory.resolve(".paper-remapped").resolve(original.getFileName());
        Files.createDirectories(remapped.getParent());
        Files.writeString(original, "original host artifact", StandardCharsets.UTF_8);
        Files.writeString(remapped, "remapped host artifact", StandardCharsets.UTF_8);
        assertEquals(original, CraftEngineBaseline.originalArtifact(remapped));
        assertNotEquals(CraftEngineBaseline.digest(remapped), CraftEngineBaseline.digest(
                CraftEngineBaseline.originalArtifact(remapped)));
        assertEquals(original, CraftEngineBaseline.originalArtifact(original));
    }

    @Test void knownStableArtifactsShareTheVerifiedContractAndFutureSnapshotsAreRefused() {
        assertEquals("26.9.2", CraftEngineBaseline.versionForHash(CraftEngineBaseline.STABLE_SHA256));
        assertEquals("26.9.2", CraftEngineBaseline.versionForHash(CraftEngineBaseline.STABLE_ARCHIVE_SHA256));
        assertEquals(CraftEngineBaseline.VERSION, CraftEngineBaseline.versionForHash(CraftEngineBaseline.SHA256));
        var failure = assertThrows(IllegalStateException.class,
                () -> CraftEngineBaseline.versionForHash("0000000000000000000000000000000000000000000000000000000000000000"));
        assertTrue(failure.getMessage().contains("Unsupported CraftEngine build"));
        assertTrue(failure.getMessage().contains("26.9.2"));
    }
}
