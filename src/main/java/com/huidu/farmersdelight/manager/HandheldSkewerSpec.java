package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.block.behavior.CropBehaviorOptions;
import net.momirealms.craftengine.core.util.Key;
import java.util.Map;

public record HandheldSkewerSpec(Key source, Key cookingProxy, Key result, int cookTicks) {
    public HandheldSkewerSpec {
        if (source == null || cookingProxy == null || result == null) throw new IllegalArgumentException("Skewer item identifiers are required");
        if (cookTicks < 1 || cookTicks > 72_000) throw new IllegalArgumentException("cook_ticks must be in [1, 72000]");
    }
    public static HandheldSkewerSpec parse(Key source, Map<String, Object> arguments) {
        return new HandheldSkewerSpec(source, identifier(arguments, "cooking_proxy"), identifier(arguments, "result"),
                CropBehaviorOptions.integer(arguments, "cook_ticks", 120));
    }
    private static Key identifier(Map<String, Object> arguments, String name) {
        Object value = CropBehaviorOptions.get(arguments, name);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(name + " must be a nonempty item identifier");
        return Key.of(text);
    }
}
