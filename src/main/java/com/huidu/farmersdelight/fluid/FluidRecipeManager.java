package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.recipe.AdvancedRecipeTags;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Publishes immutable recipe snapshots; fluid storage and inventory access remain on their owner. */
public final class FluidRecipeManager {
    private record Snapshot(Map<String, FluidRecipeSpec> byId, List<FluidRecipeSpec> ordered, long generation) { }
    private final FarmersDelightPlugin plugin;
    private final FluidRecipeFiles files;
    private final FluidCoreBridge bridge;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of(), 0);
    private boolean warnedUnavailable;

    public FluidRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.files = new FluidRecipeFiles(plugin);
        this.bridge = FluidCoreBridge.create(plugin);
    }

    public FarmersDelightPlugin plugin() { return plugin; }
    public FluidCoreBridge bridge() { return bridge; }
    public List<FluidRecipeSpec> recipes() { return snapshot.ordered(); }
    public FluidRecipeSpec recipe(String id) { return snapshot.byId().get(id); }
    public long generation() { return snapshot.generation(); }

    public void reload() {
        bridge.resetBindings();
        Map<String, FluidRecipeSpec> parsed = new LinkedHashMap<>();
        for (FluidRecipeFiles.Entry entry : files.load()) {
            FluidRecipeSpec recipe = entry.recipe();
            try {
                AdvancedRecipeTags.requireDefined(recipe.ingredient());
                if (!recipe.result().isEmpty()) {
                    ItemStack result = RecipeItemCodec.deserializeItem(recipe.result());
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
        snapshot = new Snapshot(Collections.unmodifiableMap(parsed), ordered, snapshot.generation() + 1);
        if (!ordered.isEmpty() && !bridge.available() && !warnedUnavailable) {
            warnedUnavailable = true;
            I18n.logWarning("fluid.unavailable", "error", bridge.unavailableReason());
        }
        if (bridge.available()) warnedUnavailable = false;
    }

    public List<FluidRecipeSpec> candidates(ItemStack input) {
        return snapshot.ordered().stream().filter(recipe -> FluidCoreBridge.itemMatches(recipe, input)).toList();
    }

    public CompletableFuture<Boolean> saveAsync(FluidRecipeSpec recipe) {
        try {
            AdvancedRecipeTags.requireDefined(recipe.ingredient());
            if (!recipe.result().isEmpty()) {
                ItemStack result = RecipeItemCodec.deserializeItem(recipe.result());
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
    public void close() { snapshot = new Snapshot(Map.of(), List.of(), snapshot.generation() + 1); }
}
