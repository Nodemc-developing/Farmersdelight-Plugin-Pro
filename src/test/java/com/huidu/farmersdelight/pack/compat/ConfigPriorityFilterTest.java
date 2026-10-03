package com.huidu.farmersdelight.pack.compat;

import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
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
                ConfigSection.of("papersdelight_recipes#food", Map.of("soup", Map.of("type", "cooking"))), Map.of());
        var second = new CachedConfigSection(pack("operator"), external,
                ConfigSection.of("papersdelight_recipes", Map.of("food:soup", Map.of("type", "cooking", "time", 1))), Map.of());
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
