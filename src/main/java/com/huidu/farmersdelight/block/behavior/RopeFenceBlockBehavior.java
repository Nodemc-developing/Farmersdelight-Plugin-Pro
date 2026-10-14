package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.FenceBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
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
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.entity.Player;

/** A fence family with its own connection rules and CraftEngine's native lead interaction. */
public final class RopeFenceBlockBehavior extends BukkitBlockBehavior {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private final FenceBlockBehavior leadInteraction;
    private final Key gate;

    private RopeFenceBlockBehavior(BlockDefinition block, FenceBlockBehavior leadInteraction, Key gate) {
        super(block);
        this.leadInteraction = leadInteraction;
        this.gate = gate;
    }

    public static final BlockBehaviorFactory<RopeFenceBlockBehavior> FACTORY = (block, section) -> {
        ConfigSection config = section == null ? ConfigSection.ofRoot(java.util.Map.of()) : section;
        return new RopeFenceBlockBehavior(block, FenceBlockBehavior.FACTORY.create(block, config),
                Key.of(config.getString("rope-gate", "farmersdelight:rope_fence_gate")));
    };

    @Override public boolean isPathFindable(Object self, Object[] args) { return false; }

    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return connections(state, (World) context.getLevel().platformWorld(), context.getClickedPos());
    }

    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;
        Player player = ItemUtils.getBukkitPlayer(context.getPlayer());
        World world = (World) context.getLevel().platformWorld();
        Block block = RopeWorldAccess.block(world, context.getClickedPos());
        if (player == null || block == null || !RopeBlockBehavior.playerOwned(player)
                || !ProtectionCompat.canUse(player, block, ProtectionCompat.Feature.ROPE)) return InteractionResult.PASS;
        return leadInteraction.useWithoutItem(context, state);
    }

    @Override public void neighborChanged(Object self, Object[] args) {
        if (args != null && args.length > 2) RopeBlockListener.queueNeighborRefresh(
                CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[2]));
    }

    @Override public Object updateShape(Object self, Object[] args) {
        if (args == null || args.length <= Math.max(updateShape$level, updateShape$blockPos)) return args == null || args.length == 0 ? null : args[0];
        World world = CraftEngineAdapter.toWorld(args[updateShape$level]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[updateShape$blockPos]);
        if (!RopeWorldAccess.available(world, pos)) return args[0];
        ImmutableBlockState state = BlockStateUtils.getNullableCustomBlockState(args[0]);
        if (state == null || state.owner().value() != block()) return args[0];
        Direction direction = DirectionUtils.fromNMSDirection(args[updateShape$direction]);
        if (!direction.axis().isHorizontal()) return args[0];
        BlockFace side = BlockFace.valueOf(direction.name());
        Property<Boolean> property = sideProperty(state, side);
        Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + side.getModX(), pos.y(), pos.z() + side.getModZ()));
        if (property == null || neighbor == null) return args[0];
        ImmutableBlockState updated = state.with(property, connects(neighbor, side));
        return updated == state ? args[0] : updated.customBlockState().minecraftState();
    }

    private ImmutableBlockState connections(ImmutableBlockState state, World world, BlockPos pos) {
        if (!RopeWorldAccess.available(world, pos)) return state;
        ImmutableBlockState updated = state;
        for (BlockFace side : SIDES) {
            Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + side.getModX(), pos.y(), pos.z() + side.getModZ()));
            Property<Boolean> property = sideProperty(state, side);
            if (neighbor != null && property != null) updated = updated.with(property, connects(neighbor, side));
        }
        return updated;
    }

    private boolean connects(Block neighbor, BlockFace side) {
        ImmutableBlockState custom = CustomBlockUtils.getStateIfResident(neighbor);
        if (CustomBlockUtils.hasBehavior(custom, RopeFenceBlockBehavior.class)) return true;
        if (custom != null && !custom.isEmpty() && gate.equals(custom.owner().value().id())) {
            Property<Direction> facing = RopeStateProperties.property(custom, "facing", Direction.class);
            return facing != null && RopeConnectionRules.gateConnects(custom.get(facing), side);
        }
        var wrapper = custom == null || custom.isEmpty() ? BlockStateUtils.toBlockStateWrapper(neighbor.getBlockData()) : custom.customBlockState();
        return !FenceBlockBehavior.isExceptionForConnection(wrapper)
                && neighbor.getBlockData().isFaceSturdy(side.getOppositeFace(), BlockSupport.FULL);
    }

    private static Property<Boolean> sideProperty(ImmutableBlockState state, BlockFace side) {
        return RopeStateProperties.property(state, side.name().toLowerCase(java.util.Locale.ROOT), Boolean.class);
    }

    public static void refresh(Block block) {
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        if (!RopeWorldAccess.available(block.getWorld(), pos)) return;
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        RopeFenceBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeFenceBlockBehavior.class);
        if (behavior == null) return;
        ImmutableBlockState updated = behavior.connections(state, block.getWorld(), pos);
        if (updated != state) CraftEngineBlocks.place(block.getLocation(), updated, UpdateFlags.UPDATE_CLIENTS, false);
    }
}
