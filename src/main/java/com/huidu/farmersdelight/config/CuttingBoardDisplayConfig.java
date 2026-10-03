package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CuttingBoardDisplayConfig {

    private static final float DEFAULT_ITEM_SPREAD = 0.15F;
    private static final Vector3f ZERO_OFFSET = new Vector3f(0.0F, 0.0F, 0.0F);
    private static final String DISPLAY_OVERRIDES_FILE = "display-overrides.yml";

    private final Map<String, DisplayOverride> itemOverrides = new ConcurrentHashMap<>();
    // tagOverrides keeps LinkedHashMap for definition-order iteration (first-match override semantics);
    // mutations only happen on reload from the main thread, reads from event handlers — wrap with
    // synchronized to make those reload-vs-read transitions atomic without losing ordering.
    private final Map<String, DisplayOverride> tagOverrides = Collections.synchronizedMap(new LinkedHashMap<>());
    private final DisplayOverride fallbackDefaults;
    private final float fallbackItemSpread;
    // Reload writes, event handlers read on Folia region threads — volatile publishes the new values.
    private volatile DisplayOverride defaultOverride;
    private volatile float itemSpread;
    private volatile DisplayOverride blockOverride;
    private volatile float blockSpread;
    private volatile float stackYOffset;
    private volatile float blockStackYOffset;
    private volatile boolean absoluteItemPosition;
    private volatile boolean absoluteBlockPosition;
    private volatile List<String> forceBlock = List.of();
    private volatile List<String> forceItem = List.of();

    public CuttingBoardDisplayConfig() {
        this(DisplayOverride.empty(), DEFAULT_ITEM_SPREAD);
    }

    public CuttingBoardDisplayConfig(DisplayOverride fallbackDefaults, float fallbackItemSpread) {
        this.fallbackDefaults = fallbackDefaults == null ? DisplayOverride.empty() : fallbackDefaults.copy();
        this.fallbackItemSpread = Math.max(0.0F, fallbackItemSpread);
        reset();
    }

    public void loadFromConfig(ConfigurationSection section) {
        reset();
        if (section == null) {
            return;
        }

        defaultOverride = defaultOverride.withConfiguredDefaults(DisplayOverride.fromDefaultConfig(section));
        itemSpread = Math.max(0.0F, (float) section.getDouble(
                "display-item-spread",
                section.getDouble("item-spread", fallbackItemSpread)
        ));
        ConfigurationSection station = section.getName().equals("display") ? section.getParent() : section;
        ConfigurationSection itemDisplay = section.getName().equals("display")
                ? section : section.getConfigurationSection("display");
        if (itemDisplay != null) {
            defaultOverride = defaultOverride.withConfiguredDefaults(publicDisplay(itemDisplay, DisplayStyle.AUTO));
            itemSpread = spread(itemDisplay, itemSpread);
            stackYOffset = nonnegative(itemDisplay, "stack-y-offset", "stack_y_offset", 0.03F);
            absoluteItemPosition = hasPosition(itemDisplay);
        }
        ConfigurationSection blockDisplay = ConfigLookup.firstSection(station, "display-block", "display_block");
        blockOverride = defaultOverride.copy();
        blockSpread = itemSpread;
        blockStackYOffset = stackYOffset;
        absoluteBlockPosition = absoluteItemPosition;
        if (blockDisplay != null) {
            blockOverride = blockOverride.withConfiguredDefaults(publicDisplay(blockDisplay, DisplayStyle.BLOCK));
            blockSpread = spread(blockDisplay, itemSpread);
            blockStackYOffset = nonnegative(blockDisplay, "stack-y-offset", "stack_y_offset", stackYOffset);
            absoluteBlockPosition = hasPosition(blockDisplay);
        }
        forceBlock = List.copyOf(ConfigLookup.stringList(station, "block-display-overrides", "block_display_overrides"));
        forceItem = List.copyOf(ConfigLookup.stringList(station, "item-display-overrides", "item_display_overrides"));
    }

    /**
     * Loads the item and tag display tables, which live in their own file (display-overrides.yml, sections
     * {@code items} and {@code tags}) so config.yml only carries the board's own settings. Call after
     * {@link #loadFromConfig(ConfigurationSection)}, which resets both tables.
     */
    public void loadOverridesFromFile(ConfigurationSection overrides) {
        loadOverrides(overrides == null ? null : overrides.getConfigurationSection("items"), false,
                DISPLAY_OVERRIDES_FILE);
        loadOverrides(overrides == null ? null : overrides.getConfigurationSection("tags"), true,
                DISPLAY_OVERRIDES_FILE);
    }

    private void reset() {
        itemOverrides.clear();
        tagOverrides.clear();
        defaultOverride = fallbackDefaults.copy();
        itemSpread = fallbackItemSpread;
        blockOverride = defaultOverride.copy();
        blockSpread = itemSpread;
        stackYOffset = blockStackYOffset = 0.03F;
        absoluteItemPosition = absoluteBlockPosition = false;
        forceBlock = forceItem = List.of();
    }

    private void loadOverrides(ConfigurationSection displaySection, boolean tagSection, String sourceFile) {
        if (displaySection == null) {
            return;
        }

        for (String configuredKey : displaySection.getKeys(false)) {
            String key = configuredKey == null ? "" : configuredKey.trim();
            boolean tagKey = tagSection || key.startsWith("#");
            if (tagKey && !isValidTagId(key)) {
                I18n.logWarning("plugin.config_invalid_key", "file", sourceFile,
                        "path", displaySection.getCurrentPath() + "." + configuredKey);
                continue;
            }
            if (!tagKey && !ItemUtils.isValidItemId(key)) {
                I18n.logWarning("plugin.config_invalid_key", "file", sourceFile,
                        "path", displaySection.getCurrentPath() + "." + configuredKey);
                continue;
            }
            ConfigurationSection overrideSection = displaySection.getConfigurationSection(configuredKey);
            if (overrideSection == null) {
                I18n.logWarning("plugin.config_value_invalid", "file", sourceFile,
                        "path", displaySection.getCurrentPath() + "." + configuredKey,
                        "error", "expected a section");
                continue;
            }
            DisplayOverride override = DisplayOverride.fromConfig(overrideSection);
            if (tagKey) {
                tagOverrides.put(normalizeTag(key), override);
            } else {
                itemOverrides.put(normalize(key), override);
            }
        }
    }

    public DisplayOverride getOverride(ItemStack storedItem) {
        String itemId = getItemId(storedItem);
        if (itemId == null) {
            return defaultOverride.copy();
        }

        boolean block = blockStyle(storedItem);
        DisplayOverride resolved = block ? blockOverride : defaultOverride;
        resolved = new DisplayOverride(resolved.displayItemId(), block ? DisplayStyle.BLOCK : DisplayStyle.ITEM,
                resolved.offset(), resolved.translation(), resolved.rotationDegrees(), resolved.scale());
        synchronized (tagOverrides) { // iterate under the monitor; see field javadoc
            for (Map.Entry<String, DisplayOverride> entry : tagOverrides.entrySet()) {
                try {
                    if (ItemUtils.matchesCustomOrVanillaTag(storedItem, entry.getKey())) {
                        resolved = resolved.merge(entry.getValue());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        DisplayOverride itemOverride = itemOverrides.get(normalize(itemId));
        if (itemOverride != null) {
            resolved = resolved.merge(itemOverride);
        }
        return resolved;
    }

    public ItemStack resolveDisplayItem(ItemStack storedItem) {
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }
        return resolveDisplayItem(storedItem, getOverride(storedItem));
    }

    public ItemStack resolveDisplayItem(ItemStack storedItem, DisplayOverride override) {
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }

        if (override != null && override.displayItemId() != null) {
            ItemStack displayItem = ItemUtils.createItem(override.displayItemId());
            if (displayItem != null && !displayItem.getType().isAir()) {
                displayItem.setAmount(1);
                return displayItem;
            }
        }

        ItemStack fallback = storedItem.clone();
        fallback.setAmount(1);
        return fallback;
    }

    public Vector3f getDefaultOffset() {
        return defaultOverride.offset() == null ? new Vector3f(ZERO_OFFSET) : new Vector3f(defaultOverride.offset());
    }

    public float getDefaultUniformScale(float fallback) {
        Vector3f scale = defaultOverride.scale();
        return scale == null ? fallback : scale.x();
    }

    public float getItemSpread() {
        return itemSpread;
    }

    public float getItemSpread(ItemStack item) { return blockStyle(item) ? blockSpread : itemSpread; }

    public float getStackYOffset(ItemStack item) { return blockStyle(item) ? blockStackYOffset : stackYOffset; }

    public boolean isAbsolutePosition(ItemStack item) {
        return blockStyle(item) ? absoluteBlockPosition : absoluteItemPosition;
    }

    private boolean blockStyle(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        String id = getItemId(item);
        if (matches(item, id, forceItem)) return false;
        if (matches(item, id, forceBlock)) return true;
        if (defaultOverride.style() == DisplayStyle.BLOCK) return true;
        if (defaultOverride.style() == DisplayStyle.ITEM) return false;
        return ItemUtils.shouldUseBlockStyleDisplay(item);
    }

    private boolean matches(ItemStack item, String id, List<String> rules) {
        for (String rule : rules) {
            if (rule.startsWith("#")) {
                if (ItemUtils.matchesCustomOrVanillaTag(item, rule.substring(1))) return true;
            } else if (normalize(rule).equals(normalize(id))) return true;
        }
        return false;
    }

    private static boolean hasPosition(ConfigurationSection section) {
        for (String axis : List.of("x", "y", "z")) {
            if (section.isSet("translate-" + axis) || section.isSet("translate_" + axis)) return true;
        }
        return false;
    }

    private static float spread(ConfigurationSection section, float fallback) {
        return 2.0F * nonnegative(section, "stack-xz-offset", "stack_xz_offset", fallback * 0.5F);
    }

    private static float nonnegative(ConfigurationSection section, String normalized, String canonical, float fallback) {
        double value = ConfigLookup.doubleValue(section, fallback, normalized, canonical);
        return Double.isFinite(value) ? (float) Math.max(0, Math.min(16, value)) : fallback;
    }

    private static DisplayOverride publicDisplay(ConfigurationSection section, DisplayStyle style) {
        Vector3f position = hasPosition(section) ? new Vector3f(
                (float) ConfigLookup.doubleValue(section, 0, "translate-x", "translate_x"),
                (float) ConfigLookup.doubleValue(section, 0, "translate-y", "translate_y"),
                (float) ConfigLookup.doubleValue(section, 0, "translate-z", "translate_z")) : null;
        boolean rotation = section.isSet("rotation-pitch") || section.isSet("rotation_pitch")
                || section.isSet("rotation-y") || section.isSet("rotation_y")
                || section.isSet("rotation-roll") || section.isSet("rotation_roll");
        Vector3f degrees = rotation ? new Vector3f(
                (float) ConfigLookup.doubleValue(section, 0, "rotation-pitch", "rotation_pitch"),
                (float) ConfigLookup.doubleValue(section, 0, "rotation-y", "rotation_y"),
                (float) ConfigLookup.doubleValue(section, 0, "rotation-roll", "rotation_roll")) : null;
        return new DisplayOverride(null, style, position, null, degrees,
                DisplayOverride.readVector(section, "scale"));
    }

    private String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTag(String tagId) {
        String normalized = tagId == null ? "" : tagId.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalize(normalized);
    }

    private boolean isValidTagId(String tagId) {
        return ItemUtils.isValidItemId(normalizeTag(tagId));
    }

    private String getItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        NamespacedKey key = Registry.MATERIAL.getKey(item.getType());
        return key == null ? null : key.toString();
    }

    public record DisplayOverride(
            @Nullable String displayItemId,
            DisplayStyle style,
            @Nullable Vector3f offset,
            @Nullable Vector3f translation,
            @Nullable Vector3f rotationDegrees,
            @Nullable Vector3f scale
    ) {
        private static final DisplayOverride EMPTY = new DisplayOverride(null, DisplayStyle.AUTO, null, null, null, null);

        public static DisplayOverride empty() {
            return EMPTY;
        }

        public DisplayOverride copy() {
            return new DisplayOverride(
                    displayItemId,
                    style == null ? DisplayStyle.AUTO : style,
                    copyVector(offset),
                    copyVector(translation),
                    copyVector(rotationDegrees),
                    copyVector(scale)
            );
        }

        public DisplayOverride merge(DisplayOverride override) {
            if (override == null) {
                return copy();
            }
            return new DisplayOverride(
                    override.displayItemId != null ? override.displayItemId : displayItemId,
                    override.style != null && override.style != DisplayStyle.AUTO ? override.style : normalizedStyle(),
                    addVectors(offset, override.offset),
                    override.translation != null ? copyVector(override.translation) : copyVector(translation),
                    override.rotationDegrees != null ? copyVector(override.rotationDegrees) : copyVector(rotationDegrees),
                    override.scale != null ? copyVector(override.scale) : copyVector(scale)
            );
        }

        private DisplayOverride withConfiguredDefaults(DisplayOverride configuredDefaults) {
            if (configuredDefaults == null) {
                return copy();
            }
            return new DisplayOverride(
                    configuredDefaults.displayItemId != null ? configuredDefaults.displayItemId : displayItemId,
                    configuredDefaults.style != null && configuredDefaults.style != DisplayStyle.AUTO
                            ? configuredDefaults.style
                            : normalizedStyle(),
                    configuredDefaults.offset != null ? copyVector(configuredDefaults.offset) : copyVector(offset),
                    configuredDefaults.translation != null ? copyVector(configuredDefaults.translation) : copyVector(translation),
                    configuredDefaults.rotationDegrees != null ? copyVector(configuredDefaults.rotationDegrees) : copyVector(rotationDegrees),
                    configuredDefaults.scale != null ? copyVector(configuredDefaults.scale) : copyVector(scale)
            );
        }

        private DisplayStyle normalizedStyle() {
            return style == null ? DisplayStyle.AUTO : style;
        }

        private static DisplayOverride fromConfig(ConfigurationSection section) {
            String displayItemId = section.getString("display-item");
            if (displayItemId != null && !ItemUtils.isValidItemId(displayItemId)) {
                if (section.contains("display-item")) {
                    I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                            "path", section.getCurrentPath() + ".display-item", "error", "invalid item id");
                }
                displayItemId = null;
            }
            warnUnknownStyle(section, "style");
            return new DisplayOverride(
                    displayItemId,
                    DisplayStyle.fromConfig(section.getString("style")),
                    readVector(section, "position", "offset"),
                    readVector(section, "translation"),
                    readVector(section, "rotation"),
                    readVector(section, "scale")
            );
        }

        private static DisplayOverride fromDefaultConfig(ConfigurationSection section) {
            String displayItemId = section.getString("default-display-item");
            if (displayItemId != null && !ItemUtils.isValidItemId(displayItemId)) {
                if (section.contains("default-display-item")) {
                    I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                            "path", section.getCurrentPath() + ".default-display-item", "error", "invalid item id");
                }
                displayItemId = null;
            }

            Vector3f defaultOffset = readVector(
                    section,
                    "default-display-position",
                    "default-display-offset",
                    "default-position",
                    "default-offset",
                    "position",
                    "offset"
            );
            if (defaultOffset == null && section.contains("y-offset")) {
                defaultOffset = new Vector3f(0.0F, (float) section.getDouble("y-offset"), 0.0F);
            }

            warnUnknownStyle(section, "default-display-style");
            warnUnknownStyle(section, "default-style");
            warnUnknownStyle(section, "style");
            return new DisplayOverride(
                    displayItemId,
                    DisplayStyle.fromConfig(firstString(section)),
                    defaultOffset,
                    readVector(section, "default-display-translation", "default-translation", "translation"),
                    readVector(section, "default-display-rotation", "default-rotation", "rotation"),
                    readVector(section, "default-display-scale", "default-scale", "scale")
            );
        }

        private static void warnUnknownStyle(ConfigurationSection section, String key) {
            if (!section.contains(key)) {
                return;
            }
            String value = section.getString(key);
            if (value == null || value.isBlank()) {
                return;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            if (!Set.of("auto", "item", "flat", "generated", "block", "block_style", "blockstyle", "cube").contains(normalized)) {
                I18n.logWarning("plugin.config_value_invalid", "file", "config.yml",
                        "path", section.getCurrentPath() + "." + key, "error", "unknown display style " + value);
            }
        }

        @Nullable
        private static String firstString(ConfigurationSection section) {
            for (String key : new String[]{"default-display-style", "default-style", "style"}) {
                if (section.contains(key)) {
                    String value = ConfigSectionReader.optionalString(section, key);
                    if (value != null) {
                        return value;
                    }
                }
            }
            return null;
        }

        @Nullable
        static Vector3f readVector(ConfigurationSection section, String... keys) {
            for (String key : keys) {
                if (!section.contains(key)) {
                    continue;
                }
                Object value = section.get(key);
                Vector3f vector = parseVector(value);
                if (vector != null) {
                    return vector;
                }
            }
            return null;
        }

        @Nullable
        private static Vector3f parseVector(Object value) {
            try {
                if (value instanceof Number number) {
                    float v = number.floatValue();
                    return new Vector3f(v, v, v);
                }
                if (value instanceof List<?> list) {
                    if (list.size() == 1) {
                        float v = Float.parseFloat(list.getFirst().toString());
                        return new Vector3f(v, v, v);
                    }
                    if (list.size() >= 3) {
                        return new Vector3f(
                                Float.parseFloat(list.get(0).toString()),
                                Float.parseFloat(list.get(1).toString()),
                                Float.parseFloat(list.get(2).toString())
                        );
                    }
                }
                if (value instanceof String string) {
                    String[] split = string.replace("_", "").split(",");
                    if (split.length == 1) {
                        float v = Float.parseFloat(split[0].trim());
                        return new Vector3f(v, v, v);
                    }
                    if (split.length >= 3) {
                        return new Vector3f(
                                Float.parseFloat(split[0].trim()),
                                Float.parseFloat(split[1].trim()),
                                Float.parseFloat(split[2].trim())
                        );
                    }
                }
                if (value instanceof ConfigurationSection vectorSection) {
                    return new Vector3f(
                            (float) ConfigSectionReader.optionalDouble(vectorSection, "x", 0.0D),
                            (float) ConfigSectionReader.optionalDouble(vectorSection, "y", 0.0D),
                            (float) ConfigSectionReader.optionalDouble(vectorSection, "z", 0.0D)
                    );
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        @Nullable
        private static Vector3f copyVector(@Nullable Vector3f vector) {
            return vector == null ? null : new Vector3f(vector);
        }

        @Nullable
        private static Vector3f addVectors(@Nullable Vector3f base, @Nullable Vector3f override) {
            if (base == null) {
                return copyVector(override);
            }
            if (override == null) {
                return copyVector(base);
            }
            return new Vector3f(base).add(override);
        }
    }

    public enum DisplayStyle {
        AUTO,
        ITEM,
        BLOCK;

        private static DisplayStyle fromConfig(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "item", "flat", "generated" -> ITEM;
                case "block", "block_style", "blockstyle", "cube" -> BLOCK;
                default -> AUTO;
            };
        }
    }
}
