package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.template.argument.PlainStringTemplateArgument;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigPriorityFilterTest {
    @TempDir Path directory;

    @Test void repeatedDefaultTemplateInputsLoadOnceWithoutChangingTheSource() throws Exception {
        Path file = write("models.yml");
        byte[] original = Files.readAllBytes(file);
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        Map<String, Object> body = Map.of("model", Map.of("type", "minecraft:model", "path", "${model_path}"));
        var first = new CachedConfigSection(pack("default"), file,
                ConfigSection.of("templates#models#block", Map.of("model/cube_all", body)), null);
        var reparsed = new CachedConfigSection(pack("default"), file,
                ConfigSection.of("templates#models#block", Map.of("model/cube_all", ConfigPriorityFilter.copy(body))), null);
        for (var input : List.of(List.of(first, first), List.of(first, reparsed))) {
            var selected = ConfigPriorityFilter.filter("craftengine:template", input, List.of(), ownership,
                    new ContentConflicts(directory.resolve("conflicts.json")));
            assertEquals(1, selected.configs().size());
            assertEquals(Map.of("model/cube_all", body), selected.configs().getFirst().config().values());
            assertEquals(first.config().values(), reparsed.config().values());
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertFalse(Files.readString(directory.resolve("conflicts.json")).contains("conflict; loading refused"));
    }

    @Test void duplicateTemplatesKeepMapListAndScalarValuesUnexpanded() throws Exception {
        Path file = write("templates.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        Map<String, Object> values = Map.of("scalar", "${unresolved}", "list", List.of("${unresolved}", "second"),
                "map", Map.of("template", "default:scalar", "arguments", Map.of("value", "${unresolved}")));
        var cached = new CachedConfigSection(pack("default"), file, ConfigSection.of("templates#nested", values), null);
        var selected = ConfigPriorityFilter.filter("craftengine:template", List.of(cached, cached), List.of(), ownership,
                new ContentConflicts(directory.resolve("conflicts.json")));
        assertEquals(1, selected.configs().size());
        assertEquals(values, selected.configs().getFirst().config().values());
    }

    @Test void changedBodyOrExpansionContextFromTheSameSourceStillFails() throws Exception {
        Path file = write("models.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        var body = Map.<String, Object>of("model/cube_all", Map.of("textures", Map.of("all", "first")));
        var first = new CachedConfigSection(pack("default"), file, ConfigSection.of("templates#models#block", body), null);
        var changedBody = new CachedConfigSection(pack("default"), file,
                ConfigSection.of("templates#models#block", Map.of("model/cube_all", Map.of("textures", Map.of("all", "second")))), null);
        var changedArguments = new CachedConfigSection(pack("default"), file, ConfigSection.of("templates#models#block", body),
                Map.of("texture", PlainStringTemplateArgument.plain("second")));
        var emptyArguments = new CachedConfigSection(pack("default"), file, ConfigSection.of("templates#models#block", body), Map.of());
        for (var changed : List.of(changedBody, changedArguments, emptyArguments)) {
            var failure = assertThrows(IllegalStateException.class, () -> ConfigPriorityFilter.filter("craftengine:template",
                    List.of(first, changed), List.of(), ownership, new ContentConflicts(directory.resolve("conflicts.json"))));
            assertTrue(failure.getMessage().contains("repeated source"));
        }
    }

    @Test void identicalTemplateBodiesInDifferentFilesRemainARealConflict() throws Exception {
        Path first = write("first.yml"), second = write("second.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        Map<String, Object> body = Map.of("model/cube_all", Map.of("parent", "minecraft:block/cube_all"));
        var failure = assertThrows(IllegalStateException.class, () -> ConfigPriorityFilter.filter("craftengine:template",
                List.of(new CachedConfigSection(pack("default"), first, ConfigSection.of("templates", body), null),
                        new CachedConfigSection(pack("default"), second, ConfigSection.of("templates", body), null)),
                List.of(), ownership, new ContentConflicts(directory.resolve("conflicts.json"))));
        assertTrue(failure.getMessage().contains("first.yml"));
        assertTrue(failure.getMessage().contains("second.yml"));
    }

    @Test void repeatedPendingDefinitionsReachTheHostOnlyOnce() throws Exception {
        Path file = write("block.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        var first = new PendingConfigSection(pack("food"), file, Key.of("food:tank"), ConfigSection.of("blocks.tank", Map.of("model", "tank")));
        var reparsed = new PendingConfigSection(pack("food"), file, Key.of("food:tank"), ConfigSection.of("blocks.tank", Map.of("model", "tank")));
        var selected = ConfigPriorityFilter.filter("craftengine:blocks", List.of(), List.of(first, first, reparsed), ownership,
                new ContentConflicts(directory.resolve("conflicts.json")));
        assertTrue(selected.configs().isEmpty());
        assertEquals(1, selected.pending().size());
        assertEquals(first.id(), selected.pending().getFirst().id());
        assertEquals(first.section().values(), selected.pending().getFirst().section().values());
    }

    @Test void pendingEmbeddedBlockYieldsToExternalBlockAndNeverMutatesCachedDocuments() throws Exception {
        Path own = write("own.yml"); Path external = write("external.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(own, Files.readAllBytes(own));
        var report = new ContentConflicts(directory.resolve("conflicts.json"));
        Pack pack = pack("food");
        Map<String, Object> nested = new LinkedHashMap<>(); nested.put("behaviors", new ArrayList<>(List.of("custom")));
        var rawMap = new LinkedHashMap<String, Object>(); rawMap.put("tank", nested);
        var cached = new CachedConfigSection(pack, external, ConfigSection.of("blocks", rawMap), Map.of());
        var pending = new PendingConfigSection(pack, own, Key.of("food:tank"), ConfigSection.of("block", Map.of("model", "own")));
        var selected = ConfigPriorityFilter.filter("craftengine:blocks", List.of(cached), List.of(pending), ownership, report);
        assertTrue(selected.pending().isEmpty());
        assertEquals(1, selected.configs().size());
        @SuppressWarnings("unchecked") Map<String, Object> copied = (Map<String, Object>) selected.configs().getFirst().config().get("tank");
        @SuppressWarnings("unchecked") List<String> copiedBehaviors = (List<String>) copied.get("behaviors");
        copiedBehaviors.add("later mutation");
        assertEquals(List.of("custom"), nested.get("behaviors"));
        assertEquals("own", pending.section().get("model"));
    }

    @Test void distinctItemAndBlockParserTypesAreIndependentAndRecipeSuffixSuppliesNamespace() throws Exception {
        Path own = write("own.yml"); Path external = write("external.yml");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json"));
        ownership.record(own, Files.readAllBytes(own));
        var report = new ContentConflicts(directory.resolve("conflicts.json"));
        var first = new CachedConfigSection(pack("bundle"), own,
                ConfigSection.of("farmersdelight_recipes#food", Map.of("soup", Map.of("station", "cooking_pot"))), Map.of());
        var second = new CachedConfigSection(pack("operator"), external,
                ConfigSection.of("farmersdelight_recipes", Map.of("food:soup", Map.of("station", "cooking_pot", "process", Map.of("ticks", 1)))), Map.of());
        var selected = ConfigPriorityFilter.filter("farmersdelight:pack_sections", List.of(first, second), List.of(), ownership, report);
        assertEquals(List.of(external), selected.configs().stream().map(CachedConfigSection::path).toList());
        assertEquals(1, ConfigPriorityFilter.filter("craftengine:items", List.of(first), List.of(), ownership, report).configs().size());
        assertEquals(1, ConfigPriorityFilter.filter("craftengine:blocks", List.of(first), List.of(), ownership, report).configs().size());
    }

    @Test void currentDefaultsReplaceHistoricalValuesAndAddNewRootsOnlyInLoadingCopies() throws Exception {
        Path file = directory.resolve("configuration/items.yml"); Files.createDirectories(file.getParent());
        Files.writeString(file, "historical release bytes");
        var ownership = new BundledContentManifest(directory.resolve("ownership.json")); ownership.record(file, Files.readAllBytes(file));
        var old = new CachedConfigSection(pack("food"), file, ConfigSection.of("items", Map.of("plate", Map.of("material", "old"))), Map.of());
        var overlay = new BundledPackOverlay(Map.of(file, Map.of("items", Map.of("plate", Map.of("material", "current")),
                "item-tags", Map.of("food:plates", List.of("food:plate")))));
        var item = overlay.prepare(new String[]{"items"}, List.of(old), List.of(pack("food")), ownership);
        assertEquals("current", ((Map<?, ?>) item.getFirst().config().get("plate")).get("material"));
        assertEquals("old", ((Map<?, ?>) old.config().get("plate")).get("material"));
        assertEquals("historical release bytes", Files.readString(file));
        assertEquals(1, overlay.prepare(new String[]{"item-tags"}, List.of(), List.of(pack("food")), ownership).size());
        Files.writeString(file, "operator edit");
        assertEquals(List.of(old), overlay.prepare(new String[]{"items"}, List.of(old), List.of(pack("food")), ownership));
        assertNull(overlay.document(file, ownership));
    }

    private Path write(String name) throws Exception { Path file = directory.resolve(name); Files.writeString(file, name); return file; }
    private Pack pack(String namespace) { return new Pack(directory, new PackMeta("test", "test", "1", namespace), true, new String[0]); }
}
