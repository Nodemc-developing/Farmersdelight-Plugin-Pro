package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.i18n.I18n;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Resolves item tags for recipe matching, result indexes and ingredient icons.
 * Merge common-tags.yml and addon registrations by taking the union of members per tag.
 * Publish immutable snapshots so concurrent region readers never observe a partial update.
 */
public final class CommonTagResolver {
    private record TagSnapshot(Map<String, Set<String>> members, Map<String, Set<String>> reverse,
                               boolean loaded) { }
    private static volatile TagSnapshot snapshot = new TagSnapshot(Map.of(), Map.of(), false);
    private static TagSnapshot currentSnapshot() {
        return com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.get(CommonTagResolver.class, snapshot);
    }

    public static final String FILE_NAME = "common-tags.yml";
    private static final String ROOT_KEY = "tags";
    private static final String BUILTIN_SOURCE = "farmersdelight";

    // Externally registered sources (addons). Guarded by this class's monitor during merge; reads.
    // never touch it directly. Values and member sets are immutable after registration.
    private static final Map<String, Map<String, Set<String>>> externals = new ConcurrentHashMap<>();

    // FD's own default, loaded from <dataFolder>/common-tags.yml. Immutable snapshot.
    private static volatile Map<String, Set<String>> builtin = Map.of();

    // Effective merged snapshots, published whole on every change.
    private static volatile Map<String, Set<String>> tagToItems = Map.of();
    private static volatile Map<String, Set<String>> itemToTags = Map.of();
    private static volatile boolean loaded = false;
    private static Set<String> reportedProblems = Set.of();
    private static volatile boolean preparedReloads;
    private static final Map<String, Map<String, Set<String>>> pendingSources = new LinkedHashMap<>();
    private static long sourceEpoch;
    public record Prepared(Map<String, Set<String>> builtin,
                           Map<String, Map<String, Set<String>>> sources, long sourceEpoch) { }
    public record HostTagDiagnostic(String source, String path, String tag, String member, String reason) { }
    public record HostTagExport(Map<String, Set<String>> groups, Map<String, String> sourceFiles,
                                List<HostTagDiagnostic> diagnostics) { }
    public record HostTagInput(Map<String, Set<String>> groups, Map<String, String> sourceFiles,
                               Set<String> invalidGroups, List<HostTagDiagnostic> diagnostics,
                               Map<String, Map<String, Set<String>>> capturedSources) {
        public HostTagInput(Map<String, Set<String>> groups, Map<String, String> sourceFiles,
                            Set<String> invalidGroups, List<HostTagDiagnostic> diagnostics) {
            this(groups, sourceFiles, invalidGroups, diagnostics, Map.of());
        }
        public HostTagInput {
            Map<String, Set<String>> frozen = new LinkedHashMap<>();
            groups.forEach((tag, members) -> frozen.put(tag, Set.copyOf(members)));
            groups = Map.copyOf(frozen); sourceFiles = Map.copyOf(sourceFiles);
            invalidGroups = Set.copyOf(invalidGroups); diagnostics = List.copyOf(diagnostics);
            Map<String, Map<String, Set<String>>> frozenSources = new LinkedHashMap<>();
            capturedSources.forEach((source, tags) -> {
                Map<String, Set<String>> frozenTags = new LinkedHashMap<>();
                tags.forEach((tag, members) -> frozenTags.put(tag, Set.copyOf(members)));
                frozenSources.put(source, Map.copyOf(frozenTags));
            });
            capturedSources = Map.copyOf(frozenSources);
        }
    }
    private static volatile HostTagInput hostInput;
    private static final Object HOST_INPUT_LOCK = new Object();
    private static long hostInputEpoch;
    private static long hostInputRequest, hostPublishedRequest;
    private record HostTagRequest(long epoch, long sequence, Map<String, Map<String, Set<String>>> sources) { }

    private CommonTagResolver() {
    }

    /** Reloads FD's own default config and re-merges all externally registered sources. */
    public static synchronized void reload(JavaPlugin plugin) {
        if (preparedReloads) return;
        // Initial enable reuses startup preparation. Recipe reloads use their existing worker Prepared flow.
        if (hostInput == null) prepareHostReloadAsync(plugin).join();
        builtin = hostInput.groups();
        rebuild();
    }

    /** Worker preparation; the returned document is published with its dependent recipe indexes. */
    public static Map<String, Set<String>> prepareReload(JavaPlugin plugin) { return loadFile(plugin); }

    /** Startup preparation only; the host's item post-processing hook performs no document reads. */
    public static HostTagInput prepareHostReload(JavaPlugin plugin) throws java.io.IOException {
        HostTagRequest request = captureHostRequest();
        return readHostInputStrict(plugin, request.epoch(), request.sequence(), request.sources());
    }

    /** A loading phase may await this future; runtime recipe preparation already runs on its worker. */
    public static java.util.concurrent.CompletableFuture<HostTagInput> prepareHostReloadAsync(JavaPlugin plugin) {
        HostTagRequest request = captureHostRequest();
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return readHostInputStrict(plugin, request.epoch(), request.sequence(), request.sources()); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        });
    }

    /**
     * Compiles a separate loading snapshot without publishing or changing gameplay tags.
     * The callback returns null for an unknown host tag and a set for a genuinely registered tag.
     */
    public static HostTagExport prepareHostExport(JavaPlugin plugin, Function<String, Set<String>> hostTagMembers) {
        HostTagInput input = hostInput;
        if (input == null) throw new IllegalStateException("Common tag input was not prepared before host item loading: "
                + new File(plugin.getDataFolder(), FILE_NAME));
        return prepareHostExport(input, hostTagMembers);
    }

    /** Hosts pass the raw input captured for this loading run, independently of later worker preparation. */
    public static HostTagExport prepareHostExport(HostTagInput input, Function<String, Set<String>> hostTagMembers) {
        Map<String, Set<String>> definitions = new LinkedHashMap<>(input.groups());
        Map<String, String> sources = new LinkedHashMap<>(input.sourceFiles());
        input.capturedSources().forEach((source, groups) -> groups.forEach((tag, members) -> {
            Set<String> combined = new HashSet<>(definitions.getOrDefault(tag, Set.of()));
            combined.addAll(members); definitions.put(tag, Set.copyOf(combined));
            sources.merge(tag, "addon:" + source, (left, right) -> left + "; " + right);
        }));
        return HostCommonTagCompiler.compile(definitions, sources, input.invalidGroups(), input.diagnostics(), hostTagMembers);
    }

    /** Pending registrations belong to the next recipe transaction and are not part of this loading run. */
    static synchronized Map<String, Map<String, Set<String>>> captureHostSources() { return Map.copyOf(externals); }

    private static synchronized HostTagRequest captureHostRequest() {
        synchronized (HOST_INPUT_LOCK) {
            return new HostTagRequest(hostInputEpoch, ++hostInputRequest, Map.copyOf(externals));
        }
    }

    public static Prepared prepareContent(JavaPlugin plugin) throws java.io.IOException {
        Map<String, Map<String, Set<String>>> sources;
        long epoch;
        synchronized (CommonTagResolver.class) {
            sources = new LinkedHashMap<>(externals);
            pendingSources.forEach((id, members) -> { if (members == null) sources.remove(id); else sources.put(id, members); });
            epoch = sourceEpoch;
        }
        return new Prepared(readFileStrict(plugin), Map.copyOf(sources), epoch);
    }

    public static synchronized void validatePrepared(Prepared prepared) throws java.io.IOException {
        if (prepared.sourceEpoch() != sourceEpoch) throw new java.io.IOException("Common tag sources changed during recipe preparation");
    }

    public static synchronized void publishPrepared(Prepared prepared) {
        if (prepared.sourceEpoch() != sourceEpoch) throw new IllegalStateException("Common tag sources changed before recipe publication");
        builtin = prepared.builtin();
        externals.clear(); externals.putAll(prepared.sources()); pendingSources.clear();
        preparedReloads = true;
        ++sourceEpoch;
        rebuild();
    }

    public static synchronized void publishPreparedBuiltin(Map<String, Set<String>> next) {
        builtin = next;
        preparedReloads = true;
        rebuild();
    }

    public static synchronized Runnable captureBuiltinRollback() {
        Map<String, Set<String>> previous = builtin;
        boolean previousPreparedReloads = preparedReloads;
        Map<String, Map<String, Set<String>>> oldSources = new LinkedHashMap<>(externals);
        Map<String, Map<String, Set<String>>> appliedPending = new LinkedHashMap<>(pendingSources);
        return () -> { synchronized (CommonTagResolver.class) {
            builtin = previous;
            preparedReloads = previousPreparedReloads;
            appliedPending.forEach((id, value) -> {
                if (oldSources.containsKey(id)) externals.put(id, oldSources.get(id)); else externals.remove(id);
                if (!pendingSources.containsKey(id)) pendingSources.put(id, value);
            });
            ++sourceEpoch;
            rebuild();
        } };
    }

    /** Call after dependent workers stop, so a later enable starts with a fresh loading phase. */
    public static synchronized void clear() {
        externals.clear(); pendingSources.clear(); builtin = Map.of();
        tagToItems = Map.of(); itemToTags = Map.of(); loaded = false;
        snapshot = new TagSnapshot(Map.of(), Map.of(), false);
        synchronized (HOST_INPUT_LOCK) { ++hostInputEpoch; hostInput = null; }
        reportedProblems = Set.of(); preparedReloads = false; ++sourceEpoch;
        com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.remove(CommonTagResolver.class);
    }

    /**
     * Registers (or replaces) an addon's tag mapping. Members are merged across sources, so multiple
     * addons may contribute to the same tag (e.g. every addon adds its knives to farmersdelight:tools/knives).
     */
    public static synchronized void registerSource(String source, Map<String, List<String>> tagToMemberItems) {
        if (source == null || tagToMemberItems == null) {
            return;
        }
        Map<String, Set<String>> frozen = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : tagToMemberItems.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String tag = normalize(entry.getKey());
            if (tag.isEmpty()) {
                continue;
            }
            Set<String> members = new HashSet<>();
            for (String itemId : entry.getValue()) {
                String member = normalizeMember(itemId);
                if (!member.isEmpty()) {
                    members.add(member);
                }
            }
            if (!members.isEmpty()) {
                frozen.put(tag, Set.copyOf(members));
            }
        }
        if (preparedReloads && !com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.isStaging()) {
            pendingSources.put(source, Map.copyOf(frozen));
        } else {
            externals.put(source, Map.copyOf(frozen));
            rebuild();
        }
        ++sourceEpoch;
    }

    /** Removes a previously registered addon source (idempotent). */
    public static synchronized void unregisterSource(String source) {
        if (source == null) {
            return;
        }
        if (preparedReloads && !com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.isStaging()) {
            pendingSources.put(source, null);
        } else {
            externals.remove(source);
            rebuild();
        }
        ++sourceEpoch;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public static synchronized Runnable captureSourceRollback(String source) {
        Map<String, Set<String>> previous = externals.get(source);
        return () -> {
            synchronized (CommonTagResolver.class) {
                if (previous == null) externals.remove(source);
                else externals.put(source, previous);
                ++sourceEpoch;
                rebuild();
            }
        };
    }

    /** True if the given tag key is registered in this central registry. */
    public static boolean isCommonTag(String tagKey) {
        TagSnapshot current = currentSnapshot();
        return current.loaded() && current.members().containsKey(normalize(tagKey));
    }

    /** Concrete item ids the given tag expands to (empty when unregistered). */
    public static Set<String> getMembers(String tagKey) {
        return currentSnapshot().members().getOrDefault(normalize(tagKey), Set.of());
    }

    public static Set<String> getMembers(Key tagKey) {
        return tagKey == null ? Set.of() : getMembers(tagKey.toString());
    }

    /** Tags owned by the given concrete item id (matching any id form an item reports). */
    public static Set<String> getTagsForItemId(String itemId) {
        return itemId == null ? Set.of()
                : currentSnapshot().reverse().getOrDefault(itemId.trim().toLowerCase(Locale.ROOT), Set.of());
    }

    /** Immutable snapshot of every registered tag and its concrete members, for datapack export. */
    public static Map<String, Set<String>> tagSnapshot() {
        return currentSnapshot().members();
    }

    private static void rebuild() {
        Map<String, Set<String>> rawTags = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : builtin.entrySet()) {
            rawTags.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        for (Map<String, Set<String>> source : externals.values()) {
            for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
                rawTags.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
            }
        }
        Map<String, Set<String>> mergedTagToItems = new HashMap<>();
        Set<String> problems = new HashSet<>();
        for (String tag : rawTags.keySet()) {
            mergedTagToItems.put(tag, expandTag(tag, rawTags, mergedTagToItems, new HashSet<>(),
                    new ArrayDeque<>(), problems));
        }
        Map<String, Set<String>> mergedItemToTags = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedTagToItems.entrySet()) {
            for (String member : entry.getValue()) {
                mergedItemToTags.computeIfAbsent(member, k -> new HashSet<>()).add(entry.getKey());
            }
        }
        Map<String, Set<String>> frozenTagToItems = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedTagToItems.entrySet()) {
            frozenTagToItems.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        Map<String, Set<String>> frozenItemToTags = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : mergedItemToTags.entrySet()) {
            frozenItemToTags.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        TagSnapshot next = new TagSnapshot(Map.copyOf(frozenTagToItems), Map.copyOf(frozenItemToTags), true);
        com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication.publish(CommonTagResolver.class, snapshot, next);
        snapshot = next;
        tagToItems = next.members();
        itemToTags = next.reverse();
        loaded = true;
        reportProblems(problems);
    }

    private static Map<String, Set<String>> loadFile(JavaPlugin plugin) {
        try { return readFileStrict(plugin); }
        catch (java.io.IOException error) {
            I18n.logWarning("plugin.config_load_failed", "file", FILE_NAME, "error", error.getMessage());
            return Map.of();
        }
    }

    private static Map<String, Set<String>> readFileStrict(JavaPlugin plugin) throws java.io.IOException {
        return prepareHostReload(plugin).groups();
    }

    private static HostTagInput readHostInputStrict(JavaPlugin plugin, long expectedEpoch, long request,
                                                   Map<String, Map<String, Set<String>>> capturedSources) throws java.io.IOException {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists() && plugin.getResource(FILE_NAME) != null) {
            plugin.saveResource(FILE_NAME, false);
        }
        try {
            YamlConfiguration config = ConfigFileUpdater.readYamlFile(file.toPath());
            if (config.getConfigurationSection(ROOT_KEY) == null) {
                I18n.logWarning("plugin.config_missing_section", "file", FILE_NAME, "section", ROOT_KEY);
            }
            YamlConfiguration bundled = new YamlConfiguration();
            try (var stream = plugin.getResource(FILE_NAME)) {
                if (stream != null) {
                    bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                }
            }
            ConfigurationSection userTags = config.getConfigurationSection(ROOT_KEY);
            ConfigurationSection bundledTags = bundled.getConfigurationSection(ROOT_KEY);
            Map<String, String> sources = new LinkedHashMap<>();
            if (bundledTags != null) for (String key : bundledTags.getKeys(false)) sources.put(normalize(key), "jar:/" + FILE_NAME);
            if (userTags != null) for (String key : userTags.getKeys(false)) sources.put(normalize(key), file.toPath().toAbsolutePath().normalize().toString());
            Set<String> invalidGroups = new HashSet<>();
            List<HostTagDiagnostic> diagnostics = new java.util.ArrayList<>();
            Map<String, Set<String>> next = mergedDefinitions(bundledTags, userTags, (path, error) -> {
                String tag = normalize(path); invalidGroups.add(tag);
                diagnostics.add(new HostTagDiagnostic(sources.getOrDefault(tag, file.toString()), ROOT_KEY + "." + path,
                        tag, "", error == null ? "Invalid group definition" : error));
                I18n.logWarning("plugin.config_value_invalid", "file", FILE_NAME, "path", ROOT_KEY + "." + path, "error", error);
            });
            HostTagInput prepared = new HostTagInput(next, Map.copyOf(sources), Set.copyOf(invalidGroups), List.copyOf(diagnostics), capturedSources);
            synchronized (HOST_INPUT_LOCK) {
                if (expectedEpoch != hostInputEpoch) throw new java.io.IOException("Common tag loading input was retired during preparation");
                if (request >= hostPublishedRequest) { hostInput = prepared; hostPublishedRequest = request; }
            }
            return prepared;
        } catch (Exception e) { throw new java.io.IOException("Could not prepare " + FILE_NAME, e); }
    }

    /** Current defaults fill absent tag IDs in memory; declared user values, including empty groups, win. */
    static Map<String, Set<String>> mergedDefinitions(ConfigurationSection bundled, ConfigurationSection user,
                                                      BiConsumer<String, String> diagnostic) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        readDefinitions(user, result, diagnostic);
        readDefinitions(bundled, result, diagnostic);
        return Map.copyOf(result);
    }

    private static void readDefinitions(ConfigurationSection section, Map<String, Set<String>> result,
                                        BiConsumer<String, String> diagnostic) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String tag = normalize(key);
            if (result.containsKey(tag)) continue;
            Set<String> members = new HashSet<>();
            try {
                for (String itemId : ConfigSectionReader.optionalStringList(section, key)) {
                    String member = normalizeMember(itemId);
                    if (!member.isEmpty()) members.add(member);
                }
            } catch (RuntimeException error) {
                // An invalid declared override is diagnosed and stays empty instead of silently using a default.
                diagnostic.accept(key, error.getMessage());
            }
            result.put(tag, Set.copyOf(members));
        }
    }

    private static String normalizeMember(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            String tag = normalize(normalized.substring(1));
            return tag.isEmpty() ? "" : "#" + tag;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static Set<String> expandTag(String tag, Map<String, Set<String>> rawTags,
                                         Map<String, Set<String>> expanded, Set<String> visiting,
                                         Deque<String> path, Set<String> problems) {
        Set<String> cached = expanded.get(tag);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(tag)) {
            problems.add("cycle:" + formatCycle(path, tag));
            return Set.of();
        }
        path.addLast(tag);
        Set<String> members = new HashSet<>();
        for (String member : rawTags.getOrDefault(tag, Set.of())) {
            if (member.startsWith("#")) {
                String referenced = member.substring(1);
                if (!rawTags.containsKey(referenced)) {
                    problems.add("missing:" + tag + " -> " + referenced);
                    continue;
                }
                members.addAll(expandTag(referenced, rawTags, expanded, visiting, path, problems));
            } else {
                members.add(member);
            }
        }
        path.removeLast();
        visiting.remove(tag);
        Set<String> frozen = Set.copyOf(members);
        expanded.put(tag, frozen);
        return frozen;
    }

    private static String formatCycle(Deque<String> path, String repeated) {
        StringBuilder result = new StringBuilder();
        boolean include = false;
        for (String tag : path) {
            if (tag.equals(repeated)) {
                include = true;
            }
            if (include) {
                if (result.length() > 0) {
                    result.append(" -> ");
                }
                result.append(tag);
            }
        }
        return result.append(" -> ").append(repeated).toString();
    }

    private static void reportProblems(Set<String> problems) {
        Set<String> current = Set.copyOf(problems);
        for (String problem : current) {
            if (!reportedProblems.contains(problem)) {
                int separator = problem.indexOf(':');
                String type = separator < 0 ? "unknown" : problem.substring(0, separator);
                String path = separator < 0 ? problem : problem.substring(separator + 1);
                I18n.logWarning("plugin.common_tag_reference_invalid", "type", type, "path", path);
            }
        }
        reportedProblems = current;
    }

    private static String normalize(String tagKey) {
        if (tagKey == null) {
            return "";
        }
        String normalized = tagKey.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
