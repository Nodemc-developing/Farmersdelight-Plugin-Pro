package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** World scans must not load another chunk or read another Folia region. */
public final class RopeWorldAccess {
    private RopeWorldAccess() { }

    public static boolean available(World world, BlockPos pos) {
        if (world == null || pos == null || pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) return false;
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return Bukkit.isOwnedByCurrentRegion(location) && world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4);
    }

    public static Block block(World world, BlockPos pos) {
        return available(world, pos) ? world.getBlockAt(pos.x(), pos.y(), pos.z()) : null;
    }
}
