package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Fertilizer prepares a bounded set of owner-local edits before it changes the world. */
public final class SandyShrubBlockBehavior extends FarmersDelightBlockBehavior {
    private record Position(UUID world, int x, int y, int z) { }
    private record Proposal(Block block, BlockState snapshot,
                            SandyShrubPatch.Change<OwnedBlockPairTransaction.State> change) { }
    private static final ThreadLocal<Set<Position>> FERTILIZING = ThreadLocal.withInitial(HashSet::new);
    private final FarmersDelightPlugin plugin;
    private final SandyShrubPatch.Options options;
    private final SoilRuleSupport.SoilRules soils;

    private SandyShrubBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, ConfigSection config) {
        super(block);
        this.plugin = plugin;
        Map<String, Object> arguments = config == null ? Map.of() : config.values();
        options = SandyShrubPatch.Options.parse(arguments);
        soils = SoilRuleSupport.parseSoilRules(arguments.containsKey("bottom-blocks") || arguments.containsKey("bottom-block-tags")
                ? arguments : Map.of("bottom-block-tags", List.of("minecraft:sand")));
    }

    public static BlockBehaviorFactory<SandyShrubBlockBehavior> factory(FarmersDelightPlugin plugin) {
        return (block, config) -> new SandyShrubBlockBehavior(plugin, block, config);
    }
    @Override public boolean isPathFindable(Object block, Object[] arguments) { return true; }

    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null || !(context.getPlayer().platformPlayer() instanceof Player player)) return InteractionResult.PASS;
        ItemStack held = ItemUtils.getItemInHand(player, context.getHand());
        if (held == null || held.getType() != Material.BONE_MEAL || held.getAmount() < 1) return InteractionResult.PASS;
        if (!(context.getLevel().platformWorld() instanceof World world) || !world.equals(player.getWorld()) || !owns(player)) return InteractionResult.FAIL;
        BlockPos pos = context.getClickedPos();
        if (!resident(world, pos.x(), pos.y(), pos.z())) return InteractionResult.FAIL;
        Block origin = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!ProtectionCompat.canPlace(player, origin, null) || CraftEngineBlocks.getCustomBlockState(origin) != state) return InteractionResult.FAIL;
        Position key = position(origin);
        Set<Position> busy = FERTILIZING.get();
        if (!busy.add(key)) return InteractionResult.FAIL;
        try {
            ItemStack expectedItem = held.clone();
            GameMode expectedMode = player.getGameMode();
            OwnedBlockPairTransaction.State installed = OwnedBlockPairTransaction.custom(block().defaultState());
            List<Proposal> proposals = new ArrayList<>();
            SandyShrubPatch.prepare(options, ThreadLocalRandom.current(), offset -> {
                Block target = candidate(world, pos.x() + offset.x(), pos.y() + offset.y(), pos.z() + offset.z(), player);
                if (target == null) return null;
                var cell = protectedCell(target, player);
                var change = new SandyShrubPatch.Change<>(cell, cell.read(), installed);
                BlockState snapshot = target.getState(false);
                snapshot.setBlockData(installed.vanilla());
                proposals.add(new Proposal(target, snapshot, change));
                return change;
            });
            if (proposals.isEmpty()) return InteractionResult.FAIL;
            BlockFertilizeEvent fertilizer = new BlockFertilizeEvent(origin, player,
                    new ArrayList<>(proposals.stream().map(Proposal::snapshot).toList()));
            Bukkit.getPluginManager().callEvent(fertilizer);
            if (fertilizer.isCancelled()) return InteractionResult.FAIL;

            Set<Position> retained = new HashSet<>();
            for (BlockState snapshot : fertilizer.getBlocks()) {
                if (snapshot != null && snapshot.getWorld().equals(world)
                        && snapshot.getBlockData().equals(installed.vanilla())) retained.add(position(snapshot));
            }
            List<SandyShrubPatch.Change<OwnedBlockPairTransaction.State>> changes = new ArrayList<>();
            for (Proposal proposal : proposals) {
                if (!retained.contains(position(proposal.block)) || !proposal.change.cell().resident()) continue;
                BlockGrowEvent grow = new BlockGrowEvent(proposal.block, proposal.snapshot);
                Bukkit.getPluginManager().callEvent(grow);
                if (!grow.isCancelled() && grow.getNewState().getBlockData().equals(installed.vanilla())) changes.add(proposal.change);
            }
            var result = SandyShrubPatch.commit(changes, () -> owns(player) && player.getGameMode() == expectedMode
                    && player.isOnline() && player.isValid() && !player.isDead() && world.equals(player.getWorld())
                    && resident(world, pos.x(), pos.y(), pos.z()) && CraftEngineBlocks.getCustomBlockState(origin) == state
                    && ProtectionCompat.canPlace(player, origin, null)
                    && expectedItem.equals(ItemUtils.getItemInHand(player, context.getHand())));
            if (!result.committed()) {
                if (!result.rollbackComplete() && plugin != null) plugin.getLogger().warning("Shrub fertilizer rollback could not restore all cells at " + origin.getLocation());
                return InteractionResult.FAIL;
            }
            if (expectedMode != GameMode.CREATIVE) {
                ItemStack actual = ItemUtils.getItemInHand(player, context.getHand());
                actual.setAmount(actual.getAmount() - 1);
            }
            playBonemealEffect(world, pos.x(), pos.y(), pos.z());
            ItemUtils.swingHand(player, context.getHand());
            return InteractionResult.SUCCESS_AND_CANCEL;
        } finally {
            busy.remove(key);
            if (busy.isEmpty()) FERTILIZING.remove();
        }
    }

    private Block candidate(World world, int x, int y, int z, Player player) {
        if (!resident(world, x, y, z) || !resident(world, x, y - 1, z)) return null;
        Block target = world.getBlockAt(x, y, z);
        if (target.getType() != Material.AIR || !allowed(target, player)) return null;
        ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(target);
        return custom == null || custom.isEmpty() ? target : null;
    }
    private boolean allowed(Block block, Player player) {
        if (!resident(block.getWorld(), block.getX(), block.getY(), block.getZ())
                || !resident(block.getWorld(), block.getX(), block.getY() - 1, block.getZ())) return false;
        Block below = block.getWorld().getBlockAt(block.getX(), block.getY() - 1, block.getZ());
        return SoilRuleSupport.matches(below, soils) && ProtectionCompat.canPlace(player, block, null)
                && ProtectionCompat.canBuild(player, below);
    }
    private OwnedBlockPairTransaction.Cell<OwnedBlockPairTransaction.State> protectedCell(Block block, Player player) {
        var nativeCell = OwnedBlockPairTransaction.cell(plugin, block);
        return new OwnedBlockPairTransaction.Cell<>() {
            @Override public boolean resident() { return nativeCell.resident(); }
            @Override public OwnedBlockPairTransaction.State read() { return nativeCell.read(); }
            @Override public boolean write(OwnedBlockPairTransaction.State state) {
                return (state.custom() == null || allowed(block, player)) && nativeCell.write(state);
            }
        };
    }
    private boolean owns(Player player) {
        return plugin != null && plugin.scheduler().isFolia() ? Bukkit.isOwnedByCurrentRegion(player) : Bukkit.isPrimaryThread();
    }
    private boolean resident(World world, int x, int y, int z) {
        Location location = new Location(world, x, y, z);
        return y >= world.getMinHeight() && y < world.getMaxHeight()
                && world.isChunkLoaded(x >> 4, z >> 4)
                && (plugin != null && plugin.scheduler().isFolia() ? plugin.scheduler().isOwnedByCurrentRegion(location) : Bukkit.isPrimaryThread());
    }
    private static Position position(Block block) { return new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    private static Position position(BlockState state) { return new Position(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ()); }
}
