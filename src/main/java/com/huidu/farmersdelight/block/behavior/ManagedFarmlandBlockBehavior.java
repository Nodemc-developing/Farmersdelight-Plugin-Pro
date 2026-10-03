package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.parser.BlockStateParser;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class ManagedFarmlandBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock {
    public static final BlockBehaviorFactory<ManagedFarmlandBlockBehavior> FACTORY = factory(null);
    public static BlockBehaviorFactory<ManagedFarmlandBlockBehavior> factory(FarmersDelightPlugin plugin) {
        HydrationSampler<SoilPosition> hydration = new HydrationSampler<>(256, TimeUnit.SECONDS.toNanos(2));
        return (block, config) -> new ManagedFarmlandBlockBehavior(plugin, hydration, block, config);
    }
    private record SoilPosition(UUID world, int x, int y, int z) { }
    private final FarmersDelightPlugin plugin;
    private final BlockDefinition definition;
    private final HydrationSampler<SoilPosition> hydration;
    private final Property<Integer> moisture;
    private final int maximum, waterRange;
    private final boolean trampling, randomTicking;
    private final String replacement;
    private final ConfiguredBlockSet whitelist, blacklist;

    private ManagedFarmlandBlockBehavior(FarmersDelightPlugin plugin, HydrationSampler<SoilPosition> hydration,
                                          BlockDefinition block, ConfigSection config) {
        super(block);
        this.plugin = plugin;
        this.definition = block;
        this.hydration = hydration;
        moisture = BlockBehaviorFactory.getProperty(config.path(), block, "moisture", Integer.class);
        maximum = config.getInt(new String[]{"max_moisture", "max-moisture"}, 7);
        waterRange = config.getInt(new String[]{"water_range", "water-range"}, 4);
        trampling = config.getBoolean("trampling", true);
        randomTicking = config.getBoolean(new String[]{"random-ticking", "random_ticking"}, true);
        replacement = config.getString(new String[]{"turn_to", "turn-to"}, "minecraft:dirt");
        if (!moisture.possibleValues().contains(maximum) || waterRange < 0 || waterRange > 16) throw new IllegalArgumentException("Invalid moisture or water_range");
        whitelist = ConfiguredBlockSet.parse(config.getStringList(new String[]{"solid_above_whitelist", "solid-above-whitelist"}, List.of("minecraft:melon", "minecraft:pumpkin", "#minecraft:fence_gates")));
        blacklist = ConfiguredBlockSet.parse(config.getStringList(new String[]{"solid_above_blacklist", "solid-above-blacklist"}, List.of()));
    }
    @Override public boolean canRandomlyTick(ImmutableBlockState state) { return randomTicking; }
    @Override public boolean isPathFindable(Object nativeBlock, Object[] args) { return false; }
    private boolean available() { return plugin == null || plugin.isEnabled() && FarmersDelightPlugin.isEnabled0(); }
    private boolean owned(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && (plugin == null ? Bukkit.isOwnedByCurrentRegion(location) : plugin.scheduler().isOwnedByCurrentRegion(location));
    }
    private boolean owned(Block block) {
        return owned(block.getLocation());
    }
    private boolean covered(Block above) { return blacklist.contains(above) || above.isSolid() && !whitelist.contains(above); }
    private void convert(Block block) {
        if (!available() || !owned(block)) return;
        if (replacement.startsWith("minecraft:")) block.setBlockData(Bukkit.createBlockData(replacement), true);
        else {
            var parsed = BlockStateParser.deserialize(replacement);
            if (parsed != null) CraftEngineBlocks.place(block.getLocation(), parsed, true);
        }
    }
    @Override public void neighborChanged(Object nativeBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]); BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        if (!available() || !owned(new Location(world, pos.x(), pos.y(), pos.z()))) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (owned(target) && covered(target.getRelative(BlockFace.UP))) convert(target);
    }
    @Override public void randomTick(Object nativeBlock, Object[] args) {
        if (!randomTicking || args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        World world = CraftEngineAdapter.toWorld(args[1]); BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (state == null || state.isEmpty() || world == null || pos == null) return;
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (!available() || !owned(location) || state.owner().value() != definition) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        Block above = target.getRelative(BlockFace.UP);
        if (covered(above)) { convert(target); return; }
        long generation = plugin == null ? 0L : plugin.configurationGeneration();
        hydration.sample(new SoilPosition(world.getUID(), pos.x(), pos.y(), pos.z()), pos.x(), pos.y(), pos.z(), waterRange,
                new HydrationSampler.Access() {
                    @Override public boolean available() {
                        return ManagedFarmlandBlockBehavior.this.available()
                                && (plugin == null || plugin.configurationGeneration() == generation);
                    }
                    @Override public boolean current() {
                        return available() && ManagedFarmlandBlockBehavior.this.owned(location)
                                && CraftEngineBlocks.byId(definition.id()) == definition
                                && CustomBlockUtils.getStateIfResident(target) == state;
                    }
                    @Override public boolean raining() { return world.hasStorm() && target.getRelative(BlockFace.UP).getLightFromSky() == 15; }
                    @Override public boolean loaded(int cx, int cz) { return world.isChunkLoaded(cx, cz); }
                    @Override public boolean owned(int cx, int cz) {
                        return ManagedFarmlandBlockBehavior.this.owned(new Location(world, cx * 16, pos.y(), cz * 16));
                    }
                    @Override public boolean water(int x, int y, int z) {
                        Block neighbor = world.getBlockAt(x, y, z);
                        Material type = neighbor.getType();
                        if (type == Material.WATER || type == Material.BUBBLE_COLUMN
                                || neighbor.getBlockData() instanceof Waterlogged logged && logged.isWaterlogged()) return true;
                        ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(neighbor);
                        return custom != null && !custom.isEmpty() && custom.settings().fluidState();
                    }
                    @Override public void at(int cx, int cz, Runnable action) {
                        if (plugin == null) throw new IllegalStateException("Owner scheduling requires the plugin factory");
                        plugin.scheduler().runAt(world, cx, cz, action);
                    }
                    @Override public void back(Runnable action) { plugin.scheduler().runAt(location, action); }
                }, water -> applyHydration(target, state, water));
    }
    private void applyHydration(Block target, ImmutableBlockState state, boolean water) {
        Block above = target.getRelative(BlockFace.UP);
        if (covered(above)) { convert(target); return; }
        int before = state.get(moisture), after = nextMoisture(before, maximum, water);
        if (before != after) CraftEngineBlocks.place(target.getLocation(), state.with(moisture, after), false);
        else if (after == 0 && above.getType().isAir()) convert(target);
    }
    public static int nextMoisture(int current, int maximum, boolean water) { return water ? maximum : Math.max(0, current - 1); }
    @Override public void fallOn(Object nativeBlock, Object[] args) {
        if (!trampling || args.length < 5 || !(args[4] instanceof Number distance) || distance.doubleValue() <= .5) return;
        Object entity = args[3];
        try {
            if (!(EntityUtils.adaptNMS(entity).platformEntity() instanceof LivingEntity living)) return;
            if (ThreadLocalRandom.current().nextDouble() >= Math.min(1, distance.doubleValue() - .5)) return;
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
            World world = CraftEngineAdapter.toWorld(args[0]);
            if (world == null || pos == null) return;
            if (!(living instanceof org.bukkit.entity.Player) && !Boolean.TRUE.equals(world.getGameRuleValue(org.bukkit.GameRule.MOB_GRIEFING))) return;
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!owned(block)) return;
            var customReplacement = replacement.startsWith("minecraft:") ? null : BlockStateParser.deserialize(replacement);
            if (!replacement.startsWith("minecraft:") && customReplacement == null) return;
            var nativeReplacement = customReplacement == null ? Bukkit.createBlockData(replacement)
                    : BlockStateUtils.fromBlockData(customReplacement.customBlockState().minecraftState());
            var event = new org.bukkit.event.entity.EntityChangeBlockEvent(living, block, nativeReplacement);
            Bukkit.getPluginManager().callEvent(event);
            if (!event.isCancelled()) convert(block);
        } catch (IllegalArgumentException ignored) { }
    }
}
