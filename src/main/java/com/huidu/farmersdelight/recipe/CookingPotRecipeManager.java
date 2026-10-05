package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.MMOItemsCompat;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import com.huidu.farmersdelight.api.recipe.AddonRecipeFiles;
import com.huidu.farmersdelight.util.CommonTagResolver;

public class CookingPotRecipeManager {

    private final FarmersDelightPlugin plugin;
    private static final int MAX_CACHE_SIZE = 2048;
    private final VanillaTagItemIdCache vanillaItemIdsByTagCache;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile YamlConfiguration lastFileDocument;
    private volatile RecipeParseCache<CookingPotRecipe> parsedRecipes;
    private long publicationGeneration;
    private volatile long matchCacheEpoch;

    private Snapshot currentSnapshot() { return RuntimeSnapshotPublication.get(this, snapshot); }

    private void setSnapshot(Snapshot next) {
        RuntimeSnapshotPublication.publish(this, snapshot, next);
        snapshot = next;
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public synchronized Runnable captureReloadRollback() {
        Snapshot previous = currentSnapshot();
        YamlConfiguration document = lastFileDocument;
        RecipeParseCache<CookingPotRecipe> parsing = parsedRecipes;
        return () -> {
            synchronized (this) {
                synchronized (previous.recipeCache()) {
                    ++matchCacheEpoch;
                    previous.recipeCache().clear();
                    previous.recipeMisses().clear();
                    lastFileDocument = document;
                    parsedRecipes = parsing;
                    setSnapshot(previous.withGeneration(++publicationGeneration));
                }
                afterPublication();
            }
        };
    }

    record Snapshot(Map<String, CookingPotRecipe> recipes,
                    Map<String, Map<String, CookingPotRecipe>> customRecipes,
                    Map<String, Set<String>> ingredientToRecipes,
                    Map<String, Map<String, Set<String>>> customIngredientToRecipes,
                    List<CookingPotRecipe> sortedRecipes,
                    Map<String, List<CookingPotRecipe>> sortedCustomRecipes,
                    Map<String, List<CookingPotRecipe>> sortedCustomOnlyRecipes,
                    Map<String, List<CookingPotRecipe>> resultToRecipes,
                    Set<String> validContainerKeys, int packRecipeCount, long generation,
                    Map<String, CookingPotRecipe> recipeCache, Set<String> recipeMisses,
                    FuzzyRecipeMatcher.Index fuzzyIndex, Map<String, FuzzyRecipeMatcher.Index> customFuzzyIndices,
                    List<FoodGroupSnapshot.Group> localFoodGroups, FoodGroupSnapshot foodGroups,
                    Map<String, RecipeSource> sources, Map<String, Map<String, RecipeSource>> customSources,
                    NbtMatchDependencies nbtDependencies) {
        boolean nbtSensitive() { return nbtDependencies.any(); }
        Snapshot withGeneration(long nextGeneration) {
            return new Snapshot(recipes, customRecipes, ingredientToRecipes, customIngredientToRecipes,
                    sortedRecipes, sortedCustomRecipes, sortedCustomOnlyRecipes, resultToRecipes,
                    validContainerKeys, packRecipeCount, nextGeneration, recipeCache, recipeMisses,
                    fuzzyIndex, customFuzzyIndices, localFoodGroups, foodGroups, sources, customSources, nbtDependencies);
        }

        static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(),
                    Map.of(), Set.of(), 0, 0, newMatchCache(), newMissCache(),
                    FuzzyRecipeMatcher.compile(List.of(), FoodGroupSnapshot.empty()), Map.of(), List.of(), FoodGroupSnapshot.empty(), Map.of(), Map.of(), new NbtMatchDependencies(Set.of(), Map.of()));
        }
    }

    private static Map<String, CookingPotRecipe> newMatchCache() {
        return Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CookingPotRecipe> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    private static Set<String> newMissCache() {
        return Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(MAX_CACHE_SIZE + 1, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        });
    }

    public long recipeGeneration() { return currentSnapshot().generation(); }

    public RecipeSource sourceOf(String id, String group) {
        Snapshot current = currentSnapshot();
        return group == null || group.isBlank() ? current.sources().get(id)
                : current.customSources().getOrDefault(group, Map.of()).get(id);
    }

    public record ParseMetrics(int parsed, int reused) { }
    public ParseMetrics parseMetrics() {
        RecipeParseCache<CookingPotRecipe> current = parsedRecipes;
        return current == null ? new ParseMetrics(0, 0) : new ParseMetrics(current.parsed(), current.reused());
    }

    // Recipes registered at runtime by addons via the public API. Kept separate so they survive a
    // /fd reload (which rebuilds the file-backed maps); merged into the published maps in loadRecipes().
    private final Map<String, CookingPotRecipe> externalRecipes = new ConcurrentHashMap<>();
    // Republishing after an external (un)register is coalesced to the next tick, so registering a batch
    // of addon recipes requests one complete catalog publication instead of reloading for each recipe.
    private final java.util.concurrent.atomic.AtomicBoolean externalRepublishScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    public CookingPotRecipeManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.vanillaItemIdsByTagCache = new VanillaTagItemIdCache(plugin);
    }

    public void loadRecipes() {
        loadRecipes(RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml"), false);
    }

    public void loadRecipesIncrementally() {
        loadRecipes(RecipeFileLoader.loadRecipeFile(plugin, "recipes/cooking_pot_recipes.yml"), true);
    }

    private synchronized void loadRecipes(YamlConfiguration config, boolean incremental) {
        RecipePublicationTransaction.run(() -> loadRecipesInTransaction(config, incremental),
                captureReloadRollback(), FoodGroupStore.captureReloadRollback());
    }

    private void loadRecipesInTransaction(YamlConfiguration config, boolean incremental) {
        if (config == null) return;
        FoodGroupStore.Loaded foodGroups;
        try {
            foodGroups = FoodGroupStore.load(plugin, currentSnapshot().localFoodGroups());
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Food groups were not published; previous recipes remain active", invalid);
            throw invalid;
        }
        RecipeParseCache<CookingPotRecipe> parsing = new RecipeParseCache<>(incremental ? parsedRecipes : null);
        // Build everything into fresh local collections first, then publish atomically (below), so readers
        // never see a half-cleared map. Do not clear()/refill the live fields in place.
        Map<String, CookingPotRecipe> newRecipes = new LinkedHashMap<>();
        Map<String, Map<String, CookingPotRecipe>> newCustomRecipes = new HashMap<>();
        Map<String, Set<String>> newIngredientToRecipes = new HashMap<>();
        Map<String, Map<String, Set<String>>> newCustomIngredientToRecipes = new HashMap<>();
        Set<String> newValidContainerKeys = new HashSet<>();
        // Recipes that got their container from their own result instead of the file; reported below so an
        // operator can see which entries rely on the inference.
        int[] inferredContainers = {0};
        // Ids whose winning definition came from a CraftEngine pack section; the registry buckets in the
        // startup summary read this, so it may only count entries that survived the merges below.
        Set<String> packIds = new HashSet<>();
        Map<String, RecipeSource> sources = new LinkedHashMap<>();
        Map<String, Map<String, RecipeSource>> customSources = new LinkedHashMap<>();

        Set<String> overriddenExternalIds = externalOverrideIds(config, "cooking_pot");
        RecipeFileLoader.loadRecipeSections(plugin, config, "cooking_pot_recipes", "cooking pot",
                "recipes/cooking_pot_recipes.yml", (recipeId, section) -> {
                    CookingPotRecipe recipe = parsing.parse("file", recipeId, section, () -> parseRecipe(recipeId, section, 6));
                    newRecipes.put(recipeId, recipe);
                    sources.put(recipeId, RecipePackFiles.source(plugin, RecipePackFiles.POT_FILE, recipeId, null, config));

                    indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                    indexContainer(newValidContainerKeys, recipe);
                    if (section.get("container") == null && recipe.getContainer() != null) {
                        inferredContainers[0]++;
                    }
                });
        loadCustomRecipes(config, newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys, inferredContainers, parsing, "file");
        for (var group : newCustomRecipes.entrySet()) {
            Map<String, RecipeSource> groupSources = new LinkedHashMap<>();
            for (String id : group.getValue().keySet()) groupSources.put(id, RecipePackFiles.source(plugin, RecipePackFiles.POT_FILE, id, group.getKey(), config));
            customSources.put(group.getKey(), groupSources);
        }

        // Recipes a CraftEngine pack declares under cooking_recipes. Loaded after the plugin's own file so a
        // pack can never silently replace a built-in recipe, and before the API merge below so an explicit
        // runtime registration still wins on an id clash. CraftEngine read the files; see PackSections.
        for (PackSections.Section packSection : RecipePackFiles.sections(plugin, PackSection.COOKING_POT)) {
            RecipeFileLoader.loadRecipeSections(plugin, packSection.yaml(), PackSection.COOKING_POT.rootKey(),
                    "cooking pot [" + packSection.source() + "]",
                    packSection.source(),
                    (recipeId, section) -> {
                        if (newRecipes.containsKey(recipeId)) {
                            I18n.logWarning("recipe.pack_duplicate_skipped", "id", recipeId, "source", packSection.source());
                            return;
                        }
                        CookingPotRecipe recipe = parsing.parse(packSection.source(), recipeId, section, () -> parseRecipe(recipeId, section, 6));
                        newRecipes.put(recipeId, recipe);
                        if (packSection.file() != null) sources.put(recipeId, new RecipeSource(packSection.file(),
                                List.of(packSection.sectionKey(), recipeId), packSection.sectionKey().split("#", 2)[0].equals(NativeRecipeSchema.ROOT), true));
                        packIds.add(recipeId);
                        indexDefaultRecipe(newIngredientToRecipes, recipeId, recipe);
                        indexContainer(newValidContainerKeys, recipe);
                        if (section.get("container") == null && recipe.getContainer() != null) {
                            inferredContainers[0]++;
                        }
                    });
            loadCustomRecipes(packSection.yaml(), newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys, inferredContainers, parsing, packSection.source());
        }

        for (PackSections.Section packSection : RecipePackFiles.sections(plugin, PackSection.CUSTOM_COOKING_POT)) {
            if (RecipePackFiles.file(plugin, RecipePackFiles.POT_FILE).equals(packSection.file())) continue;
            loadCustomRecipes(packSection.yaml(), newCustomRecipes, newCustomIngredientToRecipes, newValidContainerKeys, inferredContainers, parsing, packSection.source());
            ConfigurationSection groups = packSection.yaml().getConfigurationSection("custom_cooking_pot_recipes");
            if (groups == null || packSection.file() == null) continue;
            for (String group : groups.getKeys(false)) {
                ConfigurationSection entries = groups.getConfigurationSection(group);
                if (entries == null) continue;
                Map<String, RecipeSource> sourceGroup = customSources.computeIfAbsent(group, ignored -> new LinkedHashMap<>());
                for (String id : entries.getKeys(false)) {
                    ConfigurationSection body = entries.getConfigurationSection(id);
                    if (body != null && newCustomRecipes.getOrDefault(group, Map.of()).containsKey(id)) sourceGroup.putIfAbsent(id,
                            new RecipeSource(packSection.file(), List.of(packSection.sectionKey(), id), true, true));
                }
            }
        }

        // Merge addon-registered recipes last so they survive reloads; an editor override is explicit and wins.
        for (CookingPotRecipe recipe : externalRecipes.values()) {
            if (!overriddenExternalIds.contains(recipe.getId()) || !newRecipes.containsKey(recipe.getId())
                    || AddonRecipeFiles.ownerOf("cooking_pot", recipe.getId()) != null) {
                newRecipes.put(recipe.getId(), recipe);
                sources.remove(recipe.getId());
                packIds.remove(recipe.getId());
                indexDefaultRecipe(newIngredientToRecipes, recipe.getId(), recipe);
                indexContainer(newValidContainerKeys, recipe);
            }
        }
        int newPackRecipeCount = packIds.size();

        List<CookingPotRecipe> newSortedRecipes = sortedRecipeList(newRecipes);
        // Only reported when it actually happens: a pack that declares every container keeps the boot log quiet.
        if (inferredContainers[0] > 0) {
            I18n.logDetail("recipe", "recipe.cooking_pot_inferred_containers", "count", inferredContainers[0]);
        }
        Map<String, List<CookingPotRecipe>> newSortedCustomRecipes = new HashMap<>();
        Map<String, List<CookingPotRecipe>> newSortedCustomOnlyRecipes = new HashMap<>();
        for (Map.Entry<String, Map<String, CookingPotRecipe>> entry : newCustomRecipes.entrySet()) {
            newSortedCustomOnlyRecipes.put(entry.getKey(), sortedRecipeList(entry.getValue()));
            Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(newRecipes);
            merged.putAll(entry.getValue());
            newSortedCustomRecipes.put(entry.getKey(), sortedRecipeList(merged));
        }

        Map<String, List<CookingPotRecipe>> newResultToRecipes = buildResultIndex(newSortedRecipes);
        for (List<CookingPotRecipe> groupRecipes : newSortedCustomOnlyRecipes.values()) {
            for (CookingPotRecipe recipe : groupRecipes) {
                newResultToRecipes.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
            }
        }

        Map<String, FuzzyRecipeMatcher.Index> customFuzzyIndices = new HashMap<>();
        newCustomRecipes.forEach((group, recipes) -> customFuzzyIndices.put(group, compileFuzzy(recipes, foodGroups.combined())));

        Snapshot next = new Snapshot(RecipeCollections.freezeMap(newRecipes),
                RecipeCollections.freezeNested(newCustomRecipes), RecipeCollections.freezeSets(newIngredientToRecipes),
                RecipeCollections.freezeNestedSets(newCustomIngredientToRecipes), List.copyOf(newSortedRecipes),
                RecipeCollections.freezeLists(newSortedCustomRecipes), RecipeCollections.freezeLists(newSortedCustomOnlyRecipes),
                RecipeCollections.freezeLists(newResultToRecipes), Set.copyOf(newValidContainerKeys), newPackRecipeCount,
                ++publicationGeneration, newMatchCache(), newMissCache(), compileFuzzy(newRecipes, foodGroups.combined()),
                Map.copyOf(customFuzzyIndices), foodGroups.local(), foodGroups.combined(), Map.copyOf(sources), RecipeCollections.freezeNested(customSources),
                NbtMatchDependencies.compile(newSortedRecipes, newCustomRecipes));
        lastFileDocument = config;
        parsedRecipes = parsing;
        ++matchCacheEpoch;
        setSnapshot(next);
        afterPublication();
    }

    private void afterPublication() {
        RuntimeSnapshotPublication.afterCommit(vanillaItemIdsByTagCache, vanillaItemIdsByTagCache::clear);
        RuntimeSnapshotPublication.afterCommit(com.huidu.farmersdelight.gui.GuiCacheInvalidator.class,
                com.huidu.farmersdelight.gui.GuiCacheInvalidator::clearRecipeDisplayCaches);
        RuntimeSnapshotPublication.afterCommit(RecipeItemCodec.class, RecipeItemCodec::clearDecodeCache);
        RuntimeSnapshotPublication.afterCommit(this, () -> {
            if (plugin != null && plugin.getTickManager() != null) plugin.getTickManager().wakeAllNativePots();
        });
    }

    private void loadCustomRecipes(YamlConfiguration config,
                                   Map<String, Map<String, CookingPotRecipe>> targetCustomRecipes,
                                   Map<String, Map<String, Set<String>>> targetCustomIndex,
                                   Set<String> targetContainerKeys,
                                   int[] inferredContainers, RecipeParseCache<CookingPotRecipe> parsing, String source) {
        ConfigurationSection root = config.getConfigurationSection("custom_cooking_pot_recipes");
        if (root == null) {
            return;
        }

        int loadedCount = 0;
        for (String groupId : root.getKeys(false)) {
            ConfigurationSection groupSection = root.getConfigurationSection(groupId);
            if (groupSection == null) {
                continue;
            }
            Map<String, CookingPotRecipe> groupRecipes = targetCustomRecipes.computeIfAbsent(groupId, key -> new LinkedHashMap<>());
            for (String recipeId : groupSection.getKeys(false)) {
                ConfigurationSection section = groupSection.getConfigurationSection(recipeId);
                if (section == null) {
                    continue;
                }
                // A recipe already present in this group was contributed by the plugin's own file or an
                // earlier pack; a later pack must not replace it silently.
                if (groupRecipes.containsKey(recipeId)) {
                    I18n.logWarning("recipe.pack_duplicate_skipped", "id", groupId + "." + recipeId, "source", "custom cooking pot group");
                    continue;
                }
                try {
                    CookingPotRecipe recipe = parsing.parse(source, List.of("custom", groupId, recipeId), section,
                            () -> parseRecipe(recipeId, section, 54));
                    groupRecipes.put(recipeId, recipe);
                    indexCustomRecipe(targetCustomIndex, groupId, recipeId, recipe);
                    indexContainer(targetContainerKeys, recipe);
                    if (section.get("container") == null && recipe.getContainer() != null) {
                        inferredContainers[0]++;
                    }
                    loadedCount++;
                } catch (Exception e) {
                    I18n.logWarning("recipe.custom_cooking_pot_load_failed",
                            "id", groupId + "." + recipeId,
                            "path", groupSection.getCurrentPath() + "." + recipeId,
                            "error", e.getMessage());
                }
            }
        }
        I18n.logDetail("recipe", "recipe.custom_cooking_pot_loaded", "count", loadedCount);
    }

    private List<CookingPotRecipe> sortedRecipeList(Map<String, CookingPotRecipe> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<CookingPotRecipe> sorted = new ArrayList<>(source.values());
        sorted.sort(Comparator.comparingInt(CookingPotRecipe::getPriority).reversed()
                .thenComparing(CookingPotRecipe::getId));
        return Collections.unmodifiableList(sorted);
    }

    private void indexDefaultRecipe(Map<String, Set<String>> ingredientIndex, String recipeId, CookingPotRecipe recipe) {
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                ingredientIndex.computeIfAbsent(ingredientKey, k -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexCustomRecipe(Map<String, Map<String, Set<String>>> customIndex, String groupId, String recipeId, CookingPotRecipe recipe) {
        Map<String, Set<String>> groupIndex = customIndex.computeIfAbsent(groupId, key -> new HashMap<>());
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            for (String ingredientKey : flattenIngredientKeys(ingredient)) {
                groupIndex.computeIfAbsent(ingredientKey, key -> new HashSet<>()).add(recipeId);
            }
        }
    }

    private void indexContainer(Set<String> containerKeys, CookingPotRecipe recipe) {
        ItemStack container = recipe.getContainer();
        if (container != null && !container.getType().isAir()) {
            String customId = ItemUtils.getCustomItemId(container);
            if (customId != null) {
                containerKeys.add(customId);
            }
            containerKeys.add("minecraft:" + container.getType().name().toLowerCase(Locale.ROOT));
        }
    }

    private Map<String, List<CookingPotRecipe>> buildResultIndex(List<CookingPotRecipe> recipesToIndex) {
        Map<String, List<CookingPotRecipe>> index = new HashMap<>();
        for (CookingPotRecipe recipe : recipesToIndex) {
            if (recipe.getResult() == null) {
                continue;
            }
            index.computeIfAbsent(getItemKey(recipe.getResult()), k -> new ArrayList<>(1)).add(recipe);
        }
        return index;
    }

    /** Recipes (default + external, excluding custom-group duplicates) that produce this item. */
    public List<CookingPotRecipe> getRecipesProducing(ItemStack item) {
        Snapshot view = currentSnapshot();
        if (item == null || item.getType().isAir()) {
            return List.of();
        }
        List<CookingPotRecipe> matches = view.resultToRecipes().get(getItemKey(item));
        return matches == null ? List.of() : matches;
    }

    private CookingPotRecipe parseRecipe(String id, ConfigurationSection section, int maxIngredients) {
        section = RecipeSchemaAdapter.normalizePot(section);
        RecipeParsingSupport.requireKnownSemantics(section, Set.of("match-mode"));
        FuzzyRecipeSpec fuzzy = parseFuzzy(section);
        Object rawIngredients = section.get("ingredients");
        if (fuzzy != null) rawIngredients = new ArrayList<>(fuzzy.perfect().keySet());
        if (!(rawIngredients instanceof List<?> ingredientValues) || ingredientValues.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (ingredientValues.size() > maxIngredients) {
            throw new IllegalArgumentException("Recipe can have at most " + maxIngredients + " ingredients");
        }

        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int ingredientIndex = 0; ingredientIndex < ingredientValues.size(); ingredientIndex++) {
            Object rawIngredient = ingredientValues.get(ingredientIndex);
            if (rawIngredient instanceof ConfigurationSection nested) {
                rawIngredient = sectionToMap(nested);
            }
            try {
                RecipeIngredient ingredient = RecipeParsingSupport.parseIngredientValue(rawIngredient);
                AdvancedRecipeTags.requireDefined(ingredient);
                if (!ingredientHasMembers(ingredient)) {
                    throw new IllegalArgumentException("Ingredient item or tag has no loaded items at ingredients[" + ingredientIndex + "]: " + rawIngredient);
                }
                ingredients.add(ingredient);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + ingredientIndex + "]: " + e.getMessage(), e);
            }
        }

        Object containerValue = section.get("container");
        // "container: none" (also "air" or an empty string) is the explicit opt-out: this recipe needs no
        // container even though its result declares a remainder. Without the field at all the container is
        // inferred from the result below, which is what lets a hand-written or addon recipe behave like the
        // mod's own container-carrying recipes without repeating the container on every entry.
        ItemStack container = null;
        if (containerValue != null && !isContainerOptOut(containerValue)) {
            container = parseItemValue(containerValue);
            if (container == null || container.getType().isAir()) {
                throw new IllegalArgumentException("Invalid container item: " + containerValue);
            }
        }

        Object resultValue = section.get("result");
        if (resultValue == null) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        ItemStack result = parseItemValue(resultValue);
        if (result == null) {
            throw new IllegalArgumentException("Invalid result item: " + resultValue);
        }
        if (!(resultValue instanceof Map) && !(resultValue instanceof ConfigurationSection)) {
            result.setAmount(Math.max(1, ConfigSectionReader.optionalInt(section, "result-count", 1)));
        }

        if (container == null && containerValue == null) {
            ItemStack inferred = inferContainer(result, id);
            if (inferred != null) {
                container = inferred;
            }
        }
        boolean needsContainer = container != null && !container.getType().isAir();

        float experience = Math.max(0, (float) ConfigSectionReader.optionalDouble(section, "experience", 0.0));
        int defaultCookTime = Math.max(1, plugin.getConfigInt(Constants.DEFAULT_COOKING_TIME_COOKING_POT,
                "cooking-pot.cooking.default-cook-time",
                "cooking-pot.default-cook-time"));
        int minCookTime = Math.max(1, plugin.getConfigInt(20,
                "cooking-pot.cooking.min-cook-time",
                "cooking-pot.min-cook-time"));
        int maxCookTime = Math.max(minCookTime, plugin.getConfigInt(6000,
                "cooking-pot.cooking.max-cook-time",
                "cooking-pot.max-cook-time"));
        int cookTime = Math.max(minCookTime, Math.min(maxCookTime,
                ConfigSectionReader.optionalInt(section, "cooking_time", defaultCookTime,
                        "cooking-time", "cook-time")));
        String category = ConfigSectionReader.optionalString(section, "category", "misc");
        int priority = ConfigSectionReader.optionalInt(section, "priority", 0);

        return new CookingPotRecipe(id, ingredients, container, needsContainer, result, experience, cookTime, category, priority, fuzzy);
    }

    static FuzzyRecipeSpec parseFuzzy(ConfigurationSection section) {
        String mode = section.getString("match-mode", section.contains("perfect") ? "fuzzy" : "exact");
        if ("exact".equals(mode)) return null;
        if (!"fuzzy".equals(mode)) throw new IllegalArgumentException("Unknown match-mode: " + mode);
        Map<String, Integer> perfect = new LinkedHashMap<>();
        ConfigurationSection weights = section.getConfigurationSection("perfect");
        if (weights != null) {
            for (var entry : weights.getValues(false).entrySet()) {
                if (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.intValue()) throw new IllegalArgumentException("Ideal weights must be integers");
                perfect.put(entry.getKey(), number.intValue());
            }
        } else {
            for (String value : section.getStringList("perfect")) {
                String[] parts = value.trim().split("\\s+", 2);
                if (parts.length != 2 || perfect.putIfAbsent(parts[0], Integer.parseInt(parts[1])) != null)
                    throw new IllegalArgumentException("Invalid or duplicate perfect ingredient: " + value);
            }
        }
        return new FuzzyRecipeSpec(perfect,
                ConfigSectionReader.optionalBoolean(section, "use-equivalent-foods", true, "use_equivalent_foods"),
                ConfigSectionReader.optionalBoolean(section, "use-seasonings", true, "use_seasonings"),
                ConfigSectionReader.optionalDouble(section, "minimum-score", 0.15));
    }

    private static FuzzyRecipeMatcher.Index compileFuzzy(Map<String, CookingPotRecipe> recipes, FoodGroupSnapshot groups) {
        List<FuzzyRecipeMatcher.Definition> definitions = new ArrayList<>();
        for (CookingPotRecipe recipe : recipes.values()) {
            if (recipe.isFuzzy()) definitions.add(new FuzzyRecipeMatcher.Definition(recipe.id(), recipe.fuzzy(), recipe.priority()));
        }
        return FuzzyRecipeMatcher.compile(definitions, groups);
    }

    public List<FoodGroupSnapshot.Group> getLocalFoodGroups() { return currentSnapshot().localFoodGroups(); }
    public FoodGroupSnapshot getFoodGroups() { return currentSnapshot().foodGroups(); }

    /**
     * Values of the {@code container} field that explicitly declare "this recipe needs no container", so a
     * result whose own remainder would otherwise be inferred (a soup's bowl, a drink's bottle) can still be
     * cooked without one. Written as a string, because a map value is always a real item snapshot.
     */
    static boolean isContainerOptOut(Object containerValue) {
        if (!(containerValue instanceof String text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("none") || normalized.equals("air");
    }

    /**
     * The container a recipe needs, taken from its result item's own remainder (a soup's bowl, a drink's
     * bottle): what a meal leaves behind when eaten is the container it is served in. Null when the inference
     * is switched off, when the result declares nothing, or when the remainder is a configured tool remainder.
     */
    private ItemStack inferContainer(ItemStack result, String recipeId) {
        if (!plugin.getConfigBoolean(true, "cooking-pot.container-inference.enabled",
                "container-inference.enabled")) {
            return null;
        }
        Set<String> excluded = excludedContainerRemainders();
        ItemStack inferred = ItemUtils.craftingRemainderOf(result, recipeId);
        if (inferred == null || inferred.getType().isAir()) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(inferred);
        String vanillaId = ItemUtils.getVanillaMaterialItemId(inferred);
        return isExcludedRemainder(excluded, customId, vanillaId) ? null : inferred;
    }

    private Set<String> excludedContainerRemainders() {
        List<String> configured = plugin.getConfigStringList("cooking-pot.container-inference.excluded-remainders",
                "container-inference.excluded-remainders");
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> excluded = new HashSet<>(configured.size());
        for (String id : configured) {
            if (id != null && !id.isBlank()) {
                excluded.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return excluded;
    }

    /**
     * True when the inferred container must be dropped because it is a configured tool remainder. Ids are
     * compared case-insensitively, and either id form matches, so one entry covers a vanilla item and a
     * CraftEngine item built on it.
     */
    static boolean isExcludedRemainder(Set<String> excluded, String customId, String vanillaId) {
        if (excluded.isEmpty()) {
            return false;
        }
        return (customId != null && excluded.contains(customId.toLowerCase(Locale.ROOT)))
                || (vanillaId != null && excluded.contains(vanillaId.toLowerCase(Locale.ROOT)));
    }

    private boolean ingredientHasMembers(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.AdvancedTag tag) return !AdvancedRecipeTags.members(tag.key()).isEmpty();
        if (ingredient instanceof RecipeIngredient.Item item) {
            ItemStack stack = item.createStack();
            return stack != null && !stack.getType().isAir();
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return !plugin.getCraftEngine().itemManager().itemIdsByTag(tag.key()).isEmpty()
                    || !getVanillaItemIdsByTag(tag.key()).isEmpty()
                    || !CommonTagResolver.getMembers(tag.key()).isEmpty();
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            return choice.options().stream().anyMatch(this::ingredientHasMembers);
        }
        return false;
    }

    private RecipeIngredient parseIngredient(String str) {
        return RecipeParsingSupport.parseIngredientChoice(str);
    }

    private List<String> flattenIngredientKeys(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.AdvancedTag tag) return List.of("advtag:" + tag.key());
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return List.of(itemIngredient.key().toString());
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return List.of("#" + tagIngredient.key());
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<String> keys = new ArrayList<>();
            for (RecipeIngredient option : choiceIngredient.options()) {
                keys.addAll(flattenIngredientKeys(option));
            }
            return keys;
        }
        return List.of();
    }

    private ItemStack createItem(String itemId) {
        return ItemUtils.createItem(itemId);
    }

    private ItemStack parseItemValue(Object value) {
        // Bukkit deserializes nested YAML maps into ConfigurationSection, not java.util.Map, so item
        // objects written in recipes must be converted to a plain map first.
        if (value instanceof ConfigurationSection section) {
            return RecipeItemCodec.deserializeItem(sectionToMap(section));
        }
        if (value instanceof Map<?, ?> map) {
            return RecipeItemCodec.deserializeItem(RecipeItemCodec.coerceStringMap(map));
        }
        return createItem(value.toString());
    }

    // Recursively flattens a configuration section into a plain map so nested sections (e.g. the
    // components map) survive the conversion and reach the item deserializer unchanged.
    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                map.put(key, sectionToMap(nested));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container) {
        return matchRecipe(inputItems, container, null);
    }

    public CookingPotRecipe matchRecipe(List<ItemStack> inputItems, ItemStack container, String customRecipeGroupId) {
        try (var scope = RuntimeSnapshotPublication.readScope()) {
            return matchRecipeInScope(inputItems, container, customRecipeGroupId);
        }
    }

    private CookingPotRecipe matchRecipeInScope(List<ItemStack> inputItems, ItemStack container, String customRecipeGroupId) {
        if (inputItems == null || inputItems.isEmpty()) {
            return null;
        }
        
        List<ResolvedRecipeInput> nonEmptyInputs = ResolvedRecipeInput.resolve(inputItems, this::getItemKey);
        
        if (nonEmptyInputs.isEmpty()) {
            return null;
        }

        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        // Capture the epoch first so a rollback that restores the same snapshot cannot admit an old scan.
        long cacheEpoch = matchCacheEpoch;
        Snapshot view = currentSnapshot();
        // NBT predicates distinguish stacks with the same item ID; ordinary recipes retain the compact key.
        boolean nbtDependent = false;
        if (view.nbtDependencies().any(normalizedGroupId)) {
            for (ResolvedRecipeInput input : nonEmptyInputs) {
                if (view.nbtDependencies().dependsOn(input.identity(), normalizedGroupId)) {
                    nbtDependent = true;
                    break;
                }
            }
        }
        String cacheKey = nbtDependent ? null : buildCacheKey(nonEmptyInputs, container, normalizedGroupId);
        CookingPotRecipe cached = null;
        boolean cachedMiss = false;
        if (cacheKey != null) synchronized (view.recipeCache()) {
            if (currentSnapshot() == view && matchCacheEpoch == cacheEpoch) {
                cached = view.recipeCache().get(cacheKey);
                cachedMiss = cached == null && view.recipeMisses().contains(cacheKey);
            }
        }
        if (cachedMiss) {
            return null;
        }
        if (cached != null) {
            return cached;
        }

        CookingPotRecipe result = null;
        if (normalizedGroupId != null) {
            result = matchCustomRecipe(nonEmptyInputs, container, normalizedGroupId, view);
        }

        if (result == null) {
            result = matchDefaultRecipe(nonEmptyInputs, container, view);
        }

        cacheMatch(view, cacheEpoch, cacheKey, result);
        return result;
    }

    void cacheMatch(Snapshot view, long epoch, String key, CookingPotRecipe result) {
        if (key == null) return;
        synchronized (view.recipeCache()) {
            if (currentSnapshot() != view || matchCacheEpoch != epoch) return;
            if (result != null) view.recipeCache().put(key, result);
            else view.recipeMisses().add(key);
        }
    }

    static boolean hasNbtSensitiveIngredients(java.util.Collection<CookingPotRecipe> recipes) {
        return recipes.stream().anyMatch(recipe -> recipe.ingredients().stream()
                .anyMatch(CookingPotRecipeManager::hasNbtSensitiveIngredient));
    }

    private static boolean hasNbtSensitiveIngredient(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) return item.nbt() != null;
        return ingredient instanceof RecipeIngredient.Choice choice && choice.options().stream()
                .anyMatch(CookingPotRecipeManager::hasNbtSensitiveIngredient);
    }

    private CookingPotRecipe matchCustomRecipe(List<ResolvedRecipeInput> nonEmptyInputs, ItemStack container, String customRecipeGroupId, Snapshot view) {
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(customRecipeGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return null;
        }
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, view.customIngredientToRecipes().get(customRecipeGroupId));
        List<CookingPotRecipe> orderedRecipes = view.sortedCustomOnlyRecipes().getOrDefault(customRecipeGroupId, List.of());
        CookingPotRecipe matched = matchFirstRecipe(orderedRecipes, candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            matched = matchFirstRecipe(orderedRecipes, null, container, nonEmptyInputs);
            if (matched != null) return matched;
        }
        return matchFuzzy(nonEmptyInputs, view.customFuzzyIndices().get(customRecipeGroupId), groupRecipes);
    }

    private CookingPotRecipe matchDefaultRecipe(List<ResolvedRecipeInput> nonEmptyInputs, ItemStack container, Snapshot view) {
        Set<String> candidateRecipes = findCandidateRecipes(nonEmptyInputs, view.ingredientToRecipes());

        CookingPotRecipe matched = matchFirstRecipe(view.sortedRecipes(), candidateRecipes, container, nonEmptyInputs);
        if (matched != null) {
            return matched;
        }
        if (candidateRecipes != null) {
            matched = matchFirstRecipe(view.sortedRecipes(), null, container, nonEmptyInputs);
            if (matched != null) return matched;
        }
        return matchFuzzy(nonEmptyInputs, view.fuzzyIndex(), view.recipes());
    }

    private CookingPotRecipe matchFuzzy(List<ResolvedRecipeInput> inputs, FuzzyRecipeMatcher.Index index,
                                        Map<String, CookingPotRecipe> definitions) {
        if (index == null || index.isEmpty()) return null;
        Map<String, Integer> counts = new HashMap<>();
        for (ResolvedRecipeInput input : inputs) {
            if (input.stack().getAmount() > 0) counts.merge(input.identity(), 1, Integer::sum);
        }
        counts = Map.copyOf(counts);
        FuzzyRecipeMatcher.Match match = index.match(counts);
        if (match == null) return null;
        CookingPotRecipe source = definitions.get(match.id());
        return new CookingPotRecipe(source.id(), source.ingredients(), source.container(), source.needsContainer(),
                FuzzyDishFactory.create(source.result(), match), source.experience(), source.cookTime(), source.category(),
                source.priority(), source.fuzzy(), counts);
    }

    private Map<String, Integer> fuzzyCounts(List<ItemStack> inputs) {
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack input : inputs) {
            if (input != null && !input.getType().isAir() && input.getAmount() > 0) counts.merge(getItemKey(input), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private CookingPotRecipe matchFirstRecipe(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                              ItemStack container, List<ResolvedRecipeInput> nonEmptyInputs) {
        if (orderedRecipes == null || orderedRecipes.isEmpty()) {
            return null;
        }
        // Prefer a recipe that consumes exactly the filled slots; only when none does, allow a lenient match
        // (extra slots of an ingredient the recipe already uses), so exact recipes are never shadowed.
        // The C slot is a batch-output channel, not a cook gate: the recipe's required container is
        // checked at extraction time (storeCookedResult / tryMovePendingToOutput), never here.
        CookingPotRecipe exact = matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, true);
        if (exact != null) {
            return exact;
        }
        return matchPass(orderedRecipes, candidateRecipeIds, nonEmptyInputs, false);
    }

    private CookingPotRecipe matchPass(List<CookingPotRecipe> orderedRecipes, Set<String> candidateRecipeIds,
                                       List<ResolvedRecipeInput> nonEmptyInputs, boolean exactSlots) {
        for (CookingPotRecipe recipe : orderedRecipes) {
            if (recipe.isFuzzy()) continue;
            if (candidateRecipeIds != null && !candidateRecipeIds.contains(recipe.getId())) {
                continue;
            }
            // matchRecipePrefiltered skips the per-call ArrayList alloc that matchRecipe's defensive
            // filter does — caller (matchRecipe public) has already stripped nulls/airs into the list,
            // and matchPass runs this in a tight loop over every recipe in orderedRecipes.
            if (matchRecipePrefiltered(recipe, nonEmptyInputs, exactSlots)) {
                return recipe;
            }
        }
        return null;
    }

    private String buildCacheKey(List<ResolvedRecipeInput> inputs, ItemStack container, String customRecipeGroupId) {
        List<String> keys = new ArrayList<>();
        // Clamp the per-slot amount that goes into the key. IngredientMatching caps each slot at
        // min(amount, ingredientCount) interchangeable units, and only recipes with
        // ingredientCount <= inputs.size() can pass its slot-count gate, so any amount above
        // inputs.size() is indistinguishable to the matcher. Collapsing it keeps a hopper-fed pot
        // whose stacks keep growing on one cache entry instead of evicting the whole LRU each tick.
        int amountCap = inputs.size();
        for (ResolvedRecipeInput input : inputs) {
            keys.add(input.identity() + ":" + Math.min(input.stack().getAmount(), amountCap));
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(key).append(";");
        }
        sb.append("|container=").append(getItemKey(container));
        // Standalone tag-source APIs can publish without changing this manager's own snapshot.
        // The read scope pins this value, so late old readers keep separate bounded LRU entries.
        sb.append("|catalog=").append(RuntimeSnapshotPublication.generation());
        if (customRecipeGroupId != null) {
            sb.append("|group=").append(customRecipeGroupId);
        }
        return sb.toString();
    }

    private Set<String> findCandidateRecipes(List<ResolvedRecipeInput> inputs, Map<String, Set<String>> recipeIndex) {
        if (recipeIndex == null || recipeIndex.isEmpty()) {
            return null;
        }
        // Copy-on-write avoids per-call HashSet allocations when only one source set needs merging.
        // candidates / recipesForItem start as shared references to an unmodified index entry; we
        // allocate a real HashSet copy only when a second source forces a union or intersection.
        Set<String> candidates = null;
        boolean candidatesShared = false;

        for (ResolvedRecipeInput input : inputs) {
            ItemStack item = input.stack();
            Set<String> recipesForItem = null;
            boolean recipesForItemShared = false;

            // The material alias is only a broad index hint; actual Item predicates use the
            // authoritative identity below and cannot accept a custom item as vanilla.
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);
            for (int hint = 0; hint < 2; ++hint) {
                String itemId = hint == 0 ? input.identity() : vanillaId;
                if (itemId == null || (hint == 1 && itemId.equals(input.identity()))) continue;
                Set<String> indexed = recipeIndex.get(itemId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }
            for (String tagId : ItemUtils.getItemTagIds(item, input.identity())) {
                Set<String> indexed = recipeIndex.get("#" + tagId);
                if (indexed == null || indexed.isEmpty()) {
                    continue;
                }
                if (recipesForItem == null) {
                    recipesForItem = indexed;
                    recipesForItemShared = true;
                } else {
                    if (recipesForItemShared) {
                        recipesForItem = new HashSet<>(recipesForItem);
                        recipesForItemShared = false;
                    }
                    recipesForItem.addAll(indexed);
                }
            }

            for (String tagId : AdvancedRecipeTags.tagsForItemId(input.identity())) {
                Set<String> indexed = recipeIndex.get("advtag:" + tagId);
                if (indexed == null || indexed.isEmpty()) continue;
                if (recipesForItem == null) { recipesForItem = indexed; recipesForItemShared = true; }
                else {
                    if (recipesForItemShared) { recipesForItem = new HashSet<>(recipesForItem); recipesForItemShared = false; }
                    recipesForItem.addAll(indexed);
                }
            }

            if (recipesForItem != null) {
                if (candidates == null) {
                    candidates = recipesForItem;
                    candidatesShared = recipesForItemShared;
                } else {
                    if (candidatesShared) {
                        candidates = new HashSet<>(candidates);
                        candidatesShared = false;
                    }
                    candidates.retainAll(recipesForItem);
                }
            }
        }

        return candidates;
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs) {
        // Lenient acceptance: an exact-slot match also satisfies this, so canCraft uses it.
        return matchRecipe(recipe, inputs, false);
    }

    private boolean matchRecipe(CookingPotRecipe recipe, List<ItemStack> inputs, boolean exactSlots) {
        List<ResolvedRecipeInput> nonEmpty = ResolvedRecipeInput.resolve(inputs, this::getItemKey);
        return matchRecipePrefiltered(recipe, nonEmpty, exactSlots);
    }

    private boolean matchRecipePrefiltered(CookingPotRecipe recipe, List<ResolvedRecipeInput> nonEmptyInputs, boolean exactSlots) {
        // Exact matching gives each filled slot a budget of one unit so every slot matches one ingredient.
        // Using stack amounts here could satisfy several ingredients from one slot and leave another unused.
        // Lenient matching uses stack amounts to support ingredients spread across slots,
        // while separately requiring every filled slot to contain a usable ingredient.
        ToIntFunction<ResolvedRecipeInput> unitBudget = exactSlots ? slot -> 1 : slot -> slot.stack().getAmount();
        return IngredientMatching.matchesIngredients(
                recipe.getIngredients(), nonEmptyInputs, exactSlots,
                this::matchIngredient, unitBudget);
    }

    public boolean canCraft(CookingPotRecipe recipe, List<ItemStack> inputs) {
        if (recipe != null && recipe.isFuzzy()) {
            return recipe.matchedInputs() != null && recipe.matchedInputs().equals(fuzzyCounts(inputs));
        }
        return recipe != null && matchRecipe(recipe, inputs);
    }

    /** Validates and assigns one cook; the caller must hold its inventory lock until applying the plan. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public int[] prepareConsumption(CookingPotRecipe recipe, List<ItemStack> inputs) {
        if (recipe == null || inputs == null || recipe.isFuzzy() && recipe.matchedInputs() == null) return null;
        try (var scope = RuntimeSnapshotPublication.readScope()) {
            return consumptionAssignment(inputs, recipe.getIngredients(), recipe.isFuzzy(), recipe.matchedInputs(),
                    item -> item == null || item.getType().isAir() || recipe.isFuzzy() && item.getAmount() <= 0,
                    item -> new ResolvedRecipeInput(item, getItemKey(item)),
                    input -> input.stack().getAmount(), ResolvedRecipeInput::identity, this::matchIngredient);
        }
    }

    // Operation-local resolution preserves the existing lenient slot gate and the consumption preference:
    // use each filled slot once when possible, then allow stacked units for overlapping ingredients.
    // Returned indices refer to the original input list, including its empty slots. Nothing is cached across cooks.
    static <Input, Resolved, Ingredient> int[] consumptionAssignment(
            List<Input> inputs, List<Ingredient> required, boolean fuzzy, Map<String, Integer> expectedCounts,
            Predicate<Input> empty, Function<Input, Resolved> resolve, ToIntFunction<Resolved> amount,
            Function<Resolved, String> identity, BiPredicate<Resolved, Ingredient> matcher) {
        if (fuzzy && expectedCounts == null) return null;
        if (!fuzzy && required.size() == 1) {
            Ingredient ingredient = required.getFirst();
            int sourceSlot = -1;
            for (int slot = 0; slot < inputs.size(); slot++) {
                Input input = inputs.get(slot);
                if (empty.test(input)) continue;
                Resolved resolved = resolve.apply(input);
                // Even zero-amount non-air extras must be usable under the existing lenient slot rule.
                if (!matcher.test(resolved, ingredient)) return null;
                if (sourceSlot < 0 && amount.applyAsInt(resolved) > 0) sourceSlot = slot;
            }
            // A single ingredient has no overlapping assignment to solve: the normal unit preference
            // selects the first positive slot after every filled slot has passed the same predicate.
            return sourceSlot < 0 ? null : new int[]{sourceSlot};
        }
        List<Resolved> filled = new ArrayList<>(inputs.size());
        int[] originalSlots = new int[inputs.size()];
        for (int slot = 0; slot < inputs.size(); slot++) {
            Input input = inputs.get(slot);
            if (empty.test(input)) continue;
            originalSlots[filled.size()] = slot;
            filled.add(resolve.apply(input));
        }
        if (fuzzy) {
            Map<String, Integer> counts = new HashMap<>();
            int[] assignment = new int[filled.size()];
            int used = 0;
            for (int slot = 0; slot < filled.size(); slot++) {
                Resolved input = filled.get(slot);
                if (amount.applyAsInt(input) <= 0) continue;
                String key = identity.apply(input);
                if (key == null) return null;
                counts.merge(key, 1, Integer::sum);
                assignment[used++] = originalSlots[slot];
            }
            if (!expectedCounts.equals(counts)) return null;
            return used == assignment.length ? assignment : java.util.Arrays.copyOf(assignment, used);
        }
        if (filled.size() < required.size()) return null;
        for (Resolved input : filled) {
            boolean usable = false;
            for (Ingredient ingredient : required) {
                if (matcher.test(input, ingredient)) { usable = true; break; }
            }
            if (!usable) return null;
        }
        int[] assignment = IngredientMatching.assignIngredients(required, filled, matcher,
                input -> amount.applyAsInt(input) > 0 ? 1 : 0);
        if (assignment == null) assignment = IngredientMatching.assignIngredients(required, filled, matcher, amount);
        if (assignment == null) return null;
        for (int index = 0; index < assignment.length; index++) assignment[index] = originalSlots[assignment[index]];
        return assignment;
    }

    public boolean matchesIngredient(ItemStack item, RecipeIngredient ingredient) {
        return matchIngredient(item, ingredient);
    }

    private boolean matchIngredient(ItemStack item, RecipeIngredient ingredient) {
        if (item == null || item.getType().isAir()) return false;
        return matchIngredient(new ResolvedRecipeInput(item, getItemKey(item)), ingredient);
    }

    private boolean matchIngredient(ResolvedRecipeInput input, RecipeIngredient ingredient) {
        ItemStack item = input.stack();
        if (ingredient instanceof RecipeIngredient.AdvancedTag tag) return AdvancedRecipeTags.matchesItemId(input.identity(), tag.key());
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (!input.matches(itemIngredient.key().toString())) {
                return false;
            }
            if (itemIngredient.nbt() == null) {
                return true;
            }
            return RecipeItemCodec.matchesSnapshot(itemIngredient.nbt(), item);
        } else if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            for (RecipeIngredient option : choiceIngredient.options()) {
                if (matchIngredient(input, option)) {
                    return true;
                }
            }
            return false;
        } else if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            String vanillaId = ItemUtils.getVanillaMaterialItemId(item);

            for (Key excluded : tagIngredient.excludedItems()) {
                if (input.matches(excluded.toString())) return false;
            }

            Set<String> itemTags = ItemUtils.getItemTagIds(item, input.identity());
            if (itemTags.contains(tagIngredient.key().toString())) {
                for (Key excludedTag : tagIngredient.excludedTags()) {
                    if (itemTags.contains(excludedTag.toString())) {
                        return false;
                    }
                }
                return true;
            }

            Set<String> vanillaTags = getVanillaItemIdsByTag(tagIngredient.key());
            boolean matchesBase = vanillaId != null && (vanillaTags.contains(vanillaId)
                    || ItemUtils.matchesVanillaItemTag(item, tagIngredient.key(),
                    tagIngredient.excludedItems(), tagIngredient.excludedTags()));
            if (!matchesBase) {
                return false;
            }
            for (Key excludedTag : tagIngredient.excludedTags()) {
                boolean blocked = vanillaId != null && getVanillaItemIdsByTag(excludedTag).contains(vanillaId);
                if (blocked) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public Set<String> getVanillaItemIdsByTag(Key tagKey) {
        return vanillaItemIdsByTagCache.getIds(tagKey);
    }

    private String getItemKey(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "none";
        }

        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        // Distinct MMOItems items can share a base material; key on their identity so different
        // mmoitems:<TYPE>:<ID> items never collide in the recipe cache.
        String mmoId = MMOItemsCompat.getNonCraftEngineItemId(item);
        if (mmoId != null) {
            return mmoId;
        }
        return ItemUtils.getVanillaMaterialItemId(item);
    }

    public Map<String, CookingPotRecipe> getRecipes() {
        Snapshot view = currentSnapshot();
        return Collections.unmodifiableMap(view.recipes());
    }

    /** Local group contents for editing, without inheriting the default recipes. */
    public Map<String, Map<String, CookingPotRecipe>> getCustomRecipeGroups() {
        return currentSnapshot().customRecipes();
    }

    public List<CookingPotRecipe> getEditableRecipes(String customRecipeGroupId) {
        Snapshot view = currentSnapshot();
        String group = normalizeRecipeGroupId(customRecipeGroupId);
        return group == null ? view.sortedRecipes() : view.sortedCustomOnlyRecipes().getOrDefault(group, List.of());
    }

    public List<CookingPotRecipe> getAllRecipes() {
        Snapshot view = currentSnapshot();
        Map<String, CookingPotRecipe> defaultRecipes = view.recipes();
        Map<String, Map<String, CookingPotRecipe>> groupedRecipes = view.customRecipes();
        List<CookingPotRecipe> all = new ArrayList<>(defaultRecipes.values());
        for (Map<String, CookingPotRecipe> groupRecipes : groupedRecipes.values()) {
            all.addAll(groupRecipes.values());
        }
        return Collections.unmodifiableList(all);
    }

    public Map<String, CookingPotRecipe> getRecipes(String customRecipeGroupId) {
        Snapshot view = currentSnapshot();
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.recipes();
        }
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(normalizedGroupId);
        if (groupRecipes == null || groupRecipes.isEmpty()) {
            return view.recipes();
        }
        Map<String, CookingPotRecipe> merged = new LinkedHashMap<>(view.recipes());
        merged.putAll(groupRecipes);
        return Collections.unmodifiableMap(merged);
    }

    public List<CookingPotRecipe> getSortedRecipes(String customRecipeGroupId) {
        Snapshot view = currentSnapshot();
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.sortedRecipes();
        }
        return view.sortedCustomRecipes().getOrDefault(normalizedGroupId, view.sortedRecipes());
    }

    public Set<String> getValidContainerKeys() {
        Snapshot view = currentSnapshot();
        return view.validContainerKeys();
    }

    public int getRecipeCount() {
        Snapshot view = currentSnapshot();
        return view.recipes().size();
    }

    public int getExternalRecipeCount() {
        return externalRecipes.size();
    }

    /** Recipes that reached this manager through a CraftEngine pack section, not the plugin's own file. */
    public int getPackRecipeCount() {
        Snapshot view = currentSnapshot();
        return view.packRecipeCount();
    }

    public boolean isExternalRecipe(String id) {
        return id != null && externalRecipes.containsKey(id);
    }

    private static Set<String> externalOverrideIds(YamlConfiguration config, String station) {
        ConfigurationSection overrides = config.getConfigurationSection("external-overrides");
        return overrides == null ? Set.of() : Set.copyOf(overrides.getStringList(station));
    }

    public int getCustomRecipeCount() {
        Snapshot view = currentSnapshot();
        int count = 0;
        for (Map<String, CookingPotRecipe> groupRecipes : view.customRecipes().values()) {
            count += groupRecipes.size();
        }
        return count;
    }

    public CookingPotRecipe getRecipe(String id) {
        Snapshot view = currentSnapshot();
        return view.recipes().get(id);
    }

    public CookingPotRecipe getRecipe(String customRecipeGroupId, String id) {
        Snapshot view = currentSnapshot();
        String normalizedGroupId = normalizeRecipeGroupId(customRecipeGroupId);
        if (normalizedGroupId == null) {
            return view.recipes().get(id);
        }
        Map<String, CookingPotRecipe> groupRecipes = view.customRecipes().get(normalizedGroupId);
        CookingPotRecipe customRecipe = groupRecipes == null ? null : groupRecipes.get(id);
        return customRecipe != null ? customRecipe : view.recipes().get(id);
    }

    public void reload() {
        loadRecipes();
    }

    public void registerExternalRecipe(String id, List<String> ingredientSpecs, ItemStack container,
                                       ItemStack result, float experience, int cookTime, String category) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Recipe id is required");
        }
        if (ingredientSpecs == null || ingredientSpecs.isEmpty()) {
            throw new IllegalArgumentException("Recipe must have at least one ingredient");
        }
        if (result == null || result.getType().isAir()) {
            throw new IllegalArgumentException("Recipe must have a result");
        }
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (int i = 0; i < ingredientSpecs.size(); i++) {
            String spec = ingredientSpecs.get(i);
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("Invalid ingredient at ingredients[" + i + "]: empty");
            }
            ingredients.add(parseIngredient(spec));
        }
        boolean needsContainer = container != null && !container.getType().isAir();
        CookingPotRecipe recipe = new CookingPotRecipe(id, ingredients, needsContainer ? container : null,
                needsContainer, result, Math.max(0f, experience), Math.max(1, cookTime),
                category == null ? "misc" : category, 0);
        externalRecipes.put(id, recipe);
        scheduleExternalRepublish();
    }

    public void unregisterExternalRecipe(String id) {
        if (id != null && externalRecipes.remove(id) != null) {
            scheduleExternalRepublish();
        }
    }

    private void scheduleExternalRepublish() {
        if (!externalRepublishScheduled.compareAndSet(false, true)) return;
        try {
            plugin.scheduler().runLater(() -> {
                externalRepublishScheduled.set(false);
                plugin.reloadEditedRecipeFilesAsync().whenComplete((ignored, failure) -> {
                    if (failure != null && plugin.isEnabled()) plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "External recipe catalog publication failed; previous recipes remain active", failure);
                });
            }, 1L);
        } catch (RuntimeException stoppedScheduler) {
            externalRepublishScheduled.set(false);
            throw stoppedScheduler;
        }
    }
    
    public void clearCache() {
        Snapshot view = currentSnapshot();
        synchronized (view.recipeCache()) {
            view.recipeCache().clear();
            view.recipeMisses().clear();
        }
    }

    private String normalizeRecipeGroupId(String customRecipeGroupId) {
        if (customRecipeGroupId == null || customRecipeGroupId.isBlank()) {
            return null;
        }
        return customRecipeGroupId.trim();
    }
}
