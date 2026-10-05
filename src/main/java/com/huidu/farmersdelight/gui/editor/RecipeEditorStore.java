package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.RecipeItemCodec;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipePackFiles;
import com.huidu.farmersdelight.recipe.RecipeSource;
import com.huidu.farmersdelight.recipe.RecipeSchemaAdapter;
import com.huidu.farmersdelight.recipe.NativeRecipeSchema;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles.RecipeOwner;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RecipeEditorStore {

    private static final String COOKING_POT_FILE = "recipes/cooking_pot_recipes.yml";
    private static final String CUTTING_BOARD_FILE = "recipes/cutting_board_recipes.yml";

    private static final String EXTERNAL_OVERRIDES_ROOT = "external-overrides";
    /** Written when a recipe must keep no container although its result declares one (see the recipe loader). */
    static final String CONTAINER_OPT_OUT = "none";

    private final FarmersDelightPlugin plugin;

    public RecipeEditorStore(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    private record Edit(File file, String localPath, YamlMutation mutation, boolean literal) {
        private Edit(File file, String localPath, YamlMutation mutation) { this(file, localPath, mutation, false); }
    }

    public boolean saveCookingPotRecipe(CookingPotRecipe recipe, String customGroupId) {
        return captureAndReload(() -> cookingPotEdit(recipe, customGroupId));
    }

    public CompletableFuture<Boolean> saveCookingPotRecipeAsync(CookingPotRecipe recipe, String customGroupId) {
        return captureAsync(() -> cookingPotEdit(recipe, customGroupId));
    }

    private Edit cookingPotEdit(CookingPotRecipe recipe, String group) {
        // CraftEngine/NMS item serialization belongs to the caller's owning thread.
        Map<String, Object> body = buildCookingPotBody(recipe);
        if (group != null && !group.isBlank()) body.put("group", group);
        RecipeSource source = plugin.getCookingPotRecipes().sourceOf(recipe.getId(), group);
        if (source != null) return recipeEdit(source, body, true);
        if (group == null || group.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId());
            if (owner != null) {
                return external(owner, yaml -> {
                    ConfigurationSection previous = yaml.getConfigurationSection(owner.yamlPath());
                    com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(yaml, owner.yamlPath(),
                            mergeRecipeBody(previous == null ? Map.of() : previous.getValues(false), body));
                });
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(recipe.getId())) {
                RecipeSource target = new RecipeSource(RecipePackFiles.file(plugin, COOKING_POT_FILE),
                        List.of(NativeRecipeSchema.EDITOR_ROOT, recipe.getId()), true);
                Map<String, Object> formatted = RecipeSchemaAdapter.formatPot(body, true);
                return new Edit(target.file().toFile(), null, yaml -> {
                    saveRecipeSource(yaml, target, formatted, true);
                    setExternalOverride(yaml, "cooking_pot", recipe.getId(), true);
                }, true);
            }
        }
        RecipeSource target = new RecipeSource(RecipePackFiles.file(plugin, COOKING_POT_FILE),
                List.of(NativeRecipeSchema.EDITOR_ROOT, recipe.getId()), true);
        return recipeEdit(target, body, true);
    }

    public boolean deleteCookingPotRecipe(String recipeId, String customGroupId) {
        return captureAndReload(() -> deleteCookingPotEdit(recipeId, customGroupId));
    }

    public CompletableFuture<Boolean> deleteCookingPotRecipeAsync(String recipeId, String customGroupId) {
        return captureAsync(() -> deleteCookingPotEdit(recipeId, customGroupId));
    }

    private Edit deleteCookingPotEdit(String id, String group) {
        RecipeSource source = plugin.getCookingPotRecipes().sourceOf(id, group);
        if (source != null) return recipeEdit(source, null, true);
        if (group == null || group.isBlank()) {
            RecipeOwner owner = AddonRecipeFiles.ownerOf("cooking_pot", id);
            if (owner != null) {
                return external(owner, yaml -> yaml.set(owner.yamlPath(), null));
            }
            if (plugin.getCookingPotRecipes().isExternalRecipe(id)) {
                return new Edit(RecipePackFiles.file(plugin, COOKING_POT_FILE).toFile(), null, yaml -> {
                    deleteRecipeSource(yaml, new RecipeSource(RecipePackFiles.file(plugin, COOKING_POT_FILE),
                            List.of(NativeRecipeSchema.EDITOR_ROOT, id), true), true, RecipePackFiles.file(plugin, COOKING_POT_FILE));
                    setExternalOverride(yaml, "cooking_pot", id, false);
                }, true);
            }
        }
        return recipeEdit(new RecipeSource(RecipePackFiles.file(plugin, COOKING_POT_FILE),
                List.of(NativeRecipeSchema.EDITOR_ROOT, id), true, true), null, true);
    }

    public boolean saveCuttingBoardRecipe(CuttingBoardRecipe recipe) {
        return captureAndReload(() -> cuttingBoardEdit(recipe));
    }

    public CompletableFuture<Boolean> saveCuttingBoardRecipeAsync(CuttingBoardRecipe recipe) {
        return captureAsync(() -> cuttingBoardEdit(recipe));
    }

    private Edit cuttingBoardEdit(CuttingBoardRecipe recipe) {
        Map<String, Object> body = buildCuttingBoardBody(recipe);
        String id = recipe.getId();
        RecipeSource source = plugin.getCuttingBoardRecipes().sourceOf(id);
        if (source != null) return recipeEdit(source, body, false);
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", id);
        if (owner != null) {
            return external(owner, yaml -> {
                ConfigurationSection previous = yaml.getConfigurationSection(owner.yamlPath());
                com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(yaml, owner.yamlPath(),
                        mergeRecipeBody(previous == null ? Map.of() : previous.getValues(false), body));
            });
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(id)) {
            RecipeSource target = new RecipeSource(RecipePackFiles.file(plugin, CUTTING_BOARD_FILE),
                    List.of(NativeRecipeSchema.EDITOR_ROOT, id), true);
            Map<String, Object> formatted = RecipeSchemaAdapter.formatBoard(body, true);
            return new Edit(target.file().toFile(), null, yaml -> {
                saveRecipeSource(yaml, target, formatted, false);
                setExternalOverride(yaml, "cutting_board", id, true);
            }, true);
        }
        return recipeEdit(new RecipeSource(RecipePackFiles.file(plugin, CUTTING_BOARD_FILE), List.of(NativeRecipeSchema.EDITOR_ROOT, id), true), body, false);
    }

    public boolean deleteCuttingBoardRecipe(String recipeId) {
        return captureAndReload(() -> deleteCuttingBoardEdit(recipeId));
    }

    public CompletableFuture<Boolean> deleteCuttingBoardRecipeAsync(String recipeId) {
        return captureAsync(() -> deleteCuttingBoardEdit(recipeId));
    }

    private Edit deleteCuttingBoardEdit(String id) {
        RecipeSource source = plugin.getCuttingBoardRecipes().sourceOf(id);
        if (source != null) return recipeEdit(source, null, false);
        RecipeOwner owner = AddonRecipeFiles.ownerOf("cutting_board", id);
        if (owner != null) {
            return external(owner, yaml -> yaml.set(owner.yamlPath(), null));
        }
        if (plugin.getCuttingBoardRecipes().isExternalRecipe(id)) {
            return new Edit(RecipePackFiles.file(plugin, CUTTING_BOARD_FILE).toFile(), null, yaml -> {
                deleteRecipeSource(yaml, new RecipeSource(RecipePackFiles.file(plugin, CUTTING_BOARD_FILE),
                        List.of(NativeRecipeSchema.EDITOR_ROOT, id), true), false, RecipePackFiles.file(plugin, CUTTING_BOARD_FILE));
                setExternalOverride(yaml, "cutting_board", id, false);
            }, true);
        }
        return recipeEdit(new RecipeSource(RecipePackFiles.file(plugin, CUTTING_BOARD_FILE),
                List.of(NativeRecipeSchema.EDITOR_ROOT, id), true, true), null, false);
    }

    private Edit recipeEdit(RecipeSource source, Map<String, Object> body, boolean pot) {
        Map<String, Object> formatted = body == null ? null : pot
                ? RecipeSchemaAdapter.formatPot(body, true)
                : RecipeSchemaAdapter.formatBoard(body, true);
        java.nio.file.Path defaultFile = formatted == null ? RecipePackFiles.file(plugin, pot ? COOKING_POT_FILE : CUTTING_BOARD_FILE) : null;
        return new Edit(source.file().toFile(), null, yaml -> {
            if (formatted == null) { deleteRecipeSource(yaml, source, pot, defaultFile); return; }
            saveRecipeSource(yaml, source, formatted, pot);
        }, true);
    }

    static void saveRecipeSource(YamlConfiguration yaml, RecipeSource source, Map<String, Object> formatted) {
        saveRecipeSource(yaml, source, formatted, "cooking_pot".equals(formatted.get("station")));
    }

    static void saveRecipeSource(YamlConfiguration yaml, RecipeSource source, Map<String, Object> formatted, boolean pot) {
        requireNativeSource(source);
        if (!source.existingNode() && source.exists(yaml)) {
            throw new IllegalStateException("A recipe with this ID already exists at the destination");
        }
        Map<String, Object> previous = source.body(yaml);
        String expected = pot ? "cooking_pot" : "cutting_board";
        if (!expected.equals(formatted.get("station"))) {
            throw new IllegalStateException("The edited recipe belongs to a different station");
        }
        requireOriginalStation(yaml, source, expected);
        Map<String, Object> replacement = NativeRecipeSchema.copy(formatted);
        if (!replacement.containsKey("group") && previous.containsKey("group")) replacement.put("group", previous.get("group"));
        source.put(yaml, mergeRecipeBody(previous, replacement));
    }

    private static void requireNativeSource(RecipeSource source) {
        if (!source.nativeFormat() || !source.keys().getFirst().split("#", 2)[0].equals(NativeRecipeSchema.ROOT)) {
            throw new IllegalStateException("Recipe edits require a native content-pack source");
        }
    }

    private static void requireOriginalStation(YamlConfiguration yaml, RecipeSource source, String expected) {
        if (source.exists(yaml) && !expected.equals(source.body(yaml).get("station"))) {
            throw new IllegalStateException("The recipe station changed at the original source; the edit was refused");
        }
    }

    static void deleteRecipeSource(YamlConfiguration yaml, RecipeSource source, boolean pot, java.nio.file.Path defaultFile) {
        requireNativeSource(source);
        requireOriginalStation(yaml, source, pot ? "cooking_pot" : "cutting_board");
        source.put(yaml, null);
        if (!source.file().equals(defaultFile.toAbsolutePath().normalize())) return;
        String station = pot ? "cooking_pot" : "cutting_board";
        String id = source.keys().getLast();
        ConfigurationSection overrides = yaml.getConfigurationSection(EXTERNAL_OVERRIDES_ROOT);
        if (overrides != null && overrides.getStringList(station).contains(id)) {
            setExternalOverride(yaml, station, id, false);
        }
    }

    static Map<String, Object> mergeRecipeBody(Map<String, Object> previous, Map<String, Object> edited) {
        Map<String, Object> unplaced = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) mergeRecipeValue(
                NativeRecipeSchema.copy(previous), NativeRecipeSchema.copy(edited), "recipe", unplaced);
        if (!unplaced.isEmpty()) {
            Map<String, Object> extensions = result.get("extensions") instanceof Map<?, ?> values
                    ? NativeRecipeSchema.copy(values) : new LinkedHashMap<>();
            if (result.get("extensions") != null && !(result.get("extensions") instanceof Map<?, ?>)) {
                extensions.put("original_extensions", result.get("extensions"));
            }
            Map<String, Object> saved = extensions.get("saved_fields") instanceof Map<?, ?> values
                    ? NativeRecipeSchema.copy(values) : new LinkedHashMap<>();
            unplaced.forEach(saved::putIfAbsent);
            extensions.put("saved_fields", saved);
            result.put("extensions", extensions);
        }
        return result;
    }

    private static Object mergeRecipeValue(Object previous, Object edited, String path, Map<String, Object> unplaced) {
        // These maps contain gameplay keys, even when a key happens to begin with "x-".
        if (path.endsWith("/components") || path.endsWith("/perfect")) return edited;
        if (previous instanceof Map<?, ?> before && edited instanceof Map<?, ?> after) {
            Map<String, Object> result = NativeRecipeSchema.copy(after);
            for (var entry : before.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (extensionKey(key)) {
                    if (result.containsKey(key)) result.put(key, mergeExtensionValue(entry.getValue(), result.get(key)));
                    else result.put(key, entry.getValue());
                } else if (result.containsKey(key)) {
                    result.put(key, mergeRecipeValue(entry.getValue(), result.get(key), path + "/" + key, unplaced));
                } else if (path.equals("recipe") && !managedRootField(key)) {
                    result.put(key, entry.getValue());
                } else {
                    collectExtensions(entry.getValue(), path + "/" + key, unplaced);
                }
            }
            return result;
        }
        if (previous instanceof List<?> before && edited instanceof List<?> after) {
            List<Object> result = new ArrayList<>(after.size());
            for (int index = 0; index < after.size(); index++) result.add(mergeRecipeValue(
                    index < before.size() ? before.get(index) : null, after.get(index), path + "/" + index, unplaced));
            for (int index = after.size(); index < before.size(); index++) collectExtensions(before.get(index), path + "/" + index, unplaced);
            return result;
        }
        collectExtensions(previous, path, unplaced);
        return edited;
    }

    private static Object mergeExtensionValue(Object previous, Object edited) {
        if (previous instanceof Map<?, ?> before && edited instanceof Map<?, ?> after) {
            Map<String, Object> result = NativeRecipeSchema.copy(before);
            after.forEach((key, value) -> result.put(String.valueOf(key), mergeExtensionValue(before.get(key), value)));
            return result;
        }
        return edited;
    }

    private static boolean extensionKey(String key) {
        return key.startsWith("x-") || key.equals("extensions") || key.equals("metadata");
    }

    private static boolean managedRootField(String key) {
        return java.util.Set.of("station", "input", "output", "process", "matching", "operation", "fluid",
                "group", "category", "priority", "ingredients", "result", "results", "result-count", "container",
                "cook-time", "experience", "tool", "tools", "sound", "match-mode", "perfect",
                "use-equivalent-foods", "use-seasonings", "minimum-score", "sound-volume", "sound-pitch").contains(key);
    }

    private static void collectExtensions(Object value, String path, Map<String, Object> found) {
        if (path.endsWith("/components") || path.endsWith("/perfect")) return;
        if (value instanceof Map<?, ?> fields) fields.forEach((key, nested) -> {
            String name = String.valueOf(key), child = path + "/" + name;
            if (extensionKey(name)) found.put(child, nested);
            else if (!name.equals("components") && !name.equals("perfect")) collectExtensions(nested, child, found);
        });
        else if (value instanceof List<?> values) for (int index = 0; index < values.size(); index++) {
            collectExtensions(values.get(index), path + "/" + index, found);
        }
    }

    private Edit external(RecipeOwner owner, YamlMutation mutation) {
        return new Edit(owner.file(), null, mutation);
    }

    public CompletableFuture<Boolean> saveFoodGroupAsync(String originalId, com.huidu.farmersdelight.recipe.FoodGroupSnapshot.Group group) {
        Map<String, Object> body = Map.of("kind", group.kind().name().toLowerCase(java.util.Locale.ROOT), "items", group.items());
        return captureAsync(() -> {
            RecipeSource old = originalId == null ? null : com.huidu.farmersdelight.recipe.FoodGroupStore.sourceOf(originalId);
            List<String> keys = old == null ? List.of("food_groups", group.id()) : List.of(old.keys().getFirst(), group.id());
            RecipeSource target = new RecipeSource(old == null ? RecipePackFiles.file(plugin, RecipePackFiles.GROUP_FILE) : old.file(), keys, false);
            return new Edit(target.file().toFile(), null, yaml -> {
                Map<String, Object> merged = old == null ? target.body(yaml) : old.body(yaml);
                merged.putAll(body);
                if (old != null && !originalId.equals(group.id())) old.put(yaml, null);
                target.put(yaml, merged);
            }, true);
        });
    }

    public CompletableFuture<Boolean> deleteFoodGroupAsync(String id) {
        return captureAsync(() -> {
            RecipeSource source = com.huidu.farmersdelight.recipe.FoodGroupStore.sourceOf(id);
            if (source == null) throw new IllegalStateException("No editable food-group source: " + id);
            return new Edit(source.file().toFile(), null, yaml -> source.put(yaml, null), true);
        });
    }

    private Map<String, Object> buildCookingPotBody(CookingPotRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();

        List<Object> ingredients = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            ingredients.add(RecipeSerializer.serializeIngredientValue(ingredient));
        }
        body.put("ingredients", ingredients);
        if (recipe.isFuzzy()) {
            body.remove("ingredients");
            body.put("match-mode", "fuzzy");
            body.put("perfect", new LinkedHashMap<>(recipe.fuzzy().perfect()));
            if (!recipe.fuzzy().useEquivalentFoods()) body.put("use-equivalent-foods", false);
            if (!recipe.fuzzy().useSeasonings()) body.put("use-seasonings", false);
            if (recipe.fuzzy().minimumScore() != 0.15) body.put("minimum-score", recipe.fuzzy().minimumScore());
        }

        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(container);
            body.put("container", snapshot != null ? snapshot : RecipeSerializer.itemIdString(container));
        } else if (recipe.getResult() != null && !recipe.getResult().getType().isAir()
                && ItemUtils.craftingRemainderOf(recipe.getResult(), recipe.getId()) != null) {
            // Saved without a container while the result declares one: write the explicit opt-out, otherwise
            // loading the file would infer that container right back.
            body.put("container", CONTAINER_OPT_OUT);
        }

        ItemStack result = recipe.getResult();
        Map<String, Object> resultSnapshot = RecipeItemCodec.snapshotIfCustom(result);
        if (resultSnapshot != null) {
            body.put("result", resultSnapshot);
        } else {
            body.put("result", RecipeSerializer.itemIdString(result));
            if (result != null && result.getAmount() > 1) {
                body.put("result-count", result.getAmount());
            }
        }
        if (recipe.getExperience() > 0.0f) {
            body.put("experience", (double) recipe.getExperience());
        }
        body.put("cook-time", recipe.getCookTime());
        if (recipe.getCategory() != null && !recipe.getCategory().isBlank()) {
            body.put("category", recipe.getCategory());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private Map<String, Object> buildCuttingBoardBody(CuttingBoardRecipe recipe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", RecipeSerializer.serializeIngredientValue(recipe.getInput()));

        List<String> tools = new ArrayList<>();
        for (CuttingBoardRecipe.ToolRequirement tool : recipe.getTools()) {
            tools.add(RecipeSerializer.serializeTool(tool));
        }
        if (tools.size() == 1) {
            body.put("tool", tools.getFirst());
        } else if (!tools.isEmpty()) {
            body.put("tools", tools);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            ItemStack item = entry.getItem();
            if (item == null || item.getType().isAir()) {
                continue;
            }
            Map<String, Object> resultMap = new LinkedHashMap<>();
            Map<String, Object> snapshot = RecipeItemCodec.snapshotIfCustom(item);
            if (snapshot != null) {
                resultMap.putAll(snapshot);
            } else {
                resultMap.put("item", RecipeSerializer.itemIdString(item));
                if (item.getAmount() > 1) {
                    resultMap.put("count", item.getAmount());
                }
            }
            if (entry.getChance() < 1.0d) {
                resultMap.put("chance", entry.getChance());
            }
            results.add(resultMap);
        }
        body.put("results", results);
        if (recipe.getSoundVolume() != null) body.put("sound-volume", recipe.getSoundVolume());
        if (recipe.getSoundPitch() != null) body.put("sound-pitch", recipe.getSoundPitch());

        if (recipe.getSound() != null && !recipe.getSound().isBlank()
                && !recipe.getSound().equals(Constants.SOUND_CUTTING_BOARD_KNIFE)) {
            body.put("sound", recipe.getSound());
        }
        if (recipe.getPriority() != 0) {
            body.put("priority", recipe.getPriority());
        }
        return body;
    }

    private interface YamlMutation {
        void apply(YamlConfiguration yaml);
    }

    private boolean apply(Edit edit) {
        try {
            return YamlFileTransactions.execute(edit.file().toPath(), () -> {
                YamlConfiguration yaml;
                if (edit.localPath() != null) {
                    yaml = RecipeFileLoader.loadPlainRecipeFile(plugin, edit.localPath());
                    if (yaml == null) {
                        throw new IOException("Cannot read recipe file; edit was refused");
                    }
                } else {
                    // Strict plain parsing keeps legacy serialization maps untouched during worker I/O.
                    yaml = edit.file().isFile() ? edit.literal()
                            ? com.huidu.farmersdelight.config.PlainYamlDocuments.readLiteral(edit.file().toPath())
                            : com.huidu.farmersdelight.config.PlainYamlDocuments.read(edit.file().toPath()) : new YamlConfiguration();
                    if (edit.literal()) yaml.options().pathSeparator('\u0001');
                }
                edit.mutation().apply(yaml);
                writeAtomically(edit.file(), yaml.saveToString());
                return true;
            });
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            I18n.logWarning("plugin.recipe_save_failed", "file", edit.file().getPath(), "error", error.getMessage());
            return false;
        }
    }

    private boolean captureAndReload(java.util.function.Supplier<Edit> capture) {
        try {
            return applyAndReload(capture.get());
        } catch (RuntimeException | LinkageError error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not capture recipe edit", error);
            return false;
        }
    }

    private CompletableFuture<Boolean> captureAsync(java.util.function.Supplier<Edit> capture) {
        try {
            return applyAsync(capture.get());
        } catch (RuntimeException | LinkageError error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private boolean applyAndReload(Edit edit) {
        if (!apply(edit)) {
            return false;
        }
        plugin.reloadRecipeFiles();
        return true;
    }

    private CompletableFuture<Boolean> applyAsync(Edit edit) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!plugin.scheduler().tryRunAsync(() -> {
            try {
                if (!apply(edit)) {
                    result.complete(false);
                    return;
                }
                if (!plugin.isEnabled()) {
                    result.complete(true); // Disk commit succeeded; startup will publish it next time.
                    return;
                }
                plugin.reloadEditedRecipeFilesAsync().whenComplete((ignored, error) -> {
                    if (error == null) result.complete(true);
                    else result.completeExceptionally(error);
                });
            } catch (RuntimeException | LinkageError error) {
                result.completeExceptionally(error);
            }
        })) {
            result.complete(false);
        }
        return result;
    }

    private static void putRecipe(YamlConfiguration yaml, String rootName, String id, Object value) {
        ConfigurationSection root = yaml.getConfigurationSection(rootName);
        Map<String, Object> entries = root == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(root.getValues(false));
        if (value == null) {
            entries.remove(id);
        } else {
            entries.put(id, value);
        }
        com.huidu.farmersdelight.config.PlainYamlDocuments.setValue(yaml, rootName, entries.isEmpty() ? null : entries);
    }

    private static void setExternalOverride(YamlConfiguration yaml, String station, String id, boolean enabled) {
        ConfigurationSection root = yaml.getConfigurationSection(EXTERNAL_OVERRIDES_ROOT);
        List<String> ids = root == null ? new ArrayList<>() : new ArrayList<>(root.getStringList(station));
        ids.removeIf(id::equals);
        if (enabled) {
            ids.add(id);
        }
        if (root == null && !ids.isEmpty()) root = yaml.createSection(EXTERNAL_OVERRIDES_ROOT);
        if (root != null) root.set(station, ids.isEmpty() ? null : ids);
    }

    static void writeAtomically(File target, String content) throws IOException {
        ConfigFileUpdater.writeStringAtomically(target.toPath(), content, true);
    }
}
