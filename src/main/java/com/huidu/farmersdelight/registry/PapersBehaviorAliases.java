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
            "papersdelight:basket", "papersdelight:advanced_crop", "papersdelight:roped_crop",
            "papersdelight:double_crop", "papersdelight:grant_advancement", "papersdelight:remove_random_effect",
            "papersdelight:organic_compost", "papersdelight:rich_soil", "papersdelight:farmland",
            "papersdelight:rope", "papersdelight:rope_block", "papersdelight:skewer_item",
            "papersdelight:high_temperature", "papersdelight:pairable_block",
            "papersdelight:horizontal_double_block_item", "papersdelight:horizontal_double_block",
            "papersdelight:wild_rice", "dumplings_delight:garlic_effect");

    public static Set<String> unsupportedIdentifiers() { return UNSUPPORTED; }

    public static void register() {
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
        registerBuff("papersdelight:nourishment_effect", FoodBuffFunction.Kind.NOURISHMENT);
        registerBuff("papersdelight:comfort_effect", FoodBuffFunction.Kind.COMFORT);
    }

    private static void registerBlock(String alias, String source, UnaryOperator<ConfigSection> normalize) {
        Key key = Key.of(alias);
        var original = BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of(source));
        if (original != null && BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(key) == null) {
            BlockBehaviors.register(key, (block, section) -> original.factory().create(block, normalize.apply(section)));
        }
    }

    private static void registerBuff(String alias, FoodBuffFunction.Kind kind) {
        Key key = Key.of(alias);
        if (BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(key) == null) {
            CommonFunctions.register(key, FoodBuffFunction.ticksFactory(kind, CommonConditions::fromConfig));
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
