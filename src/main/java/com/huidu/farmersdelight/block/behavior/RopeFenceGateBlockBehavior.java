package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.FenceGateBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Keeps native opening and redstone behavior while requiring walls on both sides. */
public final class RopeFenceGateBlockBehavior extends BukkitBlockBehavior {
    private final FenceGateBlockBehavior gate;
    private static final Key WALLS = Key.of("minecraft:walls");

    private RopeFenceGateBlockBehavior(BlockDefinition block, FenceGateBlockBehavior gate) {
        super(block);
        this.gate = gate;
    }

    public static final BlockBehaviorFactory<RopeFenceGateBlockBehavior> FACTORY = (block, section) ->
            new RopeFenceGateBlockBehavior(block, FenceGateBlockBehavior.FACTORY.create(block,
                    section == null ? ConfigSection.ofRoot(java.util.Map.of()) : section));

    @Override public boolean isPathFindable(Object self, Object[] args) { return gate.isPathFindable(self, nativeArguments(args)); }

    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        World world = (World) context.getLevel().platformWorld();
        Block block = RopeWorldAccess.block(world, context.getClickedPos());
        if (block == null) return state;
        boolean signal = block.isBlockIndirectlyPowered();
        Property<Direction> facing = RopeStateProperties.property(state, "facing", Direction.class);
        if (facing == null) return state;
        ImmutableBlockState updated = RopeStateProperties.withBoolean(RopeStateProperties.withBoolean(
                state.with(facing, context.getHorizontalDirection()), "open", signal), "powered", signal);
        return updateWalls(updated, world, context.getClickedPos());
    }

    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        Player player = ItemUtils.getBukkitPlayer(context.getPlayer());
        Block block = RopeWorldAccess.block((World) context.getLevel().platformWorld(), context.getClickedPos());
        if (player == null || block == null || !RopeBlockBehavior.playerOwned(player)
                || !ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.ROPE)) return InteractionResult.PASS;
        return gate.useWithoutItem(context, nativeState(state));
    }

    @Override public void neighborChanged(Object self, Object[] args) {
        if (args == null || args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (!RopeWorldAccess.available(world, pos)) return;
        gate.neighborChanged(self, nativeArguments(args));
        RopeBlockListener.queueNeighborRefresh(world, pos);
    }

    @Override public Object updateShape(Object self, Object[] args) {
        if (args == null || args.length <= Math.max(updateShape$level, updateShape$blockPos)) return args == null || args.length == 0 ? null : args[0];
        World world = CraftEngineAdapter.toWorld(args[updateShape$level]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[updateShape$blockPos]);
        if (!RopeWorldAccess.available(world, pos)) return args[0];
        ImmutableBlockState state = BlockStateUtils.getNullableCustomBlockState(args[0]);
        if (state == null || state.owner().value() != block()) return args[0];
        ImmutableBlockState updated = updateWalls(state, world, pos);
        return updated == state ? args[0] : updated.customBlockState().minecraftState();
    }

    private ImmutableBlockState updateWalls(ImmutableBlockState state, World world, BlockPos pos) {
        Property<Direction> facingProperty = RopeStateProperties.property(state, "facing", Direction.class);
        Property<Boolean> wallProperty = RopeStateProperties.property(state, "in_wall", Boolean.class);
        if (facingProperty == null || wallProperty == null) return state;
        Direction facing = state.get(facingProperty);
        Direction side = facing.axis() == Direction.Axis.Z ? Direction.EAST : Direction.NORTH;
        Block left = RopeWorldAccess.block(world, pos.relative(side));
        Block right = RopeWorldAccess.block(world, pos.relative(side.opposite()));
        if (left == null || right == null) return state;
        return state.with(wallProperty, RopeConnectionRules.bothWalls(wall(left), wall(right)));
    }

    private static boolean wall(Block block) {
        ImmutableBlockState custom = CustomBlockUtils.getStateIfResident(block);
        return custom == null || custom.isEmpty() ? BlockStateUtils.toBlockStateWrapper(block.getBlockData()).hasTag(WALLS)
                : custom.customBlockState().hasTag(WALLS);
    }

    private ImmutableBlockState nativeState(ImmutableBlockState state) {
        if (state.contains(gate.facingProperty) && state.contains(gate.openProperty)
                && state.contains(gate.poweredProperty) && state.contains(gate.inWallProperty)) return state;
        return gate.block().defaultState().with(state.propertiesNbt());
    }

    private Object[] nativeArguments(Object[] args) {
        if (args == null || args.length == 0) return args;
        ImmutableBlockState state = BlockStateUtils.getNullableCustomBlockState(args[0]);
        if (state == null) return args;
        ImmutableBlockState current = nativeState(state);
        if (current == state) return args;
        Object[] converted = args.clone();
        converted[0] = current.customBlockState().minecraftState();
        return converted;
    }

    public static void refresh(Block block) {
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        if (!RopeWorldAccess.available(block.getWorld(), pos)) return;
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        RopeFenceGateBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeFenceGateBlockBehavior.class);
        if (behavior == null) return;
        ImmutableBlockState updated = behavior.updateWalls(state, block.getWorld(), pos);
        if (updated != state) CraftEngineBlocks.place(block.getLocation(), updated, UpdateFlags.UPDATE_CLIENTS, false);
    }
}
