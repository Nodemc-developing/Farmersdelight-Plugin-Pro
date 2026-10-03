package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpecialRecipeRegistry {

    public static final String TYPE_ID = "farmersdelight:special";

    private final Map<String, SpecialRecipeInfo> recipes = new LinkedHashMap<>();
    private volatile LinkIndex linkIndex;
    private volatile State snapshot = new State(Map.of());
    private static final class State {
        final Map<String, SpecialRecipeInfo> recipes;
        final List<SpecialRecipeInfo> ordered;
        volatile LinkIndex index;
        State(Map<String, SpecialRecipeInfo> recipes) {
            this.recipes = Collections.unmodifiableMap(new LinkedHashMap<>(recipes));
            this.ordered = List.copyOf(this.recipes.values());
        }
    }
    private State currentState() { return RuntimeSnapshotPublication.get(this, snapshot); }
    private void publish() {
        State next = new State(recipes);
        RuntimeSnapshotPublication.publish(this, snapshot, next);
        snapshot = next;
        linkIndex = null;
    }

    public synchronized void register(SpecialRecipeInfo info) {
        if (info != null && info.id() != null) {
            recipes.put(info.id(), info);
            publish();
        }
    }

    public synchronized void unregister(String id) {
        if (id != null) {
            recipes.remove(id);
            publish();
        }
    }

    public synchronized void clear() {
        recipes.clear();
        publish();
    }

    public SpecialRecipeInfo get(String id) {
        return id == null ? null : currentState().recipes.get(id);
    }

    public List<SpecialRecipeInfo> getAll() {
        return currentState().ordered;
    }

    public boolean isEmpty() {
        return currentState().recipes.isEmpty();
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public synchronized Runnable captureReloadRollback() {
        Map<String, SpecialRecipeInfo> previous = currentState().recipes;
        return () -> {
            synchronized (this) {
                recipes.clear();
                recipes.putAll(previous);
                publish();
            }
        };
    }

    public String findProducingRecipe(ItemStack item) {
        return find(linkIndex().producing(), item);
    }

    public String findLinkedRecipe(ItemStack item) {
        return find(linkIndex().linked(), item);
    }

    public synchronized void invalidateIndex() {
        linkIndex = null;
        currentState().index = null;
    }

    private String find(Map<String, String> index, ItemStack item) {
        String itemId = ItemUtils.resolveItemId(item);
        return itemId == null ? null : index.get(itemId);
    }

    private LinkIndex linkIndex() {
        State state = currentState();
        LinkIndex current = state.index;
        if (current != null) {
            return current;
        }
        synchronized (state) {
            if (state.index == null) {
                Map<String, String> producing = new LinkedHashMap<>();
                for (SpecialRecipeInfo info : state.ordered) {
                    indexItem(producing, info.iconItemId(), info.id());
                    indexEntries(producing, info.outputSlots(), info.id());
                }
                Map<String, String> linked = new LinkedHashMap<>(producing);
                for (SpecialRecipeInfo info : state.ordered) {
                    indexEntries(linked, info.inputSlots(), info.id());
                    indexEntries(linked, info.catalystSlots(), info.id());
                }
                state.index = new LinkIndex(Map.copyOf(producing), Map.copyOf(linked));
            }
            return state.index;
        }
    }

    private static void indexEntries(Map<String, String> index, List<SpecialRecipeInfo.SlotEntry> entries,
                                     String recipeId) {
        for (SpecialRecipeInfo.SlotEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            for (ItemStack item : ItemUtils.createSlotItems(
                    entry.itemId(), entry.behaviorBlockId(), entry.behaviorListKey())) {
                String itemId = ItemUtils.resolveItemId(item);
                if (itemId != null) {
                    index.putIfAbsent(itemId, recipeId);
                }
            }
        }
    }

    private static void indexItem(Map<String, String> index, String itemId, String recipeId) {
        for (ItemStack item : ItemUtils.createSlotItems(itemId)) {
            String resolvedId = ItemUtils.resolveItemId(item);
            if (resolvedId != null) {
                index.putIfAbsent(resolvedId, recipeId);
            }
        }
    }

    private record LinkIndex(Map<String, String> producing, Map<String, String> linked) {
    }
}
