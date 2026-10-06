/* SPDX-License-Identifier: AGPL-3.0-only
 * Food point and planting-reserve rules incorporate VillagersDelight by HuiDu_OwO.
 * Modified for aggregate inventory accounting in Farmersdelight-Plugin-Pro.
 */
package com.huidu.farmersdelight.villager;

import java.util.Map;
import java.util.Set;

/** Immutable food rules; callers supply the total held across every inventory slot. */
public record VillagerFoodRules(Map<String, Integer> points, Set<String> seeds, int minimumKeptSeeds) {
    public VillagerFoodRules {
        points = Map.copyOf(points);
        seeds = Set.copyOf(seeds);
        if (minimumKeptSeeds < 0 || minimumKeptSeeds > 512
                || points.values().stream().anyMatch(value -> value < 0 || value > 12))
            throw new IllegalArgumentException("Villager food points must be 0..12 and seed reserves 0..512");
    }
    public int value(String id) { return points.getOrDefault(id, 0); }
    public int reserve(String id, boolean farmer) { return farmer && seeds.contains(id) ? minimumKeptSeeds : 0; }
    public int spare(String id, int held, boolean farmer) { return Math.max(0, held - reserve(id, farmer)); }
    public int consumable(String id, int held, int neededPoints, boolean farmer) {
        int value = value(id);
        if (value == 0 || neededPoints <= 0) return 0;
        return (int) Math.min(spare(id, held, farmer), ((long) neededPoints + value - 1) / value);
    }
    public int shareable(String id, int held, boolean farmer) { return value(id) > 0 ? spare(id, held, farmer) : 0; }
}
