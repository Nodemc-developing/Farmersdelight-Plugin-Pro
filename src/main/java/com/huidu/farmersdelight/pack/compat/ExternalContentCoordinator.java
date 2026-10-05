package com.huidu.farmersdelight.pack.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.compat.CraftEngineBaseline;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackCacheData;
import net.momirealms.craftengine.core.pack.PackManager;
import net.momirealms.craftengine.core.pack.PendingConfigSection;
import net.momirealms.craftengine.core.plugin.config.AbstractConfigParser;
import net.momirealms.craftengine.core.plugin.config.ConfigParser;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.IdConfigParser;
import net.momirealms.craftengine.core.plugin.config.IdSectionConfigParser;
import net.momirealms.craftengine.core.plugin.config.ResourceException;
import net.momirealms.craftengine.core.plugin.config.template.ArgumentString;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.registry.Registry;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredListener;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loading-only adapter for the verified host builds. No reflective access runs in gameplay tasks.
 * The adapter preserves parser stages and never adds a competing task to the loading pyramid.
 */
public final class ExternalContentCoordinator implements AutoCloseable, Listener {
    public static final String SUPPORTED_SHA256 = CraftEngineBaseline.SHA256;
    private static volatile ExternalContentCoordinator active;
    private static final List<BiFunction<String, CachedConfigSection, CachedConfigSection>> TRANSFORMERS = new CopyOnWriteArrayList<>();
    private static final Map<Path, Map<String, Object>> EXTRA_DEFAULTS = new java.util.concurrent.ConcurrentHashMap<>();
    public record SourceDefinition<T>(String id, Path file, String source, T value) { }
    public record ItemParseDiagnostic(String type, long generation, Map<String, Integer> expandedVisits,
                                      Map<String, Integer> originalAttempts, Map<String, Integer> successfulCalls,
                                      Map<String, String> sourceFiles) { }
    public record WorldgenAdaptation(String parser, String file, String section, String definition,
                                    String field, String action, String reason) { }
    public record CommonTagExportDiagnostic(String source, String path, String tag, String member,
                                            String action, String reason) { }

    /** Loading counters only. Game ticks never update or consult this diagnostic snapshot. */
    public static List<ItemParseDiagnostic> loadingDiagnostics() {
        ExternalContentCoordinator coordinator = active;
        if (coordinator == null || coordinator.closed) return List.of();
        return coordinator.bindings.stream().map(Binding::wrapper).filter(parser -> parser.itemParseSection != null)
                .map(PrioritizingParser::diagnostic).toList();
    }
    public static Path selectedResourceLayer() {
        ExternalContentCoordinator coordinator = active;
        return coordinator == null || coordinator.closed ? null : coordinator.selectedResourceLayer;
    }
    public static List<WorldgenAdaptation> worldgenLoadingDiagnostics() {
        ExternalContentCoordinator coordinator = active;
        return coordinator == null || coordinator.closed ? List.of() : coordinator.worldgenDiagnosticSnapshot();
    }
    public static List<CommonTagExportDiagnostic> commonTagLoadingDiagnostics() {
        ExternalContentCoordinator coordinator = active;
        return coordinator == null || coordinator.closed ? List.of() : coordinator.commonTagDiagnostics;
    }

    /** Loading-only transforms receive a private copy after precedence has been resolved. */
    public static AutoCloseable registerTransformer(BiFunction<String, CachedConfigSection, CachedConfigSection> transformer) {
        java.util.Objects.requireNonNull(transformer, "transformer");
        TRANSFORMERS.add(transformer);
        return () -> TRANSFORMERS.remove(transformer);
    }
    private final FarmersDelightPlugin plugin;
    private final PackManager manager;
    private final BundledContentManifest ownership;
    private final ContentConflicts report;
    private final ManagedResourceLayer resourceLayer;
    private final BundledPackOverlay currentDefaults;
    private final Path emptyRoot;
    private final Map<Pack, Path[]> originalResourceRoots = new IdentityHashMap<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final Field folderInputs;
    private final Field zipInputs;
    private final Field holderValue;
    private final Map<String, ConfigParser> routes;
    private final Map<Object, Object> registryValues;
    private final Map<Object, Integer> registryIds;
    private final java.util.function.Predicate<String> customBlock;
    private final java.util.function.Function<String, Integer> vanillaStateId;
    private final net.momirealms.craftengine.core.item.ItemManager itemManager;
    private final HostCommonTagOverlay commonTags;
    private final Path commonTagReport;
    private volatile List<CommonTagExportDiagnostic> commonTagDiagnostics = List.of();
    private final Path worldgenReport;
    private final Map<String, WorldgenAdaptation> worldgenAdaptations = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile Path selectedResourceLayer;

    private record Binding(ConfigParser original, PrioritizingParser wrapper, Object holder, int id) { }

    /** Call once in onLoad, after the addon has registered every pack parser. */
    public static ExternalContentCoordinator install(FarmersDelightPlugin plugin) {
        ExternalContentCoordinator coordinator = null;
        try {
            coordinator = new ExternalContentCoordinator(plugin);
            coordinator.bindParsers();
            coordinator.registerCacheListener();
            active = coordinator;
            return coordinator;
        } catch (Exception failure) {
            if (coordinator != null) coordinator.close();
            throw new IllegalStateException("External content priority requires a verified CraftEngine 26.9.2 or 26.10 build; "
                    + "compatibility loading was refused: " + failure.getMessage(), failure);
        }
    }

    /** Records only a newly installed default, never an imported or edited operator document. */
    public static void recordInstalledDefault(org.bukkit.plugin.java.JavaPlugin plugin, Path file) throws IOException {
        ExternalContentCoordinator coordinator = active;
        BundledContentManifest manifest = coordinator != null && coordinator.plugin == plugin
                ? coordinator.ownership : BundledContentManifest.load(plugin);
        manifest.record(file, Files.readAllBytes(file));
    }

    /** Plain recipe workers use the same ownership and precedence as the host's loading bridge. */
    public static <T> List<SourceDefinition<T>> selectDefinitions(String category, List<SourceDefinition<T>> input)
            throws IOException {
        ExternalContentCoordinator coordinator = active;
        if (coordinator == null || coordinator.closed) throw new IOException("External content loading adapter is unavailable");
        List<DefinitionPriority.Definition<SourceDefinition<T>>> definitions = new ArrayList<>();
        Map<Path, Boolean> ownership = new java.util.HashMap<>();
        for (SourceDefinition<T> source : input) {
            boolean bundled = false;
            if (source.file() != null) {
                Boolean existing = ownership.get(source.file());
                if (existing == null) {
                    existing = coordinator.ownership.isBundled(source.file());
                    ownership.put(source.file(), existing);
                }
                bundled = existing;
            }
            definitions.add(new DefinitionPriority.Definition<>(source.id(), source.source(), bundled, source));
        }
        try { return DefinitionPriority.select(category, definitions, coordinator.report).stream()
                .map(DefinitionPriority.Definition::value).toList(); }
        finally { coordinator.report.save(); }
    }

    @SuppressWarnings("unchecked")
    private ExternalContentCoordinator(FarmersDelightPlugin plugin) throws Exception {
        this.plugin = plugin;
        BukkitCraftEngine engine = BukkitCraftEngine.instance();
        if (engine == null || engine.packManager() == null) throw new IllegalStateException("CraftEngine is not loaded");
        CraftEngineBaseline.verifyHostArtifact(BukkitCraftEngine.class);
        this.manager = engine.packManager();
        var blockManager = engine.blockManager();
        itemManager = engine.itemManager();
        commonTags = new HostCommonTagOverlay(
                (Map<Key, List<UniqueKey>>) field(itemManager.getClass(), "VANILLA_TAG_TO_ITEMS").get(null),
                (Map<Key, List<UniqueKey>>) field(itemManager.getClass(), "customItemTags").get(itemManager),
                (Map<Key, Set<Key>>) field(itemManager.getClass(), "VANILLA_ITEM_TO_TAGS").get(null));
        vanillaStateId = source -> {
            var state = blockManager.createVanillaBlockState(source);
            return state == null ? null : state.registryId();
        };
        customBlock = id -> {
            // Unexpanded factory arguments and malformed IDs remain for the host's parser diagnostics.
            if (id.contains("$")) return false;
            try { return blockManager.blockById(Key.of(id)).isPresent(); }
            catch (IllegalArgumentException invalid) { return false; }
        };
        ownership = BundledContentManifest.load(plugin);
        ownership.adoptMatching(plugin);
        currentDefaults = new BundledPackOverlay(plugin);
        Path cache = plugin.getDataFolder().toPath().resolve("content-cache").toAbsolutePath().normalize();
        worldgenReport = cache.resolve("worldgen-adaptations.json");
        commonTagReport = cache.resolve("common-tag-export.json");
        report = new ContentConflicts(cache.resolve("conflicts.json"));
        resourceLayer = new ManagedResourceLayer(cache.resolve("resource-layers"), ownership, report);
        emptyRoot = cache.resolve("empty-resources");
        Files.createDirectories(emptyRoot);
        routes = (Map<String, ConfigParser>) typedField(manager.getClass(), "sectionParsers", Map.class).get(manager);
        Object registry = BuiltInRegistries.CONFIG_PARSER;
        registryValues = (Map<Object, Object>) typedField(registry.getClass(), "byValue", Map.class).get(registry);
        registryIds = (Map<Object, Integer>) typedField(registry.getClass(), "toId", Map.class).get(registry);
        holderValue = field(Class.forName("net.momirealms.craftengine.core.registry.Holder$Reference"), "value");
        folderInputs = typedField(PackCacheData.class, "externalFolders", Set.class);
        zipInputs = typedField(PackCacheData.class, "externalZips", Set.class);
        // Validate the final-field bridge against a disposable cache input before changing live parsers.
        var constructor = PackCacheData.class.getDeclaredConstructor(net.momirealms.craftengine.core.plugin.CraftEngine.class);
        constructor.setAccessible(true);
        Object probe = constructor.newInstance(engine);
        Set<Path> probeSet = new LinkedHashSet<>();
        folderInputs.set(probe, probeSet);
        zipInputs.set(probe, probeSet);
        if (folderInputs.get(probe) != probeSet || zipInputs.get(probe) != probeSet)
            throw new IllegalStateException("Resource input bridge is unavailable");
    }

    /** Returns current bundled values only for a verified, unedited installed default. */
    public static Map<String, Object> currentBundledDocument(Path file) throws IOException {
        ExternalContentCoordinator coordinator = active;
        return coordinator == null || coordinator.closed ? null : coordinator.currentDefaults.document(file, coordinator.ownership);
    }

    /** For generated defaults, register the current canonical document before loading its private copy. */
    public static void registerDefaultDocument(org.bukkit.plugin.java.JavaPlugin plugin, Path file, String currentYaml)
            throws IOException {
        Path key = file.toAbsolutePath().normalize();
        EXTRA_DEFAULTS.put(key, ConfigPriorityFilter.copyMap(net.momirealms.sparrow.yaml.SparrowYaml.create().load(currentYaml).getValues()));
        if (Files.isRegularFile(key)) {
            ExternalContentCoordinator coordinator = active;
            BundledContentManifest manifest = coordinator != null && coordinator.plugin == plugin
                    ? coordinator.ownership : BundledContentManifest.load(plugin);
            manifest.adoptIfKnown(key, BundledContentManifest.digest(currentYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)), List.of());
            manifest.persist();
        }
    }

    static Map<String, Object> registeredDefault(Path file) { return EXTRA_DEFAULTS.get(file.toAbsolutePath().normalize()); }
    static Map<Path, Map<String, Object>> registeredDefaults() { return Map.copyOf(EXTRA_DEFAULTS); }

    private List<WorldgenAdaptation> worldgenDiagnosticSnapshot() {
        return worldgenAdaptations.values().stream().sorted(java.util.Comparator.comparing(WorldgenAdaptation::file)
                .thenComparing(WorldgenAdaptation::definition).thenComparing(WorldgenAdaptation::field)).toList();
    }

    private CachedConfigSection adaptWorldgen(ConfigParser parser, CachedConfigSection input) {
        String root = input.config().path().split("#", 2)[0];
        if (!(root.equals("placed_features") || root.equals("placed-features"))) return input;
        var result = Worldgen26Inputs.adapt(input.config().values(),
                net.momirealms.craftengine.core.util.VersionHelper.isOrAbove26_3, customBlock);
        for (var change : result.changes()) {
            var diagnostic = new WorldgenAdaptation(parser.type().toString(), input.path().toAbsolutePath().normalize().toString(),
                    root, change.definition(), change.field(), change.action(), change.reason());
            worldgenAdaptations.put(diagnostic.parser() + "#" + diagnostic.file() + "#" + diagnostic.definition()
                    + "#" + diagnostic.field(), diagnostic);
            if (change.action().equals("refused")) plugin.getLogger().warning("World-generation loading conversion refused at "
                    + diagnostic.file() + "#" + diagnostic.definition() + "." + diagnostic.field() + ": " + diagnostic.reason());
        }
        return new CachedConfigSection(input.pack(), input.path(), ConfigSection.of(input.config().path(), result.values()),
                input.arguments());
    }

    private ConfigPriorityFilter.Result unifyCategories(ConfigPriorityFilter.Result selected) {
        Map<String, Object> definitions = new java.util.LinkedHashMap<>();
        CachedConfigSection anchor = null;
        PendingConfigSection pendingAnchor = null;
        for (CachedConfigSection cached : selected.configs()) {
            if (!cached.config().path().split("#", 2)[0].equals("categories")) continue;
            for (var entry : cached.config().values().entrySet()) {
                String id = categoryId(cached, entry.getKey());
                definitions.put(id, entry.getValue());
                if (id.equals(UnifiedCategories.ROOT)) anchor = cached;
                else if (anchor == null && id.equals(UnifiedCategories.LEGACY_ROOT)) anchor = cached;
            }
        }
        for (PendingConfigSection pending : selected.pending()) {
            definitions.put(pending.id().toString(), pending.section().values());
            if (pending.id().toString().equals(UnifiedCategories.ROOT)) pendingAnchor = pending;
            else if (pendingAnchor == null && pending.id().toString().equals(UnifiedCategories.LEGACY_ROOT)) pendingAnchor = pending;
        }
        if (!UnifiedCategories.recognized(definitions)) return selected;
        Map<String, Object> merged = UnifiedCategories.merge(definitions, id -> {
            Key key = Key.of(id);
            return itemManager.isVanillaItem(key) || itemManager.loadedItems().containsKey(key);
        });
        List<CachedConfigSection> configs = new ArrayList<>();
        Set<String> retained = new LinkedHashSet<>();
        for (CachedConfigSection cached : selected.configs()) {
            if (!cached.config().path().split("#", 2)[0].equals("categories")) { configs.add(cached); continue; }
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            for (String rawId : cached.config().keySet()) {
                String id = categoryId(cached, rawId);
                retained.add(id);
                values.put(rawId, merged.get(id));
            }
            if (cached == anchor) for (var entry : merged.entrySet())
                if (!definitions.containsKey(entry.getKey())) {
                    values.put(entry.getKey(), entry.getValue()); retained.add(entry.getKey());
                }
            configs.add(new CachedConfigSection(cached.pack(), cached.path(),
                    ConfigSection.of(cached.config().path(), ConfigPriorityFilter.copyMap(values)), cached.arguments()));
        }
        List<PendingConfigSection> pending = new ArrayList<>();
        for (PendingConfigSection entry : selected.pending()) {
            retained.add(entry.id().toString());
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) merged.get(entry.id().toString());
            pending.add(new PendingConfigSection(entry.pack(), entry.path(), entry.id(),
                    ConfigSection.of(entry.section().path(), ConfigPriorityFilter.copyMap(values))));
        }
        if (anchor == null && pendingAnchor != null) for (var entry : merged.entrySet())
            if (!retained.contains(entry.getKey())) {
                @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) entry.getValue();
                pending.add(new PendingConfigSection(pendingAnchor.pack(), pendingAnchor.path(), Key.of(entry.getKey()),
                        ConfigSection.of("categories." + entry.getKey(), ConfigPriorityFilter.copyMap(values))));
            }
        return new ConfigPriorityFilter.Result(configs, pending);
    }

    private static String categoryId(CachedConfigSection cached, String rawId) {
        String id = rawId;
        if (cached.hasArguments() && id.contains("$")) id = ArgumentString.preParse(cached.config().path(), id)
                .get(cached.config().path(), cached.arguments()).toString();
        return Key.withDefaultNamespace(id, cached.pack().namespace()).toString();
    }

    private synchronized void saveWorldgenDiagnostics() throws IOException {
        BundledContentManifest.atomicWrite(worldgenReport, new com.google.gson.GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(worldgenDiagnosticSnapshot()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private synchronized void exportCommonTags(CommonTagResolver.HostTagInput input, PrioritizingParser parser,
                                               java.util.concurrent.CompletableFuture<CommonTagResolver.HostTagInput> captured) {
        requireOpenLoadingGeneration();
        if (parser.commonTagInput != captured || captured.isCancelled())
            throw new IllegalStateException("The common tag loading input was replaced; this generation was refused");
        var compiled = CommonTagResolver.prepareHostExport(input, tag -> {
            try { return commonTags.existingMembers(Key.of(tag)); }
            catch (IllegalArgumentException invalid) { return null; }
        });
        List<CommonTagExportDiagnostic> diagnostics = new ArrayList<>();
        for (var problem : compiled.diagnostics()) diagnostics.add(new CommonTagExportDiagnostic(problem.source(),
                problem.path(), problem.tag(), problem.member(), "rejected", problem.reason()));
        Map<Key, HostCommonTagOverlay.Members> groups = new java.util.LinkedHashMap<>();
        for (var entry : compiled.groups().entrySet()) {
            String tagName = entry.getKey();
            String source = compiled.sourceFiles().getOrDefault(tagName, "common-tags.yml");
            String path = "tags." + tagName;
            Key tag;
            try { tag = Key.of(tagName); }
            catch (IllegalArgumentException invalid) {
                diagnostics.add(new CommonTagExportDiagnostic(source, path, tagName, tagName, "rejected", "Invalid tag ID"));
                continue;
            }
            Set<String> existing = commonTags.existingMembers(tag);
            if (existing != null && !existing.isEmpty()) {
                diagnostics.add(new CommonTagExportDiagnostic(source, path, tagName, "", "kept_native",
                        "The host's complete tag takes precedence over the common group"));
                continue;
            }
            List<UniqueKey> vanilla = new ArrayList<>(), custom = new ArrayList<>();
            boolean invalidMember = false;
            for (String member : entry.getValue()) {
                try {
                    Key id = Key.of(member);
                    if (itemManager.isVanillaItem(id)) vanilla.add(UniqueKey.create(id));
                    else if (itemManager.loadedItems().containsKey(id)) custom.add(UniqueKey.create(id));
                    else {
                        invalidMember = true;
                        diagnostics.add(new CommonTagExportDiagnostic(source, path, tagName, member, "rejected_member", "Item is not registered on the host"));
                    }
                } catch (IllegalArgumentException invalid) {
                    invalidMember = true;
                    diagnostics.add(new CommonTagExportDiagnostic(source, path, tagName, member, "rejected_member", "Invalid item ID"));
                }
            }
            if (invalidMember && vanilla.isEmpty() && custom.isEmpty()) continue;
            groups.put(tag, new HostCommonTagOverlay.Members(vanilla, custom));
            diagnostics.add(new CommonTagExportDiagnostic(source, path, tagName, "", entry.getValue().isEmpty() ? "explicit_empty" : "exported",
                    "Validated vanilla members=" + vanilla.size() + ", custom members=" + custom.size()));
        }
        commonTags.publish(groups);
        commonTagDiagnostics = List.copyOf(diagnostics);
        for (var diagnostic : diagnostics) if (diagnostic.action().startsWith("rejected"))
            plugin.getLogger().warning("Common tag export refused at " + diagnostic.source() + "#" + diagnostic.path()
                    + " member '" + diagnostic.member() + "': " + diagnostic.reason());
        try {
            BundledContentManifest.atomicWrite(commonTagReport, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .disableHtmlEscaping().create().toJson(commonTagDiagnostics).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException failure) {
            commonTags.clear();
            throw new IllegalStateException("Could not save common tag loading diagnostics", failure);
        }
    }

    private void bindParsers() throws Exception {
        for (ConfigParser parser : parserSnapshot(BuiltInRegistries.CONFIG_PARSER)) {
            PrioritizingParser wrapper = new PrioritizingParser(parser);
            Object holder = registryValues.get(parser);
            Integer id = registryIds.get(parser);
            if (holder == null || id == null || holderValue.get(holder) != parser)
                throw new IllegalStateException("Unexpected parser registry layout for " + parser.type());
            Binding binding = new Binding(parser, wrapper, holder, id);
            bindings.add(binding);
            holderValue.set(holder, wrapper);
            registryValues.remove(parser);
            registryValues.put(wrapper, holder);
            registryIds.remove(parser);
            registryIds.put(wrapper, id);
            for (String section : parser.sectionId()) {
                if (routes.get(section) != parser) throw new IllegalStateException("Parser route changed: " + section);
                routes.put(section, wrapper);
            }
        }
    }

    static List<ConfigParser> parserSnapshot(Registry<ConfigParser> registry) {
        List<ConfigParser> snapshot = new ArrayList<>();
        Set<ConfigParser> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // PackManager.parsers() owns only its internal parsers; the host loads the complete registry.
        for (ConfigParser parser : registry) {
            if (!(parser instanceof AbstractConfigParser)) continue;
            if (parser instanceof ExternalContentCoordinator.PrioritizingParser)
                throw new IllegalStateException("A content parser is already wrapped: " + parser.type());
            if (!seen.add(parser)) throw new IllegalStateException("A content parser is registered twice: " + parser.type());
            snapshot.add(parser);
        }
        return List.copyOf(snapshot);
    }

    private void registerCacheListener() {
        // The host performs its initial parse before this dependent plugin enables. Ownership of this
        // load-phase listener follows the host; close() unregisters this listener alone.
        var host = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (host == null) throw new IllegalStateException("CraftEngine plugin instance is unavailable");
        AsyncResourcePackCacheEvent.getHandlerList().register(new RegisteredListener(this,
                (listener, event) -> ((ExternalContentCoordinator) listener).cache((AsyncResourcePackCacheEvent) event),
                EventPriority.HIGHEST, host, false));
    }

    private synchronized void cache(AsyncResourcePackCacheEvent event) {
        if (closed) return;
        AssetGate gate = new AssetGate();
        gate.lifecycleCheck = this::requireOpenLoadingGeneration;
        List<ManagedResourceLayer.Source> sources = new ArrayList<>();
        List<Pack> packs = manager.loadedPacks().stream().filter(Pack::enabled).toList();
        for (Pack pack : packs) {
            Path[] originals = originalResourceRoots.computeIfAbsent(pack, ignored -> pack.resourcePackFolders().clone());
            for (Path source : originals) sources.add(new ManagedResourceLayer.Source(source, false));
        }
        for (Path path : event.cacheData().externalFolders()) sources.add(new ManagedResourceLayer.Source(path, false));
        for (Path path : event.cacheData().externalZips()) sources.add(new ManagedResourceLayer.Source(path, true));
        sources.addAll(currentDefaults.assets());
        try {
            for (ManagedResourceLayer.Source source : sources) {
                if (source.prefix().isEmpty() && !source.currentDefault())
                    (source.zip() ? gate.zipSources : gate.folderSources).add(source.path());
            }
            List<ManagedResourceLayer.Source> fixed = sources.stream()
                    .filter(source -> !source.prefix().isEmpty() || source.currentDefault()).toList();
            gate.builder = () -> {
                List<ManagedResourceLayer.Source> finalSources = new ArrayList<>(fixed);
                gate.folderSources.forEach(path -> finalSources.add(new ManagedResourceLayer.Source(path, false)));
                gate.zipSources.forEach(path -> finalSources.add(new ManagedResourceLayer.Source(path, true)));
                try { return resourceLayer.build(finalSources); }
                catch (IOException failure) { throw new IllegalStateException("Could not build managed resources", failure); }
            };
            gate.seal = () -> sealResources(gate, packs);
            // Event listeners may generate resources using the current layer, then append their output.
            // The host's first read after event dispatch completes rebuilds once and seals the final inputs.
            folderInputs.set(event.cacheData(), new SealedInputs(gate, false));
            zipInputs.set(event.cacheData(), new SealedInputs(gate, true));
        } catch (Exception failure) {
            gate.failure = "Managed resource generation refused: " + failure.getMessage();
            plugin.getLogger().severe(gate.failure + "; original sources and previous completed layer were preserved.");
        }
    }

    private synchronized void requireOpenLoadingGeneration() {
        if (closed) throw new IllegalStateException("The content loading adapter has closed; this generation was refused");
    }

    synchronized void sealResources(AssetGate gate, List<Pack> packs) {
        requireOpenLoadingGeneration();
        for (Pack pack : packs) java.util.Arrays.fill(pack.resourcePackFolders(), emptyRoot);
        selectedResourceLayer = gate.layer;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        commonTags.close(); commonTagDiagnostics = List.of();
        if (active == this) active = null;
        HandlerList.unregisterAll(this);
        for (int i = bindings.size() - 1; i >= 0; i--) {
            Binding binding = bindings.get(i);
            binding.wrapper().cancelCommonInput();
            try {
                if (holderValue.get(binding.holder()) == binding.wrapper()) holderValue.set(binding.holder(), binding.original());
                registryValues.remove(binding.wrapper());
                registryValues.put(binding.original(), binding.holder());
                registryIds.remove(binding.wrapper());
                registryIds.put(binding.original(), binding.id());
                for (String section : binding.original().sectionId())
                    if (routes.get(section) == binding.wrapper()) routes.put(section, binding.original());
            } catch (Exception failure) { plugin.getLogger().severe("Could not restore the loading adapter: " + failure.getMessage()); }
        }
        originalResourceRoots.forEach((pack, original) -> {
            Path[] current = pack.resourcePackFolders();
            for (int i = 0; i < current.length && i < original.length; i++)
                if (current[i].equals(emptyRoot)) current[i] = original[i];
        });
        originalResourceRoots.clear();
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Field typedField(Class<?> type, String name, Class<?> expectedType) throws NoSuchFieldException {
        Field field = field(type, name);
        if (!expectedType.isAssignableFrom(field.getType()))
            throw new IllegalStateException("Unsupported loading field " + type.getName() + "." + name
                    + ": expected " + expectedType.getName() + ", received " + field.getType().getName());
        return field;
    }

    private final class PrioritizingParser extends IdSectionConfigParser {
        private final ConfigParser delegate;
        private final Field storage;
        private final Field pending;
        private final MethodHandle itemParseSection;
        private final java.util.Queue<PendingConfigSection> derivedItems = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final Map<Key, PendingConfigSection> expandedItems = new java.util.concurrent.ConcurrentHashMap<>();
        private List<PendingConfigSection> postProcessItems = List.of();
        private volatile java.util.concurrent.CompletableFuture<CommonTagResolver.HostTagInput> commonTagInput;
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> expandedVisits = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> originalAttempts = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, java.util.concurrent.atomic.AtomicInteger> successfulCalls = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, String> sourceFiles = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile long loadGeneration;
        private ItemParseDiagnostic diagnostic() {
            return new ItemParseDiagnostic(type().toString(), loadGeneration, counts(expandedVisits), counts(originalAttempts),
                    counts(successfulCalls), Map.copyOf(sourceFiles));
        }
        private Map<String, Integer> counts(Map<String, java.util.concurrent.atomic.AtomicInteger> counters) {
            Map<String, Integer> frozen = new java.util.TreeMap<>(); counters.forEach((id, count) -> frozen.put(id, count.get()));
            return Collections.unmodifiableMap(frozen);
        }
        PrioritizingParser(ConfigParser delegate) throws ReflectiveOperationException {
            this.delegate = delegate;
            storage = typedField(delegate.getClass(), "configStorage", List.class);
            Field found;
            try { found = typedField(delegate.getClass(), "pendingConfigSections", List.class); }
            catch (NoSuchFieldException absent) { found = null; }
            pending = found;
            if (java.util.Arrays.asList(delegate.sectionId()).contains("items") && delegate instanceof IdSectionConfigParser) {
                if (pending == null) throw new IllegalStateException("The item parser has no pending item loading stage: " + delegate.type());
                Method parse = delegate.getClass().getDeclaredMethod("parseSection", Pack.class, Path.class, Key.class, ConfigSection.class);
                parse.setAccessible(true);
                itemParseSection = MethodHandles.lookup().unreflect(parse).bindTo(delegate);
                super.setErrorHandler(((AbstractConfigParser) delegate).errorHandler());
            } else itemParseSection = null;
        }
        @Override public Key type() { return delegate.type(); }
        @Override public String[] sectionId() { return delegate.sectionId(); }
        @Override public LoadingStage loadingStage() { return delegate.loadingStage(); }
        @Override public List<LoadingStage> dependencies() { return delegate.dependencies(); }
        @Override public boolean async() {
            // IdConfigParser calls this from its constructor to choose the ID map implementation.
            // The delegate is assigned after super(); a concurrent map is safe for both parser modes.
            return delegate == null || delegate.async();
        }
        @Override public boolean silentIfNotExists() { return delegate.silentIfNotExists(); }
        @Override public int count() { return delegate.count(); }
        @Override public void setErrorHandler(Consumer<ResourceException> handler) { super.setErrorHandler(handler); delegate.setErrorHandler(handler); }
        @Override public void addConfig(CachedConfigSection section) { delegate.addConfig(section); }
        @Override public void clearConfigs() {
            super.clearConfigs(); delegate.clearConfigs();
            cancelCommonInput();
            derivedItems.clear(); expandedItems.clear(); postProcessItems = List.of();
        }
        private void cancelCommonInput() {
            java.util.concurrent.CompletableFuture<CommonTagResolver.HostTagInput> captured;
            synchronized (ExternalContentCoordinator.this) {
                captured = commonTagInput;
                commonTagInput = null;
            }
            if (captured != null) captured.cancel(false);
        }
        @Override public boolean supportSearch() { return delegate instanceof IdConfigParser parser && parser.supportSearch(); }
        @Override public Path pathById(Key id) { return delegate instanceof IdConfigParser parser ? parser.pathById(id) : null; }
        @Override public java.util.Collection<Key> registeredKeys() {
            return delegate instanceof IdConfigParser parser ? parser.registeredKeys() : List.of();
        }
        @Override public void preProcess() {
            derivedItems.clear(); expandedItems.clear(); postProcessItems = List.of();
            if (itemParseSection != null) synchronized (ExternalContentCoordinator.this) {
                requireOpenLoadingGeneration(); commonTags.clear(); commonTagDiagnostics = List.of();
                cancelCommonInput();
                commonTagInput = CommonTagResolver.prepareHostReloadAsync(plugin);
            }
            try {
                if (java.util.Arrays.stream(sectionId()).anyMatch(section -> section.equals("placed_features") || section.equals("placed-features")))
                    worldgenAdaptations.values().removeIf(entry -> entry.parser().equals(type().toString()));
                @SuppressWarnings("unchecked") List<CachedConfigSection> raw = (List<CachedConfigSection>) storage.get(delegate);
                var current = currentDefaults.prepare(sectionId(), new ArrayList<>(raw), manager.loadedPacks(), ownership);
                raw.clear(); current.forEach(section -> raw.add(ConfigPriorityFilter.loadingCopy(section)));
            } catch (ReflectiveOperationException | IOException failure) {
                throw new IllegalStateException("Could not prepare current bundled defaults for " + type(), failure);
            }
            if (delegate instanceof IdConfigParser idParser) idParser.clearIdToPath();
            delegate.preProcess();
        }
        @Override @SuppressWarnings("unchecked") public void postProcess() {
            if (itemParseSection == null) { delegate.postProcess(); return; }
            var capturedCommonInput = commonTagInput;
            try {
                List<PendingConfigSection> target = (List<PendingConfigSection>) pending.get(delegate);
                ExpandedItemLifecycle.postProcess(target, postProcessItems, delegate::postProcess);
                if (capturedCommonInput == null) throw new IllegalStateException("Common tag loading input was not prepared for " + type());
                exportCommonTags(capturedCommonInput.join(), this, capturedCommonInput);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not retain expanded items for " + type(), failure);
            } finally {
                synchronized (ExternalContentCoordinator.this) {
                    if (commonTagInput == capturedCommonInput) commonTagInput = null;
                }
                if (capturedCommonInput != null) capturedCommonInput.cancel(false);
                postProcessItems = List.of(); expandedItems.clear(); derivedItems.clear();
            }
        }
        @Override @SuppressWarnings("unchecked") public void loadAll() {
            try {
                List<CachedConfigSection> raw = (List<CachedConfigSection>) storage.get(delegate);
                List<PendingConfigSection> delayed = pending == null ? Collections.emptyList()
                        : (List<PendingConfigSection>) pending.get(delegate);
                List<CachedConfigSection> original = new ArrayList<>(raw);
                List<PendingConfigSection> originalDelayed = new ArrayList<>(delayed);
                ConfigPriorityFilter.Result chosen;
                if (type().toString().equals("craftengine:block_state_mapping"))
                    chosen = ConfigPriorityFilter.filterBlockStateMappings(original, vanillaStateId, ownership, report);
                else if (delegate instanceof IdConfigParser || type().toString().equals("farmersdelight:pack_sections"))
                    chosen = ConfigPriorityFilter.filter(type().toString(), original, originalDelayed, ownership, report);
                else {
                    List<CachedConfigSection> copied = original.stream().map(ConfigPriorityFilter::loadingCopy).toList();
                    chosen = new ConfigPriorityFilter.Result(copied, originalDelayed);
                }
                if (itemParseSection != null) {
                    expandedVisits.clear(); originalAttempts.clear(); successfulCalls.clear(); sourceFiles.clear(); ++loadGeneration;
                    expandedItems.clear(); postProcessItems = List.of();
                    // Use the host's template engine exactly once, then adapt fully expanded item definitions.
                    configStorage.clear(); chosen.configs().forEach(section -> configStorage.add(ConfigPriorityFilter.loadingCopy(section)));
                    pendingConfigSections.clear(); pendingConfigSections.addAll(chosen.pending());
                    derivedItems.clear();
                    clearIdToPath();
                    try {
                        super.loadAll();
                        List<PendingConfigSection> originalOrder = new ArrayList<>(pendingConfigSections);
                        List<Key> derivedOrder = new ArrayList<>();
                        Set<Key> originalPendingIds = chosen.pending().stream().map(PendingConfigSection::id)
                                .collect(java.util.stream.Collectors.toSet());
                        PendingConfigSection generated;
                        int derivedCount = 0;
                        while ((generated = derivedItems.poll()) != null) {
                            if (++derivedCount > 100_000) throw new IllegalStateException("Too many derived item definitions");
                            if (idToPath.containsKey(generated.id()) || originalPendingIds.contains(generated.id())) continue;
                            if (!isDuplicate(generated.id(), generated.path(), generated.section().path())) {
                                parseSection(generated.pack(), generated.path(), generated.id(), generated.section());
                                derivedOrder.add(generated.id());
                            }
                        }
                        postProcessItems = ExpandedItemLifecycle.ordered(originalOrder, derivedOrder, expandedItems);
                        @SuppressWarnings("unchecked") Map<Key, Path> originalIds = (Map<Key, Path>) field(delegate.getClass(), "idToPath").get(delegate);
                        originalIds.clear(); originalIds.putAll(idToPath);
                    } finally {
                        configStorage.clear(); pendingConfigSections.clear(); derivedItems.clear(); expandedItems.clear();
                    }
                    return;
                }
                List<CachedConfigSection> transformed = new ArrayList<>(chosen.configs().size());
                for (CachedConfigSection section : chosen.configs()) {
                    for (var transformer : TRANSFORMERS) section = java.util.Objects.requireNonNull(
                            transformer.apply(type().toString(), section), "loading transformer returned null");
                    transformed.add(adaptWorldgen(delegate, ConfigPriorityFilter.loadingCopy(section)));
                }
                if (java.util.Arrays.stream(sectionId()).anyMatch(section -> section.equals("placed_features") || section.equals("placed-features")))
                    saveWorldgenDiagnostics();
                ConfigPriorityFilter.Result ready = new ConfigPriorityFilter.Result(transformed, chosen.pending());
                if (java.util.Arrays.asList(sectionId()).contains("categories")
                        && plugin.getConfig().getBoolean("craftengine-resources.unified-categories", true))
                    ready = unifyCategories(ready);
                raw.clear(); raw.addAll(ready.configs());
                if (pending != null) { delayed.clear(); delayed.addAll(ready.pending()); }
                try { delegate.loadAll(); }
                finally {
                    raw.clear(); raw.addAll(original);
                    if (pending != null) { delayed.clear(); delayed.addAll(originalDelayed); }
                }
            } catch (ReflectiveOperationException | IOException failure) {
                throw new IllegalStateException("Could not select pack definitions for " + type(), failure);
            }
        }

        @Override protected void parseSection(Pack pack, Path path, Key id, ConfigSection expanded) {
            if (itemParseSection == null) throw new IllegalStateException("Expanded item hook used for " + type());
            expandedVisits.computeIfAbsent(id.toString(), ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            sourceFiles.putIfAbsent(id.toString(), path.toAbsolutePath().normalize().toString());
            CachedConfigSection item = new CachedConfigSection(pack, path,
                    ConfigSection.of("items", ConfigPriorityFilter.copyMap(Map.of(id.toString(), expanded.values()))), null);
            for (var transformer : TRANSFORMERS) item = java.util.Objects.requireNonNull(
                    transformer.apply(type().toString(), item), "loading transformer returned null");
            Object adapted = item.config().get(id.toString());
            if (!(adapted instanceof Map<?, ?> values)) throw new IllegalStateException("Item transformer removed " + id);
            try {
                @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) values;
                originalAttempts.computeIfAbsent(id.toString(), ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                ConfigSection adaptedSection = ConfigSection.of(expanded.path(), ItemVersionInputs.adapt(body,
                        net.momirealms.craftengine.core.util.VersionHelper.isOrAbove1_21_2,
                        net.momirealms.craftengine.core.util.VersionHelper.isOrAbove1_21_5));
                itemParseSection.invoke(pack, path, id, adaptedSection);
                successfulCalls.computeIfAbsent(id.toString(), ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                expandedItems.putIfAbsent(id, new PendingConfigSection(pack, path, id, adaptedSection));
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Expanded item parser failed for " + id, failure); }
            // Derived visual items are queued after all original definitions; existing external IDs win.
            for (var entry : item.config().values().entrySet()) {
                Key generatedId = Key.withDefaultNamespace(entry.getKey(), pack.namespace());
                if (generatedId.equals(id)) continue;
                if (!(entry.getValue() instanceof Map<?, ?> generated)) throw new IllegalStateException("Invalid derived item " + generatedId);
                @SuppressWarnings("unchecked") Map<String, Object> definition = (Map<String, Object>) generated;
                derivedItems.add(new PendingConfigSection(pack, path, generatedId,
                        ConfigSection.of("items." + generatedId, ConfigPriorityFilter.copyMap(definition))));
            }
        }
    }

    static final class AssetGate {
        volatile Path layer;
        volatile String failure;
        final Set<Path> folderSources = new LinkedHashSet<>();
        final Set<Path> zipSources = new LinkedHashSet<>();
        java.util.function.Supplier<Path> builder;
        Runnable seal;
        Runnable lifecycleCheck = () -> { };
        boolean sealed;
        java.util.function.BooleanSupplier dispatching = ExternalContentCoordinator::eventDispatching;
        synchronized void check() {
            lifecycleCheck.run();
            if (failure != null) throw new IllegalStateException(failure);
            if (layer == null && builder != null) {
                try { layer = builder.get(); }
                catch (RuntimeException problem) { failure = "Managed resource generation refused: " + problem.getMessage(); throw problem; }
            }
            if (layer == null) throw new IllegalStateException("Managed resource layer has not completed");
            if (!sealed && !dispatching.getAsBoolean()) {
                if (seal != null) seal.run();
                sealed = true;
            }
        }
        synchronized boolean add(Path path, boolean zip) {
            lifecycleCheck.run();
            if (sealed || !dispatching.getAsBoolean()) { reject(); return false; }
            boolean added = (zip ? zipSources : folderSources).add(path);
            if (added) layer = null;
            return added;
        }
        void reject() {
            failure = "A late resource-cache listener modified sealed inputs; this generation was refused";
            throw new IllegalStateException(failure);
        }
    }

    private static boolean eventDispatching() {
        // Only resource-loading callbacks reach this check; gameplay never walks a stack.
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getMethodName().equals("callEvent") && (frame.getClassName().startsWith("org.bukkit.")
                        || frame.getClassName().startsWith("io.papermc.paper.plugin.manager."))));
    }

    static final class SealedInputs extends AbstractSet<Path> {
        private final AssetGate gate;
        private final boolean zip;
        SealedInputs(AssetGate gate, boolean zip) { this.gate = gate; this.zip = zip; }
        @Override public Iterator<Path> iterator() {
            gate.check();
            return (zip ? Set.<Path>of() : Set.of(gate.layer)).iterator();
        }
        @Override public int size() { gate.check(); return zip ? 0 : 1; }
        @Override public boolean add(Path path) { return gate.add(path, zip); }
        @Override public boolean remove(Object path) { gate.reject(); return false; }
        @Override public void clear() { gate.reject(); }
    }
}
