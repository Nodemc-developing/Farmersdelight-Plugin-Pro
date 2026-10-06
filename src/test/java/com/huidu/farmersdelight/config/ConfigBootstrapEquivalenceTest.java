package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigUpdatePolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ConfigBootstrapEquivalenceTest {
    @Test void directNativeTemplatePreservesTheGameplayAndSchedulingDefaults() throws Exception {
        var config = bundled();
        assertTrue(ConfigBootstrap.isCurrentSchema(config));
        assertEquals(4, config.getInt("config-version"));
        assertEquals(200, config.getInt("cooking-pot.cooking.default-cook-time"));
        assertEquals(20, config.getInt("cooking-pot.cooking.min-cook-time"));
        assertEquals(6000, config.getInt("cooking-pot.cooking.max-cook-time"));
        assertEquals(600, config.getInt("skillet.cooking.default-cook-time"));
        assertEquals(0.2, config.getDouble("skillet.cooking.cook-time-multiplier"));
        assertEquals(512, config.getInt("cooking-pot.tick-budget"));
        assertEquals(1, config.getInt("cooking-pot.effects.interval"));
        assertEquals(32.0, config.getDouble("cooking-pot.effects.viewer-distance"));
        assertEquals(80, config.getInt("recipe-book.tag-cycle-interval-ticks"));
        assertEquals(30, config.getInt("stats.flush-interval-seconds"));
        assertEquals(2048, config.getInt("stats.cache-player-limit"));
        assertEquals(5000, config.getInt("performance.shutdown-wait-millis"));
        assertEquals(4, config.getInt("container.tick-interval-ticks"));
        assertTrue(config.getBoolean("stats.enabled"));
        assertTrue(config.getBoolean("buff.comfort.enabled"));
        assertTrue(config.getBoolean("buff.nourishment.enabled"));
        assertEquals(80, config.getInt("buff.comfort.heal-interval-ticks"));
        assertEquals(1.0, config.getDouble("buff.comfort.heal-amount"));
        assertEquals("PROGRESS", config.getString("buff.display.styles.comfort.overlay"));
        assertEquals("BLUE", config.getString("buff.display.styles.comfort.color"));
        assertFalse(config.contains("config_format"));
        assertFalse(config.contains("lang"));
        assertFalse(config.contains("cooking_pot"));
        assertFalse(config.contains("enchantment"));
    }

    @Test void theNativeTemplateRetainsHeatRuleOrderAndItemIdentity() throws Exception {
        var config = bundled();
        var rules = config.getMapList("heat-sources.entries");
        assertEquals("minecraft:campfire", rules.getFirst().get("vanilla-block"));
        assertEquals(false, rules.getFirst().get("heat-source"));
        assertEquals(java.util.Map.of("lit", "false"), rules.getFirst().get("states"));
        assertEquals("minecraft:soul_campfire", rules.get(1).get("vanilla-block"));
        assertTrue(rules.stream().anyMatch(rule -> "farmersdelight:heat_sources".equals(rule.get("custom-block-tag"))));
        assertEquals(List.of("farmersdelight:tools/knives"), config.getStringList("knife-items.tags"));
        assertEquals(List.of("farmersdelight:flint_knife", "farmersdelight:iron_knife", "farmersdelight:golden_knife",
                "farmersdelight:diamond_knife", "farmersdelight:netherite_knife"), config.getStringList("knife-items.items"));
    }

    @Test void aMissingOldOrFutureVersionCannotBeAdmittedThroughDefaults() throws Exception {
        var source = PlainYamlDocuments.parse("stats: {enabled: false}\n", false);
        source.setDefaults(bundled());
        String before = source.saveToString();
        assertFalse(ConfigBootstrap.isCurrentSchema(source));
        assertEquals(before, source.saveToString());
        for (int version : List.of(0, 1, 3, 5, 100)) {
            source.set("config-version", version);
            assertFalse(ConfigBootstrap.isCurrentSchema(source));
        }
        source.set("config-version", 4);
        assertTrue(ConfigBootstrap.isCurrentSchema(source));
    }

    @Test void nativeDefaultMergingHonorsExplicitRegistryOptOutsAndOperatorValues() throws Exception {
        var config = PlainYamlDocuments.parse("""
                config-version: 4
                heat-sources: {entries: []}
                buff: {comfort: {enabled: false}, nourishment: {enabled: false}}
                container-returns: {}
                pet-foods: {}
                stats: {enabled: false, flush-interval-seconds: 90}
                villager:
                  harvest: {crops: {}, planting: {}, harvest-drops: {}}
                  breed: {food-points: {example: 0}}
                  compost: {chances: {}}
                """, false);
        assertTrue(config.isConfigurationSection("buff.comfort"));
        assertFalse(config.getBoolean("buff.comfort.enabled"));
        var policy = livePolicy();
        assertTrue(policy.migrations().isEmpty());
        assertTrue(policy.retiredKeys().isEmpty());
        assertEquals(Set.of("heat-sources", "buff.comfort", "buff.nourishment", "container-returns", "pet-foods",
                        "villager.harvest.crops", "villager.harvest.planting", "villager.harvest.harvest-drops",
                        "villager.breed.food-points", "villager.compost.chances"),
                Set.copyOf(policy.registrySections()));
        assertTrue(ConfigFileUpdater.copyMissingKeys(bundled(), config, policy.registrySections()) > 0);
        assertTrue(config.getMapList("heat-sources.entries").isEmpty());
        assertFalse(config.getBoolean("buff.comfort.enabled"));
        assertFalse(config.contains("buff.comfort.heal-amount"));
        assertFalse(config.getBoolean("stats.enabled"));
        assertEquals(90, config.getInt("stats.flush-interval-seconds"));
        assertEquals(2048, config.getInt("stats.cache-player-limit"));
        assertTrue(config.getConfigurationSection("villager.harvest.crops").getKeys(false).isEmpty());
        assertEquals(Map.of("example", 0), config.getConfigurationSection("villager.breed.food-points").getValues(false));
        assertTrue(config.getBoolean("villager.backpack.enabled"));
        assertEquals(0, ConfigFileUpdater.copyMissingKeys(bundled(), config, policy.registrySections()));
    }

    @Test void nativeTemplateContainsEveryDirectConsumerGroupWithoutAConversionLayer() throws Exception {
        var config = bundled();
        for (String section : List.of("cooking-pot", "cutting-board", "skillet", "stove", "heat-sources",
                "buff", "stats", "performance", "container", "recipes", "recipe-book", "enchantments")) {
            assertTrue(config.isConfigurationSection(section), section);
        }
        for (String key : config.getKeys(false)) assertFalse(key.contains("_"), key);
    }

    @Test void perItemGuiOverridesKeepExactMapsAndLeaveNonEmptyOperatorDataUntouched() throws Exception {
        var gui = PlainYamlDocuments.parse(Files.readString(Path.of("src/main/resources/gui.yml")), false);
        for (String path : List.of("cooking-pot-guis", "recipe-view-gui.recipe-detail-cooking-pot-guis", "recipe-editor-cooking-pot-guis")) {
            assertTrue(gui.isConfigurationSection(path), path);
            gui.set(path, List.of());
        }
        assertEquals(3, ConfigBootstrap.migrateEmptyGuiMaps(gui));
        assertEquals(0, ConfigBootstrap.migrateEmptyGuiMaps(gui));
        gui.set("cooking-pot-guis.custom:pot.title", "Custom pot");
        gui.set("recipe-editor-cooking-pot-guis", List.of("invalid-entry"));
        assertEquals(0, ConfigBootstrap.migrateEmptyGuiMaps(gui));
        assertEquals("Custom pot", gui.getString("cooking-pot-guis.custom:pot.title"));
        assertEquals(List.of("invalid-entry"), gui.getList("recipe-editor-cooking-pot-guis"));
    }

    private static ConfigUpdatePolicy livePolicy() throws Exception {
        Field field = ConfigBootstrap.class.getDeclaredField("CONFIG_POLICY");
        field.setAccessible(true);
        return (ConfigUpdatePolicy) field.get(null);
    }

    private static YamlConfiguration bundled() throws Exception {
        return PlainYamlDocuments.read(Path.of("src/main/resources/config.yml"));
    }
}
