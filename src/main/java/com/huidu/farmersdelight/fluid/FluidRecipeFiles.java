package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.config.PlainYamlDocuments;
import com.huidu.farmersdelight.config.YamlFileTransactions;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import com.huidu.farmersdelight.recipe.RecipeFileLoader;
import com.huidu.farmersdelight.recipe.RecipePackFiles;
import com.huidu.farmersdelight.recipe.RecipeSerializer;
import com.huidu.farmersdelight.recipe.RecipeSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Source-aware fluid recipe edits use the same bounded worker and publication batch as cooking recipes. */
public final class FluidRecipeFiles {
    public record Entry(FluidRecipeSpec recipe, RecipeSource source) { }
    private record State(Map<String, Entry> sources, Set<String> occupiedIds) { }
    private static final Set<String> TYPES = Set.of("fluid_filling", "fluid_emptying", "soaking");
    private final FarmersDelightPlugin plugin;
    private volatile State state = new State(Map.of(), Set.of());

    public FluidRecipeFiles(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    /** Parses captured pack documents; this method does not read files or mutate live inventories. */
    public List<Entry> load() {
        return loadSections(RecipePackFiles.sections(plugin, PackSection.PAPERS_RECIPES));
    }

    List<Entry> loadSections(List<PackSections.Section> sections) {
        Map<String, Entry> loaded = new LinkedHashMap<>();
        Set<String> occupied = new LinkedHashSet<>();
        for (var section : sections) {
            ConfigurationSection root = section.yaml().getConfigurationSection("papersdelight_recipes");
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                // Every node owns its ID, including other recipe kinds and currently invalid definitions.
                occupied.add(id);
                ConfigurationSection body = root.getConfigurationSection(id);
                if (body == null) {
                    RecipeFileLoader.reportProblem(section.source(), id, "Expected a recipe section");
                    continue;
                }
                if (!TYPES.contains(body.getString("type", ""))) continue;
                try {
                    FluidRecipeSpec recipe = FluidRecipeSpec.parse(id, body);
                    if (loaded.containsKey(id)) {
                        RecipeFileLoader.reportProblem(section.source(), id,
                                "Duplicate fluid recipe ID; the first definition is retained");
                        continue;
                    }
                    if (section.file() == null) throw new IllegalArgumentException("The content-pack source file is unavailable");
                    loaded.put(id, new Entry(recipe, new RecipeSource(section.file(), List.of(section.sectionKey(), id), true, true)));
                } catch (IllegalArgumentException invalid) {
                    RecipeFileLoader.reportProblem(section.source(), id, invalid.getMessage());
                }
            }
        }
        state = new State(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(loaded)), Set.copyOf(occupied));
        return List.copyOf(loaded.values());
    }

    public CompletableFuture<Boolean> saveAsync(FluidRecipeSpec recipe) {
        State current = state;
        Entry previous = current.sources().get(recipe.id());
        if (previous == null && current.occupiedIds().contains(recipe.id())) {
            return CompletableFuture.completedFuture(false);
        }
        RecipeSource source = previous == null ? new RecipeSource(defaultFile(), List.of("papersdelight_recipes#fd_pro", recipe.id()), true)
                : previous.source();
        // Ingredient and NBT serialization is captured on the caller's owning thread before worker I/O.
        Map<String, Object> captured;
        try { captured = body(recipe); }
        catch (RuntimeException | LinkageError error) { return CompletableFuture.failedFuture(error); }
        return persist(source, captured);
    }

    public CompletableFuture<Boolean> deleteAsync(String id) {
        State current = state;
        Entry previous = current.sources().get(id);
        return previous == null ? CompletableFuture.completedFuture(false) : persist(previous.source(), null);
    }

    public Path defaultFile() { return RecipePackFiles.configurationFolder(plugin).resolve("recipes/fluid_recipes.yml"); }

    private CompletableFuture<Boolean> persist(RecipeSource source, Map<String, Object> replacement) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!plugin.scheduler().tryRunAsync(() -> {
            try {
                write(source, replacement);
                if (!plugin.isEnabled()) { result.complete(true); return; }
                plugin.reloadEditedRecipeFilesAsync().whenComplete((ignored, error) -> {
                    if (error == null) result.complete(true); else result.completeExceptionally(error);
                });
            } catch (Exception | LinkageError error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not save fluid recipe at " + source.file(), error);
                result.complete(false);
            }
        })) result.complete(false);
        return result;
    }

    static void write(RecipeSource source, Map<String, Object> replacement) throws Exception {
        YamlFileTransactions.execute(source.file(), () -> {
            YamlConfiguration document = Files.isRegularFile(source.file()) ? PlainYamlDocuments.readLiteral(source.file()) : new YamlConfiguration();
            document.options().pathSeparator('\u0001');
            if (!source.existingNode() && !source.body(document).isEmpty()) {
                throw new IllegalStateException("A recipe with this ID already exists at the destination");
            }
            source.put(document, replacement == null ? null : merge(source.body(document), replacement));
            ConfigFileUpdater.writeStringAtomically(source.file(), document.saveToString(), true);
            return null;
        });
    }

    static Map<String, Object> body(FluidRecipeSpec recipe) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", recipe.type());
        String input = recipe.type().equals("fluid_filling") ? "empty_input" : recipe.type().equals("fluid_emptying") ? "filled_input" : "ingredient";
        String output = recipe.type().equals("fluid_filling") ? "filled_result" : recipe.type().equals("fluid_emptying") ? "empty_result" : "result";
        body.put(input, ingredient(RecipeSerializer.serializeIngredientValue(recipe.ingredient())));
        if (!recipe.result().isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>(recipe.result());
            Object item = result.remove("item");
            if (item != null) result.put("id", item);
            body.put(output, result);
        }
        body.put("fluid", (recipe.fluidTag() ? "#" : "") + recipe.fluidId());
        body.put("amount", recipe.amount());
        if (recipe.timeTicks() > 0 || recipe.type().equals("soaking")) body.put("time", recipe.timeTicks());
        if (!recipe.consumeFluid()) body.put("consume_fluid", false);
        if (recipe.priority() != 0) body.put("priority", recipe.priority());
        return body;
    }

    private static Object ingredient(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return value;
        Map<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, nested) -> mapped.put(String.valueOf(key), nested));
        Object choice = mapped.remove("choice");
        if (choice instanceof List<?> values) mapped.put("items", values.stream().map(FluidRecipeFiles::ingredient).toList());
        return mapped;
    }

    static Map<String, Object> merge(Map<String, Object> previous, Map<String, Object> edited) {
        Map<String, Object> result = new LinkedHashMap<>(previous);
        Object oldFluid = result.get("fluid");
        for (String key : List.of("type", "empty_input", "filled_input", "ingredient", "filled_result", "empty_result", "result",
                "fluid", "amount", "time", "consume_fluid", "priority")) result.remove(key);
        result.putAll(edited);
        Object newFluid = edited.get("fluid");
        if (oldFluid instanceof ConfigurationSection section) oldFluid = section.getValues(false);
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (oldFluid instanceof Map<?, ?> raw) raw.forEach((key, value) -> extensions.put(String.valueOf(key), value));
        for (String key : List.of("id", "tag", "fluid", "amount")) extensions.remove(key);
        if (!extensions.isEmpty() && newFluid instanceof String identity) {
            // Keep extension options without duplicating the canonical top-level amount.
            extensions.put(identity.startsWith("#") ? "tag" : "id", identity.startsWith("#") ? identity.substring(1) : identity);
            result.put("fluid", extensions);
        } else if (newFluid instanceof Map<?, ?> incoming) {
            incoming.forEach((key, value) -> extensions.put(String.valueOf(key), value));
            result.put("fluid", extensions);
        }
        return result;
    }
}
