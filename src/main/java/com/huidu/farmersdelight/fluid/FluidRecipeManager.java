package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.AdvancedRecipeTags;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.util.CommonTagResolver;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

/** Publishes immutable recipe snapshots; fluid storage and inventory access remain on their owner. */
public final class FluidRecipeManager {
    private record Snapshot(Map<String, FluidRecipeSpec> byId, List<FluidRecipeSpec> ordered,
                            FluidRecipeCandidates candidates, long generation) { }
    /** Cumulative matching work since this manager was created; no ItemStack or player state is retained. */
    public record CandidateStats(long queries, long recipesAvailable, long predicateChecks, long matches,
                                 int indexedItemKeys, int fallbackRecipes) { }
    private final FarmersDelightPlugin plugin;
    private final FluidRecipeFiles files;
    private final FluidCoreBridge bridge;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of(), FluidRecipeCandidates.compile(List.of(), unused -> Set.of()), 0);
    private final LongAdder candidateQueries = new LongAdder();
    private final LongAdder recipesAvailable = new LongAdder();
    private final LongAdder predicateChecks = new LongAdder();
    private final LongAdder candidateMatches = new LongAdder();
    private boolean warnedUnavailable;
    private long publicationGeneration;

    private Snapshot currentSnapshot() { return RuntimeSnapshotPublication.get(this, snapshot); }

    private void setSnapshot(Snapshot next) {
        RuntimeSnapshotPublication.publish(this, snapshot, next);
        snapshot = next;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public synchronized Runnable captureReloadRollback() {
        Snapshot previous = currentSnapshot();
        boolean warning = warnedUnavailable;
        Runnable restoreSources = files.captureReloadRollback();
        return () -> {
            synchronized (this) {
                restoreSources.run();
                warnedUnavailable = warning;
                setSnapshot(new Snapshot(previous.byId(), previous.ordered(), previous.candidates(), ++publicationGeneration));
                afterPublication();
            }
        };
    }

    public FluidRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.files = new FluidRecipeFiles(plugin);
        this.bridge = FluidCoreBridge.create(plugin);
    }

    public FarmersDelightPlugin plugin() { return plugin; }
    public FluidCoreBridge bridge() { return bridge; }
    public List<FluidRecipeSpec> recipes() { return currentSnapshot().ordered(); }
    public FluidRecipeSpec recipe(String id) { return currentSnapshot().byId().get(id); }
    public long generation() { return currentSnapshot().generation(); }

    public synchronized void reload() {
        com.huidu.farmersdelight.recipe.RecipePublicationTransaction.run(this::reloadInTransaction,
                captureReloadRollback());
    }

    private void reloadInTransaction() {
        Map<String, FluidRecipeSpec> parsed = new LinkedHashMap<>();
        for (FluidRecipeFiles.Entry entry : files.load()) {
            FluidRecipeSpec recipe = entry.recipe();
            try {
                AdvancedRecipeTags.requireDefined(recipe.ingredient());
                bridge.validate(recipe);
                if (!recipe.result().isEmpty()) {
                    ItemStack result = FluidResultCodec.deserialize(recipe.result());
                    if (result == null || result.getType().isAir()) {
                        throw new IllegalArgumentException("Result item is not available: " + recipe.result());
                    }
                }
                parsed.putIfAbsent(recipe.id(), recipe);
            } catch (RuntimeException invalid) {
                RecipeFileLoader.reportProblem(entry.source().file().toString(), recipe.id(), invalid.getMessage());
            }
        }
        List<FluidRecipeSpec> ordered = parsed.values().stream()
                .sorted(Comparator.comparingInt(FluidRecipeSpec::priority).reversed().thenComparing(FluidRecipeSpec::id))
                .toList();
        FluidRecipeCandidates candidates = FluidRecipeCandidates.compile(ordered, FluidRecipeManager::tagMembers);
        setSnapshot(new Snapshot(Collections.unmodifiableMap(parsed), ordered, candidates, ++publicationGeneration));
        afterPublication();
    }

    private void afterPublication() {
        RuntimeSnapshotPublication.afterCommit(bridge, () -> {
            bridge.resetBindings();
            bridge.attach(this);
            if (!currentSnapshot().ordered().isEmpty() && !bridge.available() && !warnedUnavailable) {
                warnedUnavailable = true;
                I18n.logWarning("fluid.unavailable", "error", bridge.unavailableReason());
            }
            if (bridge.available()) warnedUnavailable = false;
        });
    }

    public List<FluidRecipeSpec> candidates(ItemStack input) {
        try (var scope = RuntimeSnapshotPublication.readScope()) { return candidatesInScope(input); }
    }

    private List<FluidRecipeSpec> candidatesInScope(ItemStack input) {
        Snapshot current = currentSnapshot(); candidateQueries.increment(); recipesAvailable.add(current.ordered().size());
        String identity = ItemUtils.resolveItemId(input);
        if (identity == null) return List.of();
        List<FluidRecipeSpec> possible = current.candidates().select(identity, ItemUtils.getAllItemTagIds(input), AdvancedRecipeTags.tagsForItemId(identity));
        predicateChecks.add(possible.size());
        List<FluidRecipeSpec> matched = new ArrayList<>();
        for (FluidRecipeSpec recipe : possible) if (FluidCoreBridge.itemMatches(recipe, input)) matched.add(recipe);
        candidateMatches.add(matched.size()); return List.copyOf(matched);
    }

    public CandidateStats candidateStats() {
        Snapshot current = currentSnapshot();
        return new CandidateStats(candidateQueries.sum(), recipesAvailable.sum(), predicateChecks.sum(), candidateMatches.sum(),
                current.candidates().itemKeys(), current.candidates().fallbackRecipes());
    }

    private static Set<String> tagMembers(String tagId) {
        LinkedHashSet<String> members = new LinkedHashSet<>(CommonTagResolver.getMembers(tagId));
        try {
            for (var key : BukkitItemManager.instance().itemIdsByTag(Key.of(tagId))) members.add(key.toString());
        } catch (RuntimeException unavailable) { /* Unknown tags remain in the checked fallback bucket. */ }
        NamespacedKey key = NamespacedKey.fromString(tagId);
        if (key != null) {
            var tag = Bukkit.getTag("items", key, Material.class);
            if (tag != null) for (Material material : tag.getValues()) members.add("minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT));
        }
        return Set.copyOf(members);
    }

    public CompletableFuture<Boolean> saveAsync(FluidRecipeSpec recipe) {
        try {
            AdvancedRecipeTags.requireDefined(recipe.ingredient());
            bridge.validate(recipe);
            if (!recipe.result().isEmpty()) {
                ItemStack result = FluidResultCodec.deserialize(recipe.result());
                if (result == null || result.getType().isAir()) {
                    throw new IllegalArgumentException("Result item is not available");
                }
            }
            return files.saveAsync(recipe);
        } catch (RuntimeException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
    }
    public CompletableFuture<Boolean> deleteAsync(String id) { return files.deleteAsync(id); }
    public synchronized void close() { bridge.close(); setSnapshot(new Snapshot(Map.of(), List.of(), FluidRecipeCandidates.compile(List.of(), unused -> Set.of()), ++publicationGeneration)); }
}
