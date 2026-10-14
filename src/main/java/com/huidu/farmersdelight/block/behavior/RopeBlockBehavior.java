package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockPlaceEvent;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.DirectionUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.UpdateFlags;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.Bell;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

public class RopeBlockBehavior extends BukkitBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return true;
    }

    @Override public void fallOn(Object thisBlock, Object[] args) { }
    @Override public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) { }

    private static final String PROP_NORTH = "north";
    private static final String PROP_SOUTH = "south";
    private static final String PROP_EAST = "east";
    private static final String PROP_WEST = "west";

    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    // Blocks that never accept a rope tie even when they present a full face, matching the vanilla
    // exception list: leaves, barriers, shulker boxes, pumpkins and melons.
    private static final Set<Material> CONNECTION_EXCEPTIONS = EnumSet.of(
            Material.BARRIER,
            Material.CARVED_PUMPKIN,
            Material.JACK_O_LANTERN,
            Material.MELON,
            Material.PUMPKIN
    );

    // Ropes connect to bars and glass panes. Bukkit has no common tag or interface for them,
    // so recognize GLASS_PANE, dyed panes and _BARS materials by name.
    // Additional bar or pane types with different names require extending this predicate.
    private static final Set<Material> PANE_BLOCKS = createPaneBlocks();

    private static Set<Material> createPaneBlocks() {
        Set<Material> panes = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            String name = material.name();
            if (name.endsWith("GLASS_PANE") || name.endsWith("_BARS")) {
                panes.add(material);
            }
        }
        return panes;
    }

    private enum ConnectionMode {
        RESTRICTED,
        SOLID_FACE;

        private static ConnectionMode parse(String value) {
            if (value == null) {
                return RESTRICTED;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "solid", "solid_face", "face" -> SOLID_FACE;
                default -> RESTRICTED;
            };
        }
    }

    private enum PlacementMode {
        VANILLA,
        RESTRICTED,
        SOLID_FACE;

        private static PlacementMode parse(String value) {
            if (value == null) {
                return VANILLA;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "restricted", "rope_only", "connectors" -> RESTRICTED;
                case "solid", "solid_face", "face" -> SOLID_FACE;
                default -> VANILLA;
            };
        }
    }

    private final PlacementMode placementMode;
    private final ConnectionMode connectionMode;
    private final ConfiguredBlockSet connectorBlocks;
    private final FarmersDelightPlugin plugin;
    private final ConfiguredBlockSet exceptionBlocks;
    private final int bellSearchRange;
    private final Key reelItem;
    private final String bellPropertyName;

    private RopeBlockBehavior(FarmersDelightPlugin plugin,
                              BlockDefinition block,
                              PlacementMode placementMode,
                              ConnectionMode connectionMode,
                              ConfiguredBlockSet connectorBlocks,
                              ConfiguredBlockSet exceptionBlocks, int bellSearchRange, Key reelItem, String bellPropertyName) {
        super(block);
        this.plugin = plugin;
        this.placementMode = placementMode;
        this.connectionMode = connectionMode;
        this.connectorBlocks = connectorBlocks;
        this.exceptionBlocks = exceptionBlocks;
        this.bellSearchRange = bellSearchRange;
        this.reelItem = reelItem;
        this.bellPropertyName = bellPropertyName;
    }

    // The four connection properties stay optional: a rope that declares none of them is a plain single-model
    // rope, and climbing, reeling down and ringing a bell all work without them. They are resolved with the
    // value class checked, so a property declared under one of these names but not as a boolean is skipped
    // like an absent one instead of throwing out of the placement path on the first rope put down.
    public static final BlockBehaviorFactory<RopeBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        // Runs while CraftEngine parses the pack, which is always after this plugin enabled.
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        Object connectorRaw = BehaviorArgParser.getRaw(arguments, "connector-blocks");
        Object exceptionRaw = BehaviorArgParser.getRaw(arguments, "connection-exceptions");
        int bellRange = BehaviorArgParser.getInt(arguments, "bell-search-range",
                plugin == null ? 24 : plugin.getConfigInt(24, "rope.max-bell-distance"));
        if (bellRange < 1 || bellRange > 256) throw new IllegalArgumentException("bell-search-range must be in [1, 256]");
        return new RopeBlockBehavior(
                plugin,
                block,
                PlacementMode.parse(BehaviorArgParser.getString(arguments, "placement-mode", "vanilla")),
                ConnectionMode.parse(BehaviorArgParser.getString(arguments, "connection-mode", "restricted")),
                connectorRaw == null ? ConfiguredBlockSet.EMPTY : ConfiguredBlockSet.parse(connectorRaw),
                exceptionRaw == null ? null : ConfiguredBlockSet.parse(exceptionRaw),
                bellRange,
                Key.of(BehaviorArgParser.getString(arguments, "reel-item", block.id().toString())),
                BehaviorArgParser.getString(arguments, "tied-to-bell-property", "tied_to_bell")
        );
    };

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        if (!RopeWorldAccess.available(world, pos)) return state;
        boolean horizontalPlacement = context.getClickedFace().axis().isHorizontal();

        ImmutableBlockState result = state;
        for (BlockFace face : HORIZONTAL_FACES) {
            Property<Boolean> property = connectionProperty(state, face);
            if (property != null) result = result.with(property, connectsOnPlacement(world, pos, face, horizontalPlacement));
        }

        Property<Boolean> waterlogged = RopeStateProperties.property(state, "waterlogged", Boolean.class);
        if (waterlogged != null) result = result.with(waterlogged, context.isWaterSource());
        return updateBellConnection(result, world, pos);
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {

    }

    // A rope re-derives its connection towards whatever changed next to it. These are the NMS hooks the
    // block itself receives, and they replace a BlockPhysicsEvent listener that had to watch every block
    // update in the world and then ask whether a rope was nearby. Both are hooked because the two fire from
    // different paths -- neighborChanged from updateNeighborsAt, updateShape from updateNeighbourShapes --
    // and the refresh queue collapses a double hit on the same position.
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        RopeBlockListener.queueNeighborRefresh(
                CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[2]));
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        if (args == null || args.length <= Math.max(updateShape$level, updateShape$blockPos)) return args == null || args.length == 0 ? null : args[0];
        World world = CraftEngineAdapter.toWorld(args[updateShape$level]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[updateShape$blockPos]);
        if (!RopeWorldAccess.available(world, pos)) return args[0];
        ImmutableBlockState state = BlockStateUtils.getNullableCustomBlockState(args[0]);
        if (state == null || state.owner().value() != block()) return args[0];
        net.momirealms.craftengine.core.util.Direction direction = DirectionUtils.fromNMSDirection(args[updateShape$direction]);
        ImmutableBlockState updated = state;
        if (direction.axis().isHorizontal()) {
            BlockFace face = BlockFace.valueOf(direction.name());
            Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + face.getModX(), pos.y(), pos.z() + face.getModZ()));
            Property<Boolean> property = connectionProperty(state, face);
            if (neighbor != null && property != null) updated = updated.with(property, connectsToNeighbor(neighbor, face.getOppositeFace()));
        } else if (direction == net.momirealms.craftengine.core.util.Direction.UP) {
            updated = updateBellConnection(state, world, pos);
        }
        RopeBlockListener.queueNeighborRefresh(
                world, pos);
        return updated == state ? args[0] : updated.customBlockState().minecraftState();
    }


    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) return InteractionResult.PASS;
        if (!playerOwned(bukkitPlayer)) return InteractionResult.FAIL;

        ItemStack hand = ItemUtils.getItemInHand(bukkitPlayer, context.getHand());
        // Empty hand is the bell-ringing case, which lives in useWithoutItem. CraftEngine only calls that method
        // when useOnBlock reports TRY_EMPTY_HAND (the value BlockBehavior returns by default); PASS ends the
        // dispatch here and would leave the whole bell path unreachable.
        if (hand == null || hand.getType().isAir()) return InteractionResult.TRY_EMPTY_HAND;

        if (!ItemUtils.matchesItemId(hand, reelItem.toString())) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        if (!RopeWorldAccess.available(world, pos)) return InteractionResult.FAIL;

        // Reel straight down from the clicked rope. CraftEngine skips block behaviors for
        // sneaking players holding items, so that input is handled by item placement instead.
        int cx = pos.x();
        int cy = pos.y() - 1;
        int cz = pos.z();

        while (cy >= world.getMinHeight()) {
            BlockPos bp = new BlockPos(cx, cy, cz);
            Block target = RopeWorldAccess.block(world, bp);
            if (target == null) return InteractionResult.FAIL;
            if (sameRope(state, CustomBlockUtils.getStateIfResident(target))) {
                cy--;
                continue;
            }

            // Return FAIL at dead ends to prevent normal item placement against the clicked face.
            // Non-water fluids and blocks the rope cannot replace stop the downward reel.
            if (target.getType() == Material.LAVA || !target.isReplaceable()) {
                return InteractionResult.FAIL;
            }

            BlockDefinition ropeBlock = CraftEngineBlocks.byId(state.owner().value().id());
            if (ropeBlock == null) return InteractionResult.FAIL;

            ImmutableBlockState placementState = computeConnectionState(ropeBlock.defaultState(), world, bp);
            Property<Boolean> wet = RopeStateProperties.property(placementState, "waterlogged", Boolean.class);
            if (wet != null) placementState = placementState.with(wet, target.getType() == Material.WATER);

            // This path cancels native interaction and places directly through CraftEngineBlocks.place.
            // Check protection here because the native placement permission check will not run.
            Location placeLoc = new Location(world, cx + 0.5, cy, cz + 0.5);
            BlockData before = target.getBlockData().clone();
            ItemStack beforeHand = hand.clone();
            GameMode beforeMode = bukkitPlayer.getGameMode();
            ImmutableBlockState toPlace = placementState;
            boolean placed = RopeOperation.commitWithCompensation(
                    () -> allowReelPlacement(bukkitPlayer, target, toPlace, context.getHand()),
                    () -> RopeWorldAccess.available(world, bp) && target.getBlockData().equals(before)
                            && playerOwned(bukkitPlayer) && bukkitPlayer.getGameMode() == beforeMode
                            && ProtectionCompat.canPlace(bukkitPlayer, target, ProtectionCompat.Feature.ROPE)
                            && ItemUtils.getItemInHand(bukkitPlayer, context.getHand()).equals(beforeHand),
                    () -> CraftEngineBlocks.place(placeLoc, toPlace, true),
                    () -> consumeReelItem(bukkitPlayer, context.getHand(), beforeHand),
                    () -> {
                        if (!RopeWorldAccess.available(world, bp) || !sameRope(toPlace, CustomBlockUtils.getStateIfResident(target))) return false;
                        if (!CraftEngineBlocks.remove(target)) return false;
                        target.setBlockData(before, false);
                        RopeBlockListener.syncRopeIndex(world, bp);
                        return true;
                    });
            if (!placed) return InteractionResult.FAIL;

            ItemUtils.swingHand(bukkitPlayer, context.getHand());

            // CraftEngineBlocks.place is a raw block write that fires no CustomBlockPlaceEvent, so the rope
            // index has to be told about this rope directly or later neighbour changes will not refresh it.
            RopeBlockListener.syncRopeIndex(world, bp);

            if (plugin != null) {
                plugin.scheduler().runAt(placeLoc, () -> refreshAdjacentRopes(world, bp));
            }

            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.FAIL;
    }

    @Override
    public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null || !playerOwned(bukkitPlayer)) {
            return InteractionResult.PASS;
        }

        World world = (World) context.getLevel().platformWorld();
        BlockPos pos = context.getClickedPos();
        if (bukkitPlayer.isSneaking()) {
            Block clicked = RopeWorldAccess.block(world, pos);
            return context.getHand() == InteractionHand.MAIN_HAND && clicked != null && RopeBlockListener.retractRope(bukkitPlayer, clicked, state)
                    ? InteractionResult.SUCCESS_AND_CANCEL : InteractionResult.PASS;
        }

        // Mirror vanilla RopeBlock.useWithoutItem: walk up through a contiguous rope column (max 24 blocks) and
        // ring the first bell found. Any gap, or any block that is neither a rope nor a bell, stops the search.
        int x = pos.x();
        int z = pos.z();
        int maxDistance = bellSearchRange;
        for (int i = 1, y = pos.y() + 1; i <= maxDistance && y < world.getMaxHeight(); i++, y++) {
            Block above = RopeWorldAccess.block(world, new BlockPos(x, y, z));
            if (above == null) return InteractionResult.PASS;
            if (above.getType() == Material.BELL) {
                // The bell can sit up to the configured distance away, so it needs its own use check: the click
                // itself is covered by the protection plugin cancelling the interact event on the rope, but the
                // bell that far away is not.
                if (!ProtectionCompat.canUse(bukkitPlayer, above, ProtectionCompat.Feature.ROPE)) {
                    return InteractionResult.PASS;
                }
                ringBell(above, bukkitPlayer);
                // The rope's appearance is not one of the blocks the client predicts an interaction for, so
                // the swing has to be sent from here.
                bukkitPlayer.swingMainHand();
                return InteractionResult.SUCCESS;
            }
            if (!sameRope(state, CustomBlockUtils.getStateIfResident(above))) {
                return InteractionResult.PASS;
            }
        }
        return InteractionResult.PASS;
    }

    private void ringBell(Block bell, Player player) {
        Runnable ring = () -> {
            if (!RopeWorldAccess.available(bell.getWorld(), new BlockPos(bell.getX(), bell.getY(), bell.getZ())) || !playerOwned(player)) return;
            if (bell.getType() != Material.BELL || !(bell.getState() instanceof Bell bellState)) {
                return;
            }
            BlockFace direction = bell.getBlockData() instanceof Directional directional
                    ? clockwise(directional.getFacing())
                    : null;
            bellState.ring(player, direction);
        };
        if (plugin != null) {
            plugin.scheduler().runAt(bell.getLocation(), ring);
        } else {
            ring.run();
        }
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    public static ItemStack createItemForRopeBlock(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return null;
        }
        return ItemUtils.createItem(state.owner().value().id());
    }

    public static boolean sameRope(ImmutableBlockState source, ImmutableBlockState candidate) {
        return source != null && candidate != null && !candidate.isEmpty()
                && source.owner().value() == candidate.owner().value()
                && CustomBlockUtils.hasBehavior(candidate, RopeBlockBehavior.class);
    }

    public static boolean playerOwned(Player player) {
        return Bukkit.isOwnedByCurrentRegion(player);
    }

    private boolean allowReelPlacement(Player player, Block target, ImmutableBlockState state, InteractionHand hand) {
        if (!ProtectionCompat.canPlace(player, target, ProtectionCompat.Feature.ROPE)) return false;
        CustomBlockPlaceEvent event = new CustomBlockPlaceEvent(player, target.getLocation(), state, target, hand);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private static boolean consumeReelItem(Player player, InteractionHand hand, ItemStack before) {
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        if (!playerOwned(player)) return false;
        ItemStack result = before.clone(); result.setAmount(before.getAmount() - 1);
        int slot = hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        return RopeInventoryCommit.write(player.getInventory(), slot, before, result.getAmount() == 0 ? null : result);
    }

    // Ropes remain connected to ropes, bars, panes and walls. Identify custom ropes by behavior
    // because their Bukkit material is only an appearance carrier.
    private boolean tieToRopeAndWalls(Block neighbor) {
        if (CustomBlockUtils.hasBehavior(neighbor, RopeBlockBehavior.class)) {
            return true;
        }
        if (!connectorBlocks.isEmpty()) {
            return connectorBlocks.contains(neighbor);
        }
        Material material = neighbor.getType();
        return PANE_BLOCKS.contains(material) || Tag.WALLS.isTagged(material);
    }

    // The wider tie only a fresh placement against a horizontal face can make: anything that is not on the
    // exception list and turns a full solid face towards the rope.
    private boolean tieToAnythingValid(Block neighbor, BlockFace faceTowardsRope) {
        if (!isExceptionForConnection(neighbor)
                && neighbor.getBlockData().isFaceSturdy(faceTowardsRope, BlockSupport.FULL)) {
            return true;
        }
        return tieToRopeAndWalls(neighbor);
    }

    private boolean isExceptionForConnection(Block neighbor) {
        Material material = neighbor.getType();
        if (exceptionBlocks != null) {
            return exceptionBlocks.contains(neighbor);
        }
        return CONNECTION_EXCEPTIONS.contains(material)
                || Tag.LEAVES.isTagged(material)
                || Tag.SHULKER_BOXES.isTagged(material);
    }

    private boolean connectsOnPlacement(World world, BlockPos pos, BlockFace direction, boolean horizontalPlacement) {
        Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + direction.getModX(), pos.y(), pos.z() + direction.getModZ()));
        if (neighbor == null) return false;
        return switch (placementMode) {
            case RESTRICTED -> tieToRopeAndWalls(neighbor);
            case SOLID_FACE -> tieToAnythingValid(neighbor, direction.getOppositeFace());
            case VANILLA -> horizontalPlacement
                    ? tieToAnythingValid(neighbor, direction.getOppositeFace())
                    : tieToRopeAndWalls(neighbor);
        };
    }

    private static Property<Boolean> connectionProperty(ImmutableBlockState state, BlockFace face) {
        String name = switch (face) {
            case NORTH -> PROP_NORTH;
            case SOUTH -> PROP_SOUTH;
            case EAST -> PROP_EAST;
            case WEST -> PROP_WEST;
            default -> null;
        };
        if (name == null) {
            return null;
        }
        return RopeStateProperties.property(state, name, Boolean.class);
    }

    // Programmatic placement, including downward reeling and harvested tomato supports,
    // uses the rope/pane/wall connection check on every side.
    public static ImmutableBlockState computeConnectionState(ImmutableBlockState state, World world, BlockPos pos) {
        RopeBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeBlockBehavior.class);
        if (behavior == null) {
            return state;
        }
        return behavior.computeConnectionStateInternal(state, world, pos);
    }

    private ImmutableBlockState computeConnectionStateInternal(ImmutableBlockState state, World world, BlockPos pos) {
        ImmutableBlockState result = state;
        if (!RopeWorldAccess.available(world, pos)) return state;
        for (BlockFace face : HORIZONTAL_FACES) {
            Property<Boolean> property = connectionProperty(state, face);
            if (property == null) {
                continue;
            }
            Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + face.getModX(), pos.y(), pos.z() + face.getModZ()));
            if (neighbor == null) continue;
            result = result.with(property, connectsToNeighbor(neighbor, face.getOppositeFace()));
        }
        return updateBellConnection(result, world, pos);
    }

    private ImmutableBlockState updateBellConnection(ImmutableBlockState state, World world, BlockPos pos) {
        Property<Boolean> bellProperty = RopeStateProperties.property(state, bellPropertyName, Boolean.class);
        if (bellProperty == null) return state;
        if (pos.y() + 1 >= world.getMaxHeight()) return state.with(bellProperty, false);
        Block next = RopeWorldAccess.block(world, new BlockPos(pos.x(), pos.y() + 1, pos.z()));
        return next == null ? state : state.with(bellProperty, next.getType() == Material.BELL);
    }

    @Override public boolean canUseOnBlockIfSecondaryUseActive(UseOnContext context, ImmutableBlockState state) { return true; }

    private boolean connectsToNeighbor(Block neighbor, BlockFace faceTowardsRope) {
        return connectionMode == ConnectionMode.SOLID_FACE
                ? tieToAnythingValid(neighbor, faceTowardsRope)
                : tieToRopeAndWalls(neighbor);
    }

    // Re-derives the one connection each adjacent rope has towards pos, after whatever stands there changed.
    // Only that side is touched, and only with the restricted rope/pane/wall test: the wider solid-face tie is
    // made at placement time and is never restored by a neighbour update, while the connections a rope holds on
    // its other three sides are none of this update's business.
    public static void refreshAdjacentRopes(World world, BlockPos pos) {
        if (!RopeWorldAccess.available(world, pos)) return;
        // Raw CraftEngine writes fire no place or break event, so this is also where the rope index learns
        // whether pos still holds a rope. Every programmatic rope write is followed by a refresh from here.
        RopeBlockListener.syncRopeIndex(world, pos);

        Block changed = world.getBlockAt(pos.x(), pos.y(), pos.z());
        refreshBellState(changed);
        RopeFenceBlockBehavior.refresh(changed);
        RopeFenceGateBlockBehavior.refresh(changed);
        Block below = RopeWorldAccess.block(world, new BlockPos(pos.x(), pos.y() - 1, pos.z()));
        if (below != null) refreshBellState(below);

        for (BlockFace face : HORIZONTAL_FACES) {
            Block neighbor = RopeWorldAccess.block(world, new BlockPos(pos.x() + face.getModX(), pos.y(), pos.z() + face.getModZ()));
            if (neighbor == null) continue;
            RopeFenceBlockBehavior.refresh(neighbor);
            RopeFenceGateBlockBehavior.refresh(neighbor);
            // Neighbours on a chunk border are read and rewritten only while their chunk is resident: the CE
            // lookup would otherwise load it and the place below would cross into another region.
            ImmutableBlockState state = CustomBlockUtils.getStateIfResident(neighbor);
            if (state == null || state.isEmpty() || !CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) {
                continue;
            }

            RopeBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeBlockBehavior.class);
            if (behavior == null) {
                continue;
            }

            Property<Boolean> property = connectionProperty(state, face.getOppositeFace());
            if (property == null) {
                continue;
            }

            ImmutableBlockState updated = state.with(property,
                    behavior.connectsToNeighbor(changed, face.getOppositeFace()));
            // with returns the same state when the value is unchanged, so an unaffected rope is left alone
            // instead of being rewritten and resent.
            if (updated == state) {
                continue;
            }

            CraftEngineBlocks.place(neighbor.getLocation(), updated, UpdateFlags.UPDATE_CLIENTS, false);
        }
    }

    public static void refreshBellState(Block block) {
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        if (!RopeWorldAccess.available(block.getWorld(), pos)) return;
        ImmutableBlockState state = CustomBlockUtils.getStateIfResident(block);
        RopeBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeBlockBehavior.class);
        if (behavior == null) return;
        ImmutableBlockState updated = behavior.updateBellConnection(state, block.getWorld(), pos);
        if (updated != state) CraftEngineBlocks.place(block.getLocation(), updated, UpdateFlags.UPDATE_CLIENTS, false);
    }
}
