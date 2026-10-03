package com.huidu.farmersdelight.pack.compat;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExternalContentPriorityTest {
    @org.junit.jupiter.api.Test
    void eventGeneratedResourcesRebuildBeforeTheHostSealsItsFinalInputs() {
        ExternalContentCoordinator.AssetGate gate = new ExternalContentCoordinator.AssetGate();
        java.util.concurrent.atomic.AtomicBoolean dispatching = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        gate.dispatching = dispatching::get;
        gate.builder = () -> java.nio.file.Path.of("managed-layer-" + builds.incrementAndGet());
        ExternalContentCoordinator.SealedInputs folders = new ExternalContentCoordinator.SealedInputs(gate, false);
        assertTrue(folders.add(java.nio.file.Path.of("original-pack")));
        assertEquals(java.nio.file.Path.of("managed-layer-1"), folders.iterator().next());
        assertTrue(folders.add(java.nio.file.Path.of("generated-handheld-models")));
        dispatching.set(false);
        assertEquals(java.nio.file.Path.of("managed-layer-2"), folders.iterator().next());
        assertTrue(gate.folderSources.contains(java.nio.file.Path.of("generated-handheld-models")));
        assertEquals(2, builds.get());
        assertThrows(IllegalStateException.class, () -> folders.add(java.nio.file.Path.of("too-late")));
    }
    @TempDir Path directory;

    @Test void externalDefinitionWinsInEitherInputOrderAndExternalDuplicatesFail() {
        var bundled = new DefinitionPriority.Definition<>("food:pot", "bundled.yml", true, "default");
        var external = new DefinitionPriority.Definition<>("food:pot", "operator.yml", false, "operator");
        var report = new ContentConflicts(directory.resolve("conflicts.json"));
        assertEquals("operator", DefinitionPriority.select("blocks", List.of(bundled, external), report).getFirst().value());
        assertEquals("operator", DefinitionPriority.select("blocks", List.of(external, bundled), report).getFirst().value());
        assertThrows(IllegalStateException.class, () -> DefinitionPriority.select("blocks", List.of(external,
                new DefinitionPriority.Definition<>("food:pot", "second.yml", false, "other")), report));
    }

    @Test void editingInstalledDefaultTransfersOwnershipAndTheManifestSurvivesRestart() throws IOException {
        Path file = directory.resolve("builtin/configuration/items.yml");
        Files.createDirectories(file.getParent());
        byte[] content = "items: {food:plate: {material: paper}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);
        Path manifestFile = directory.resolve("manifest.json");
        var manifest = new BundledContentManifest(manifestFile);
        manifest.record(file, content);
        assertTrue(new BundledContentManifest(manifestFile).isBundled(file));
        Files.writeString(file, "items: {food:plate: {material: stick}}");
        assertFalse(manifest.isBundled(file));
    }

    @Test void onlyExactKnownHistoricalReleaseCanBeAdopted() throws IOException {
        Path file = write(directory, "old/config.yml", "previous released bytes");
        var manifest = new BundledContentManifest(directory.resolve("manifest.json"));
        String oldHash = BundledContentManifest.digest(Files.readAllBytes(file));
        manifest.adoptIfKnown(file, BundledContentManifest.digest("new release".getBytes()), List.of(oldHash));
        assertTrue(manifest.isBundled(file));
        Files.writeString(file, "operator edit");
        manifest.adoptIfKnown(file, BundledContentManifest.digest("new release".getBytes()), List.of(oldHash));
        assertFalse(manifest.isBundled(file));
    }

    @Test void managedLayerUsesExternalModelAndMergesLanguageKeysWithoutChangingSources() throws IOException {
        Path own = directory.resolve("bundled/resourcepack");
        Path outside = directory.resolve("external/resourcepack");
        Path ownModel = write(own, "assets/food/models/block/tank.json", "{\"parent\":\"own\"}");
        Path ownLanguage = write(own, "assets/food/lang/zh_cn.json", "{\"title\":\"自带\",\"missing\":\"补缺\"}");
        Path externalModel = write(outside, "assets/food/models/block/tank.json", "{\"parent\":\"external\"}");
        Path externalLanguage = write(outside, "assets/food/lang/zh_cn.json", "{\"title\":\"外部\",\"external\":\"保留\"}");
        var ownership = new BundledContentManifest(directory.resolve("manifest.json"));
        ownership.record(ownModel, Files.readAllBytes(ownModel));
        ownership.record(ownLanguage, Files.readAllBytes(ownLanguage));
        var builder = new ManagedResourceLayer(directory.resolve("cache"), ownership,
                new ContentConflicts(directory.resolve("conflicts.json")));
        var sources = List.of(new ManagedResourceLayer.Source(own, false), new ManagedResourceLayer.Source(outside, false));
        Path layer = builder.build(sources);
        assertEquals(Files.readString(externalModel), Files.readString(layer.resolve("assets/food/models/block/tank.json")));
        var language = JsonParser.parseString(Files.readString(layer.resolve("assets/food/lang/zh_cn.json"))).getAsJsonObject();
        assertEquals("外部", language.get("title").getAsString());
        assertEquals("补缺", language.get("missing").getAsString());
        assertEquals("保留", language.get("external").getAsString());
        assertEquals("{\"title\":\"自带\",\"missing\":\"补缺\"}", Files.readString(ownLanguage));
        assertEquals("{\"title\":\"外部\",\"external\":\"保留\"}", Files.readString(externalLanguage));
        long modified = Files.getLastModifiedTime(layer.resolve("assets/food/lang/zh_cn.json")).toMillis();
        assertEquals(layer, builder.build(sources));
        assertEquals(modified, Files.getLastModifiedTime(layer.resolve("assets/food/lang/zh_cn.json")).toMillis());
        assertTrue(Files.readString(directory.resolve("conflicts.json")).contains("external resource takes precedence"));
    }

    @Test void differingExternalAssetsFailAndZipTraversalIsRejected() throws IOException {
        Path first = directory.resolve("first"); Path second = directory.resolve("second");
        write(first, "assets/food/models/item/plate.json", "{\"a\":1}");
        write(second, "assets/food/models/item/plate.json", "{\"a\":2}");
        var builder = new ManagedResourceLayer(directory.resolve("cache"),
                new BundledContentManifest(directory.resolve("manifest.json")), new ContentConflicts(directory.resolve("conflicts.json")));
        assertThrows(IOException.class, () -> builder.build(List.of(new ManagedResourceLayer.Source(first, false),
                new ManagedResourceLayer.Source(second, false))));
        Path zip = directory.resolve("unsafe.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("../escaped.json")); output.write(new byte[]{1}); output.closeEntry();
        }
        assertThrows(IOException.class, () -> builder.build(List.of(new ManagedResourceLayer.Source(zip, true))));
        assertFalse(Files.exists(directory.resolve("escaped.json")));
    }

    @Test void currentJarAssetsReplaceHistoricalDefaultsWhileExternalStillWins() throws IOException {
        Path own = directory.resolve("old/resourcepack");
        Path oldModel = write(own, "assets/food/models/block/tank.json", "{\"model\":\"old\"}");
        var manifest = new BundledContentManifest(directory.resolve("ownership.json")); manifest.record(oldModel, Files.readAllBytes(oldModel));
        Path jar = directory.resolve("current.jar"); String prefix = "craftengine/food/resourcepack/";
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry(prefix + "assets/food/models/block/tank.json"));
            zip.write("{\"model\":\"new\"}".getBytes()); zip.closeEntry();
        }
        var builder = new ManagedResourceLayer(directory.resolve("cache"), manifest, new ContentConflicts(directory.resolve("conflicts.json")));
        var sources = new java.util.ArrayList<>(List.of(new ManagedResourceLayer.Source(own, false),
                new ManagedResourceLayer.Source(jar, true, prefix, true)));
        Path first = builder.build(sources);
        assertEquals("{\"model\":\"new\"}", Files.readString(first.resolve("assets/food/models/block/tank.json")));
        Path external = directory.resolve("external"); write(external, "assets/food/models/block/tank.json", "{\"model\":\"operator\"}");
        sources.add(new ManagedResourceLayer.Source(external, false)); Path second = builder.build(sources);
        assertEquals("{\"model\":\"operator\"}", Files.readString(second.resolve("assets/food/models/block/tank.json")));
        assertEquals("{\"model\":\"old\"}", Files.readString(oldModel));
    }

    @Test void lateEventMutationPoisonsBothCacheInputSets() {
        var gate = new ExternalContentCoordinator.AssetGate();
        gate.layer = directory.resolve("complete-layer");
        var folders = new ExternalContentCoordinator.SealedInputs(gate, false);
        var zips = new ExternalContentCoordinator.SealedInputs(gate, true);
        assertEquals(List.of(gate.layer), folders.stream().toList());
        assertTrue(zips.isEmpty());
        assertThrows(IllegalStateException.class, () -> zips.add(directory.resolve("late.zip")));
        assertThrows(IllegalStateException.class, folders::iterator);
        assertThrows(IllegalStateException.class, zips::iterator);
    }

    private static Path write(Path root, String relative, String value) throws IOException {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, value); return file;
    }
}
