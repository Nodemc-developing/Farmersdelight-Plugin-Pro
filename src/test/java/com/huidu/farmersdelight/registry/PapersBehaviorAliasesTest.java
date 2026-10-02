package com.huidu.farmersdelight.registry;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PapersBehaviorAliasesTest {
    @Test void stoveAliasUsesLitAndLeavesContentPackEventsInChargeOfIgnitionAndDamage() {
        ConfigSection source = ConfigSection.ofRoot(Map.of("type", "papersdelight:stove",
                "sound", "minecraft:block.fire.ambient"));
        ConfigSection normalized = PapersBehaviorAliases.stoveSection(source);
        assertEquals("lit", normalized.get("property"));
        assertEquals("minecraft:block.fire.ambient", normalized.get("crackle-sound"));
        assertEquals(false, normalized.getSection("burn").get("enabled"));
        assertEquals(false, normalized.getSection("ignite").get("enabled"));
        assertEquals(false, normalized.getSection("extinguish").get("enabled"));
        assertFalse(source.containsKey("property"));
        assertFalse(source.containsKey("ignite"));
    }

    @Test void explicitExtensionSettingsRemainAuthoritative() {
        ConfigSection source = ConfigSection.ofRoot(Map.of("property", "fire", "burn", Map.of("enabled", true),
                "crackle-sound", "minecraft:block.lava.ambient", "sound", "minecraft:block.fire.ambient"));
        ConfigSection normalized = PapersBehaviorAliases.stoveSection(source);
        assertEquals("fire", normalized.get("property"));
        assertEquals(true, normalized.getSection("burn").get("enabled"));
        assertEquals("minecraft:block.lava.ambient", normalized.get("crackle-sound"));
    }

    @Test void unsupportedMechanicsRemainExplicitAndUnregisteredByTheAliasLayer() {
        assertTrue(PapersBehaviorAliases.unsupportedIdentifiers().contains("papersdelight:advanced_crop"));
        assertTrue(PapersBehaviorAliases.unsupportedIdentifiers().contains("papersdelight:high_temperature"));
        assertFalse(PapersBehaviorAliases.unsupportedIdentifiers().contains("papersdelight:cooking_pot"));
        assertThrows(UnsupportedOperationException.class, () -> PapersBehaviorAliases.unsupportedIdentifiers().clear());
    }
}
