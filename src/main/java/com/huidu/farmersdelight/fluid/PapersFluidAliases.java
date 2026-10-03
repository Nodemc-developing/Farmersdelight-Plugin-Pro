package com.huidu.farmersdelight.fluid;

import org.bukkit.Bukkit;

/** Keeps optional FluidCore types out of the ordinary cooking plugin's loading path. */
public final class PapersFluidAliases {
    private static boolean ownsJug;
    private PapersFluidAliases() {}
    static boolean ownsJug() { return ownsJug; }
    public static void register() {
        if (Bukkit.getPluginManager().getPlugin("FluidCore") == null) {
            Bukkit.getLogger().info("[Farmersdelight-Plugin-Pro] FluidCore is absent; fluid content is unavailable. Cooking features remain enabled.");
            return;
        }
        try { ownsJug = TypedFluidContentAliases.register(); }
        catch (LinkageError | RuntimeException incompatible) {
            Bukkit.getLogger().warning("[Farmersdelight-Plugin-Pro] FluidCore content API could not be linked: " + incompatible.getMessage());
        }
    }
}
