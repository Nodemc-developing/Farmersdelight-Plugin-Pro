package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.AutoStateGroup;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.pack.allocator.VisualBlockStateAllocator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Loading-phase aliases reuse existing dry-rope visuals without discarding any saved state mapping. */
public final class RopeVisualStateAliases {
    private RopeVisualStateAliases() { }
    private static final String WET_ALIAS = "kelp[id=farmersdelight:rope/waterlogged]";

    // The order is the existing content pack's sixteen connection appearances.
    private static final String[] LEGACY_APPEARANCES = {
            "east=false,north=false,south=false,west=false",
            "east=false,north=true,south=false,west=false",
            "east=true,north=false,south=false,west=false",
            "east=false,north=false,south=true,west=false",
            "east=false,north=false,south=false,west=true",
            "east=false,north=true,south=true,west=false",
            "east=true,north=false,south=false,west=true",
            "east=true,north=true,south=false,west=false",
            "east=true,north=false,south=true,west=false",
            "east=false,north=false,south=true,west=true",
            "east=false,north=true,south=false,west=true",
            "east=true,north=true,south=true,west=false",
            "east=false,north=true,south=true,west=true",
            "east=true,north=false,south=true,west=true",
            "east=true,north=true,south=false,west=true",
            "east=true,north=true,south=true,west=true"
    };

    /** Invoke after CE resets/loads its allocator and before its block definition parser runs. */
    public static int install(VisualBlockStateAllocator allocator) {
        synchronized (allocator) {
            Map<String, BlockStateWrapper> aliases = choose(allocator.cachedBlockStates(), AutoStateGroup.TWISTING_VINES::test);
            int completed = 0;
            for (var entry : aliases.entrySet()) {
                // CE's cachedBlockStates view is immutable. Completing the public allocation future also
                // avoids the allocator's duplicate-candidate guard when an old key has reserved this state.
                if (allocator.requestAutoState(entry.getKey(), AutoStateGroup.TWISTING_VINES).complete(entry.getValue())) ++completed;
            }
            for (var entry : chooseWet(allocator.cachedBlockStates(), AutoStateGroup.KELP::test).entrySet()) {
                if (allocator.requestAutoState(entry.getKey(), AutoStateGroup.KELP).complete(entry.getValue())) ++completed;
            }
            return completed;
        }
    }

    static <T> Map<String, T> choose(Map<String, T> cache, Predicate<T> inGroup) {
        Map<String, T> aliases = new LinkedHashMap<>();
        for (int i = 0; i < LEGACY_APPEARANCES.length; ++i) {
            String alias = "twisting_vines[id=farmersdelight:rope/appearance_" + i + "]";
            T selected = cache.get(alias);
            if (selected == null || !inGroup.test(selected)) selected = cache.get("farmersdelight:rope[appearance=" + LEGACY_APPEARANCES[i] + "]");
            if (selected != null && inGroup.test(selected)) aliases.put(alias, selected);
        }
        return aliases;
    }

    static <T> Map<String, T> chooseWet(Map<String, T> cache, Predicate<T> inGroup) {
        T selected = cache.get(WET_ALIAS);
        if (selected != null && inGroup.test(selected)) return Map.of(WET_ALIAS, selected);
        for (int i = 16; i < 32; ++i) {
            selected = cache.get("kelp[id=farmersdelight:rope/appearance_" + i + "]");
            if (selected != null && inGroup.test(selected)) return Map.of(WET_ALIAS, selected);
        }
        return Map.of();
    }
}
