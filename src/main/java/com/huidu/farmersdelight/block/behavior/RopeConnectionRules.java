package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.util.Direction;
import org.bukkit.block.BlockFace;

final class RopeConnectionRules {
    private RopeConnectionRules() { }

    static boolean gateConnects(Direction facing, BlockFace side) {
        return switch (facing) {
            case NORTH, SOUTH -> side == BlockFace.EAST || side == BlockFace.WEST;
            case EAST, WEST -> side == BlockFace.NORTH || side == BlockFace.SOUTH;
            default -> false;
        };
    }

    static boolean bothWalls(boolean first, boolean second) { return first && second; }
}
