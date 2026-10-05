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
    private static final List<String> MANAGED_FILES = List.of(POT_FILE, BOARD_FILE, GROUP_FILE, SPECIAL_FILE);
    private static volatile Map<PackSection, List<PackSections.Section>> preparedSections = Map.of();
    private static volatile Map<Path, String> packNamespaces = Map.of();
    private static volatile List<PackSections.Section> generatedSections = List.of();
    private RecipePackFiles() { }

    public static boolean managed(String name) { return MANAGED_FILES.contains(name); }

    public static Path configurationFolder(FarmersDelightPlugin plugin) {
        return plugin.getDataFolder().toPath().getParent().resolve("CraftEngine/resources/farmersdelight/configuration")
                .toAbsolutePath().normalize();
    }

    public static Path file(FarmersDelightPlugin plugin, String name) {
        if (!managed(name)) return plugin.getDataFolder().toPath().resolve(name).toAbsolutePath().normalize();
        return configurationFolder(plugin).resolve(name).normalize();
    }

    /** Installs native defaults once; existing operator documents are never converted or replaced. */
    public static void installDefaults(FarmersDelightPlugin plugin) throws Exception {
        Path marker = plugin.getDataFolder().toPath().resolve(".native-recipe-defaults-installed");
        boolean fresh = !Files.exists(marker);
        for (String name : MANAGED_FILES) {
            YamlConfiguration document;
            try (InputStream input = plugin.getResource(name)) {
                if (input == null) throw new IOException("Bundled recipe file missing: " + name);
                document = PlainYamlDocuments.parse(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), true);
            }
            Path target = file(plugin, name);
            if (installDefaultDocument(target, document, fresh)) {
                com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator.recordInstalledDefault(plugin, target);
            }
            com.huidu.farmersdelight.pack.compat.ExternalContentCoordinator.registerDefaultDocument(plugin, target, document.saveToString());
        }
        if (fresh) ConfigFileUpdater.writeStringAtomically(marker, "Native recipes are maintained in CraftEngine content packs.\n", true);
    }

    static boolean installDefaultDocument(Path target, YamlConfiguration document, boolean fresh) throws Exception {
        if (!fresh) return false;
        return YamlFileTransactions.execute(target, () -> {
            if (Files.exists(target)) return false;
            ConfigFileUpdater.writeStringAtomically(target, document.saveToString(), true);
            return true;
        });
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
            else if (section.section() == PackSection.NATIVE_RECIPES && recipeKind(kind)) {
                YamlConfiguration raw = new YamlConfiguration();
                raw.options().pathSeparator('\u0001');
                PlainYamlDocuments.setValue(raw, section.sectionKey(), section.yaml().get(PackSection.NATIVE_RECIPES.rootKey()));
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
        YamlConfiguration out = new YamlConfiguration(); out.options().pathSeparator('\u0001');
        PackSection kind = POT_FILE.equals(name) ? PackSection.COOKING_POT : BOARD_FILE.equals(name) ? PackSection.CUTTING_BOARD : null;
        if (kind == null) {
            document.getValues(false).forEach((key, value) -> PlainYamlDocuments.setValue(out, key, value));
            if (GROUP_FILE.equals(name)) PlainYamlDocuments.setValue(out, "groups", document.get("food_groups", document.get("groups")));
            return out;
        }
        Map<String, Object> selected = new LinkedHashMap<>(), groups = new LinkedHashMap<>();
        for (String key : document.getKeys(false)) {
            if (key.equals("external_recipe_overrides")) PlainYamlDocuments.setValue(out, key, document.get(key));
            if (!key.split("#", 2)[0].equals(NativeRecipeSchema.ROOT)) continue;
            PlainYamlDocuments.setValue(out, key, document.get(key));
            ConfigurationSection root = document.getConfigurationSection(key);
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                ConfigurationSection body = root.getConfigurationSection(id);
                if (body == null) continue;
                if (typeMatches(kind, body)) selected.putIfAbsent(id, body);
                if (kind == PackSection.COOKING_POT && typeMatches(PackSection.CUSTOM_COOKING_POT, body)) {
                    @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) groups.computeIfAbsent(body.getString("group"), ignored -> new LinkedHashMap<>());
                    entries.putIfAbsent(id, body);
                }
            }
        }
        PlainYamlDocuments.setValue(out, kind.rootKey(), selected);
        if (kind == PackSection.COOKING_POT) PlainYamlDocuments.setValue(out, PackSection.CUSTOM_COOKING_POT.rootKey(), groups);
        return out;
    }

    private static boolean recipeKind(PackSection kind) {
        return kind == PackSection.COOKING_POT || kind == PackSection.CUTTING_BOARD || kind == PackSection.CUSTOM_COOKING_POT;
    }

    static void validateNativeDocument(YamlConfiguration document, String source) {
        for (String key : document.getKeys(false)) {
            String base = key.split("#", 2)[0];
            if (!base.equals(NativeRecipeSchema.ROOT)) {
                if (base.endsWith("_recipes") && !base.equals("special_recipes"))
                    RecipeFileLoader.reportProblem(source, key, "Unsupported recipe root; use " + NativeRecipeSchema.ROOT);
                continue;
            }
            ConfigurationSection root = document.getConfigurationSection(key);
            if (root == null) { RecipeFileLoader.reportProblem(source, key, "Recipe registry must be a mapping"); continue; }
            for (String id : root.getKeys(false)) {
                ConfigurationSection body = root.getConfigurationSection(id);
                if (body == null || !Set.of("cooking_pot", "cutting_board", "fluid_tank").contains(NativeRecipeSchema.station(body)))
                    RecipeFileLoader.reportProblem(source, id, "station must be cooking_pot, cutting_board or fluid_tank");
            }
        }
    }

    private static boolean typeMatches(PackSection kind, ConfigurationSection body) {
        String station = NativeRecipeSchema.station(body), group = body.getString("group", "");
        return kind == PackSection.COOKING_POT ? station.equals("cooking_pot") && group.isBlank()
                : kind == PackSection.CUSTOM_COOKING_POT ? station.equals("cooking_pot") && !group.isBlank()
                : kind == PackSection.CUTTING_BOARD && station.equals("cutting_board");
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
        if (GROUP_FILE.equals(name)) return new RecipeSource(path, List.of(document.isSet("food_groups") ? "food_groups" : "groups", id), false, true);
        for (String key : document.getKeys(false)) {
            if (!key.split("#", 2)[0].equals(NativeRecipeSchema.ROOT)) continue;
            ConfigurationSection root = document.getConfigurationSection(key);
            ConfigurationSection body = root == null ? null : root.getConfigurationSection(id);
            if (body != null && java.util.Objects.equals(body.getString("group", ""), group == null ? "" : group))
                return new RecipeSource(path, List.of(key, id), true, true);
        }
        return new RecipeSource(path, List.of(NativeRecipeSchema.ROOT, id), true);
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
        if (!recipeKind(kind)) return List.copyOf(result);
        for (PackSections.Section section : plugin.packSectionsOf(PackSection.NATIVE_RECIPES)) {
            if (section.file() == null || section.file().equals(defaultFile)) continue;
            YamlConfiguration raw = new YamlConfiguration();
            raw.options().pathSeparator('\u0001');
            PlainYamlDocuments.setValue(raw, section.sectionKey(), section.yaml().get(PackSection.NATIVE_RECIPES.rootKey()));
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
                boolean typedRecipes = recipeKind(kind);
                if (typedRecipes ? !base.equals(NativeRecipeSchema.ROOT) : !base.equals(kind.sectionId()) && !base.equals(kind.rootKey())) continue;
                ConfigurationSection root = document.getConfigurationSection(key);
                if (root == null) continue;
                Map<String, Object> selected = new LinkedHashMap<>();
                for (var recipe : root.getValues(false).entrySet()) {
                    if (kind == PackSection.NATIVE_RECIPES || recipe.getValue() instanceof ConfigurationSection body
                            && (!typedRecipes || !base.equals(NativeRecipeSchema.ROOT) || typeMatches(kind, body))) {
                        if (kind == PackSection.CUSTOM_COOKING_POT && recipe.getValue() instanceof ConfigurationSection custom) {
                            @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) selected.computeIfAbsent(custom.getString("group"), ignored -> new LinkedHashMap<>());
                            entries.put(recipe.getKey(), recipe.getValue());
                        } else selected.put(recipe.getKey(), recipe.getValue());
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
