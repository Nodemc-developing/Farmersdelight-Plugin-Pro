package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.template.argument.PlainStringTemplateArgument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateMappingPriorityTest {
    @TempDir Path directory;

    @Test void nativeStateIdentitySelectsTheExternalMappingInEitherLoadOrderWithoutEditingSources() throws Exception {
        Path bundled = file("bundled.yml"), external = file("external.yml");
        byte[] originalBundled = Files.readAllBytes(bundled), originalExternal = Files.readAllBytes(external);
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(bundled, originalBundled);
        String firstSpelling = "soul_campfire[lit=false,facing=east]";
        String secondSpelling = "minecraft:soul_campfire[facing=east,lit=false]";
        var own = section(bundled, "first_namespace", Map.of(firstSpelling, "soul_campfire[facing=east]"));
        var imported = section(external, "second_namespace", Map.of(secondSpelling, "campfire[facing=east]"));
        for (var input : List.of(List.of(own, imported), List.of(imported, own))) {
            var result = ConfigPriorityFilter.filterBlockStateMappings(input,
                    source -> Map.of(firstSpelling, 931, secondSpelling, 931).get(source), ownership,
                    new ContentConflicts(directory.resolve("conflicts.json")));
            assertTrue(result.pending().isEmpty());
            assertEquals(List.of(external), result.configs().stream().map(CachedConfigSection::path).toList());
            assertEquals("campfire[facing=east]", result.configs().getFirst().config().get(secondSpelling));
            assertEquals(Map.of(firstSpelling, "soul_campfire[facing=east]"), own.config().values());
            assertEquals(Map.of(secondSpelling, "campfire[facing=east]"), imported.config().values());
        }
        assertArrayEquals(originalBundled, Files.readAllBytes(bundled));
        assertArrayEquals(originalExternal, Files.readAllBytes(external));
        String diagnostics = Files.readString(directory.resolve("conflicts.json"));
        assertTrue(diagnostics.contains("registry:931"));
        assertTrue(diagnostics.contains("external content takes precedence"));
        assertTrue(diagnostics.contains("external.yml"));
        assertTrue(diagnostics.contains("bundled.yml"));
    }

    @Test void unresolvedAndThrowingNativeStatesRemainForHostDiagnosticsWithoutSuppressingOtherMappings() throws Exception {
        Path ownPath = file("own.yml"), externalPath = file("external.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(ownPath, Files.readAllBytes(ownPath));
        var own = section(ownPath, "food", Map.of("unknown", "own_unknown", "throwing", "own_throwing", "known", "own_known"));
        var imported = section(externalPath, "food", Map.of("unknown", "external_unknown", "throwing", "external_throwing", "known", "external_known"));
        var result = ConfigPriorityFilter.filterBlockStateMappings(List.of(own, imported), source -> {
            if (source.equals("throwing")) throw new IllegalArgumentException("native rejected source");
            return source.equals("known") ? 52 : null;
        }, ownership, new ContentConflicts(directory.resolve("conflicts.json")));
        assertEquals(2, result.configs().size());
        assertEquals(Map.of("unknown", "own_unknown", "throwing", "own_throwing"), result.configs().getFirst().config().values());
        assertEquals(imported.config().values(), result.configs().getLast().config().values());
        assertEquals(3, own.config().keySet().size());
    }

    @Test void conflictingExternalSpellingsFailWithBothOriginalFilesAndKeysInTheReport() throws Exception {
        Path first = file("one.yml"), second = file("two.yml");
        var reportPath = directory.resolve("conflicts.json");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        var error = assertThrows(IllegalStateException.class, () -> ConfigPriorityFilter.filterBlockStateMappings(
                List.of(section(first, "one", Map.of("source_first", "target_one")),
                        section(second, "two", Map.of("source_second", "target_two"))),
                source -> 42, ownership, new ContentConflicts(reportPath)));
        assertTrue(error.getMessage().contains("one.yml"));
        assertTrue(error.getMessage().contains("two.yml"));
        String report = Files.readString(reportPath);
        assertTrue(report.contains("source_first"));
        assertTrue(report.contains("source_second"));
        assertTrue(report.contains("external-external conflict; loading refused"));
    }

    @Test void sectionArgumentsNeverExpandTheSourceIdentityOrSuppressAValidMapping() throws Exception {
        Path ownPath = file("own.yml"), importedPath = file("imported.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(ownPath, Files.readAllBytes(ownPath));
        var own = section(ownPath, "food", Map.of("native_source", "own_target"));
        var arguments = Map.of("source", PlainStringTemplateArgument.plain("native_source"));
        var imported = new CachedConfigSection(pack("operator"), importedPath,
                ConfigSection.of("block-state-mappings", Map.of("${source}", "external_target")), Map.copyOf(arguments));
        var seen = new java.util.ArrayList<String>();
        var result = ConfigPriorityFilter.filterBlockStateMappings(List.of(own, imported), source -> {
            seen.add(source); return source.equals("native_source") ? 84 : null;
        }, ownership, new ContentConflicts(directory.resolve("conflicts.json")));
        assertEquals(List.of("native_source", "${source}"), seen);
        assertEquals(List.of(ownPath, importedPath), result.configs().stream().map(CachedConfigSection::path).toList());
        assertEquals("own_target", result.configs().getFirst().config().get("native_source"));
        assertEquals("external_target", result.configs().getLast().config().get("${source}"));
        assertEquals(1, arguments.size());
        assertNotSame(imported.arguments(), result.configs().getLast().arguments());
    }

    private Path file(String name) throws Exception {
        Path path = directory.resolve(name); Files.writeString(path, "original " + name); return path;
    }
    private Pack pack(String namespace) { return new Pack(directory, new PackMeta("test", "test", "1", namespace), true, new String[0]); }
    private CachedConfigSection section(Path path, String namespace, Map<String, Object> values) {
        return new CachedConfigSection(pack(namespace), path, ConfigSection.of("block-state-mappings", new LinkedHashMap<>(values)), null);
    }
}
