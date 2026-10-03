package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class RichSoilFarmlandBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock {

    private static final int MAX_MOISTURE = 7;
    private static final String MOISTURE_PROPERTY = "moisture";
    private record SoilPosition(UUID world, int x, int y, int z) { }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private final float boostChance;
    private final boolean randomTicking;
    private final FarmersDelightPlugin plugin;
    private final BlockDefinition definition;
    private final HydrationSampler<SoilPosition> hydration;
    private final Property<Integer> moistureProperty;
    private final Key richSoilBlockId;
    // Null when the configuration declares no unaffected-blocks key of its own, in which case the list is
    // borrowed from the rich soil block named by rich-soil-block. An explicitly configured empty list is a
    // parsed empty set, not null, so writing an empty list does disable the exclusions.
    private final ConfiguredBlockSet configuredUnaffectedBlocks;
    // Resolved copy of the borrowed list. The rich soil block may not be registered yet when this behavior is
    // built, so the lookup is deferred to first use and cached once it succeeds. Written from the tick thread
    // that first resolves it and read from every tick thread, hence volatile; the referenced set is immutable
    // and the field only ever moves from null to a fully built set, so a racing reader either misses the cache
    // and repeats the lookup or sees the finished set.
    private volatile ConfiguredBlockSet borrowedUnaffectedBlocks;

    private RichSoilFarmlandBlockBehavior(FarmersDelightPlugin plugin, HydrationSampler<SoilPosition> hydration,
                                          BlockDefinition block, float boostChance, Property<Integer> moistureProperty,
                                          Key richSoilBlockId, ConfiguredBlockSet configuredUnaffectedBlocks, boolean randomTicking) {
        super(block);
        this.plugin = plugin;
        this.definition = block;
        this.hydration = hydration;
        this.boostChance = boostChance;
        this.randomTicking = randomTicking;
        this.moistureProperty = moistureProperty;
        this.richSoilBlockId = richSoilBlockId;
        this.configuredUnaffectedBlocks = configuredUnaffectedBlocks;
    }

    private ConfiguredBlockSet unaffectedBlocks() {
        if (configuredUnaffectedBlocks != null) {
            return configuredUnaffectedBlocks;
        }
        ConfiguredBlockSet cached = borrowedUnaffectedBlocks;
        if (cached != null) {
            return cached;
        }
        BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
        if (richSoil == null) {
            return ConfiguredBlockSet.EMPTY;
        }
        RichSoilBlockBehavior behavior = CustomBlockUtils.getBehavior(richSoil.defaultState(), RichSoilBlockBehavior.class);
        if (behavior == null) {
            return ConfiguredBlockSet.EMPTY;
        }
        ConfiguredBlockSet resolved = behavior.unaffectedBlocks();
        borrowedUnaffectedBlocks = resolved;
        return resolved;
    }

    public static final BlockBehaviorFactory<RichSoilFarmlandBlockBehavior> FACTORY = factory(null);

    public static BlockBehaviorFactory<RichSoilFarmlandBlockBehavior> factory(FarmersDelightPlugin plugin) {
        // Shared by all definitions created by this factory; cancelled or expired work cannot grow a queue.
        HydrationSampler<SoilPosition> hydration = new HydrationSampler<>(256, TimeUnit.SECONDS.toNanos(2));
        return (BlockDefinition block, ConfigSection section) -> {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
            // Moisture drives the whole random tick: drying out, rehydrating from water or rain, and the boost
            // that only fully wet soil performs. The property may carry another name, but one of that name has
            // to exist: a block declaring this behavior without it aborts its own load here, with the config
            // node and the name in the message, instead of loading as farmland whose moisture never changes
            // and which never boosts anything.
            String path = section != null ? section.path() : Constants.BEHAVIOR_RICH_SOIL_FARMLAND;
            String moisturePropertyName = BehaviorArgParser.getString(arguments, "moisture-property", MOISTURE_PROPERTY);
            Property<Integer> moistureProperty =
                    BlockBehaviorFactory.getProperty(path, block, moisturePropertyName, Integer.class);
            String richSoilId = BehaviorArgParser.getStringStrict(arguments, "rich-soil-block", "farmersdelight:rich_soil");
            ConfiguredBlockSet unaffected = BehaviorArgParser.hasArgument(arguments, "unaffected-blocks")
                    ? ConfiguredBlockSet.parse(arguments.get("unaffected-blocks"))
                    : null;
            boolean randomTicking = BehaviorArgParser.getBoolean(arguments, "random-ticking", true);
            return new RichSoilFarmlandBlockBehavior(plugin, hydration, block, chance, moistureProperty, Key.of(richSoilId), unaffected, randomTicking);
        };
    }

    @Override public boolean canRandomlyTick(ImmutableBlockState state) { return randomTicking; }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (!available() || !residentAndOwned(world, pos, location)) return;

        // Convert farmland to rich soil when a solid block covers it.
        // Melons, pumpkins, fence gates and moving pistons do not cause this conversion.
        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!isSuffocatingCover(above)) return;

        BlockDefinition richSoil = CraftEngineBlocks.byId(richSoilBlockId);
        if (richSoil == null) return;
        try {
            CraftEngineBlocks.place(new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5),
                    richSoil.defaultState(), true);
        } catch (RuntimeException | LinkageError ignored) {
            // block placement is best-effort during ticking; rich soil conversion continues next tick
        }
    }

    private static boolean isSuffocatingCover(Block above) {
        // Block.isSolid() checks the placed state's collision; Material.isSolid() tests the base material.
        // CE crops may use plant carrier materials whose material flag is solid but whose collision is empty.
        // Use the state check so planting crops does not revert the farmland.
        if (!above.isSolid()) return false;
        Material type = above.getType();
        if (type == Material.MELON || type == Material.PUMPKIN || type == Material.MOVING_PISTON) return false;
        return !type.name().endsWith("_FENCE_GATE");
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (!randomTicking || args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        if (!available() || !residentAndOwned(world, pos, location) || state.owner().value() != definition) return;
        long generation = plugin == null ? 0L : plugin.configurationGeneration();
        hydration.sample(new SoilPosition(world.getUID(), pos.x(), pos.y(), pos.z()), pos.x(), pos.y(), pos.z(),
                new HydrationSampler.Access() {
                    @Override public boolean available() {
                        return RichSoilFarmlandBlockBehavior.this.available()
                                && (plugin == null || plugin.configurationGeneration() == generation);
                    }
                    @Override public boolean current() {
                        return available() && residentAndOwned(world, pos, location)
                                && CraftEngineBlocks.byId(definition.id()) == definition
                                && CustomBlockUtils.getStateIfResident(world.getBlockAt(pos.x(), pos.y(), pos.z())) == state;
                    }
                    @Override public boolean raining() {
                        // Covered soil is not hydrated by rain; the block above shares the soil's chunk.
                        return world.hasStorm() && !world.isClearWeather()
                                && world.getBlockAt(pos.x(), pos.y() + 1, pos.z()).getLightFromSky() == 15;
                    }
                    @Override public boolean loaded(int cx, int cz) { return world.isChunkLoaded(cx, cz); }
                    @Override public boolean owned(int cx, int cz) {
                        return owns(new Location(world, cx * 16, pos.y(), cz * 16));
                    }
                    @Override public boolean water(int x, int y, int z) {
                        Block neighbor = world.getBlockAt(x, y, z);
                        Material type = neighbor.getType();
                        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return true;
                        ImmutableBlockState ce = BlockStateUtils.getOptionalCustomBlockState(neighbor).orElse(null);
                        return ce != null && !ce.isEmpty() && ce.settings().fluidState();
                    }
                    @Override public void at(int cx, int cz, Runnable action) {
                        if (plugin == null) throw new IllegalStateException("Owner scheduling requires the plugin factory");
                        plugin.scheduler().runAt(world, cx, cz, action);
                    }
                    @Override public void back(Runnable action) { plugin.scheduler().runAt(location, action); }
                }, hydrated -> applyHydration(world, pos, state, hydrated));
    }

    private boolean available() {
        return plugin == null || (plugin.isEnabled() && FarmersDelightPlugin.isEnabled0());
    }

    private boolean owns(Location location) {
        return plugin == null ? Bukkit.isOwnedByCurrentRegion(location) : plugin.scheduler().isOwnedByCurrentRegion(location);
    }

    private boolean residentAndOwned(World world, BlockPos pos, Location location) {
        return world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4) && owns(location);
    }

    private void applyHydration(World world, BlockPos pos, ImmutableBlockState state, boolean hydrated) {
        // Water or rain restores maximum moisture; otherwise each random tick removes one level.
        // Only fully wet soil boosts the plant above. Moisture 7 uses the wet appearance, while 0..6 appear dry.
        Integer currentMoisture = state.get(moistureProperty);
        int moisture = currentMoisture == null ? 0 : currentMoisture;

        if (!hydrated) {
            if (moisture > 0) {
                setMoisture(world, pos, state, moisture - 1);
            }
        } else if (moisture < MAX_MOISTURE) {
            setMoisture(world, pos, state, MAX_MOISTURE);
        } else if (boostChance > 0F && ThreadLocalRandom.current().nextFloat() <= boostChance) {
            boostAbove(world, pos);
        }
    }

    private void setMoisture(World world, BlockPos pos, ImmutableBlockState state, int value) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        CraftEngineBlocks.place(block.getLocation(), state.with(moistureProperty, value), false);
    }

    private void boostAbove(World world, BlockPos pos) {
        Block plant = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (plant.getType() == Material.AIR) return;
        if (unaffectedBlocks().contains(plant)) return;
        try {
            // applyBoneMeal invokes BonemealableBlock growth for vanilla crops and CE blocks
            // that implement the interface.
            if (plant.applyBoneMeal(BlockFace.UP)) {
                plant.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        plant.getLocation().add(0.5, 0.5, 0.5), 10, 0.3, 0.3, 0.3);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // cosmetic only; effect failure does not block growth
        }
    }

}
