package com.huidu.farmersdelight.fluid;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FluidTagResolutionTest {
    @Test void onlyOneRegisteredMemberCanSupplyAContainerlessFixedRecipe() {
        var known = FluidTagResolution.uniqueMember(List.of("minecraft:water"), "minecraft:water"::equals);
        assertEquals("minecraft:water", known.fluidId());
        assertNull(known.failure());
        var unknown = FluidTagResolution.uniqueMember(List.of("example:missing"), ignored -> false);
        assertNull(unknown.fluidId());
        assertEquals(FluidCoreBridge.Outcome.NO_FLUID, unknown.failure());
        assertEquals(FluidCoreBridge.Outcome.NO_FLUID,
                FluidTagResolution.uniqueMember(List.of(), ignored -> true).failure());
    }

    @Test void ambiguousMembersNeverSelectTheFirstVariantIncludingUnregisteredMembers() {
        var result = FluidTagResolution.uniqueMember(List.of("minecraft:water", "example:unregistered"),
                "minecraft:water"::equals);
        assertNull(result.fluidId());
        assertEquals(FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT, result.failure());
        assertEquals("minecraft:water", FluidTagResolution.uniqueMember(
                List.of("minecraft:water", "minecraft:water"), ignored -> true).fluidId());
    }

    @Test void aliasesApplyOnlyWhenTheRealTagHasNeitherDeclarationNorMembers() {
        assertEquals("fluidcore:water", FluidTagResolution.effectiveTag("c:water", false, Set.of()));
        assertEquals("fluidcore:milk", FluidTagResolution.effectiveTag("c:milk", false, Set.of()));
        assertEquals("fluidcore:lava", FluidTagResolution.effectiveTag("c:lava", false, Set.of()));
        assertEquals("c:water", FluidTagResolution.effectiveTag("c:water", true, Set.of()));
        assertEquals("c:water", FluidTagResolution.effectiveTag("c:water", false, Set.of("example:water")));
        assertEquals("c:honey", FluidTagResolution.effectiveTag("c:honey", false, Set.of()));
    }
}
