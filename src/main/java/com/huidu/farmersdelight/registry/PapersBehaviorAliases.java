package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.effect.FoodBuffFunction;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.item.behavior.CompositeItemBehavior;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.CommonFunctions;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Compatibility identifiers reuse this plugin's registered mechanics without replacing other registrations. */
public final class PapersBehaviorAliases {
    private static final Set<String> UNSUPPORTED = Set.of(
            "dumplings_delight:garlic_effect");

    public static Set<String> unsupportedIdentifiers() { return UNSUPPORTED; }

    public static void register() {
        register(null);
    }
    public static void register(com.huidu.farmersdelight.FarmersDelightPlugin plugin) {
        registerBlock("papersdelight:cooking_pot", "farmersdelight:cooking_pot", section -> {
            ConfigSection normalized = copy(section);
            normalized.put("support-integer", true);
            return normalized;
        });
        registerBlock("papersdelight:cutting_board", "farmersdelight:cutting_board", PapersBehaviorAliases::copy);
        registerBlock("papersdelight:skillet", "farmersdelight:skillet", PapersBehaviorAliases::copy);
        registerBlock("papersdelight:stove", "farmersdelight:stove", PapersBehaviorAliases::stoveSection);

        Key skilletItem = Key.of("papersdelight:skillet_item");
        var handheld = BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("farmersdelight:skillet_item"));
        if (handheld != null && BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(skilletItem) == null) {
            ItemBehaviors.register(skilletItem, (pack, path, id, section) -> {
                var cooking = handheld.factory().create(pack, path, id, section);
                Key placedBlock = Key.of(section.getNonEmptyString("block"));
                return new CompositeItemBehavior(List.of(cooking, new BlockItemBehavior(placedBlock)));
            });
        }
        registerBuff(plugin, "papersdelight:nourishment_effect", FoodBuffFunction.Kind.NOURISHMENT);
        registerBuff(plugin, "papersdelight:comfort_effect", FoodBuffFunction.Kind.COMFORT);
        registerExtendedMechanics(plugin);
    }

    private static void registerExtendedMechanics(com.huidu.farmersdelight.FarmersDelightPlugin plugin) {
        var blocks = com.huidu.farmersdelight.block.behavior.CompatibilityMechanicFactories.blockFactories(plugin);
        var items = com.huidu.farmersdelight.block.behavior.CompatibilityMechanicFactories.itemFactories(
                plugin == null ? () -> null : plugin::getSkewerCookingService);
        for (String namespace : List.of("farmersdelight", "papersdelight")) {
            blocks.forEach((suffix, factory) -> {
                Key id = Key.of(namespace + ":" + suffix);
                if (BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(id) == null) BlockBehaviors.register(id, factory);
            });
            items.forEach((suffix, factory) -> {
                Key id = Key.of(namespace + ":" + suffix);
                if (BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(id) == null) ItemBehaviors.register(id, factory);
            });
            com.huidu.farmersdelight.effect.ContentEffectFunction.factories(plugin).forEach((suffix, factory) -> {
                Key id = Key.of(namespace + ":" + suffix);
                if (BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(id) == null) CommonFunctions.register(id, factory);
            });
            Key sneaking = Key.of(namespace + ":is_sneaking");
            if (BuiltInRegistries.COMMON_CONDITION_TYPE.getValue(sneaking) == null) CommonConditions.register(sneaking,
                    com.huidu.farmersdelight.condition.SneakingCondition.FACTORY);
            Key advancement = Key.of(namespace + ":grant_advancement");
            if (BuiltInRegistries.LOOT_FUNCTION_TYPE.getValue(advancement) == null)
                net.momirealms.craftengine.core.loot.function.LootFunctions.register(advancement,
                        com.huidu.farmersdelight.loot.GrantAdvancementFunction.factory(plugin));
        }
    }

    private static void registerBlock(String alias, String source, UnaryOperator<ConfigSection> normalize) {
        Key key = Key.of(alias);
        var original = BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(source));
        if (original != null && BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(key) == null) {
            BlockBehaviors.register(key, (block, section) -> original.factory().create(block, normalize.apply(section)));
        }
    }

    private static void registerBuff(com.huidu.farmersdelight.FarmersDelightPlugin plugin, String alias, FoodBuffFunction.Kind kind) {
        Key key = Key.of(alias);
        if (BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(key) == null) {
            CommonFunctions.register(key, FoodBuffFunction.ticksFactory(plugin, kind, CommonConditions::fromConfig));
        }
    }

    static ConfigSection stoveSection(ConfigSection section) {
        ConfigSection normalized = copy(section);
        if (!normalized.containsKey("property")) normalized.put("property", "lit");
        if (!normalized.containsKey("crackle-sound") && normalized.containsKey("sound")) {
            normalized.put("crackle-sound", normalized.get("sound"));
        }
        // The content pack's events own ignition; its separate temperature behavior owns damage.
        if (!normalized.containsKey("burn")) normalized.put("burn", Map.of("enabled", false));
        if (!normalized.containsKey("ignite")) normalized.put("ignite", Map.of("enabled", false));
        if (!normalized.containsKey("extinguish")) normalized.put("extinguish", Map.of("enabled", false));
        return normalized;
    }

    private static ConfigSection copy(ConfigSection section) {
        return section == null ? ConfigSection.ofRoot(new LinkedHashMap<>()) : section.copy();
    }

    private PapersBehaviorAliases() { }
}
