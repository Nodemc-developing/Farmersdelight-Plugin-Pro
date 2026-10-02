package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerialization;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-owned plain documents, converted to Bukkit objects only inside the publication scope. */
public final class PreparedRecipeFiles implements RecipeReloadCoordinator.Batch {
    public static final List<String> FILES = List.of("recipes/cooking_pot_recipes.yml", "recipes/cutting_board_recipes.yml", FoodGroupStore.FILE, RecipePackFiles.SPECIAL_FILE);
    private static final ThreadLocal<PreparedRecipeFiles> CURRENT = new ThreadLocal<>();
    private record Revision(long size, FileTime modified, Object key) {
        static Revision read(Path path) throws IOException {
            BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class);
            return new Revision(value.size(), value.lastModifiedTime(), value.fileKey());
        }
    }

    private final Map<Path, YamlConfiguration> plain;
    private final Map<Path, Revision> revisions;
    private final Map<Path, YamlConfiguration> materialized = new LinkedHashMap<>();
    private List<Path> packRoots = List.of();
    private List<Path> packFiles = List.of();
    private Map<Path, Revision> directoryRevisions = Map.of();
    private List<com.huidu.farmersdelight.pack.PackSections.Section> generatedSections = List.of();
    private Path defaultConfiguration;

    private PreparedRecipeFiles(Map<Path, YamlConfiguration> plain, Map<Path, Revision> revisions) {
        this.plain = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(plain));
        this.revisions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(revisions));
    }

    public static PreparedRecipeFiles read(FarmersDelightPlugin plugin, boolean mergeMissing) throws Exception {
        return read(plugin, mergeMissing, RecipePackFiles.configurationRoots(plugin));
    }

    public static PreparedRecipeFiles read(FarmersDelightPlugin plugin, boolean mergeMissing, List<Path> roots) throws Exception {
        Map<Path, YamlConfiguration> documents = new LinkedHashMap<>();
        Map<Path, Revision> revisions = new LinkedHashMap<>();
        List<Path> files = RecipePackFiles.files(roots);
        Map<Path, Revision> directories = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path directory : paths.filter(Files::isDirectory).sorted().toList()) directories.put(directory, Revision.read(directory));
            }
        }
        for (Path path : files) {
            YamlFileTransactions.execute(path, () -> {
                Revision before = Revision.read(path);
                YamlConfiguration document = com.huidu.farmersdelight.config.PlainYamlDocuments.readLiteral(path);
                if (!before.equals(Revision.read(path))) throw new IOException("Recipe file changed during preparation: " + path);
                boolean relevant = document.getKeys(false).stream().anyMatch(key -> {
                    String base = key.split("#", 2)[0];
                    for (var kind : com.huidu.farmersdelight.pack.PackSection.values()) if (base.equals(kind.sectionId()) || base.equals(kind.rootKey())) return true;
                    return base.equals("food_groups") || base.equals("custom_cooking_pot_recipes") || RecipePackFiles.containsFactory(document);
                });
                if (relevant) { documents.put(path, document); revisions.put(path, before); }
                return null;
            });
        }
        // Missing managed documents are intentional deletions, represented by empty documents rather than defaults.
        for (String name : FILES) documents.putIfAbsent(RecipePackFiles.file(plugin, name), new YamlConfiguration());
        PreparedRecipeFiles prepared = new PreparedRecipeFiles(documents, revisions);
        prepared.packRoots = List.copyOf(roots);
        prepared.packFiles = files;
        prepared.directoryRevisions = Map.copyOf(directories);
        prepared.generatedSections = RecipePackFiles.generatedSections();
        prepared.defaultConfiguration = RecipePackFiles.configurationFolder(plugin);
        if (!files.equals(RecipePackFiles.files(roots))) throw new IOException("Content-pack recipe file set changed during preparation");
        prepared.validateCurrent();
        return prepared;
    }

    public void validateCurrent() throws IOException {
        // Directory metadata detects added/deleted files without walking or parsing packs on a tick thread.
        for (var directory : directoryRevisions.entrySet()) if (!directory.getValue().equals(Revision.read(directory.getKey())))
            throw new IOException("Content-pack recipe file set changed during preparation");
        for (var entry : revisions.entrySet()) {
            if (!entry.getValue().equals(Revision.read(entry.getKey()))) {
                throw new IOException("Recipe file changed during preparation: " + entry.getKey().getFileName());
            }
        }
    }

    public void publishWithin(Runnable publication) {
        PreparedRecipeFiles previous = CURRENT.get();
        CURRENT.set(this);
        try {
            // A bad legacy serialized value must fail before either manager changes its live snapshot.
            for (Path path : plain.keySet()) currentDocument(path);
            publication.run();
            if (defaultConfiguration != null) {
                Map<com.huidu.farmersdelight.pack.PackSection, List<com.huidu.farmersdelight.pack.PackSections.Section>> sections = new LinkedHashMap<>();
                for (var kind : com.huidu.farmersdelight.pack.PackSection.values()) sections.put(kind, currentSections(kind));
                RecipePackFiles.retainPreparedSections(sections);
            }
        }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static YamlConfiguration currentDocument(Path path) {
        PreparedRecipeFiles batch = CURRENT.get();
        if (batch == null) return null;
        Path key = path.toAbsolutePath().normalize();
        YamlConfiguration plain = batch.plain.get(key);
        if (plain == null) return null;
        return batch.materialized.computeIfAbsent(key, ignored -> materialize(plain));
    }

    static List<com.huidu.farmersdelight.pack.PackSections.Section> currentSections(com.huidu.farmersdelight.pack.PackSection kind) {
        PreparedRecipeFiles batch = CURRENT.get();
        if (batch == null || batch.defaultConfiguration == null) return null;
        Map<Path, YamlConfiguration> documents = new LinkedHashMap<>();
        for (Path path : batch.plain.keySet()) documents.put(path, currentDocument(path));
        Path defaultFile = kind == com.huidu.farmersdelight.pack.PackSection.COOKING_POT ? batch.defaultConfiguration.resolve(RecipePackFiles.POT_FILE)
                : kind == com.huidu.farmersdelight.pack.PackSection.CUTTING_BOARD ? batch.defaultConfiguration.resolve(RecipePackFiles.BOARD_FILE) : null;
        return RecipePackFiles.preserveGenerated(RecipePackFiles.sections(documents, defaultFile, kind), batch.generatedSections, documents, kind);
    }

    static YamlConfiguration materialize(YamlConfiguration plain) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().pathSeparator(plain.options().pathSeparator());
        copy(plain.getValues(false), configuration);
        return configuration;
    }

    private static void copy(Map<?, ?> values, ConfigurationSection destination) {
        values.forEach((key, value) -> {
            String name = String.valueOf(key);
            Object converted = convert(value);
            if (converted instanceof Map<?, ?> children) copy(children, destination.createSection(name));
            else destination.set(name, converted);
        });
    }

    private static Object convert(Object value) {
        if (value instanceof ConfigurationSection section) return convert(section.getValues(false));
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, child) -> copy.put(String.valueOf(key), convert(child)));
            if (copy.containsKey(ConfigurationSerialization.SERIALIZED_TYPE_KEY)) {
                Object decoded = ConfigurationSerialization.deserializeObject(copy);
                if (decoded == null) throw new IllegalArgumentException("Invalid serialized recipe value: " + copy.get(ConfigurationSerialization.SERIALIZED_TYPE_KEY));
                return decoded;
            }
            return copy;
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>(source.size());
            for (Object child : source) copy.add(convert(child));
            return copy;
        }
        return value;
    }
}
