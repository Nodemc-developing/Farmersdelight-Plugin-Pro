package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLoadingRaceTest {
    @TempDir Path directory;

    @Test void languageChangedBetweenEnumerationAndMergeRefusesTheGenerationWithoutChangingSources() throws Exception {
        Path resourceRoot = directory.resolve("resourcepack");
        Path language = resourceRoot.resolve("assets/food/lang/zh_cn.json");
        Files.createDirectories(language.getParent());
        String original = "{\"title\":\"released\"}";
        Files.writeString(language, original);
        var manifest = new BundledContentManifest(directory.resolve("ownership.json"));
        manifest.record(language, Files.readAllBytes(language));
        Path cache = directory.resolve("cache");
        var builder = new ManagedResourceLayer(cache, manifest, new ContentConflicts(directory.resolve("conflicts.json")));
        Path completeLayer = builder.build(List.of(new ManagedResourceLayer.Source(resourceRoot, false)));
        Path completeManifest = completeLayer.resolveSibling(completeLayer.getFileName() + ".complete");
        byte[] previousManifest = Files.readAllBytes(completeManifest);
        byte[] previousLanguage = Files.readAllBytes(completeLayer.resolve("assets/food/lang/zh_cn.json"));

        // Enumeration fixes both the digest and the bundled classification before the second read.
        String enumeratedDigest;
        try (var input = Files.newInputStream(language)) { enumeratedDigest = BundledContentManifest.digest(input); }
        var enumerated = new ManagedResourceLayer.Asset("assets/food/lang/zh_cn.json", language.toString(),
                true, false, enumeratedDigest, () -> Files.newInputStream(language));
        String operatorEdit = "{\"title\":\"operator edit\",\"added\":\"preserve\"}";
        Files.writeString(language, operatorEdit);

        var merge = ManagedResourceLayer.class.getDeclaredMethod("mergeLanguage", String.class, List.class);
        merge.setAccessible(true);
        InvocationTargetException failed = assertThrows(InvocationTargetException.class,
                () -> merge.invoke(builder, enumerated.path(), List.of(enumerated)));
        assertInstanceOf(IOException.class, failed.getCause());
        assertTrue(failed.getCause().getMessage().contains("source changed"));
        assertTrue(failed.getCause().getMessage().contains(language.toString()));
        assertEquals(operatorEdit, Files.readString(language));
        assertFalse(manifest.isBundled(language));
        assertArrayEquals(previousLanguage, Files.readAllBytes(completeLayer.resolve(enumerated.path())));
        assertArrayEquals(previousManifest, Files.readAllBytes(completeManifest));
        try (var entries = Files.list(cache)) {
            assertEquals(List.of(completeLayer, completeManifest).stream().sorted().toList(), entries.sorted().toList());
        }
    }

    @Test void aMatchingSecondLanguageReadStillMergesNormally() throws Exception {
        Path language = directory.resolve("zh_cn.json");
        Files.writeString(language, "{\"title\":\"same bytes\"}");
        byte[] source = Files.readAllBytes(language);
        var asset = new ManagedResourceLayer.Asset("assets/food/lang/zh_cn.json", language.toString(),
                false, false, BundledContentManifest.digest(source), () -> Files.newInputStream(language));
        assertArrayEquals(source, asset.bytes());
    }

    @Test void closeWhileAnOldGenerationBuildsPreventsResealingRestoredPackRoots() throws Exception {
        ExternalContentCoordinator coordinator = coordinatorWithoutHostInstallation();
        Pack pack = new Pack(directory.resolve("pack"), new PackMeta("test", "test", "1", "food"), true, new String[0]);
        Path[] originals = pack.resourcePackFolders().clone();
        assertTrue(originals.length > 0, "This real host Pack must expose a resource root");
        @SuppressWarnings("unchecked") var retained = (IdentityHashMap<Pack, Path[]>)
                ExternalContentCoordinator.field(ExternalContentCoordinator.class, "originalResourceRoots").get(coordinator);
        retained.put(pack, originals);
        var selected = new ExternalContentCoordinator.AssetGate();
        selected.layer = directory.resolve("previous-complete-layer");
        coordinator.sealResources(selected, List.of(pack));
        assertFalse(java.util.Arrays.equals(originals, pack.resourcePackFolders()));

        CountDownLatch building = new CountDownLatch(1), release = new CountDownLatch(1);
        var pending = new ExternalContentCoordinator.AssetGate();
        pending.dispatching = () -> false;
        pending.builder = () -> {
            building.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            return directory.resolve("new-complete-layer");
        };
        pending.seal = () -> coordinator.sealResources(pending, List.of(pack));
        CompletableFuture<Throwable> result = CompletableFuture.supplyAsync(() -> {
            try { pending.check(); return null; }
            catch (Throwable failure) { return failure; }
        });
        try {
            assertTrue(building.await(5, TimeUnit.SECONDS));
            coordinator.close();
            assertArrayEquals(originals, pack.resourcePackFolders());
        } finally { release.countDown(); }
        Throwable refusal = result.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, refusal);
        assertTrue(refusal.getMessage().contains("closed"));
        assertFalse(pending.sealed);
        assertArrayEquals(originals, pack.resourcePackFolders());
        assertThrows(IllegalStateException.class, () -> coordinator.sealResources(selected, List.of(pack)));
        assertArrayEquals(originals, pack.resourcePackFolders());
    }

    @Test void previouslySealedInputSetsAlsoRefuseReadsAndEventAddsAfterLifecycleClosure() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var gate = new ExternalContentCoordinator.AssetGate();
        gate.layer = directory.resolve("complete-layer");
        gate.dispatching = () -> false;
        gate.lifecycleCheck = () -> {
            if (closed.get()) throw new IllegalStateException("closed");
        };
        var folders = new ExternalContentCoordinator.SealedInputs(gate, false);
        var zips = new ExternalContentCoordinator.SealedInputs(gate, true);
        assertEquals(gate.layer, folders.iterator().next());
        assertTrue(gate.sealed);
        closed.set(true);
        assertThrows(IllegalStateException.class, folders::iterator);
        assertThrows(IllegalStateException.class, zips::size);
        assertThrows(IllegalStateException.class, () -> gate.add(directory.resolve("late"), false));
        assertFalse(gate.folderSources.contains(directory.resolve("late")));
    }

    private ExternalContentCoordinator coordinatorWithoutHostInstallation() throws Exception {
        // Host installation is excluded; close() and the real sealing method run against a real Pack.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        var singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        var coordinator = (ExternalContentCoordinator) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), ExternalContentCoordinator.class);
        ExternalContentCoordinator.field(ExternalContentCoordinator.class, "bindings").set(coordinator, new ArrayList<>());
        ExternalContentCoordinator.field(ExternalContentCoordinator.class, "originalResourceRoots")
                .set(coordinator, new IdentityHashMap<Pack, Path[]>());
        ExternalContentCoordinator.field(ExternalContentCoordinator.class, "emptyRoot").set(coordinator, directory.resolve("empty"));
        ExternalContentCoordinator.field(ExternalContentCoordinator.class, "commonTags").set(coordinator,
                new HostCommonTagOverlay(new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>()));
        return coordinator;
    }
}
