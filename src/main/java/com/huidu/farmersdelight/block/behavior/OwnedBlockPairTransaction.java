package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-confined edits retract only the states they wrote, preserving later external edits. */
final class OwnedBlockPairTransaction {
    interface Cell<S> { boolean resident(); S read(); boolean write(S state); }
    record Outcome(boolean committed, boolean rollbackComplete) { }
    record State(BlockData vanilla, ImmutableBlockState custom) { }
    private record Position(UUID world, int x, int y, int z) { }
    private static final ThreadLocal<Set<Position>> EDITING = new ThreadLocal<>();
    private OwnedBlockPairTransaction() { }
    static boolean editing(Location location) { Set<Position> editing = EDITING.get(); return editing != null && editing.contains(position(location)); }
    private static Position position(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
    static Cell<State> cell(FarmersDelightPlugin plugin, Block block) {
        return new Cell<>() {
            @Override public boolean resident() {
                return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                        && (plugin == null ? Bukkit.isOwnedByCurrentRegion(block) : plugin.scheduler().isOwnedByCurrentRegion(block.getLocation()));
            }
            @Override public State read() {
                ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(block);
                return new State(block.getBlockData().clone(), custom != null && !custom.isEmpty() ? custom : null);
            }
            @Override public boolean write(State state) {
                if (state.custom != null && !state.custom.isEmpty()) return CraftEngineBlocks.place(block.getLocation(), state.custom, false);
                ImmutableBlockState current = CraftEngineBlocks.getCustomBlockState(block);
                if (current != null && !current.isEmpty() && !CraftEngineBlocks.remove(block, false)) return false;
                block.setBlockData(state.vanilla, false);
                return read().equals(state);
            }
        };
    }
    static State custom(ImmutableBlockState state) { return new State(BlockStateUtils.fromBlockData(state.customBlockState().minecraftState()), state); }
    static Outcome update(FarmersDelightPlugin plugin, Block firstBlock, State firstBefore, State firstAfter,
                          Block secondBlock, State secondBefore, State secondAfter) {
        Position first = position(firstBlock.getLocation()), second = position(secondBlock.getLocation());
        Set<Position> editing = EDITING.get();
        if (editing == null) { editing = new HashSet<>(); EDITING.set(editing); }
        if (editing.contains(first) || editing.contains(second)) return new Outcome(false, true);
        editing.add(first); editing.add(second);
        try { return update(cell(plugin, firstBlock), firstBefore, firstAfter, cell(plugin, secondBlock), secondBefore, secondAfter); }
        finally { editing.remove(first); editing.remove(second); if (editing.isEmpty()) EDITING.remove(); }
    }
    static boolean compensate(FarmersDelightPlugin plugin, Block firstBlock, State firstInstalled, State firstBefore,
                              Block secondBlock, State secondInstalled, State secondBefore) {
        Position first = position(firstBlock.getLocation()), second = position(secondBlock.getLocation());
        Set<Position> editing = EDITING.get();
        if (editing == null) { editing = new HashSet<>(); EDITING.set(editing); }
        if (editing.contains(first) || editing.contains(second)) return false;
        editing.add(first); editing.add(second);
        try {
            boolean right = restore(cell(plugin, secondBlock), secondInstalled, secondBefore);
            boolean left = restore(cell(plugin, firstBlock), firstInstalled, firstBefore);
            return right && left;
        } finally { editing.remove(first); editing.remove(second); if (editing.isEmpty()) EDITING.remove(); }
    }
    static <S> Outcome update(Cell<S> first, S firstBefore, S firstAfter, Cell<S> second, S secondBefore, S secondAfter) {
        if (!matches(first, firstBefore) || !matches(second, secondBefore)) return new Outcome(false, true);
        boolean firstAttempted = !Objects.equals(firstBefore, firstAfter), secondAttempted = false;
        try {
            boolean firstWritten = Objects.equals(firstBefore, firstAfter) || first.write(firstAfter);
            if (!firstWritten || !matches(first, firstAfter) || !matches(second, secondBefore))
                return new Outcome(false, restore(first, firstAfter, firstBefore));
            secondAttempted = !Objects.equals(secondBefore, secondAfter);
            boolean secondWritten = Objects.equals(secondBefore, secondAfter) || second.write(secondAfter);
            if (secondWritten && matches(first, firstAfter) && matches(second, secondAfter)) return new Outcome(true, true);
            boolean right = restore(second, secondAfter, secondBefore), left = restore(first, firstAfter, firstBefore);
            return new Outcome(false, right && left);
        } catch (RuntimeException | Error failure) {
            try { if (secondAttempted) restore(second, secondAfter, secondBefore); if (firstAttempted) restore(first, firstAfter, firstBefore); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    static <S> boolean restore(Cell<S> cell, S installed, S previous) {
        if (Objects.equals(installed, previous)) return true;
        if (!cell.resident()) return false;
        S current = cell.read();
        if (Objects.equals(current, previous) || !Objects.equals(current, installed)) return true;
        boolean written = cell.write(previous);
        return written && matches(cell, previous);
    }
    private static <S> boolean matches(Cell<S> cell, S expected) { return cell.resident() && Objects.equals(cell.read(), expected); }
}
