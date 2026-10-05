package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.recipe.NativeRecipeSchema;
import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;

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
    private final FarmersDelightPlugin plugin;
    private volatile State state = new State(Map.of(), Set.of());

    public FluidRecipeFiles(FarmersDelightPlugin plugin) { this.plugin = plugin; }

    private State currentState() { return RuntimeSnapshotPublication.get(this, state); }
    private void setState(State next) { RuntimeSnapshotPublication.publish(this, state, next); state = next; }

    Runnable captureReloadRollback() {
        State previous = currentState();
        return () -> setState(previous);
    }

    /** Parses captured pack documents; this method does not read files or mutate live inventories. */
    public List<Entry> load() {
        return loadSections(RecipePackFiles.sections(plugin, PackSection.NATIVE_RECIPES));
    }

    List<Entry> loadSections(List<PackSections.Section> sections) {
        Map<String, Entry> loaded = new LinkedHashMap<>();
        Set<String> occupied = new LinkedHashSet<>();
        for (var section : sections) {
            ConfigurationSection root = section.yaml().getConfigurationSection(NativeRecipeSchema.ROOT);
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                // Every node owns its ID, including other recipe kinds and currently invalid definitions.
                occupied.add(id);
                ConfigurationSection body = root.getConfigurationSection(id);
                if (body == null) {
                    RecipeFileLoader.reportProblem(section.source(), id, "Expected a recipe section");
                    continue;
                }
                if (!"fluid_tank".equals(body.getString("station", ""))) continue;
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
        setState(new State(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(loaded)), Set.copyOf(occupied)));
        return List.copyOf(loaded.values());
    }

    public CompletableFuture<Boolean> saveAsync(FluidRecipeSpec recipe) {
        State current = currentState();
        Entry previous = current.sources().get(recipe.id());
        if (previous == null && current.occupiedIds().contains(recipe.id())) {
            return CompletableFuture.completedFuture(false);
        }
        RecipeSource source = previous == null ? new RecipeSource(defaultFile(), List.of(NativeRecipeSchema.EDITOR_ROOT, recipe.id()), true)
                : previous.source();
        // Ingredient and NBT serialization is captured on the caller's owning thread before worker I/O.
        Map<String, Object> captured;
        try { captured = body(recipe); }
        catch (RuntimeException | LinkageError error) { return CompletableFuture.failedFuture(error); }
        return persist(source, captured);
    }

    public CompletableFuture<Boolean> deleteAsync(String id) {
        State current = currentState();
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

    private static void preserveUnplacedMetadata(Map<String, Object> result, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> unplaced = new LinkedHashMap<>();
        before.forEach((path, value) -> { if (!java.util.Objects.equals(value, after.get(path))) unplaced.put(path, value); });
        if (unplaced.isEmpty()) return;
        Object original = result.get("extensions");
        if (original instanceof ConfigurationSection section) original = section.getValues(false);
        Map<String, Object> extensions = new LinkedHashMap<>();
        if (original instanceof Map<?, ?> map) map.forEach((key, value) -> extensions.put(String.valueOf(key), FluidExpression.immutable(value)));
        else if (original != null) extensions.put("original_extensions", FluidExpression.immutable(original));
        Map<String, Object> saved = new LinkedHashMap<>();
        mapping(extensions.get("saved_fields")).forEach(saved::put);
        unplaced.forEach(saved::putIfAbsent); extensions.put("saved_fields", saved); result.put("extensions", extensions);
    }
    private static void metadata(Object value, String path, String kind, Map<String, Object> found) {
        if (value instanceof ConfigurationSection section) value = section.getValues(false);
        if (value instanceof List<?> list) { for (int i = 0; i < list.size(); i++) metadata(list.get(i), path + "/" + i, kind, found); return; }
        if (!(value instanceof Map<?, ?> map)) return;
        List<String> managed = kind.equals("fluid") ? List.of("id", "tag", "fluid", "amount", "any-of", "components", "exact-components")
                : kind.equals("components") ? List.of() : List.of("id", "item", "count", "nbt", "items", "choice", "components");
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()); String child = path + "/" + key;
            if ((kind.equals("components") && (key.startsWith("x-") || key.equals("extensions"))) || (!kind.equals("components") && !managed.contains(key))) found.put(child, FluidExpression.immutable(entry.getValue()));
            else if (key.equals("components")) metadata(entry.getValue(), child, "components", found);
            else if (key.equals("items") || key.equals("choice") || key.equals("any-of")) metadata(entry.getValue(), child, kind, found);
        }
    }

    static void write(RecipeSource source, Map<String, Object> replacement) throws Exception {
        YamlFileTransactions.execute(source.file(), () -> {
            YamlConfiguration document = Files.isRegularFile(source.file()) ? PlainYamlDocuments.readLiteral(source.file()) : new YamlConfiguration();
            document.options().pathSeparator('\u0001');
            Map<String, Object> previous = source.body(document);
            if (!source.existingNode() && !previous.isEmpty()) {
                throw new IllegalStateException("A recipe with this ID already exists at the destination");
            }
            if (source.existingNode() && !previous.isEmpty() && !"fluid_tank".equals(previous.get("station"))) {
                throw new IllegalStateException("The recipe station changed before the fluid edit was saved");
            }
            source.put(document, replacement == null ? null : merge(previous, replacement));
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
        Object expression = recipe.fluidExpression();
        if (expression instanceof Map<?, ?> raw) {
            Map<String, Object> changed = new LinkedHashMap<>();
            raw.forEach((key, value) -> changed.put(String.valueOf(key), value));
            // The canonical amount is edited once, never shadowed by an old nested amount.
            changed.remove("amount");
            expression = changed;
        }
        body.put("fluid", expression);
        body.put("amount", recipe.amount());
        if (recipe.timeTicks() > 0 || recipe.type().equals("soaking")) body.put("time", recipe.timeTicks());
        if (!recipe.consumeFluid()) body.put("consume_fluid", false);
        if (recipe.priority() != 0) body.put("priority", recipe.priority());
        return NativeRecipeSchema.formatFluid(body);
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
        return mergeNative(previous, edited);
    }

    private static Map<String, Object> mergeNative(Map<String, Object> previous, Map<String, Object> edited) {
        Map<String, Object> result = new LinkedHashMap<>(previous);
        for (String key : List.of("station", "operation", "input", "output", "fluid", "process", "priority",
                "type", "empty_input", "filled_input", "ingredient", "filled_result", "empty_result", "result",
                "amount", "time", "consume_fluid")) result.remove(key);
        result.putAll(edited);
        if (edited.containsKey("extensions")) result.put("extensions", preserveExtensionValue(previous.get("extensions"), edited.get("extensions")));
        Map<String, Object> oldInput = mapping(previous.get("input")), newInput = mapping(edited.get("input"));
        if (edited.containsKey("input")) {
            Map<String, Object> input = preserveFields(oldInput, newInput, List.of("item"));
            if (newInput.containsKey("item")) input.put("item", preserveNested(oldInput.get("item"), newInput.get("item"), false));
            result.put("input", input);
        }
        if (edited.containsKey("output")) result.put("output", preserveNested(previous.get("output"), edited.get("output"), true));
        Map<String, Object> oldFluid = mapping(previous.get("fluid")), newFluid = mapping(edited.get("fluid"));
        if (edited.containsKey("fluid")) {
            Map<String, Object> fluid = preserveFields(oldFluid, newFluid, List.of("match", "amount-mb", "consume"));
            if (newFluid.containsKey("match")) fluid.put("match", preserveFluidMetadata(oldFluid.get("match"), newFluid.get("match")));
            result.put("fluid", fluid);
        }
        Map<String, Object> process = preserveFields(mapping(previous.get("process")), mapping(edited.get("process")), List.of("ticks"));
        if (edited.containsKey("process") || !process.isEmpty()) result.put("process", process);
        retainNativeMetadata(previous, result);
        return result;
    }

    private static Map<String, Object> preserveFields(Map<String, Object> previous, Map<String, Object> edited, List<String> managed) {
        Map<String, Object> result = new LinkedHashMap<>();
        previous.forEach((key, value) -> { if (!managed.contains(key)) result.put(key, FluidExpression.immutable(value)); });
        edited.forEach((key, value) -> result.put(key, managed.contains(key) ? FluidExpression.immutable(value)
                : preserveExtensionValue(previous.get(key), value)));
        return result;
    }

    private static Map<String, Object> mapping(Object value) {
        if (value instanceof ConfigurationSection section) value = section.getValues(false);
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, nested) -> result.put(String.valueOf(key), nested));
        return result;
    }

    private static Object preserveExtensionValue(Object previous, Object edited) {
        if (previous instanceof ConfigurationSection section) previous = section.getValues(false);
        if (edited instanceof ConfigurationSection section) edited = section.getValues(false);
        if (previous instanceof Map<?, ?> old && edited instanceof Map<?, ?> update) {
            Map<String, Object> result = new LinkedHashMap<>();
            old.forEach((key, value) -> result.put(String.valueOf(key), FluidExpression.immutable(value)));
            update.forEach((key, value) -> result.put(String.valueOf(key), preserveExtensionValue(old.get(key), value)));
            return result;
        }
        return FluidExpression.immutable(edited);
    }

    private static Object preserveFluidMetadata(Object previous, Object edited) {
        Map<String, Object> extensions = mapping(previous);
        for (String key : List.of("id", "tag", "fluid", "any-of", "components", "exact-components", "amount")) extensions.remove(key);
        if (extensions.isEmpty()) return FluidExpression.immutable(edited);
        if (edited instanceof String identity) {
            extensions.put(identity.startsWith("#") ? "tag" : "id", identity.startsWith("#") ? identity.substring(1) : identity);
            return extensions;
        }
        if (edited instanceof ConfigurationSection || edited instanceof Map<?, ?>) {
            extensions.putAll(mapping(edited));
            return extensions;
        }
        return FluidExpression.immutable(edited);
    }

    private static void retainNativeMetadata(Map<String, Object> previous, Map<String, Object> result) {
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        for (Map<String, Object> body : List.of(previous, result)) {
            Map<String, Object> fields = body == previous ? before : after;
            metadata(mapping(body.get("input")).get("item"), "input/item", "item", fields);
            metadata(body.get("output"), "output", "item", fields);
            metadata(mapping(body.get("fluid")).get("match"), "fluid/match", "fluid", fields);
        }
        preserveUnplacedMetadata(result, before, after);
    }

    private static Object preserveNested(Object previous, Object edited, boolean output) {
        if (previous instanceof ConfigurationSection section) previous = section.getValues(false);
        if (edited instanceof ConfigurationSection section) edited = section.getValues(false);
        if (previous instanceof Map<?, ?> old && !(edited instanceof Map<?, ?>) && edited instanceof String id) {
            edited = Map.of(output ? "id" : "item", id);
        }
        if (previous instanceof Map<?, ?> old && edited instanceof Map<?, ?> update) {
            Map<String, Object> merged = new LinkedHashMap<>();
            old.forEach((key, value) -> merged.put(String.valueOf(key), FluidExpression.immutable(value)));
            for (String key : List.of("item", "id", "nbt", "count", "items", "choice", "components")) merged.remove(key);
            for (var entry : update.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (key.equals("components")) {
                    Map<String, Object> components = new LinkedHashMap<>();
                    if (old.get(key) instanceof Map<?, ?> values) values.forEach((component, value) -> {
                        String name = String.valueOf(component);
                        if (name.startsWith("x-") || name.equals("extensions")) components.put(name, FluidExpression.immutable(value));
                    });
                    if (entry.getValue() instanceof Map<?, ?> values) values.forEach((component, value) -> {
                        String name = String.valueOf(component);
                        components.put(name, name.startsWith("x-") || name.equals("extensions")
                                ? preserveExtensionValue(components.get(name), value) : FluidExpression.immutable(value));
                    });
                    merged.put(key, components);
                } else merged.put(key, key.startsWith("x-") || key.equals("extensions")
                        ? preserveExtensionValue(old.get(key), entry.getValue())
                        : preserveNested(old.get(key), entry.getValue(), output));
            }
            return merged;
        }
        if (previous instanceof List<?> old && edited instanceof List<?> update) {
            List<Object> merged = new java.util.ArrayList<>(update.size());
            for (int index = 0; index < update.size(); index++) merged.add(preserveNested(index < old.size() ? old.get(index) : null, update.get(index), output));
            return merged;
        }
        return FluidExpression.immutable(edited);
    }
}
