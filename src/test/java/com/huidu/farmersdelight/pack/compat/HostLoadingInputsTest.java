package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.core.plugin.config.IdSectionConfigParser;
import net.momirealms.craftengine.core.plugin.config.SectionConfigParser;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStages;
import net.momirealms.craftengine.core.plugin.config.template.argument.PlainStringTemplateArgument;
import net.momirealms.craftengine.core.plugin.config.template.argument.TemplateArgument;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostLoadingInputsTest {
    @TempDir Path directory;
    private static java.lang.reflect.Field hostConfigInstance;
    private static Object previousHostConfig;

    @BeforeAll static void bootstrapTheRealTemplateParsersConfigurationFlag() throws Exception {
        hostConfigInstance = Config.class.getDeclaredField("instance"); hostConfigInstance.setAccessible(true);
        previousHostConfig = hostConfigInstance.get(null);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        var unsafeInstance = unsafeType.getDeclaredField("theUnsafe"); unsafeInstance.setAccessible(true);
        Config configuration = (Config) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafeInstance.get(null), Config.class);
        var asyncFlag = Config.class.getDeclaredField("misc$multi_threaded_configuration_load"); asyncFlag.setAccessible(true);
        asyncFlag.setBoolean(configuration, false);
        // The real TemplateManager and its real parser constructors run; only their host flag is supplied.
        hostConfigInstance.set(null, configuration);
    }

    @AfterAll static void restoreTheHostConfigurationInstance() throws Exception {
        if (hostConfigInstance != null) hostConfigInstance.set(null, previousHostConfig);
    }

    @Test void actualHostLoadAllInjectsReservedFactoryArgumentsOnlyIntoTheMutableLoadingCopy() {
        Map<String, TemplateArgument> sourceArguments = Map.of("name", PlainStringTemplateArgument.plain("plate"));
        Map<String, Object> sourceBody = Map.of("expanded_id", "${__NAMESPACE__}/${__ID__}",
                "nested", Map.of("values", List.of("original")));
        var original = new CachedConfigSection(pack(), directory.resolve("factory.yml"),
                ConfigSection.of("items", Map.of("${name}", sourceBody)), sourceArguments);
        CachedConfigSection copy = ConfigPriorityFilter.loadingCopy(original);
        var parser = new RecordingParser();
        parser.addConfig(copy);
        parser.loadAll();
        assertEquals(1, parser.calls);
        assertEquals(Key.of("food:plate"), parser.loadedId);
        assertEquals("food/plate", parser.received.getString("expanded_id"));
        assertNotSame(sourceArguments, copy.arguments());
        assertTrue(copy.arguments().containsKey("__NAMESPACE__"));
        assertTrue(copy.arguments().containsKey("__ID__"));
        assertEquals(1, sourceArguments.size());
        assertEquals("${__NAMESPACE__}/${__ID__}", sourceBody.get("expanded_id"));
        assertEquals(List.of("original"), ((Map<?, ?>) sourceBody.get("nested")).get("values"));
        assertEquals(1, original.config().keySet().size());
    }

    @Test void bothNullAndImmutableEmptyArgumentsFollowRealHostLoadingWithoutMutationFailures() throws Exception {
        Path file = directory.resolve("configuration/items.yml");
        Files.createDirectories(file.getParent()); Files.writeString(file, "old default bytes");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(file, Files.readAllBytes(file));
        Map<String, Object> document = Map.of("items", Map.of("plate", Map.of("expanded_id", "${__NAMESPACE__}/${__ID__}",
                "nested", Map.of("values", List.of("original")))));
        var overlay = new BundledPackOverlay(Map.of(file, document));
        CachedConfigSection current = overlay.prepare(new String[]{"items"}, List.of(), List.of(pack()), ownership).getFirst();
        assertNull(current.arguments());
        for (Map<String, TemplateArgument> arguments : java.util.Arrays.<Map<String, TemplateArgument>>asList(null, Map.of())) {
            var source = new CachedConfigSection(current.pack(), current.path(), current.config(), arguments);
            var parser = new RecordingParser();
            CachedConfigSection copy = ConfigPriorityFilter.loadingCopy(source);
            parser.addConfig(copy); parser.loadAll();
            assertEquals(1, parser.calls);
            assertEquals("food/plate", parser.received.getString("expanded_id"));
            if (arguments == null) assertNull(copy.arguments());
            else {
                assertTrue(copy.arguments().containsKey("__ID__"));
                assertTrue(arguments.isEmpty());
            }
            assertEquals("${__NAMESPACE__}/${__ID__}", ((Map<?, ?>) source.config().get("plate")).get("expanded_id"));
        }
        assertEquals("old default bytes", Files.readString(file));
    }

    @Test void versionSelectionAndReservedArgumentsReachTheRealParserExactlyOnceWithoutRewritingItsSource() throws Exception {
        Path file = directory.resolve("versioned-factory.yml");
        Files.writeString(file, "operator source bytes stay untouched");
        byte[] originalBytes = Files.readAllBytes(file);
        Map<String, Object> body = Map.of("expanded_id", "${__NAMESPACE__}/${__ID__}",
                "nested", Map.of("$$>=26.3", Map.of("values", List.of("original")),
                        "$$fallback", Map.of("values", List.of("wrong"))));
        var source = new CachedConfigSection(pack(), file, ConfigSection.of("items", Map.of("${name}", body)),
                Map.of("name", PlainStringTemplateArgument.plain("plate")));
        var parser = new RecordingParser();
        parser.addConfig(ConfigPriorityFilter.loadingCopy(source)); parser.loadAll();
        assertEquals(1, parser.calls);
        assertEquals("food/plate", parser.received.getString("expanded_id"));
        assertEquals(List.of("original", "parser mutation"), ((Map<?, ?>) parser.received.get("nested")).get("values"));
        assertTrue(((Map<?, ?>) body.get("nested")).containsKey("$$>=26.3"));
        assertEquals("${__NAMESPACE__}/${__ID__}", body.get("expanded_id"));
        assertArrayEquals(originalBytes, Files.readAllBytes(file));
        assertEquals(1, source.arguments().size());
    }

    @Test void prioritizedMappingStillUsesTheRealSectionParserAndItsOriginalLoadingStage() throws Exception {
        Path ownPath = directory.resolve("own-mappings.yml"), externalPath = directory.resolve("external-mappings.yml");
        Files.writeString(ownPath, "unchanged bundled mappings"); Files.writeString(externalPath, "unchanged external mappings");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(ownPath, Files.readAllBytes(ownPath));
        var own = new CachedConfigSection(pack(), ownPath,
                ConfigSection.of("block-state-mappings", Map.of("native_source", "own_target", "independent", "unchanged_target")), null);
        var imported = new CachedConfigSection(pack(), externalPath,
                ConfigSection.of("block-state-mappings", Map.of("native_source", "external_target")),
                Map.of("source", PlainStringTemplateArgument.plain("native_source")));
        var selected = ConfigPriorityFilter.filterBlockStateMappings(List.of(own, imported),
                source -> source.equals("native_source") ? 17 : 18, ownership,
                new ContentConflicts(directory.resolve("conflicts.json")));
        var parser = new RecordingMappingParser();
        selected.configs().forEach(parser::addConfig);
        parser.loadAll();
        assertEquals(LoadingStages.BLOCK_STATE_MAPPING, parser.loadingStage());
        assertEquals(2, parser.sectionCalls);
        assertEquals(Map.of("native_source", "external_target", "independent", "unchanged_target"), parser.loaded);
        assertEquals("unchanged bundled mappings", Files.readString(ownPath));
        assertEquals("unchanged external mappings", Files.readString(externalPath));
        assertEquals(2, own.config().keySet().size());
        assertEquals(1, imported.arguments().size());
    }

    private Pack pack() { return new Pack(directory, new PackMeta("test", "test", "1", "food"), true, new String[0]); }

    private static final class RecordingParser extends IdSectionConfigParser {
        int calls;
        Key loadedId;
        ConfigSection received;
        RecordingParser() { setErrorHandler(error -> { throw new AssertionError(error); }); }
        @Override public Key type() { return Key.of("local_test:host_loading"); }
        @Override public String[] sectionId() { return new String[]{"items"}; }
        @Override public LoadingStage loadingStage() { return LoadingStages.ITEM; }
        @Override public boolean async() { return false; }
        @Override protected void parseSection(Pack pack, Path path, Key id, ConfigSection section) {
            ++calls; loadedId = id; received = section;
            section.put("host_mutated", true);
            @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) section.get("nested");
            @SuppressWarnings("unchecked") List<Object> values = (List<Object>) nested.get("values");
            values.add("parser mutation");
        }
    }

    private static final class RecordingMappingParser extends SectionConfigParser {
        int sectionCalls;
        final Map<String, String> loaded = new java.util.LinkedHashMap<>();
        RecordingMappingParser() { setErrorHandler(error -> { throw new AssertionError(error); }); }
        @Override public Key type() { return Key.of("craftengine:block_state_mapping"); }
        @Override public String[] sectionId() { return new String[]{"block-state-mappings"}; }
        @Override public LoadingStage loadingStage() { return LoadingStages.BLOCK_STATE_MAPPING; }
        @Override public boolean async() { return false; }
        @Override public void parseSection(Pack pack, Path path, ConfigSection section) {
            ++sectionCalls;
            for (String source : section.keySet()) assertNull(loaded.put(source, section.getString(source)));
        }
    }
}
