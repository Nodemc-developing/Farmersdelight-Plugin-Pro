package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.compat.OtherDelightIds;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.pack.PackSection;
import com.huidu.farmersdelight.pack.PackSections;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpecialRecipeLoader {

    private static final String FILE_NAME = "recipes/special_recipes.yml";
    private static final String ROOT_KEY = "special_recipes";
    private static final Set<String> SUPPORTED_OTHER_DELIGHT_TYPES = Set.of(
            "cooking", "cutting", "fluid_filling", "fluid_emptying", "soaking");

    // Cards the CraftEngine pack layer registered, with the exact instance it registered, so a later pass can
    // replace or drop them again without touching entries an addon or the plugin file owns under the same id.
    private static final Map<String, SpecialRecipeInfo> PACK_REGISTERED = new LinkedHashMap<>();
    // Cards this loader registered from the operator's file and from the bundled defaults, tracked separately
    // so a reload can withdraw exactly the entries that vanished from their source and nothing else.
    private static final Map<String, SpecialRecipeInfo> FILE_REGISTERED = new LinkedHashMap<>();
    private static final Map<String, SpecialRecipeInfo> BACKFILL_REGISTERED = new LinkedHashMap<>();

    private SpecialRecipeLoader() {
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public static Runnable captureReloadRollback() {
        Map<String, SpecialRecipeInfo> packs = new LinkedHashMap<>(PACK_REGISTERED);
        Map<String, SpecialRecipeInfo> files = new LinkedHashMap<>(FILE_REGISTERED);
        Map<String, SpecialRecipeInfo> bundled = new LinkedHashMap<>(BACKFILL_REGISTERED);
        return () -> {
            PACK_REGISTERED.clear();
            PACK_REGISTERED.putAll(packs);
            FILE_REGISTERED.clear();
            FILE_REGISTERED.putAll(files);
            BACKFILL_REGISTERED.clear();
            BACKFILL_REGISTERED.putAll(bundled);
        };
    }

    public static void load(FarmersDelightPlugin plugin, SpecialRecipeRegistry registry) {
        Map<String, SpecialRecipeInfo> fileEntries = new LinkedHashMap<>();
        YamlConfiguration config = loadConfig(plugin);
        if (config == null) return;
        if (config != null) {
            parseInto(fileEntries, config, FILE_NAME);
            if (config.getConfigurationSection(ROOT_KEY) == null) {
                I18n.logWarning("recipe.special_recipe_missing_root", "file", FILE_NAME);
            }
        }
        // Deleting a card from the file has to disable it, so entries this loader registered are withdrawn as
        // soon as the file stops defining them (a later addon registration under the same id is left alone).
        withdrawVanished(registry, FILE_REGISTERED, fileEntries);
        for (SpecialRecipeInfo info : fileEntries.values()) {
            registry.register(info);
        }
        FILE_REGISTERED.clear();
        FILE_REGISTERED.putAll(fileEntries);
        I18n.logDetail("recipe", "recipe.special_recipe_loaded", "count", fileEntries.size());

        // Backfill bundled recipes the on-disk file does not define, so servers with an older file still gain
        // newly bundled entries. Gated by the same switch as the other recipe files: an entry the operator
        // deliberately deleted from the file must not come back on every reload.
        YamlConfiguration bundled = loadBundled(plugin);
        boolean backfillBundled = bundled != null && ConfigSectionReader.optionalBoolean(
                plugin.getConfig(), RecipeFileLoader.MERGE_MISSING_SETTING, false);
        if (backfillBundled) {
            Map<String, SpecialRecipeInfo> bundledEntries = new LinkedHashMap<>();
            parseInto(bundledEntries, bundled, FILE_NAME);
            withdrawVanished(registry, BACKFILL_REGISTERED, bundledEntries);
            BACKFILL_REGISTERED.clear();
            int backfilled = 0;
            for (Map.Entry<String, SpecialRecipeInfo> entry : bundledEntries.entrySet()) {
                if (registry.get(entry.getKey()) == null) {
                    registry.register(entry.getValue());
                    BACKFILL_REGISTERED.put(entry.getKey(), entry.getValue());
                    backfilled++;
                }
            }
            if (backfilled > 0) {
                I18n.logDetail("recipe", "recipe.special_recipe_backfilled", "count", backfilled);
            }
        } else {
            withdrawVanished(registry, BACKFILL_REGISTERED, Map.of());
            BACKFILL_REGISTERED.clear();
        }
        loadPackSections(plugin, registry);
    }

    /** Withdraws entries this loader registered whose source no longer defines them, leaving replaced ids alone. */
    private static void withdrawVanished(SpecialRecipeRegistry registry,
                                        Map<String, SpecialRecipeInfo> registered,
                                        Map<String, SpecialRecipeInfo> current) {
        for (Map.Entry<String, SpecialRecipeInfo> previous : registered.entrySet()) {
            if (!current.containsKey(previous.getKey())
                    && registry.get(previous.getKey()) == previous.getValue()) {
                registry.unregister(previous.getKey());
            }
        }
    }

    /**
     * Applies the cards a CraftEngine pack declares under special_recipes. They lose to everything already
     * registered (the plugin file, the bundled defaults, addon registrations), so only ids the pack layer
     * itself owns are refreshed or removed when a pack changes; see PackSections.
     */
    private static void loadPackSections(FarmersDelightPlugin plugin, SpecialRecipeRegistry registry) {
        Map<String, SpecialRecipeInfo> current = new LinkedHashMap<>();
        for (PackSections.Section section : RecipePackFiles.sections(plugin, PackSection.SPECIAL_RECIPE)) {
            parseInto(current, section.yaml(), section.source());
        }
        for (PackSections.Section section : RecipePackFiles.sections(plugin, PackSection.OTHER_DELIGHT_RECIPES)) {
            ConfigurationSection root = section.yaml().getConfigurationSection(PackSection.OTHER_DELIGHT_RECIPES.rootKey());
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                ConfigurationSection body = root.getConfigurationSection(id);
                if (body == null) {
                    I18n.logWarning("recipe.special_recipe_parse_failed", "id", id,
                            "error", section.source() + ": expected a recipe mapping");
                    continue;
                }
                String type = body.getString("type", "");
                if ("info".equals(type)) {
                    try { current.putIfAbsent(id, parseOtherDelightInfo(id, body)); }
                    catch (IllegalArgumentException invalid) {
                        I18n.logWarning("recipe.special_recipe_parse_failed", "id", id,
                                "error", section.source() + ": " + invalid.getMessage());
                    }
                } else if (!SUPPORTED_OTHER_DELIGHT_TYPES.contains(type)) {
                    I18n.logWarning(OtherDelightIds.UNSUPPORTED_RECIPE_MESSAGE, "type", type, "id", id, "file", section.source());
                }
            }
        }

        for (Map.Entry<String, SpecialRecipeInfo> previous : PACK_REGISTERED.entrySet()) {
            if (!current.containsKey(previous.getKey()) && registry.get(previous.getKey()) == previous.getValue()) {
                registry.unregister(previous.getKey());
            }
        }
        int applied = 0;
        for (Map.Entry<String, SpecialRecipeInfo> entry : current.entrySet()) {
            SpecialRecipeInfo existing = registry.get(entry.getKey());
            if (existing == null || existing == PACK_REGISTERED.get(entry.getKey())) {
                registry.register(entry.getValue());
                applied++;
            }
        }
        PACK_REGISTERED.clear();
        PACK_REGISTERED.putAll(current);
        if (applied > 0) {
            I18n.logDetail("recipe", "recipe.special_recipe_pack_loaded", "count", applied);
        }
    }

    /** Parses one config's cards into {@code target} without registering them, reporting per-entry issues. */
    private static void parseInto(Map<String, SpecialRecipeInfo> target,
                                  YamlConfiguration config, String source) {
        ConfigurationSection root = config == null ? null : config.getConfigurationSection(ROOT_KEY);
        if (root == null) {
            return;
        }
        for (String recipeId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(recipeId);
            if (section == null) {
                continue;
            }
            try {
                target.put(recipeId, parseRecipe(recipeId, section));
            } catch (Exception e) {
                I18n.logWarning("recipe.special_recipe_parse_failed", "id", recipeId, "error", e.getMessage());
            }
        }
    }

    private static YamlConfiguration loadConfig(FarmersDelightPlugin plugin) {
        return RecipeFileLoader.loadRecipeFile(plugin, FILE_NAME);
    }

    public static SpecialRecipeInfo parseOtherDelightInfo(String id, ConfigurationSection section) {
        Object item = section.get("item");
        if (!(item instanceof String text) || text.isBlank()) throw new IllegalArgumentException("info.item must name an item");
        Object description = section.get("description");
        List<String> lines = new ArrayList<>();
        if (description != null) {
            if (!(description instanceof List<?> list)) throw new IllegalArgumentException("info.description must be a list");
            for (Object line : list) {
                if (!(line instanceof String textLine)) throw new IllegalArgumentException("info.description entries must be text");
                lines.add(textLine);
            }
        }
        return new SpecialRecipeInfo(id, "", text, lines, List.of(), List.of(), false, false, false,
                List.of(), SpecialRecipeInfo.DISPLAY_OTHER_DELIGHT_INFO);
    }

    private static YamlConfiguration loadBundled(FarmersDelightPlugin plugin) {
        try (Reader reader = new BufferedReader(
                new InputStreamReader(plugin.getResource(FILE_NAME), StandardCharsets.UTF_8), 8192)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("recipe.special_recipe_load_failed",
                    "file", FILE_NAME, "error", e.getMessage());
            return null;
        }
    }

    /** Parses one special-recipe config entry into a SpecialRecipeInfo; exposed so addons can drive their
     *  special recipes from a config file exactly like their other recipes. */
    public static SpecialRecipeInfo parseRecipe(String id, ConfigurationSection section) {
        String titleKey = ConfigSectionReader.optionalString(section, "title", "gui.special_recipe." + id + ".title");
        String iconItemId = ConfigSectionReader.optionalString(section, "icon", "minecraft:barrier");
        List<String> descriptionKeys = ConfigSectionReader.optionalStringList(section, "description");
        String displayType = ConfigSectionReader.optionalString(section, "display-type", SpecialRecipeInfo.DISPLAY_RECIPE);

        List<SpecialRecipeInfo.SlotEntry> inputSlots = parseSlotEntries(section, "inputs");
        List<SpecialRecipeInfo.SlotEntry> outputSlots = parseSlotEntries(section, "outputs");

        // Conditions: sunlight, water, catalyst_info, catalysts
        ConfigurationSection conditions = section.getConfigurationSection("conditions");
        boolean hasSunlight = false;
        boolean hasWater = false;
        boolean hasCatalystInfo = false;
        List<SpecialRecipeInfo.SlotEntry> catalystSlots = List.of();

        if (conditions != null) {
            hasSunlight = ConfigSectionReader.optionalBoolean(conditions, "sunlight", false);
            hasWater = ConfigSectionReader.optionalBoolean(conditions, "water", false);
            hasCatalystInfo = ConfigSectionReader.optionalBoolean(conditions, "catalyst_info", false);
        }

        // Top-level catalysts list (for recipes without a conditions block)
        catalystSlots = parseSlotEntries(section, "catalysts");
        if (catalystSlots.isEmpty() && conditions != null) {
            catalystSlots = parseSlotEntries(conditions, "catalysts");
        }

        return new SpecialRecipeInfo(id, titleKey, iconItemId,
                descriptionKeys, inputSlots, outputSlots,
                hasSunlight, hasWater, hasCatalystInfo, catalystSlots, displayType);
    }

    private static List<SpecialRecipeInfo.SlotEntry> parseSlotEntries(ConfigurationSection parent, String key) {
        List<SpecialRecipeInfo.SlotEntry> entries = new ArrayList<>();
        // Bukkit hands a YAML list-of-maps back as Map elements, not ConfigurationSection, so read it as
        // a map list and wrap each entry in a section before parsing its fields.
        for (Map<?, ?> raw : parent.getMapList(key)) {
            SpecialRecipeInfo.SlotEntry entry = parseSlotEntry(new MemoryConfiguration().createSection("entry", raw));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static SpecialRecipeInfo.SlotEntry parseSlotEntry(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) return null;
        return parseSlotEntry(section);
    }

    private static SpecialRecipeInfo.SlotEntry parseSlotEntry(ConfigurationSection section) {
        String itemId = ConfigSectionReader.optionalString(section, "item");
        // Behavior-list reference: "behavior: <block id>" + "list: <config key>" (e.g. the
        // organic_compost behavior's "activators"). Resolved lazily at display time.
        String behaviorBlockId = ConfigSectionReader.optionalString(section, "behavior");
        String behaviorListKey = ConfigSectionReader.optionalString(section, "list");
        if (itemId == null && behaviorBlockId == null) return null;
        String nameKey = ConfigSectionReader.optionalString(section, "name", "");
        List<String> loreKeys = ConfigSectionReader.optionalStringList(section, "lore");
        return new SpecialRecipeInfo.SlotEntry(itemId, behaviorBlockId, behaviorListKey, nameKey, loreKeys);
    }
}
