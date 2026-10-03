package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CompatibilityMechanicsTest {
    private static Map<String, Object> crop(Map<String, Object> extra) {
        Map<String, Object> config = new LinkedHashMap<>(extra);
        config.put("soils", List.of(Map.of("block", "minecraft:farmland[moisture=7]", "growth_modifier", -1, "bonemeal_chance", .1)));
        return config;
    }
    @Test void cropFieldsUseBothSpellingsAndKeepExactSoilDescriptor() {
        CropBehaviorOptions options = CropBehaviorOptions.parse(crop(Map.of("grow-speed", .25,
                "spawn-light-requirement", 9, "can-harvest-by-villagers", true, "upper-min-age", 2)), 7);
        assertEquals(.25f, options.speed());
        assertEquals(9, options.spawnLight());
        assertTrue(options.villageHarvest());
        assertEquals(-1, options.soils().getFirst().growthModifier());
        assertEquals("minecraft:farmland[moisture=7]", options.soils().getFirst().block());
        assertThrows(UnsupportedOperationException.class, () -> options.soils().clear());
    }
    @Test void impossibleCropStatesFailBeforeRuntime() {
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("max_age", 8), Map.<String, Object>of("upper_min_age", 8),
                Map.<String, Object>of("ropelogged_min_age", -1), Map.<String, Object>of("light_requirement", 16),
                Map.<String, Object>of("ropelogged_max_height", 257), Map.<String, Object>of("max_age", 1.5),
                Map.<String, Object>of("max_age", Long.MAX_VALUE), Map.<String, Object>of("grow_speed", Double.NaN))) {
            assertThrows(IllegalArgumentException.class, () -> CropBehaviorOptions.parse(crop(invalid), 7), invalid.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> CropBehaviorOptions.parse(Map.of("soils", List.of(Map.of("block", "minecraft:dirt", "bonemeal_chance", 2))), 7));
        assertThrows(IllegalArgumentException.class, () -> CropBehaviorOptions.parse(Map.of("soils", List.of()), 7));
    }
    @Test void boneMealOverflowCannotWrapOrMoveAgeBackwards() {
        assertEquals(7, CropBehaviorOptions.incrementAge(6, Integer.MAX_VALUE, 7));
        assertEquals(6, CropBehaviorOptions.incrementAge(6, -100, 7));
        assertEquals(0, CropBehaviorOptions.incrementAge(-10, 0, 7));
    }
    @Test void translatedGroupedSettingsReachTheActualBehaviorReader() {
        Map<String, Object> original = new LinkedHashMap<>(Map.of("activator_multiplier", .7,
                "activator", Map.of("radius", 3), "water_bonus", .8, "light_threshold", 11));
        ConfigSection converted = CompatibilityMechanicFactories.compost(ConfigSection.ofRoot(original));
        assertEquals(.7, BehaviorArgParser.getDouble(converted.values(), "activator.bonus-per-neighbor", 0));
        assertEquals(3, BehaviorArgParser.getInt(converted.values(), "activator.radius", 0));
        assertEquals(.8, BehaviorArgParser.getDouble(converted.values(), "water.bonus", 0));
        assertEquals(11, BehaviorArgParser.getInt(converted.values(), "light.threshold", 0));
        assertFalse(original.containsKey("water"));
        assertEquals(Map.of("radius", 3), original.get("activator"));
    }
    @Test void publicInteractionAndCollectionSettingsAreEffective() {
        var pair = CompatibilityMechanicFactories.pair(ConfigSection.ofRoot(new LinkedHashMap<>(Map.of("disable_when_sneaking", false, "facing", "orientation", "paired", "joined"))));
        assertTrue(BehaviorArgParser.getBoolean(pair.values(), "pair.while-sneaking", false));
        assertEquals("orientation", pair.getString("facing-property"));
        assertEquals("joined", pair.getString("paired-property"));
        assertEquals(1, CompatibilityMechanicFactories.basket(ConfigSection.ofRoot(new LinkedHashMap<>())).getInt("transfer-cooldown"));
        assertEquals(6, CompatibilityMechanicFactories.basket(ConfigSection.ofRoot(new LinkedHashMap<>(Map.of("collect_interval", 6)))).getInt("transfer-cooldown"));
    }
    @Test void heatAreaUsesPixelsAndOnlyTouchesTheConfiguredTopFace() {
        var area = HeatContactVolume.parse(List.of(4, 4, 12, 12));
        assertTrue(area.touches(new BoundingBox(10.4, 21, 30.4, 10.6, 22, 30.6), 10, 20, 30));
        assertFalse(area.touches(new BoundingBox(10, 21, 30, 10.2, 22, 30.2), 10, 20, 30));
        assertFalse(area.touches(new BoundingBox(10.4, 20, 30.4, 10.6, 20.9, 30.6), 10, 20, 30));
        assertEquals(.5, HeatContactVolume.parse(List.of(0, 0, 0, 16, 8, 16)).y1());
        for (List<?> invalid : List.of(List.of(0, 0, 0), List.of(8, 0, 4, 16), List.of(-1, 0, 16, 16), List.of(0, 0, Double.NaN, 16)))
            assertThrows(IllegalArgumentException.class, () -> HeatContactVolume.parse(invalid));
    }
    @Test void comparatorAndMoistureStayInBounds() {
        assertEquals(0, IntegerComparatorBlockBehavior.signal(-1, 7));
        assertEquals(15, IntegerComparatorBlockBehavior.signal(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(15, IntegerComparatorBlockBehavior.signal(99, 7));
        assertEquals(6, IntegerComparatorBlockBehavior.signal(3, 7));
        assertEquals(0, IntegerComparatorBlockBehavior.signal(3, 0));
        assertEquals(7, ManagedFarmlandBlockBehavior.nextMoisture(0, 7, true));
        assertEquals(0, ManagedFarmlandBlockBehavior.nextMoisture(0, 7, false));
        assertEquals(6, ManagedFarmlandBlockBehavior.nextMoisture(7, 7, false));
    }
}
