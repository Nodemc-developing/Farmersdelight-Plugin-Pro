package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.config.PlainYamlDocuments;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PreparedRecipeFilesTest {
    @TempDir Path directory;

    @Test void bundledRecipeDocumentsKeepBukkitReaderSemantics() throws Exception {
        for (String name : PreparedRecipeFiles.FILES) {
            String text = Files.readString(Path.of("src/main/resources").resolve(name));
            var expected = new YamlConfiguration();
            expected.loadFromString(text);
            var plain = PlainYamlDocuments.parse(text);
            var actual = PreparedRecipeFiles.materialize(plain);
            assertEquals(leaves(expected), leaves(actual), name);
        }
    }

    @Test void workerDocumentsKeepSerializedValuesPlainUntilPublication() throws Exception {
        ConfigurationSerialization.registerClass(Note.class, "fd-test-note");
        try {
            String text = "legacy:\n  ==: fd-test-note\n  name: soup\nlist:\n  - {==: fd-test-note, name: bread}\n  - null\n";
            var plain = PlainYamlDocuments.parse(text);
            assertTrue(plain.get("legacy") instanceof ConfigurationSection);
            var materialized = PreparedRecipeFiles.materialize(plain);
            assertEquals("soup", ((Note) materialized.get("legacy")).name);
            assertEquals("bread", ((Note) materialized.getList("list").getFirst()).name);
            assertNull(materialized.getList("list").get(1));
            assertTrue(plain.get("legacy") instanceof ConfigurationSection);
        } finally {
            ConfigurationSerialization.unregisterClass(Note.class);
        }
    }

    @Test void nestedPublicationScopesRestoreTheOuterDocumentAndClearAfterwards() throws Exception {
        Path path = directory.resolve("recipes.yml");
        var first = batch(Map.of(path, PlainYamlDocuments.parse("value: outer\n")), Map.of());
        var second = batch(Map.of(path, PlainYamlDocuments.parse("value: inner\n")), Map.of());
        first.publishWithin(() -> {
            assertEquals("outer", PreparedRecipeFiles.currentDocument(path).getString("value"));
            second.publishWithin(() -> assertEquals("inner", PreparedRecipeFiles.currentDocument(path).getString("value")));
            assertEquals("outer", PreparedRecipeFiles.currentDocument(path).getString("value"));
        });
        assertNull(PreparedRecipeFiles.currentDocument(path));
    }

    @Test void invalidLegacyValuesAbortTheWholeBatchBeforeAnyManagerPublishes() throws Exception {
        Path valid = directory.resolve("pot.yml");
        Path invalid = directory.resolve("board.yml");
        var prepared = batch(Map.of(valid, PlainYamlDocuments.parse("valid: true\n"),
                invalid, PlainYamlDocuments.parse("legacy:\n  ==: unknown-fd-test-type\n")), Map.of());
        AtomicBoolean published = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> prepared.publishWithin(() -> published.set(true)));
        assertFalse(published.get());
        assertNull(PreparedRecipeFiles.currentDocument(valid));
    }

    @Test void changedOrDeletedFilesAreRejectedBeforePublication() throws Exception {
        Path file = directory.resolve("pot.yml");
        Files.writeString(file, "value: original\n");
        Class<?> revision = Class.forName(PreparedRecipeFiles.class.getName() + "$Revision");
        var read = revision.getDeclaredMethod("read", Path.class);
        read.setAccessible(true);
        var prepared = batch(Map.of(file, PlainYamlDocuments.read(file)), Map.of(file, read.invoke(null, file)));
        prepared.validateCurrent();
        Files.writeString(file, "value: changed and longer\n");
        assertThrows(IOException.class, prepared::validateCurrent);
        Files.delete(file);
        assertThrows(IOException.class, prepared::validateCurrent);
    }

    @Test void literalContentPackIdentifiersRemainIntactDuringOwnerMaterialization() throws Exception {
        var plain = PlainYamlDocuments.parse("papersdelight_recipes#addon: {addon:soup.v2: {type: cooking, result: minecraft:carrot}}\n", true);
        var materialized = PreparedRecipeFiles.materialize(plain);
        assertEquals(java.util.List.of("addon:soup.v2"), java.util.List.copyOf(materialized.getConfigurationSection("papersdelight_recipes#addon").getKeys(false)));
        assertEquals("minecraft:carrot", materialized.getConfigurationSection("papersdelight_recipes#addon").getConfigurationSection("addon:soup.v2").getString("result"));
    }

    @Test void directoryRevisionsRejectFilesAddedWhilePublicationWaits() throws Exception {
        Path folder = directory.resolve("configuration");
        Files.createDirectories(folder);
        Class<?> revision = Class.forName(PreparedRecipeFiles.class.getName() + "$Revision");
        var read = revision.getDeclaredMethod("read", Path.class);
        read.setAccessible(true);
        var prepared = batch(Map.of(), Map.of());
        var directories = PreparedRecipeFiles.class.getDeclaredField("directoryRevisions");
        directories.setAccessible(true);
        directories.set(prepared, Map.of(folder, read.invoke(null, folder)));
        prepared.validateCurrent();
        Files.writeString(folder.resolve("new.yml"), "papersdelight_recipes: {}\n");
        Files.setLastModifiedTime(folder, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        assertThrows(IOException.class, prepared::validateCurrent);
    }

    @Test void preparedSourcesAreVisibleDuringPublicationAndRetainedAfterwards() throws Exception {
        Path file = directory.resolve("configuration/addon.yml");
        var prepared = batch(Map.of(file, PlainYamlDocuments.parse("papersdelight_recipes: {addon:soup.v2: {type: cooking, result: minecraft:carrot}}\n", true)), Map.of());
        var defaultConfiguration = PreparedRecipeFiles.class.getDeclaredField("defaultConfiguration");
        defaultConfiguration.setAccessible(true);
        defaultConfiguration.set(prepared, directory.resolve("configuration"));
        try {
            prepared.publishWithin(() -> {
                var sections = PreparedRecipeFiles.currentSections(com.huidu.farmersdelight.pack.PackSection.COOKING_POT);
                assertEquals(1, sections.size());
                assertEquals(file, sections.getFirst().file());
                assertTrue(sections.getFirst().yaml().getConfigurationSection("cooking_pot_recipes").isConfigurationSection("addon:soup.v2"));
            });
            assertNull(PreparedRecipeFiles.currentDocument(file));
        } finally { RecipePackFiles.invalidatePreparedSections(); }
    }

    private PreparedRecipeFiles batch(Map<Path, YamlConfiguration> documents, Map<?, ?> revisions) throws Exception {
        var constructor = PreparedRecipeFiles.class.getDeclaredConstructor(Map.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(documents, revisions);
    }

    private Map<String, Object> leaves(YamlConfiguration yaml) {
        Map<String, Object> result = new TreeMap<>();
        yaml.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) result.put(key, value);
        });
        return result;
    }

    public static final class Note implements ConfigurationSerializable {
        final String name;
        Note(String name) { this.name = name; }
        public Map<String, Object> serialize() { return Map.of("name", name); }
        public static Note deserialize(Map<String, Object> values) { return new Note((String) values.get("name")); }
    }
}
