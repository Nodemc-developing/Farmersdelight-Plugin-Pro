package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PapersDelightConfigFormatTest {
    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration result = new YamlConfiguration();
        result.loadFromString(text);
        return result;
    }

    @Test
    void configuredDefaultToolsReachCuttingRecipesFromCanonicalAndLegacyFiles() throws Exception {
        for (String config : List.of("cutting_board:\n  default_tools: [minecraft:shears]\n",
                "cutting-board:\n  default-tools: [minecraft:shears]\n")) {
            YamlConfiguration source = yaml(config);
            PapersDelightConfigFormat.normalize(source);
            YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
            assertEquals(List.of("minecraft:shears"), source.getStringList("cutting_board.default_tools"));
            assertEquals(List.of("minecraft:shears"), runtime.getStringList("cutting-board.default-tools"));
            var recipe = com.huidu.farmersdelight.recipe.RecipeSchemaAdapter.normalizeBoard(
                    yaml("type: cutting\ningredient: minecraft:carrot\nresults: [minecraft:orange_dye]\n"),
                    runtime.getStringList("cutting-board.default-tools"));
            assertEquals(List.of("minecraft:shears"), recipe.getStringList("tools"));
        }
    }

    @Test
    void legacyStationOptionsMigrateWithoutChangingTheirEffectiveValues() throws Exception {
        YamlConfiguration source = yaml("""
                language: zh_cn
                cooking-pot:
                  allow-hopper: false
                  effects:
                    interval: 3
                    viewer-distance: 12.5
                    bubble:
                      y-offset: 0.03
                cutting-board:
                  dispenser-behavior: false
                  allow-hopper: false
                  display-item-spread: 0.2
                  default-display-offset: '0,-0.03,0'
                  sounds:
                    retrieve-volume: 0.7
                skillet:
                  display:
                    y-offset: 0.8
                    item-spread: 0.12
                performance:
                  shutdown-wait-millis: 2300
                """);
        assertTrue(PapersDelightConfigFormat.normalize(source));
        assertEquals("zh_cn", source.getString("lang"));
        assertFalse(source.contains("cooking-pot", true));
        assertFalse(source.getBoolean("cutting_board.dispenser_cutting", true));
        assertFalse(source.getBoolean("cutting_board.hopper_interaction", true));
        assertEquals(12, source.getInt("cooking_pot.particles.interval_ticks"));
        assertEquals(2300, source.getInt("stats.shutdown_wait_millis"));
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertFalse(runtime.getBoolean("cooking-pot.allow-hopper", true));
        assertFalse(runtime.getBoolean("cutting-board.dispenser-behavior", true));
        assertEquals(3, runtime.getInt("cooking-pot.effects.interval"));
        assertEquals(12.5, runtime.getDouble("cooking-pot.effects.viewer-distance"));
        assertEquals(0.03, runtime.getDouble("cooking-pot.effects.bubble.y-offset"));
        assertEquals(0.2, runtime.getDouble("cutting-board.display-item-spread"));
        assertEquals(List.of(0.0, -0.03, 0.0), runtime.getList("cutting-board.default-display-offset"));
        assertEquals(0.12, runtime.getDouble("skillet.display.item-spread"));
        assertEquals(List.of(0.0, 0.8, 0.0), runtime.getList("skillet.display.position"));
        assertEquals(0.7, runtime.getDouble("cutting-board.sounds.retrieve-volume"));
        assertFalse(PapersDelightConfigFormat.normalize(source), "normalization must be idempotent");
    }

    @Test
    void explicitCanonicalValuesWinInBothYamlOrdersAndBeforeDefaultsMerge() throws Exception {
        String legacy = "cutting-board:\n  allow-hopper: true\n  dispenser-behavior: true\n";
        String canonical = "cutting_board:\n  hopper_interaction: false\n  dispenser_cutting: false\n";
        for (String text : List.of(legacy + canonical, canonical + legacy)) {
            YamlConfiguration source = yaml(text);
            PapersDelightConfigFormat.normalize(source);
            YamlConfiguration defaults = yaml("cutting_board:\n  hopper_interaction: true\n  dispenser_cutting: true\n");
            ConfigFileUpdater.copyMissingKeys(defaults, source, List.of());
            YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
            assertFalse(runtime.getBoolean("cutting-board.allow-hopper", true));
            assertFalse(runtime.getBoolean("cutting-board.dispenser-behavior", true));
        }
    }

    @Test
    void optionRenamesDoNotRenameNamespacedItemsOrUnknownFields() throws Exception {
        YamlConfiguration source = yaml("""
                container-returns:
                  my-pack:drink-with-underscore_here: my-pack:empty-bottle
                custom-field:
                  retained-key: exact
                cutting-board:
                  unsupported-key: keep
                  sounds:
                    tool-sounds:
                      '#my-pack:tools/knives_v2': my-pack:knife-sound
                """);
        PapersDelightConfigFormat.normalize(source);
        assertEquals("my-pack:empty-bottle", source.getString("container_returns.my-pack:drink-with-underscore_here"));
        assertEquals("exact", source.getString("custom-field.retained-key"));
        assertEquals("keep", source.getString("cutting_board.unsupported-key"));
        assertEquals("my-pack:knife-sound", source.getString("cutting_board.tool_sounds.#my-pack:tools/knives_v2"));
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertEquals("my-pack:empty-bottle", runtime.getString("container-returns.my-pack:drink-with-underscore_here"));
    }

    @Test
    void materialAndCraftEngineHeatEntriesPreserveRulesAndEmptyLists() throws Exception {
        YamlConfiguration source = yaml("""
                heat_sources:
                  - material: CAMPFIRE
                    states: {lit: 'false'}
                    heat_source: false
                  - ce_block_tag: my-pack:hot_blocks
                  - material: HOPPER
                    conductor: true
                    heat_source: false
                """);
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        List<Map<?, ?>> entries = runtime.getMapList("heat-sources.entries");
        assertEquals("minecraft:campfire", entries.getFirst().get("vanilla-block"));
        assertEquals(false, entries.getFirst().get("heat-source"));
        assertEquals(Map.of("lit", "false"), entries.getFirst().get("states"));
        assertEquals("my-pack:hot_blocks", entries.get(1).get("custom-block-tag"));
        assertEquals(true, entries.get(2).get("conductor"));
        source.set("heat_sources", List.of());
        assertNotNull(PapersDelightConfigFormat.runtimeViewOf(source).getConfigurationSection("heat-sources"));
        assertTrue(PapersDelightConfigFormat.runtimeViewOf(source).getMapList("heat-sources.entries").isEmpty());
    }

    @Test
    void legacyHeatListsAndUnknownHeatMetadataKeepTheirOriginalFallbackSemantics() throws Exception {
        YamlConfiguration source = yaml("""
                heat-sources:
                  entries:
                    - vanilla-block: minecraft:campfire
                      states: {lit: 'false'}
                      heat-source: false
                  vanilla-blocks: [minecraft:stone]
                  conductors: [minecraft:stone]
                  custom-notes: operator
                """);
        PapersDelightConfigFormat.normalize(source);
        assertEquals(1, source.getMapList("heat_sources").size());
        assertEquals("operator", source.getString("heat_source_legacy.custom-notes"));
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertEquals(List.of("minecraft:stone"), runtime.getStringList("heat-sources.vanilla-blocks"));
        assertEquals(List.of("minecraft:stone"), runtime.getStringList("heat-sources.conductors"));
        assertEquals("operator", runtime.getString("heat-sources.custom-notes"));
    }

    @Test
    void effectStylesAndEnchantmentSwitchUsePapersFields() throws Exception {
        YamlConfiguration source = yaml("""
                comfort_effect:
                  enable: false
                  bossbar: {color: BLUE, style: SEGMENTED_20}
                  heal_interval_ticks: 40
                nourishment_effect:
                  enable: true
                  bossbar: {color: YELLOW, style: SOLID}
                enchantment:
                  enable: false
                """);
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertFalse(runtime.getBoolean("buff.comfort.enabled", true));
        assertTrue(runtime.getBoolean("buff.nourishment.enabled"));
        assertEquals(40, runtime.getInt("buff.comfort.heal-interval-ticks"));
        assertEquals("BLUE", runtime.getString("buff.display.styles.comfort.color"));
        assertEquals("NOTCHED_20", runtime.getString("buff.display.styles.comfort.overlay"));
        assertEquals("PROGRESS", runtime.getString("buff.display.styles.nourishment.overlay"));
        assertFalse(runtime.getBoolean("enchantments.enabled", true));
    }

    @Test
    void foreignVersionCanMigrateButOurFutureVersionCannot() throws Exception {
        YamlConfiguration foreign = yaml("""
                config-version: 12
                license: unused
                lang: zh_cn
                heat_sources: []
                cooking_pot: {}
                cutting_board: {}
                stats: {}
                """);
        assertTrue(PapersDelightConfigFormat.isForeignConfiguration(foreign));
        assertTrue(PapersDelightConfigFormat.mayMigrate(foreign));
        foreign.set("config_format", PapersDelightConfigFormat.FORMAT);
        assertFalse(PapersDelightConfigFormat.isForeignConfiguration(foreign));
        assertFalse(PapersDelightConfigFormat.mayMigrate(foreign));
        YamlConfiguration future = yaml("config-version: 4\ncustom: retained\n");
        assertFalse(PapersDelightConfigFormat.mayMigrate(future));
        assertEquals(4, future.getInt("config-version"));
        assertEquals("retained", future.getString("custom"));
        future.set("license", "unused");
        future.set("lang", "zh_cn");
        future.setDefaults(foreign);
        assertFalse(PapersDelightConfigFormat.isForeignConfiguration(future), "defaults must not manufacture a foreign-file fingerprint");
    }

    @Test
    void foreignOptionsAreRetainedAndUnsupportedFeaturesAreReported() throws Exception {
        YamlConfiguration source = yaml("""
                garlic_effect: {enable: true}
                stats: {enabled: true, io_wait_millis: 300}
                particle_throttle: {stove_threshold: 8}
                heat_sources:
                  - material: CAMPFIRE
                    tray: true
                """);
        PapersDelightConfigFormat.normalize(source);
        assertTrue(source.getBoolean("garlic_effect.enable"));
        assertEquals(300, source.getInt("stats.io_wait_millis"));
        assertEquals(List.of("garlic_effect", "stats.enabled", "stats.io_wait_millis", "particle_throttle", "heat_sources[].tray"),
                PapersDelightConfigFormat.unsupportedOptions(source));
    }

    @Test
    void runtimeMaterializationDoesNotAddCompatibilityAliasesToTheSavedDocument() throws Exception {
        YamlConfiguration source = yaml("lang: zh_cn\ncooking_pot:\n  tick_budget: 12\nheat_sources: []\n");
        String before = source.saveToString();
        YamlConfiguration view = PapersDelightConfigFormat.runtimeView(source);
        assertEquals(12, view.getInt("cooking-pot.tick-budget"));
        assertEquals(before, source.saveToString());
        view.set("cooking-pot.tick-budget", 99);
        assertEquals(12, source.getInt("cooking_pot.tick_budget"));
    }

    @Test
    void defaultsRemainAvailableThroughLegacyPathsWithoutOverwritingAnExplicitSwitch() throws Exception {
        YamlConfiguration defaults = yaml("cooking_pot:\n  tick_budget: 512\ncomfort_effect:\n  enable: true\n");
        YamlConfiguration source = yaml("comfort_effect:\n  enable: false\n");
        source.setDefaults(defaults);
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeView(source);
        assertEquals(512, runtime.getInt("cooking-pot.tick-budget"));
        assertFalse(runtime.getBoolean("buff.comfort.enabled", true));
        assertFalse(source.contains("cooking_pot.tick_budget", true));
    }

    @Test
    void papersDisplayCoordinatesAndGridGenerateExistingDisplayConfigShapes() throws Exception {
        YamlConfiguration source = yaml("""
                cutting_board:
                  display:
                    translate_x: 0.2
                    translate_y: 0.1
                    translate_z: 0.3
                    scale: 0.7
                    rotation_pitch: 90
                    rotation_y: 30
                    rotation_roll: 5
                    stack_xz_offset: 0.075
                stove:
                  display:
                    translate_y: 1.1
                    slot_1_x: 0.3
                    slot_1_z: 0.2
                    col_spacing: 0.3
                    row_spacing: 0.4
                """);
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertEquals(List.of(0.2, 0.1, 0.3), runtime.getList("cutting-board.default-display-offset"));
        assertEquals(List.of(90.0, 30.0, 5.0), runtime.getList("cutting-board.default-display-rotation"));
        assertEquals(0.7, runtime.getDouble("cutting-board.default-display-scale"));
        assertEquals(0.15, runtime.getDouble("cutting-board.display-item-spread"));
        assertEquals(6, runtime.getStringList("stove.display.slot-offsets").size());
        assertEquals("0.3,1.1,0.2", runtime.getStringList("stove.display.slot-offsets").getFirst());
        assertEquals("-0.3,1.1,-0.2", runtime.getStringList("stove.display.slot-offsets").getLast());
    }

    @Test
    void emptyRegistriesRemainEmptyWhenMissingDefaultsAreMerged() throws Exception {
        YamlConfiguration defaults = yaml("heat_sources:\n  - material: FIRE\ncontainer_returns:\n  test:bottle: test:empty\n");
        YamlConfiguration source = yaml("heat_sources: []\ncontainer_returns: {}\n");
        ConfigFileUpdater.copyMissingKeys(defaults, source,
                PapersDelightConfigFormat.canonicalRegistrySections(List.of("heat-sources", "container-returns")));
        assertTrue(source.getMapList("heat_sources").isEmpty());
        assertTrue(source.getConfigurationSection("container_returns").getKeys(false).isEmpty());
    }

    @Test
    void bundledConfigurationIsCanonicalAndRuntimeKeepsCoreDefaults() {
        YamlConfiguration source = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/config.yml").toFile());
        assertEquals(PapersDelightConfigFormat.VERSION, source.getInt("config-version"));
        assertEquals(PapersDelightConfigFormat.FORMAT, source.getString("config_format"));
        assertFalse(source.contains("license", true));
        assertTrue(source.isList("heat_sources"));
        assertFalse(source.contains("cooking-pot", true));
        YamlConfiguration runtime = PapersDelightConfigFormat.runtimeViewOf(source);
        assertEquals(512, runtime.getInt("cooking-pot.tick-budget"));
        assertEquals(80, runtime.getInt("buff.comfort.heal-interval-ticks"));
        assertEquals(5000, runtime.getInt("performance.shutdown-wait-millis"));
        assertTrue(runtime.getBoolean("enchantments.enabled"));
        assertTrue(runtime.getBoolean("compatibility.kaleidoscope.recipe-auto-fill"));
        assertEquals(50, runtime.getInt("performance.budgets.chunk-effect-packet-budget"));
        assertTrue(runtime.getBoolean("cooking_pot.recipe_book"));
        assertEquals(20, StationSettings.previewCallbacks(runtime.getInt("recipe_book.tag_cycle_interval_ticks")));
        assertEquals(4, runtime.getInt("skillet.particles.interval_ticks"));
        assertEquals(4, runtime.getInt("stove.particles.interval_ticks"));
    }

    @Test
    void veryLongParticleIntervalsCannotOverflowIntoFrequentEffects() throws Exception {
        YamlConfiguration canonical = yaml("cooking_pot:\n  particles:\n    interval_ticks: 9223372036854775807\n");
        assertEquals(Integer.MAX_VALUE, PapersDelightConfigFormat.runtimeViewOf(canonical)
                .getInt("cooking-pot.effects.interval"));
        YamlConfiguration legacy = yaml("cooking-pot:\n  effects:\n    interval: 2147483647\n");
        PapersDelightConfigFormat.normalize(legacy);
        assertEquals(8_589_934_588L, legacy.getLong("cooking_pot.particles.interval_ticks"));
        assertEquals(Integer.MAX_VALUE, PapersDelightConfigFormat.runtimeViewOf(legacy)
                .getInt("cooking-pot.effects.interval"));
    }
}
