package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Plain pack documents are read by workers; item resolution remains in the recipe managers. */
public final class RecipePackFiles {
    public static final String POT_FILE = "recipes/cooking_pot_recipes.yml";
    public static final String BOARD_FILE = "recipes/cutting_board_recipes.yml";
    public static final String GROUP_FILE = "recipes/food_groups.yml";
    public static final String SPECIAL_FILE = "recipes/special_recipes.yml";
    private static final List<String> LEGACY_FILES = List.of(POT_FILE, BOARD_FILE, GROUP_FILE, SPECIAL_FILE);
    private static volatile Map<PackSection, List<PackSections.Section>> preparedSections = Map.of();
    private static volatile Map<Path, String> packNamespaces = Map.of();
    private static volatile List<PackSections.Section> generatedSections = List.of();
    private RecipePackFiles() { }

    public static boolean managed(String name) { return LEGACY_FILES.contains(name); }

    public static Path configurationFolder(FarmersDelightPlugin plugin) {
        return plugin.getDataFolder().toPath().getParent().resolve("CraftEngine/resources/farmersdelight/configuration")
                .toAbsolutePath().normalize();
    }

    public static Path file(FarmersDelightPlugin plugin, String name) {
        if (!managed(name)) return plugin.getDataFolder().toPath().resolve(name).toAbsolutePath().normalize();
        return configurationFolder(plugin).resolve(name).normalize();
    }

    /** Must run before CraftEngine's initial pack parse. A completed migration never restores deleted entries. */
    public static void installAndMigrate(FarmersDelightPlugin plugin) throws Exception {
        Map<String, Boolean> bundledNew = new LinkedHashMap<>();
        for (String name : LEGACY_FILES) bundledNew.put(name, !Files.exists(file(plugin, name))
                && !Files.exists(plugin.getDataFolder().toPath().resolve(name)));
        installAndMigrate(plugin.getDataFolder().toPath(), configurationFolder(plugin), name -> {
            try (InputStream input = plugin.getResource(name)) {
                if (input == null) throw new IOException("Bundled recipe file missing: " + name);
                return PlainYamlDocuments.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), true);
            }
        });
        for (var entry : bundledNew.entrySet()) if (entry.getValue() && Files.isRegularFile(file(plugin, entry.getKey())))
            com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator.recordInstalledDefault(plugin, file(plugin, entry.getKey()));
        for (String name : LEGACY_FILES) try (InputStream input = plugin.getResource(name)) {
            if (input == null) throw new IOException("Bundled recipe file missing: " + name);
            YamlConfiguration bundled = PlainYamlDocuments.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), true);
            com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator.registerDefaultDocument(
                    plugin, file(plugin, name), canonical(bundled, name).saveToString());
        }
    }

    @FunctionalInterface interface BundledReader { YamlConfiguration read(String name) throws Exception; }

    static void installAndMigrate(Path dataFolder, Path configurationFolder, BundledReader bundled) throws Exception {
        Path marker = dataFolder.resolve(".recipes-in-content-pack-v1");
        if (Files.exists(marker)) return;
        // Parse every source before writing anything; malformed operator documents are never replaced.
        Map<String, YamlConfiguration> documents = new LinkedHashMap<>();
        for (String name : LEGACY_FILES) {
            Path old = dataFolder.resolve(name);
            documents.put(name, Files.isRegularFile(old) ? PlainYamlDocuments.readLiteral(old) : bundled.read(name));
            Path target = configurationFolder.resolve(name);
            if (Files.isRegularFile(target)) PlainYamlDocuments.readLiteral(target);
        }
        for (String name : LEGACY_FILES) {
            Path old = dataFolder.resolve(name);
            Path target = configurationFolder.resolve(name).toAbsolutePath().normalize();
            YamlConfiguration incoming = canonical(documents.get(name), name);
            YamlFileTransactions.execute(target, () -> {
                YamlConfiguration existing = Files.isRegularFile(target) ? PlainYamlDocuments.readLiteral(target) : null;
                if (Files.isRegularFile(old)) ConfigFileUpdater.backup(old);
                if (existing == null) {
                    ConfigFileUpdater.writeStringAtomically(target, incoming.saveToString(), true);
                } else if (Files.isRegularFile(old)) {
                    // Content-pack definitions retain ownership on conflicts; import only missing legacy nodes.
                    ConfigFileUpdater.backup(target);
                    mergeMissing(existing, incoming);
                    ConfigFileUpdater.writeStringAtomically(target, existing.saveToString(), true);
                }
                return null;
            });
        }
        ConfigFileUpdater.writeStringAtomically(marker, "Recipes are maintained in CraftEngine content packs.\n", true);
    }

    private static void mergeMissing(ConfigurationSection target, ConfigurationSection incoming) {
        for (var entry : incoming.getValues(false).entrySet()) {
            String key = entry.getKey();
            if (!target.isSet(key)) PlainYamlDocuments.setValue(target, key, entry.getValue());
            else if (entry.getValue() instanceof ConfigurationSection source && target.get(key) instanceof ConfigurationSection destination) {
                // Registry entries are indivisible: never splice part of a conflicting recipe into a pack's node.
                if (key.equals("papersdelight_recipes") || key.equals("food_groups") || key.equals("custom_cooking_pot_recipes")) {
                    for (var child : source.getValues(false).entrySet()) if (!destination.isSet(child.getKey())) PlainYamlDocuments.setValue(destination, child.getKey(), child.getValue());
                }
            }
        }
    }

    static YamlConfiguration canonical(YamlConfiguration legacy, String name) {
        YamlConfiguration out = new YamlConfiguration();
        out.options().pathSeparator('\u0001');
        legacy.getValues(false).forEach((key, value) -> PlainYamlDocuments.setValue(out, key, value));
        if (POT_FILE.equals(name) || BOARD_FILE.equals(name)) {
            String oldRoot = POT_FILE.equals(name) ? "cooking_pot_recipes" : "cutting_board_recipes";
            ConfigurationSection root = legacy.getConfigurationSection(oldRoot);
            ConfigurationSection existing = legacy.getConfigurationSection("papersdelight_recipes");
            Map<String, Object> recipes = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing.getValues(false));
            if (root != null) root.getValues(false).forEach((id, raw) -> {
                if (raw instanceof ConfigurationSection section) {
                    Map<String, Object> body = new LinkedHashMap<>(section.getValues(false));
                    body = POT_FILE.equals(name) ? RecipeSchemaAdapter.formatPot(body, true) : RecipeSchemaAdapter.formatBoard(body, true);
                    recipes.putIfAbsent(id, body);
                }
            });
            out.set(oldRoot, null);
            PlainYamlDocuments.setValue(out, "papersdelight_recipes", recipes);
        } else if (GROUP_FILE.equals(name)) {
            if (legacy.isSet("groups") && !legacy.isSet("food_groups")) PlainYamlDocuments.setValue(out, "food_groups", legacy.get("groups"));
            out.set("groups", null);
        }
        return out;
    }

    /** Snapshot CE's enabled configuration folders on the owning server thread, before worker I/O. */
    public static List<Path> configurationRoots(FarmersDelightPlugin plugin) {
        Set<Path> roots = new LinkedHashSet<>();
        Map<Path, String> namespaces = new LinkedHashMap<>();
        BukkitCraftEngine engine = BukkitCraftEngine.instance();
        if (engine != null && engine.packManager() != null) {
            for (var pack : engine.packManager().loadedPacks()) {
                if (pack.enabled()) for (Path folder : pack.configurationFolders()) {
                    Path root = folder.toAbsolutePath().normalize();
                    roots.add(root);
                    namespaces.put(root, pack.namespace());
                }
            }
        }
        if (defaultPackEnabled(plugin)) {
            roots.add(configurationFolder(plugin));
            namespaces.putIfAbsent(configurationFolder(plugin), "farmersdelight");
        }
        packNamespaces = Map.copyOf(namespaces);
        List<PackSections.Section> generated = new ArrayList<>();
        for (PackSection kind : PackSection.values()) for (var section : plugin.packSectionsOf(kind)) if (section.generated()) generated.add(section);
        generatedSections = List.copyOf(generated);
        return List.copyOf(roots);
    }

    static List<PackSections.Section> generatedSections() { return generatedSections; }
    static Map<Path, String> namespaces() { return packNamespaces; }

    static boolean containsFactory(YamlConfiguration document) {
        return document.getKeys(false).stream().anyMatch(key -> Set.of("config_factory", "config-factory", "config_factories", "config-factories").contains(key.split("#", 2)[0]));
    }

    static List<PackSections.Section> preserveGenerated(List<PackSections.Section> sections, List<PackSections.Section> generated,
                                                       Map<Path, YamlConfiguration> documents, PackSection kind) {
        List<PackSections.Section> result = new ArrayList<>(sections);
        for (var section : generated) {
            YamlConfiguration source = documents.get(section.file());
            if (source == null || !containsFactory(source)) continue;
            if (section.section() == kind) result.add(section);
            else if (section.section() == PackSection.PAPERS_RECIPES && (kind == PackSection.COOKING_POT || kind == PackSection.CUTTING_BOARD)) {
                YamlConfiguration raw = new YamlConfiguration();
                raw.options().pathSeparator('\u0001');
                PlainYamlDocuments.setValue(raw, section.sectionKey(), section.yaml().get(PackSection.PAPERS_RECIPES.rootKey()));
                for (var converted : sections(Map.of(section.file(), raw), null, kind)) result.add(new PackSections.Section(kind,
                        section.source(), section.namespace(), converted.yaml(), section.file(), section.sectionKey(), true));
            }
        }
        return List.copyOf(result);
    }

    public static boolean defaultPackEnabled(FarmersDelightPlugin plugin) {
        BukkitCraftEngine engine = BukkitCraftEngine.instance();
        if (engine != null && engine.packManager() != null) {
            Path folder = configurationFolder(plugin).getParent();
            for (var pack : engine.packManager().loadedPacks()) if (pack.folder().toAbsolutePath().normalize().equals(folder)) return pack.enabled();
        }
        return true;
    }

    public static List<Path> files(List<Path> roots) throws IOException {
        Set<Path> result = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if ((name.endsWith(".yml") || name.endsWith(".yaml")) && !name.startsWith(".")) result.add(path.toAbsolutePath().normalize());
                }
            }
        }
        return List.copyOf(result);
    }

    public static YamlConfiguration bridge(YamlConfiguration document, String name) {
        YamlConfiguration out = new YamlConfiguration();
        out.options().pathSeparator('\u0001');
        document.getValues(false).forEach((key, value) -> PlainYamlDocuments.setValue(out, key, value));
        PackSection kind = POT_FILE.equals(name) ? PackSection.COOKING_POT : BOARD_FILE.equals(name) ? PackSection.CUTTING_BOARD : null;
        if (kind != null) {
            Map<String, Object> recipes = new LinkedHashMap<>();
            for (String key : document.getKeys(false)) {
                String base = key.split("#", 2)[0];
                if (base.equals(kind.rootKey()) || base.equals(kind.sectionId()) || base.equals("papersdelight_recipes")) {
                    ConfigurationSection root = document.getConfigurationSection(key);
                    if (root == null) continue;
                    root.getValues(false).forEach((id, value) -> {
                        if (value instanceof ConfigurationSection body && (!base.equals("papersdelight_recipes") || typeMatches(kind, body))) recipes.putIfAbsent(id, value);
                    });
                }
            }
            PlainYamlDocuments.setValue(out, kind.rootKey(), recipes);
        } else if (GROUP_FILE.equals(name)) {
            PlainYamlDocuments.setValue(out, "groups", document.get("food_groups", document.get("groups")));
        }
        return out;
    }

    private static boolean typeMatches(PackSection kind, ConfigurationSection body) {
        String type = body.getString("type", "").toLowerCase(Locale.ROOT);
        return kind == PackSection.COOKING_POT ? type.equals("cooking") || type.endsWith(":cooking")
                : type.equals("cutting") || type.endsWith(":cutting");
    }

    public static RecipeSource source(FarmersDelightPlugin plugin, String name, String id, String group) {
        Path path = file(plugin, name);
        YamlConfiguration document = PreparedRecipeFiles.currentDocument(path);
        if (document == null) {
            try { document = PlainYamlDocuments.readLiteral(path); }
            catch (Exception unreadable) { throw new IllegalStateException("Recipe source cannot be read", unreadable); }
        }
        return source(plugin, name, id, group, document);
    }

    public static RecipeSource source(FarmersDelightPlugin plugin, String name, String id, String group, YamlConfiguration document) {
        Path path = file(plugin, name);
        if (group != null && !group.isBlank()) {
            ConfigurationSection groups = document.getConfigurationSection("custom_cooking_pot_recipes");
            ConfigurationSection entries = groups == null ? null : groups.getConfigurationSection(group);
            ConfigurationSection node = entries == null ? null : entries.getConfigurationSection(id);
            return new RecipeSource(path, List.of("custom_cooking_pot_recipes", group, id), node != null && node.isSet("type"), true);
        }
        if (GROUP_FILE.equals(name)) return new RecipeSource(path, List.of(document.isSet("food_groups") ? "food_groups" : "groups", id), false, true);
        PackSection kind = POT_FILE.equals(name) ? PackSection.COOKING_POT : PackSection.CUTTING_BOARD;
        for (String key : document.getKeys(false)) {
            ConfigurationSection root = document.getConfigurationSection(key);
            if (root != null && root.getKeys(false).contains(id) && (key.split("#",2)[0].equals("papersdelight_recipes") || key.split("#",2)[0].equals(kind.sectionId()) || key.equals(kind.rootKey()))) {
                return new RecipeSource(path, List.of(key, id), key.split("#",2)[0].equals("papersdelight_recipes"), true);
            }
        }
        return new RecipeSource(path, List.of("papersdelight_recipes", id), true);
    }

    /** Uses freshly prepared documents for /fd reload and CE's transformed sections for CE reloads. */
    public static List<PackSections.Section> sections(FarmersDelightPlugin plugin, PackSection kind) {
        List<PackSections.Section> prepared = PreparedRecipeFiles.currentSections(kind);
        if (prepared != null) return prepared;
        if (preparedSections.containsKey(kind)) return preparedSections.get(kind);
        Path defaultFile = defaultFile(plugin, kind);
        List<PackSections.Section> result = new ArrayList<>();
        for (PackSections.Section section : plugin.packSectionsOf(kind)) {
            if (section.file() == null || !section.file().equals(defaultFile)) result.add(section);
        }
        if (kind != PackSection.COOKING_POT && kind != PackSection.CUTTING_BOARD) return List.copyOf(result);
        for (PackSections.Section section : plugin.packSectionsOf(PackSection.PAPERS_RECIPES)) {
            if (section.file() == null || section.file().equals(defaultFile)) continue;
            YamlConfiguration raw = new YamlConfiguration();
            raw.options().pathSeparator('\u0001');
            PlainYamlDocuments.setValue(raw, section.sectionKey(), section.yaml().get(PackSection.PAPERS_RECIPES.rootKey()));
            for (var converted : sections(Map.of(section.file(), raw), defaultFile, kind)) {
                result.add(new PackSections.Section(kind, section.source(), section.namespace(), converted.yaml(),
                        section.file(), section.sectionKey(), section.generated()));
            }
        }
        return List.copyOf(result);
    }

    static Path defaultFile(FarmersDelightPlugin plugin, PackSection kind) {
        return kind == PackSection.COOKING_POT ? file(plugin, POT_FILE) : kind == PackSection.CUTTING_BOARD ? file(plugin, BOARD_FILE) : null;
    }

    public static void invalidatePreparedSections() { preparedSections = Map.of(); }

    static void retainPreparedSections(Map<PackSection, List<PackSections.Section>> sections) {
        preparedSections = Map.copyOf(sections);
    }

    static List<PackSections.Section> sections(Map<Path, YamlConfiguration> documents, Path defaultFile, PackSection kind) {
        return sections(documents, defaultFile, kind, packNamespaces);
    }

    static List<PackSections.Section> sections(Map<Path, YamlConfiguration> documents, Path defaultFile, PackSection kind,
                                             Map<Path, String> namespaces) {
        List<PackSections.Section> result = new ArrayList<>();
        for (var entry : documents.entrySet()) {
            if (defaultFile != null && entry.getKey().equals(defaultFile)) continue;
            YamlConfiguration document = entry.getValue();
            for (String key : document.getKeys(false)) {
                String base = key.split("#", 2)[0];
                boolean typedRecipes = kind == PackSection.COOKING_POT || kind == PackSection.CUTTING_BOARD;
                if (!base.equals(kind.sectionId()) && !base.equals(kind.rootKey()) && !(typedRecipes && base.equals("papersdelight_recipes"))) continue;
                ConfigurationSection root = document.getConfigurationSection(key);
                if (root == null) continue;
                Map<String, Object> selected = new LinkedHashMap<>();
                for (var recipe : root.getValues(false).entrySet()) {
                    if (kind == PackSection.PAPERS_RECIPES || recipe.getValue() instanceof ConfigurationSection body
                            && (!typedRecipes || !base.equals("papersdelight_recipes") || typeMatches(kind, body))) {
                        selected.put(recipe.getKey(), recipe.getValue());
                    }
                }
                if (selected.isEmpty()) continue;
                YamlConfiguration bridged = new YamlConfiguration();
                bridged.options().pathSeparator('\u0001');
                PlainYamlDocuments.setValue(bridged, kind.rootKey(), selected);
                String namespace = "farmersdelight";
                int length = -1;
                // CE root suffixes distinguish sections; the pack still owns their namespace.
                for (var pack : namespaces.entrySet()) if (entry.getKey().startsWith(pack.getKey()) && pack.getKey().getNameCount() > length) {
                    namespace = pack.getValue(); length = pack.getKey().getNameCount();
                }
                result.add(new PackSections.Section(kind, entry.getKey().toString(), namespace, bridged, entry.getKey(), key));
            }
        }
        return List.copyOf(result);
    }
}
