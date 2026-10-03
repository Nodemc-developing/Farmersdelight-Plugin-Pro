package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import static org.junit.jupiter.api.Assertions.*;

class StationCompatibilitySettingsTest {
    @Test
    void flatDisplayCoordinatesAndStackSpacingAreNotAddedTwice() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                cutting_board:
                  default-display-offset: [0, 0.11, 0]
                  display:
                    translate_y: 0.11
                    scale: 0.7
                    stack_y_offset: 0.035
                    stack_xz_offset: 0.075
                    rotation_pitch: 90
                """);
        CuttingBoardDisplayConfig config = new CuttingBoardDisplayConfig();
        config.loadFromConfig(yaml.getConfigurationSection("cutting_board"));
        assertEquals(new Vector3f(0, .11F, 0), config.getDefaultOffset());
        assertEquals(.7F, config.getDefaultUniformScale(1));
        assertEquals(.15F, config.getItemSpread(), .00001F);
        assertEquals(.035F, config.getStackYOffset(null), .00001F);
        assertTrue(config.isAbsolutePosition(null));
        assertEquals(90, config.getOverride(null).rotationDegrees().x());
    }

    @Test
    void soundRangeNormalizesBothSpellingsAndFortuneRemainsIndependent() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                cutting-board:
                  fortune_bonus: 0.25
                  sounds:
                    place_item: {sound: test:place, volume: 0.6, pitch_min: 1.2, pitch_max: 0.8}
                    remove_item: {sound: test:remove, volume: 0.3, pitch: 0.7}
                    carve_tool: {sound: test:carve, volume: 0.4, pitch: 1.3}
                """);
        CuttingBoardSounds sounds = CuttingBoardSounds.from(yaml.getConfigurationSection("cutting-board.sounds"));
        assertEquals("test:place", sounds.place().soundKey());
        assertEquals(.8F, sounds.place().pitchMin());
        assertEquals(1.2F, sounds.place().pitchMax());
        assertEquals("test:remove", sounds.remove().soundKey());
        assertEquals("test:carve", sounds.carve().soundKey());
        assertEquals(.25, sounds.fortuneBonus());
    }
}
