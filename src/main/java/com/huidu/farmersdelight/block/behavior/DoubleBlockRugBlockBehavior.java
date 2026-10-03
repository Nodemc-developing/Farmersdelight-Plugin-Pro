package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

// Generic "double block" behavior for a 2-cell mat: one placement spawns a head + foot half in adjacent
// cells along the player's facing, and removing either half tears down the partner so no orphan cell
// survives. Facing and part are looked up by name from config so any property pair can drive the pairing.
public class DoubleBlockRugBlockBehavior extends RugBlockBehavior {

    private final Property<Direction> facingProperty;
    private final Property<?> partProperty;
    private final Set<String> partnerIds;
    private final boolean needsSupport;
    private final com.huidu.farmersdelight.FarmersDelightPlugin plugin;

    private DoubleBlockRugBlockBehavior(
            com.huidu.farmersdelight.FarmersDelightPlugin plugin,
            BlockDefinition block,
            Property<Direction> facingProperty,
            Property<?> partProperty,
            Set<String> partnerIds, boolean needsSupport) {
        super(block);
        this.plugin = plugin;
        this.facingProperty = facingProperty;
        this.partProperty = partProperty;
        this.partnerIds = partnerIds;
        this.needsSupport = needsSupport;
    }

    public static final BlockBehaviorFactory<DoubleBlockRugBlockBehavior> FACTORY = factory(null);
    public static BlockBehaviorFactory<DoubleBlockRugBlockBehavior> factory(com.huidu.farmersdelight.FarmersDelightPlugin plugin) {
        return new BlockBehaviorFactory<DoubleBlockRugBlockBehavior>() {
        @Override
        public DoubleBlockRugBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String facingPropertyName = BehaviorArgParser.getString(arguments, "facing-property", "facing");
            String partPropertyName = BehaviorArgParser.getString(arguments, "part-property", "part");
            Property<Direction> facingProperty = BlockBehaviorFactory.getOptionalProperty(
                    block, facingPropertyName, Direction.class);
            Property<?> partProperty = block.getProperty(partPropertyName);
            Set<String> partnerIds = new HashSet<>(BehaviorArgParser.getStringList(arguments, "partner-ids"));
            if (partnerIds.isEmpty()) {
                partnerIds.add(block.id().namespace() + ":" + block.id().value());
            }

            String path = section != null ? section.path() : Constants.BEHAVIOR_DOUBLE_BLOCK;
            if (facingProperty == null || partProperty == null) {
                throw new KnownResourceException(
                        "resource.block.behavior.missing_property", path, "facing/part");
            }
            return new DoubleBlockRugBlockBehavior(plugin, block, facingProperty, partProperty, partnerIds,
                    BehaviorArgParser.getBoolean(arguments, "needs-support", true));
        }
        };
    }

    // Writes the player's horizontal placement direction into the facing property so the head carries the
    // same facing as the foot spawned beside it. The foot cell is validated here so an occupied cell rejects
    // the placement atomically - CE never places a lone head that later gets torn down.
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockFace face = lookingHorizontalFace(context);
        if (face == null) {
            return null;
        }
        if (footCellBlocked(context, face)) {
            return null;
        }
        if (needsSupport && context.getLevel().platformWorld() instanceof World world) {
            BlockPos pos = context.getClickedPos();
            Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!self.getRelative(BlockFace.DOWN).isSolid() || !self.getRelative(face).getRelative(BlockFace.DOWN).isSolid()) return null;
        }
        // The foot cell is written by CraftEngineBlocks.place in placeMultiState, which bypasses the
        // vanilla build check the head cell went through. Reject the whole placement here (atomically,
        // before either cell exists) when the second cell falls in protected land.
        if (!footCellAllowed(context, face)) {
            return null;
        }
        return withPart(withFacing(state, face), "head");
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length < 5) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (world == null || pos == null) {
            return;
        }
        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (state == null || state.isEmpty()) {
            return;
        }
        if (!owned(self) || OwnedBlockPairTransaction.editing(self.getLocation())) return;
        placeDoubleBlock(world, self, state, args);
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }
        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (OwnedBlockPairTransaction.editing(self.getLocation())) return;
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (state == null || state.isEmpty()) {
            return;
        }
        Block partner = partnerOf(self, state);
        if (partner == null) {
            teardownSelf(self);
            return;
        }
        if (!partner.getWorld().isChunkLoaded(partner.getX() >> 4, partner.getZ() >> 4)) {
            // The other half sits in a chunk that is not resident: reading its state would load that chunk, and
            // treating it as missing would wrongly tear this half down. The pair is re-checked when the
            // partner's chunk loads again.
            return;
        }
        if (!owned(partner)) return;
        ImmutableBlockState partnerState = CustomBlockUtils.getStateIfResident(partner);
        if (!isPairedPartner(state, partnerState)) {
            teardownSelf(self);
        }
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    // Removing one half also removes its paired partner so no orphan half is ever left floating.
    private void handleRemoval(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }
        Block removed = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (OwnedBlockPairTransaction.editing(removed.getLocation())) return;
        ImmutableBlockState state = net.momirealms.craftengine.bukkit.util.BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) {
            return;
        }
        Block partner = partnerOf(removed, state);
        if (partner != null && partner.getWorld().isChunkLoaded(partner.getX() >> 4, partner.getZ() >> 4)
                && owned(partner)) {
            ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
            if (!OwnedBlockPairTransaction.editing(partner.getLocation()) && isPairedPartner(state, partnerState)) {
                partner.setType(Material.AIR, false);
            }
        }
    }

    // The head cell spawns the foot along its facing; the foot (already carrying facing from the shared
    // placement) is placed next to the head, so one item yields two cells. The foot cell was verified free
    // at placement time, so no tear-down is needed here.
    private void placeDoubleBlock(World world, Block self, ImmutableBlockState state, Object[] args) {
        if (!isPart(state, "head")) {
            // Already a foot half: its head partner was placed by the other cell, do not recurse.
            return;
        }
        BlockFace facing = facingFromState(state);
        Block partner = self.getRelative(facing);
        if (!owned(partner) || !partner.getType().isAir()) { compensateHead(self, state, args); return; }
        var headBefore = OwnedBlockPairTransaction.cell(plugin, self).read();
        var footBefore = OwnedBlockPairTransaction.cell(plugin, partner).read();
        if (headBefore.custom() != state || footBefore.custom() != null || !footBefore.vanilla().getMaterial().isAir()) {
            compensateHead(self, state, args); return;
        }
        var footAfter = OwnedBlockPairTransaction.custom(withPart(state, "foot"));
        var outcome = OwnedBlockPairTransaction.update(plugin, partner, footBefore, footAfter, self, headBefore, headBefore);
        if (!outcome.committed()) {
            if (!outcome.rollbackComplete() && plugin != null && plugin.isEnabled()) plugin.scheduler().runLaterAt(self.getLocation(), () -> {
                if (!OwnedBlockPairTransaction.compensate(plugin, partner, footAfter, footBefore, self, headBefore, headBefore))
                    plugin.getLogger().warning("A double block restoration could not finish at " + self.getLocation());
            }, 1);
            compensateHead(self, state, args);
        }
    }

    private void compensateHead(Block self, ImmutableBlockState expected, Object[] args) {
        PlacementReceipt receipt = placementReceipt(args);
        Runnable cleanup = () -> {
            if (!owned(self) || CraftEngineBlocks.getCustomBlockState(self) != expected) return;
            Block partner = partnerOf(self, expected);
            if (partner != null && owned(partner) && isPairedPartner(expected, CraftEngineBlocks.getCustomBlockState(partner))) return;
            if (CraftEngineBlocks.remove(self, false) && receipt != null && receipt.unit() != null)
                self.getWorld().dropItemNaturally(self.getLocation().add(.5, .5, .5), receipt.unit());
        };
        if (plugin != null && plugin.isEnabled()) {
            if (receipt != null && receipt.player() != null && receipt.unit() != null)
                plugin.scheduler().runLaterForEntity(receipt.player(), () -> {
                    if (receipt.consumed()) plugin.scheduler().runAt(self.getLocation(), cleanup);
                    else plugin.getLogger().warning("Double block refund was withheld because native item consumption was not confirmed at " + self.getLocation());
                }, 1);
            else plugin.scheduler().runLaterAt(self.getLocation(), cleanup, 1);
        } else if (receipt == null || receipt.unit() == null) cleanup.run();
    }
    private record PlacementReceipt(Player player, Object source, int amountBefore, org.bukkit.inventory.ItemStack unit) {
        boolean consumed() {
            org.bukkit.inventory.ItemStack current = source instanceof org.bukkit.inventory.ItemStack item ? item
                    : net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(source);
            return nativeConsumptionConfirmed(amountBefore, current == null ? 0 : current.getAmount(),
                    current == null || current.getAmount() == 0 || unit.isSimilar(current));
        }
    }
    static boolean nativeConsumptionConfirmed(int before, int after, boolean sameComponents) {
        return before > 0 && after == before - 1 && sameComponents;
    }
    private PlacementReceipt placementReceipt(Object[] args) {
        if (args.length < 5 || args[3] == null || args[4] == null) return null;
        try {
            Player player = args[3] instanceof Player value ? value
                    : args[3] instanceof net.momirealms.craftengine.core.entity.player.Player value ? ItemUtils.getBukkitPlayer(value)
                    : net.momirealms.craftengine.bukkit.util.EntityUtils.adaptNMS(args[3]).platformEntity() instanceof Player value ? value : null;
            if (player == null || !Bukkit.isOwnedByCurrentRegion(player) || player.getGameMode() == GameMode.CREATIVE) return null;
            org.bukkit.inventory.ItemStack item = args[4] instanceof org.bukkit.inventory.ItemStack value ? value
                    : net.momirealms.craftengine.bukkit.util.ItemStackUtils.getBukkitStack(args[4]);
            if (item == null || item.getType().isAir() || item.getAmount() < 1) return null;
            var unit = item.clone(); unit.setAmount(1); return new PlacementReceipt(player, args[4], item.getAmount(), unit);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean footCellAllowed(BlockPlaceContext context, BlockFace facing) {
        if (context.getPlayer() == null || !(context.getLevel().platformWorld() instanceof World world)) {
            return true;
        }
        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) {
            return true;
        }
        BlockPos clicked = context.getClickedPos();
        Block foot = world.getBlockAt(clicked.x(), clicked.y(), clicked.z()).getRelative(facing);
        if (!world.isChunkLoaded(foot.getX() >> 4, foot.getZ() >> 4)
                || !owned(foot)) return false;
        return ProtectionCompat.canBuild(bukkitPlayer, foot, (String) null);
    }

    private boolean footCellBlocked(BlockPlaceContext context, BlockFace facing) {
        if (!(context.getLevel().platformWorld() instanceof World world)) {
            return true;
        }
        BlockPos clicked = context.getClickedPos();
        Block foot = world.getBlockAt(clicked.x(), clicked.y(), clicked.z()).getRelative(facing);
        if (!world.isChunkLoaded(foot.getX() >> 4, foot.getZ() >> 4)
                || !owned(foot)) return true;
        return !foot.getType().isAir();
    }

    private BlockFace facingFromState(ImmutableBlockState state) {
        Property<?> property = state.owner().value().getProperty(facingProperty.name());
        Object value = property == null ? null : state.getNullable(property);
        if (value == null) {
            return BlockFace.NORTH;
        }
        try {
            return BlockFace.valueOf(value.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }
    private boolean owned(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && (plugin == null || plugin.scheduler().isOwnedByCurrentRegion(block.getLocation()));
    }

    // Prefers the player's horizontal facing (their yaw). That stays stable when placing on a floor or
    // ceiling, where the raw look direction is vertical and the nearest-looking-axis based fallback would
    // degrade to one fixed horizontal direction regardless of where the player turns. The clicked face
    // only backs up non-player or wall placements.
    private BlockFace lookingHorizontalFace(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        if (facing != null && facing.axis().isHorizontal()) {
            return fromDirection(facing);
        }
        return fromDirection(context.getClickedFace());
    }

    private ImmutableBlockState withFacing(ImmutableBlockState state, BlockFace face) {
        return state.with(facingProperty, toDirection(face));
    }

    private Block partnerOf(Block self, ImmutableBlockState state) {
        if (!isPart(state, "head") && !isPart(state, "foot")) return null;
        BlockFace facing = isPart(state, "head")
                ? facingFromState(state)
                : facingFromState(state).getOppositeFace();
        return self.getRelative(facing);
    }

    private boolean isPart(ImmutableBlockState state, String value) {
        if (state == null || state.isEmpty()) return false;
        Property<?> property = state.owner().value().getProperty(partProperty.name());
        Object part = property == null ? null : state.getNullable(property);
        return part != null && value.equals(part.toString());
    }

    private ImmutableBlockState withPart(ImmutableBlockState state, String value) {
        return withPropertyValue(state, partProperty, value);
    }

    private boolean isPartner(ImmutableBlockState state) {
        return matchesAnyId(state, partnerIds);
    }
    private boolean isPairedPartner(ImmutableBlockState self, ImmutableBlockState partner) {
        if (!isPartner(partner) || partner.owner().value().getProperty(facingProperty.name()) == null) return false;
        String selfPart = isPart(self, "head") ? "head" : isPart(self, "foot") ? "foot" : null;
        String partnerPart = isPart(partner, "head") ? "head" : isPart(partner, "foot") ? "foot" : null;
        return matchingPairParts(facingFromState(self), selfPart, facingFromState(partner), partnerPart);
    }
    static boolean matchingPairParts(BlockFace selfFacing, String selfPart, BlockFace partnerFacing, String partnerPart) {
        return selfFacing != null && selfFacing == partnerFacing && ("head".equals(selfPart) && "foot".equals(partnerPart)
                || "foot".equals(selfPart) && "head".equals(partnerPart));
    }

    private void teardownSelf(Block self) {
        self.setType(Material.AIR, false);
    }
}
