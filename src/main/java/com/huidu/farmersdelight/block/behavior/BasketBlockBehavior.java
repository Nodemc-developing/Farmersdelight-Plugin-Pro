package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Map;

public class BasketBlockBehavior extends FarmersDelightBlockBehavior implements EntityBlock {

    // Wait eight ticks after a successful pickup.
    public static final int DEFAULT_TRANSFER_COOLDOWN = 8;

    // Captured from the factory so the controller it creates does not have to look the plugin up.
    private final FarmersDelightPlugin plugin;
    private final int transferCooldown;
    private final boolean eject;
    private final boolean redstoneLock;
    private final String facingProperty;
    private final String enabledProperty;

    private BasketBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, int transferCooldown, boolean eject,
                                boolean redstoneLock, String facingProperty, String enabledProperty) {
        super(block);
        this.plugin = plugin;
        this.transferCooldown = transferCooldown;
        this.eject = eject;
        this.redstoneLock = redstoneLock;
        this.facingProperty = facingProperty;
        this.enabledProperty = enabledProperty;
    }

    public static final BlockBehaviorFactory<BasketBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        // Runs while CraftEngine parses the pack, which is always after this plugin enabled.
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        String cooldownKey = BehaviorArgParser.isPresent(arguments, "transfer-cooldown") ? "transfer-cooldown" : "collect-interval";
        int cooldown = Math.max(1, BehaviorArgParser.getInt(arguments, cooldownKey, DEFAULT_TRANSFER_COOLDOWN));
        // Baskets collect through their opening; a hopper can take items out of the storage inventory.
        // Direct insertion into a faced container is an optional extension.
        boolean eject = BehaviorArgParser.getBoolean(arguments, "eject", false);
        boolean lock = BehaviorArgParser.getBoolean(arguments, "redstone-lock", true);
        String facing = BehaviorArgParser.getString(arguments, "facing-property", "facing");
        String enabled = BehaviorArgParser.getString(arguments, "enabled-property", "enabled");
        return new BasketBlockBehavior(plugin, block, cooldown, eject, lock, facing, enabled);
    };

    @Override
    public BlockEntityController createBlockEntityController(BlockEntity blockEntity) {
        return new BasketVacuumController(plugin, blockEntity, transferCooldown, eject, redstoneLock, facingProperty, enabledProperty);
    }

    @Override
    public void initControllerId(int id) {
    }

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        if (!redstoneLock || !(context.getLevel().platformWorld() instanceof World world)) return state;
        BlockPos pos = context.getClickedPos();
        return enabledState(state, enabledProperty, world.getBlockAt(pos.x(), pos.y(), pos.z()).isBlockIndirectlyPowered());
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (!redstoneLock || args == null || args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (plugin != null && !plugin.scheduler().isOwnedByCurrentRegion(location)) return;
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(location.getBlock());
        if (state == null || state.isEmpty() || state.owner().value() != block()) return;
        boolean powered = location.getBlock().isBlockIndirectlyPowered();
        CustomBlockUtils.notifyControllerChanged(world, new BlockPosKey(pos), BasketVacuumController.class, null,
                controller -> controller.redstoneChanged(powered));
        syncEnabled(location, state, enabledProperty, powered);
    }

    static ImmutableBlockState enabledState(ImmutableBlockState state, String property, boolean powered) {
        // A loaded state can outlive the registry definition after a content reload.
        // Properties belong to the immutable state, even when its holder points at a new definition.
        Property<Boolean> enabled = state.getProperty(property);
        return enabled == null ? state : state.with(enabled, !powered);
    }

    static void syncEnabled(Location location, ImmutableBlockState state, String property, boolean powered) {
        ImmutableBlockState updated = enabledState(state, property, powered);
        if (updated != state) {
            // A property update preserves the same storage controller and contents.
            CraftEngineBlocks.place(location, updated, UpdateFlags.UPDATE_CLIENTS, false);
        }
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

}
