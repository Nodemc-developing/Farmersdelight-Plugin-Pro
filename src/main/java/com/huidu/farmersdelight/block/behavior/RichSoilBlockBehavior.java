package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
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
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.List;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import net.momirealms.craftengine.core.block.parser.BlockStateParser;
import org.bukkit.Bukkit;
import java.util.concurrent.ThreadLocalRandom;

public class RichSoilBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private final float boostChance;
    private final Key brownMushroomColonyId;
    private final Key redMushroomColonyId;
    private final ConfiguredBlockSet brownMushrooms;
    private final ConfiguredBlockSet redMushrooms;
    // Store unaffected blocks per behavior instance so different configurations cannot overwrite each other.
    // Publish the fully parsed final set before region ticks can read it.
    private final ConfiguredBlockSet unaffectedBlocks;
    private record Conversion(SoilRuleSupport.SoilRules source, String target) { }
    private final List<Conversion> conversions;
    private final Particle growthParticle;
    private final int particleCount;
    private final boolean randomTickEnabled;

    private RichSoilBlockBehavior(BlockDefinition block, float boostChance,
                                   Key brownMushroomColonyId, Key redMushroomColonyId,
                                   ConfiguredBlockSet unaffectedBlocks,
                                   ConfiguredBlockSet brownMushrooms,
                                   ConfiguredBlockSet redMushrooms, List<Conversion> conversions, Particle growthParticle, int particleCount,
                                   boolean randomTickEnabled) {
        super(block);
        this.boostChance = boostChance;
        this.brownMushroomColonyId = brownMushroomColonyId;
        this.redMushroomColonyId = redMushroomColonyId;
        this.unaffectedBlocks = unaffectedBlocks;
        this.brownMushrooms = brownMushrooms;
        this.redMushrooms = redMushrooms;
        this.conversions = List.copyOf(conversions);
        this.growthParticle = growthParticle;
        this.particleCount = particleCount;
        this.randomTickEnabled = randomTickEnabled;
    }

    public ConfiguredBlockSet unaffectedBlocks() {
        return unaffectedBlocks;
    }

    public Key getBrownMushroomColonyId() {
        return brownMushroomColonyId;
    }

    public Key getRedMushroomColonyId() {
        return redMushroomColonyId;
    }

    public static final BlockBehaviorFactory<RichSoilBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        float chance = BehaviorArgParser.getFloat(arguments, "boost-chance", 0.08f);
        if (!Float.isFinite(chance) || chance < 0 || chance > 1) throw new IllegalArgumentException("boost-chance must be in [0, 1]");
        String brownId = BehaviorArgParser.getStringStrict(arguments, "mushroom-colony.brown", "brown-mushroom-colony", "farmersdelight:brown_mushroom_colony");
        String redId = BehaviorArgParser.getStringStrict(arguments, "mushroom-colony.red", "red-mushroom-colony", "farmersdelight:red_mushroom_colony");
        ConfiguredBlockSet unaffected = ConfiguredBlockSet.parse(arguments.get("unaffected-blocks"));
        ConfiguredBlockSet brownMushrooms = configuredMushrooms(arguments, "brown-mushroom-blocks",
                "minecraft:brown_mushroom", Constants.BLOCK_BROWN_MUSHROOM);
        ConfiguredBlockSet redMushrooms = configuredMushrooms(arguments, "red-mushroom-blocks",
                "minecraft:red_mushroom", Constants.BLOCK_RED_MUSHROOM);
        List<Conversion> conversions = new java.util.ArrayList<>();
        if (section != null) for (ConfigSection row : section.getSectionList("conversions", value -> value)) {
            String source = row.getNonEmptyString("source");
            String target = row.getNonEmptyString("target");
            Map<String, Object> rule = source.startsWith("#") ? Map.of("bottom-block-tags", List.of(source.substring(1)))
                    : Map.of("bottom-blocks", List.of(source));
            conversions.add(new Conversion(SoilRuleSupport.parseSoilRules(rule), target));
        }
        Particle particle = Particle.valueOf(BehaviorArgParser.getString(arguments, "particle", "HAPPY_VILLAGER").toUpperCase(java.util.Locale.ROOT));
        int count = BehaviorArgParser.getInt(arguments, "particle-count", 10);
        if (count < 0 || count > 1024) throw new IllegalArgumentException("particle-count must be in [0, 1024]");
        return new RichSoilBlockBehavior(block, chance, Key.of(brownId), Key.of(redId), unaffected,
                brownMushrooms, redMushrooms, conversions, particle, count,
                section == null || section.getBoolean(new String[]{"random-ticking", "random_ticking"}, true));
    };

    @Override public boolean canRandomlyTick(ImmutableBlockState state) { return randomTickEnabled; }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (!FarmersDelightPlugin.isEnabled0() || !randomTickEnabled || args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (convertMushroomToColony(above)) return;

        tryBoost(world, pos);
    }

    public void tryBoost(World world, BlockPos pos) {
        if (boostChance <= 0F) return;
        if (ThreadLocalRandom.current().nextFloat() > boostChance) return;
        Block above = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (boostPlant(above)) return;
        Block below = world.getBlockAt(pos.x(), pos.y() - 1, pos.z());
        boostPlant(below);
    }

    private boolean convertMushroomToColony(Block target) {
        if (brownMushrooms.contains(target)) {
            return replaceWithColony(target, brownMushroomColonyId);
        }
        if (redMushrooms.contains(target)) {
            return replaceWithColony(target, redMushroomColonyId);
        }
        return false;
    }

    private static ConfiguredBlockSet configuredMushrooms(Map<String, Object> arguments, String key,
                                                         String... defaults) {
        Object configured = BehaviorArgParser.getRaw(arguments, key);
        return configured == null ? ConfiguredBlockSet.parse(List.of(defaults))
                : ConfiguredBlockSet.parse(configured);
    }

    private boolean replaceWithColony(Block target, Key colonyId) {
        BlockDefinition colony = CraftEngineBlocks.byId(colonyId);
        if (colony == null) return false;
        // Plant a mushroom colony at age 0 so it still needs to grow.
        // The colony's placement default is mature age 3, which must be overridden on this path.
        ImmutableBlockState young = colonyAgeZero(colony);
        if (young == null) return false;
        CraftEngineBlocks.place(target.getLocation().add(0.5, 0, 0.5), young, true);
        return true;
    }

    private static ImmutableBlockState colonyAgeZero(BlockDefinition colony) {
        Property<Integer> property = BlockBehaviorFactory.getOptionalProperty(colony, "age", Integer.class);
        return property == null ? null : colony.defaultState().with(property, 0);
    }

    private boolean boostPlant(Block plant) {
        // Skip configured unaffected plants so soil does not accelerate their growth or spreading.
        if (unaffectedBlocks.contains(plant)) {
            return false;
        }
        for (Conversion conversion : conversions) {
            if (!SoilRuleSupport.matches(plant, conversion.source())) continue;
            if (conversion.target().startsWith("minecraft:")) plant.setBlockData(Bukkit.createBlockData(conversion.target()), true);
            else {
                ImmutableBlockState replacement = BlockStateParser.deserialize(conversion.target());
                if (replacement == null) continue;
                CraftEngineBlocks.place(plant.getLocation(), replacement, true);
            }
            if (particleCount > 0) plant.getWorld().spawnParticle(growthParticle, plant.getLocation().add(.5, .5, .5), particleCount, .3, .3, .3);
            return true;
        }
        try {
            boolean applied = plant.applyBoneMeal(BlockFace.UP);
            if (applied) {
                plant.getWorld().spawnParticle(growthParticle,
                        plant.getLocation().add(0.5, 0.5, 0.5), particleCount, 0.3, 0.3, 0.3);
            }
            return applied;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

}
