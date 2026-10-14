package com.huidu.farmersdelight.pack.compat;

import com.google.gson.JsonParser;
import net.momirealms.craftengine.core.pack.conflict.resolution.ConditionalResolution;
import net.momirealms.craftengine.core.pack.conflict.resolution.MergeFontResolution;
import net.momirealms.craftengine.core.pack.conflict.resolution.MergeJsonResolution;
import net.momirealms.craftengine.core.pack.conflict.resolution.MergePackMcMetaResolution;
import net.momirealms.craftengine.core.pack.conflict.resolution.Resolution;
import net.momirealms.craftengine.core.pack.conflict.resolution.RetainMatchingResolution;
import net.momirealms.craftengine.core.pack.conflict.matcher.ExactPathMatcher;
import net.momirealms.craftengine.core.pack.conflict.matcher.ContainsPathMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class HostResourcePolicyTest {
    @TempDir Path directory;
    private final List<String> warnings = new ArrayList<>();

    @Test void defaultIconWinsAgainstPluginZipUsingOriginalSourcePathsInBothOrders() throws Exception {
        Path folder = directory.resolve("plugins/CraftEngine/resources/default/resourcepack");
        Path icon = write(folder, "pack.png", "default icon");
        Path zip = zip(Map.of("pack.png", "plugin icon"));
        byte[] originalZip = Files.readAllBytes(zip);
        var rule = exact("pack.png", new RetainMatchingResolution(new ContainsPathMatcher("resources/default/resourcepack")));
        var layer = builder(List.of(rule));
        for (var inputs : List.of(List.of(source(folder), zipped(zip)), List.of(zipped(zip), source(folder)))) {
            assertEquals("default icon", Files.readString(layer.build(inputs).resolve("pack.png")));
        }
        assertEquals("default icon", Files.readString(icon));
        assertArrayEquals(originalZip, Files.readAllBytes(zip));
        assertTrue(warnings.isEmpty());
    }

    @Test void nativeMetadataMergeKeepsBothOverlayDeclarations() throws Exception {
        String first = "{\"pack\":{\"pack_format\":15,\"description\":\"first\"},\"overlays\":{\"entries\":[{\"formats\":15,\"directory\":\"one\"}]}}";
        String second = "{\"pack\":{\"pack_format\":18,\"description\":\"second\"},\"overlays\":{\"entries\":[{\"formats\":18,\"directory\":\"two\"}]}}";
        var output = merge("pack.mcmeta", first, second, MergePackMcMetaResolution.INSTANCE);
        var entries = JsonParser.parseString(output).getAsJsonObject().getAsJsonObject("overlays").getAsJsonArray("entries");
        assertEquals(2, entries.size());
        assertTrue(output.contains("one") && output.contains("two"));
    }

    @Test void nativeFontMergeKeepsProvidersFromFolderAndZip() throws Exception {
        String first = "{\"providers\":[{\"type\":\"space\",\"advances\":{\"a\":1}}]}";
        String second = "{\"providers\":[{\"type\":\"space\",\"advances\":{\"b\":2}}]}";
        String result = merge("assets/minecraft/font/default.json", first, second, MergeFontResolution.INSTANCE);
        var providers = JsonParser.parseString(result).getAsJsonObject().getAsJsonArray("providers");
        assertEquals(2, providers.size());
        assertTrue(result.contains("a") && result.contains("b"));
    }

    @Test void nativeLanguageMergeHonorsHostReplacementOrder() throws Exception {
        String result = merge("assets/minecraft/lang/zh_cn.json", "{\"same\":\"first\",\"first\":\"kept\"}",
                "{\"same\":\"second\",\"second\":\"kept\"}", new MergeJsonResolution(false));
        var language = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("second", language.get("same").getAsString());
        assertTrue(language.has("first") && language.has("second"));
    }

    @Test void nativeSoundMergeKeepsBothPluginEvents() throws Exception {
        String result = merge("assets/minecraft/sounds.json", "{\"one\":{\"sounds\":[\"one\"]}}",
                "{\"two\":{\"sounds\":[\"two\"]}}", new MergeJsonResolution(false));
        var sounds = JsonParser.parseString(result).getAsJsonObject();
        assertTrue(sounds.has("one") && sounds.has("two"));
    }

    @Test void unmatchedDuplicatesWarnAndKeepHostOrderInsteadOfAlphabeticalOrder() throws Exception {
        Path first = directory.resolve("z-first"), second = directory.resolve("a-second");
        write(first, "custom.bin", "first"); write(second, "custom.bin", "second");
        var layer = builder(List.of());
        assertEquals("first", Files.readString(layer.build(List.of(source(first), source(second))).resolve("custom.bin")));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("custom.bin"));
        assertEquals("second", Files.readString(layer.build(List.of(source(second), source(first))).resolve("custom.bin")));
    }

    @Test void configuredRulesReloadAndFirstMatchingRuleIsAuthoritative() throws Exception {
        Path first = directory.resolve("first"), second = directory.resolve("second");
        write(first, "custom.bin", "first"); write(second, "custom.bin", "second");
        var rules = new AtomicReference<>(List.<ConditionalResolution>of());
        var layer = new ManagedResourceLayer(directory.resolve("cache"), manifest(), report(),
                () -> new HostResourcePolicy(rules.get(), Set.of(), warnings::add));
        var inputs = List.of(source(first), source(second));
        Path before = layer.build(inputs);
        rules.set(List.of(exact("custom.bin", new RetainMatchingResolution(new ContainsPathMatcher(""))),
                exact("custom.bin", (target, incoming) -> fail("Only the first matching rule may execute"))));
        Path after = layer.build(inputs);
        assertNotEquals(before, after);
        assertEquals("first", Files.readString(before.resolve("custom.bin")));
        assertEquals("second", Files.readString(after.resolve("custom.bin")));
    }

    @Test void hostExcludedExtensionsAreSkippedForFoldersAndZips() throws Exception {
        Path first = directory.resolve("first");
        write(first, "readme.tmp", "first"); write(first, "keep.png", "keep");
        Path zip = zip(Map.of("readme.tmp", "second", "other.tmp", "other"));
        var layer = new ManagedResourceLayer(directory.resolve("cache"), manifest(), report(),
                () -> new HostResourcePolicy(List.of(), Set.of("tmp"), warnings::add));
        Path result = layer.build(List.of(source(first), zipped(zip)));
        assertTrue(Files.exists(result.resolve("keep.png")));
        assertFalse(Files.exists(result.resolve("readme.tmp")) || Files.exists(result.resolve("other.tmp")));
        assertTrue(warnings.isEmpty());
    }

    @Test void unrelatedSingleLanguageIsPassedThroughWithoutFdParsing() throws Exception {
        Path first = directory.resolve("external");
        write(first, "assets/foreign/lang/zh_cn.json", "left to the owning plugin");
        var layer = builder(List.of());
        assertEquals("left to the owning plugin", Files.readString(layer.build(List.of(source(first)))
                .resolve("assets/foreign/lang/zh_cn.json")));
    }

    @Test void bundledLanguageFillsGapsAfterExternalFilesUseHostRules() throws Exception {
        String path = "assets/food/lang/zh_cn.json";
        Path own = directory.resolve("bundled"), outside = directory.resolve("external");
        Path ownFile = write(own, path, "{\"same\":\"bundled\",\"fallback\":\"kept\"}");
        write(outside, path, "{\"same\":\"folder\",\"external\":\"kept\"}");
        Path zip = zip(Map.of(path, "{\"same\":\"zip\"}"));
        var manifest = manifest(); manifest.record(ownFile, Files.readAllBytes(ownFile));
        var layer = new ManagedResourceLayer(directory.resolve("cache"), manifest, report(),
                () -> new HostResourcePolicy(List.of(exact(path, new MergeJsonResolution(false))), Set.of(), warnings::add));
        var result = JsonParser.parseString(Files.readString(layer.build(List.of(source(own), source(outside), zipped(zip)))
                .resolve(path))).getAsJsonObject();
        assertEquals("zip", result.get("same").getAsString());
        assertTrue(result.has("fallback") && result.has("external"));
    }

    @Test void failedHostRulePreservesCompletedGenerationAndClosesZip() throws Exception {
        Path first = directory.resolve("first"); write(first, "custom.bin", "first");
        Path zip = zip(Map.of("custom.bin", "second"));
        var rules = new AtomicReference<>(List.<ConditionalResolution>of());
        var layer = new ManagedResourceLayer(directory.resolve("cache"), manifest(), report(),
                () -> new HostResourcePolicy(rules.get(), Set.of(), warnings::add));
        var inputs = List.of(source(first), zipped(zip));
        Path before = layer.build(inputs);
        rules.set(List.of(exact("custom.bin", (target, incoming) -> { throw new IllegalStateException("fixture failure"); })));
        var failure = assertThrows(IOException.class, () -> layer.build(inputs));
        assertTrue(failure.getMessage().contains("custom.bin"));
        assertEquals("first", Files.readString(before.resolve("custom.bin")));
        Files.move(zip, zip.resolveSibling("released.zip"));
        try (var files = Files.list(directory.resolve("cache"))) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith(".merge-")));
        }
    }

    private String merge(String path, String first, String second, Resolution rule) throws Exception {
        Path folder = directory.resolve("folder"); Path original = write(folder, path, first);
        Path zip = zip(Map.of(path, second)); byte[] archive = Files.readAllBytes(zip);
        Path result = builder(List.of(exact(path, rule))).build(List.of(source(folder), zipped(zip)));
        assertEquals(first, Files.readString(original));
        assertArrayEquals(archive, Files.readAllBytes(zip));
        assertTrue(warnings.isEmpty());
        return Files.readString(result.resolve(path));
    }
    private ManagedResourceLayer builder(List<ConditionalResolution> rules) throws IOException {
        return new ManagedResourceLayer(directory.resolve("cache"), manifest(), report(),
                () -> new HostResourcePolicy(rules, Set.of(), warnings::add));
    }
    private BundledContentManifest manifest() throws IOException { return new BundledContentManifest(directory.resolve("manifest.json")); }
    private ContentConflicts report() { return new ContentConflicts(directory.resolve("conflicts.json")); }
    private static ConditionalResolution exact(String path, Resolution rule) {
        return new ConditionalResolution(new ExactPathMatcher(path), rule);
    }
    private static ManagedResourceLayer.Source source(Path folder) { return new ManagedResourceLayer.Source(folder, false); }
    private static ManagedResourceLayer.Source zipped(Path zip) { return new ManagedResourceLayer.Source(zip, true); }
    private static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); return file;
    }
    private Path zip(Map<String, String> files) throws IOException {
        Path file = directory.resolve("plugin-resourcepack.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return file;
    }
}
