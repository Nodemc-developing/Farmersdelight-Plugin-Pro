package com.huidu.farmersdelight.effect;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContentEffectFunctionTest {
    @Test void amplifierChangesSaturateWithoutOverflow() {
        assertEquals(4, ContentEffectFunction.upgradedAmplifier(3, Integer.MAX_VALUE, 4));
        assertEquals(0, ContentEffectFunction.upgradedAmplifier(3, Integer.MIN_VALUE, 4));
        assertEquals(2, ContentEffectFunction.upgradedAmplifier(1, 1, 4));
        assertEquals(0, ContentEffectFunction.upgradedAmplifier(1, 1, -1));
    }
    @Test void harmfulFilterDoesNotStripOrdinaryBeneficialEffects() {
        assertTrue(ContentEffectFunction.isHarmful("poison"));
        assertTrue(ContentEffectFunction.isHarmful("darkness"));
        assertFalse(ContentEffectFunction.isHarmful("regeneration"));
        assertFalse(ContentEffectFunction.isHarmful("speed"));
    }
    @Test void teleportTargetsRejectCommonDamageAndPortalCells() {
        for (String hazard : new String[]{"FIRE", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW", "WITHER_ROSE", "END_PORTAL"})
            assertFalse(ContentEffectFunction.safeTeleportMaterial(hazard), hazard);
        assertTrue(ContentEffectFunction.safeTeleportMaterial("STONE"));
        assertTrue(ContentEffectFunction.safeTeleportMaterial("AIR"));
    }
}
