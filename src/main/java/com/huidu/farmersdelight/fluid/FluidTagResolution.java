package com.huidu.farmersdelight.fluid;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

/** An item without a container handler can represent only an unambiguous registered fluid identity. */
final class FluidTagResolution {
    record Resolution(String fluidId, FluidCoreBridge.Outcome failure) { }

    static String effectiveTag(String requested, boolean declared, Collection<?> members) {
        if (declared || !members.isEmpty()) return requested;
        return switch (requested) {
            case "c:water" -> "fluidcore:water";
            case "c:milk" -> "fluidcore:milk";
            case "c:lava" -> "fluidcore:lava";
            case "c:honey" -> "fluidcore:honey";
            default -> requested;
        };
    }

    static Resolution uniqueMember(Collection<String> members, Predicate<String> registered) {
        var unique = new LinkedHashSet<>(members);
        if (unique.isEmpty()) return new Resolution(null, FluidCoreBridge.Outcome.NO_FLUID);
        if (unique.size() != 1) return new Resolution(null, FluidCoreBridge.Outcome.UNSUPPORTED_VARIANT);
        String fluid = unique.iterator().next();
        return registered.test(fluid) ? new Resolution(fluid, null)
                : new Resolution(null, FluidCoreBridge.Outcome.NO_FLUID);
    }

    private FluidTagResolution() { }
}
