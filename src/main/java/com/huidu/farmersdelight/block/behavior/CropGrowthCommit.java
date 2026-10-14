package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Owner-thread event and state checks for a single growth action, including both halves of a crop. */
final class CropGrowthCommit {
    private CropGrowthCommit() { }

    static boolean resident(FarmersDelightPlugin plugin, Block block) {
        return block.getY() >= block.getWorld().getMinHeight() && block.getY() < block.getWorld().getMaxHeight()
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && (plugin == null ? Bukkit.isOwnedByCurrentRegion(block.getLocation())
                : plugin.scheduler().isOwnedByCurrentRegion(block.getLocation()));
    }

    static int brightness(Block block) {
        return Math.max(block.getLightFromSky(), block.getLightFromBlocks());
    }

    static boolean apply(FarmersDelightPlugin plugin, Map<Block, ImmutableBlockState> requested,
                         Player player, Block fertilized) {
        return apply(plugin, requested, player, fertilized, () -> true);
    }

    static boolean apply(FarmersDelightPlugin plugin, Map<Block, ImmutableBlockState> requested,
                         Player player, Block fertilized, BooleanSupplier guard) {
        List<Cell> changes = new ArrayList<>();
        if (!guard.getAsBoolean()) return false;
        for (var entry : requested.entrySet()) {
            Block block = entry.getKey();
            if (!resident(plugin, block)) return false;
            if (!allowed(player, block)) return false;
            ImmutableBlockState before = CraftEngineBlocks.getCustomBlockState(block);
            if (before == entry.getValue()) continue;
            changes.add(new Cell(plugin, block, before, entry.getValue(), player, guard));
        }
        if (changes.isEmpty()) return false;
        if (fertilized != null && !requested.containsKey(fertilized)) {
            if (!resident(plugin, fertilized) || !allowed(player, fertilized)) return false;
            ImmutableBlockState origin = CraftEngineBlocks.getCustomBlockState(fertilized);
            if (origin == null || origin.isEmpty()) return false;
            changes.add(new Cell(plugin, fertilized, origin, origin, player, guard));
        }
        if (fertilized != null) {
            var states = changes.stream().filter(cell -> !cell.readOnly).map(cell -> cell.proposal).toList();
            BlockFertilizeEvent event = new BlockFertilizeEvent(fertilized, player, new ArrayList<>(states));
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            // A listener removing a proposed cell prevents a partial two-block crop from being committed.
            if (!event.getBlocks().equals(states)) return false;
        }
        return CropGrowthTransaction.commit(changes);
    }

    static boolean apply(FarmersDelightPlugin plugin, Block target, ImmutableBlockState requested) {
        return apply(plugin, Map.of(target, requested), null, null);
    }

    private static boolean allowed(Player player, Block block) {
        return player == null || ProtectionCompat.canBuild(player, block, (String) null)
                && ProtectionCompat.canPlace(player, block, null);
    }

    static boolean preservesStructure(Map<Property<?>, Comparable<?>> planned, Map<Property<?>, Comparable<?>> selected) {
        if (!planned.keySet().equals(selected.keySet())) return false;
        for (var entry : planned.entrySet()) {
            if (!"age".equals(entry.getKey().name()) && !entry.getValue().equals(selected.get(entry.getKey()))) return false;
        }
        return true;
    }

    private static final class Cell implements CropGrowthTransaction.Mutation {
        private final FarmersDelightPlugin plugin;
        private final Block block;
        private final ImmutableBlockState before;
        private final BlockData original;
        private final BlockState proposal;
        private final Player player;
        private final BooleanSupplier guard;
        private final boolean readOnly;
        private ImmutableBlockState after;

        Cell(FarmersDelightPlugin plugin, Block block, ImmutableBlockState before, ImmutableBlockState after,
             Player player, BooleanSupplier guard) {
            this.plugin = plugin;
            this.block = block;
            this.before = before;
            this.after = after;
            this.player = player;
            this.guard = guard;
            this.readOnly = before == after;
            original = block.getBlockData().clone();
            proposal = block.getState();
            proposal.setBlockData(CraftEngineBlocks.getBukkitBlockData(after));
        }

        @Override public boolean current() {
            return guard.getAsBoolean() && resident(plugin, block) && allowed(player, block)
                    && CraftEngineBlocks.getCustomBlockState(block) == before
                    && block.getBlockData().equals(original) && guard.getAsBoolean();
        }

        @Override public boolean authorize() {
            if (readOnly) return true;
            BlockGrowEvent event = new BlockGrowEvent(block, proposal);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            ImmutableBlockState selected = CraftEngineBlocks.getCustomBlockState(proposal.getBlockData());
            if (selected == null || selected.isEmpty() || selected.owner().value() != after.owner().value()) return false;
            if (!preservesStructure(after.propertyEntries(), selected.propertyEntries())) return false;
            after = selected;
            return true;
        }

        @Override public boolean apply() {
            return current() && (readOnly || CraftEngineBlocks.place(block.getLocation(), after, false));
        }

        @Override public boolean installed() {
            return guard.getAsBoolean() && resident(plugin, block) && allowed(player, block)
                    && CraftEngineBlocks.getCustomBlockState(block) == after && guard.getAsBoolean();
        }

        @Override public void restore() {
            if (readOnly) return;
            if (!resident(plugin, block) || CraftEngineBlocks.getCustomBlockState(block) != after) return;
            if (before != null && !before.isEmpty()) CraftEngineBlocks.place(block.getLocation(), before, false);
            else block.setBlockData(original, false);
        }
    }
}
