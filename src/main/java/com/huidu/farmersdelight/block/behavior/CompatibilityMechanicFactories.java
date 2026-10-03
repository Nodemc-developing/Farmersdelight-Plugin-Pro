package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resource identifiers map to validated, fully configured mechanics. */
public final class CompatibilityMechanicFactories {
    private CompatibilityMechanicFactories() { }
    public static Map<String, BlockBehaviorFactory<?>> blockFactories() {
        return blockFactories(null);
    }
    public static Map<String, BlockBehaviorFactory<?>> blockFactories(com.huidu.farmersdelight.FarmersDelightPlugin plugin) {
        Map<String, BlockBehaviorFactory<?>> map = new LinkedHashMap<>();
        map.put("advanced_crop", ManagedCropBlockBehavior.factory(plugin, ManagedCropBlockBehavior.Shape.SINGLE));
        map.put("double_crop", ManagedCropBlockBehavior.factory(plugin, ManagedCropBlockBehavior.Shape.DOUBLE));
        map.put("roped_crop", ManagedCropBlockBehavior.factory(plugin, ManagedCropBlockBehavior.Shape.ROPE));
        map.put("farmland", ManagedFarmlandBlockBehavior.factory(plugin));
        map.put("rich_soil", (block, config) -> RichSoilBlockBehavior.FACTORY.create(block, richSoil(config)));
        map.put("organic_compost", (block, config) -> OrganicCompostBlockBehavior.FACTORY.create(block, compost(config)));
        map.put("wild_rice", (block, config) -> WildRiceBlockBehavior.FACTORY.create(block, normalize(config)));
        map.put("basket", (block, config) -> BasketBlockBehavior.FACTORY.create(block, basket(config)));
        map.put("rope_block", (block, config) -> RopeBlockBehavior.FACTORY.create(block, normalize(config)));
        map.put("pairable_block", (block, config) -> TatamiPairingBehavior.factory(plugin).create(block, pair(config)));
        map.put("horizontal_double_block", (block, config) -> DoubleBlockRugBlockBehavior.factory(plugin).create(block, doubleBlock(config)));
        map.put("high_temperature", HeatContactBlockBehavior.FACTORY);
        map.put("integer_comparator", IntegerComparatorBlockBehavior.FACTORY);
        return Map.copyOf(map);
    }
    public static Map<String, ItemBehaviorFactory<?>> itemFactories() {
        return itemFactories(() -> null);
    }
    public static Map<String, ItemBehaviorFactory<?>> itemFactories(java.util.function.Supplier<com.huidu.farmersdelight.manager.SkewerCookingService> skewer) {
        ItemBehaviorFactory<BlockItemBehavior> block = (pack, path, id, config) -> new BlockItemBehavior(Key.of(config.getNonEmptyString("block")));
        return Map.of("rope", block, "horizontal_double_block_item", block,
                "skewer_item", com.huidu.farmersdelight.item.behavior.SkewerItemBehavior.factory(skewer));
    }
    public static ConfigSection normalize(ConfigSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (section != null) section.values().forEach((key, value) -> map.put(key.replace('_', '-'), value));
        return ConfigSection.ofRoot(map);
    }
    static ConfigSection basket(ConfigSection section) {
        ConfigSection result = normalize(section);
        if (result.containsKey("collect-interval")) result.put("transfer-cooldown", result.get("collect-interval"));
        else if (!result.containsKey("transfer-cooldown")) result.put("transfer-cooldown", 1);
        return result;
    }
    static ConfigSection richSoil(ConfigSection section) {
        ConfigSection result = normalize(section);
        if (!result.containsKey("boost-chance")) result.put("boost-chance", .2);
        if (!result.containsKey("particle-count")) result.put("particle-count", 15);
        return result;
    }
    static ConfigSection compost(ConfigSection section) {
        ConfigSection result = normalize(section);
        translate(result, "result", "rich-soil-block");
        translate(result, "activator-multiplier", "activator.bonus-per-neighbor");
        translate(result, "water-bonus", "water.bonus");
        translate(result, "light-bonus-high", "light.high-bonus");
        translate(result, "light-bonus-low", "light.low-bonus");
        translate(result, "light-threshold", "light.threshold");
        return result;
    }
    static ConfigSection pair(ConfigSection section) {
        ConfigSection result = normalize(section);
        translate(result, "facing", "facing-property");
        translate(result, "paired", "paired-property");
        putPath(result, "pair.while-sneaking", !result.getBoolean("disable-when-sneaking", true));
        return result;
    }
    static ConfigSection doubleBlock(ConfigSection section) {
        ConfigSection result = normalize(section);
        translate(result, "facing", "facing-property");
        translate(result, "part", "part-property");
        return result;
    }
    private static void translate(ConfigSection config, String input, String output) {
        if (config.containsKey(input)) putPath(config, output, config.get(input));
    }
    private static void putPath(ConfigSection config, String path, Object value) {
        int dot = path.indexOf('.');
        if (dot < 0) { config.put(path, value); return; }
        String parent = path.substring(0, dot);
        Object configured = config.get(parent);
        Map<String, Object> nested = new LinkedHashMap<>();
        if (configured instanceof Map<?, ?> previous) previous.forEach((key, item) -> nested.put(String.valueOf(key), item));
        ConfigSection child = ConfigSection.ofRoot(nested);
        putPath(child, path.substring(dot + 1), value);
        config.put(parent, nested);
    }
}
