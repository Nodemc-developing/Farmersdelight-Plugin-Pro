package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.util.Direction;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RopeConnectionRulesTest {
    @Test void aGateAcceptsConnectionsOnlyAtItsPosts() {
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            assertTrue(RopeConnectionRules.gateConnects(facing, BlockFace.EAST));
            assertTrue(RopeConnectionRules.gateConnects(facing, BlockFace.WEST));
            assertFalse(RopeConnectionRules.gateConnects(facing, BlockFace.NORTH));
            assertFalse(RopeConnectionRules.gateConnects(facing, BlockFace.SOUTH));
        }
        for (Direction facing : new Direction[]{Direction.EAST, Direction.WEST}) {
            assertTrue(RopeConnectionRules.gateConnects(facing, BlockFace.NORTH));
            assertTrue(RopeConnectionRules.gateConnects(facing, BlockFace.SOUTH));
            assertFalse(RopeConnectionRules.gateConnects(facing, BlockFace.EAST));
            assertFalse(RopeConnectionRules.gateConnects(facing, BlockFace.WEST));
        }
        assertFalse(RopeConnectionRules.gateConnects(Direction.UP, BlockFace.EAST));
        assertFalse(RopeConnectionRules.gateConnects(Direction.NORTH, BlockFace.UP));
    }

    @Test void oneWallDoesNotTurnTheRopeGateIntoAWallGate() {
        assertFalse(RopeConnectionRules.bothWalls(false, false));
        assertFalse(RopeConnectionRules.bothWalls(true, false));
        assertFalse(RopeConnectionRules.bothWalls(false, true));
        assertTrue(RopeConnectionRules.bothWalls(true, true));
    }
}
