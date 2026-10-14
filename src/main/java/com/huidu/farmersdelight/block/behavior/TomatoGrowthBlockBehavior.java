package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.block.BlockFace;

import java.util.concurrent.ThreadLocalRandom;

/** The tomato's ordinary age roll is separate from its vine and fertilizer interactions. */
public final class TomatoGrowthBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock {
    private final FarmersDelightPlugin plugin;
    private final Property<Integer> age;
    private final int maximum, minimumLight;
    private final float chance;
    private final SoilRuleSupport.SoilRules soils;

    private TomatoGrowthBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, ConfigSection config) {
        super(block);
        this.plugin = plugin;
        age = BlockBehaviorFactory.getProperty(config.path(), block, "age", Integer.class);
        maximum = age.possibleValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
        chance = config.getFloat("grow-speed", 1.0F / 6.0F);
        minimumLight = config.getInt("light-requirement", 9);
        if (!Float.isFinite(chance) || chance < 0 || chance > 1 || minimumLight < 0 || minimumLight > 15)
            throw new IllegalArgumentException("Invalid tomato growth chance or light requirement");
        soils = SoilRuleSupport.parseSoilRules(config.values());
        if (!soils.isConfigured()) throw new IllegalArgumentException("Tomato growth requires supporting soil rules");
    }

    public static BlockBehaviorFactory<TomatoGrowthBlockBehavior> factory(FarmersDelightPlugin plugin) {
        return (block, config) -> new TomatoGrowthBlockBehavior(plugin, block, config);
    }

    @Override public boolean isPathFindable(Object block, Object[] args) { return true; }
    @Override public boolean canRandomlyTick(ImmutableBlockState state) { return state.get(age) < maximum; }

    @Override public void randomTick(Object nativeBlock, Object[] args) {
        if (args == null || args.length < 3) return;
        var state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        var world = CraftEngineAdapter.toWorld(args[1]);
        var pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null || state == null || !canRandomlyTick(state)) return;
        var target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!CropGrowthCommit.resident(plugin, target) || CropGrowthCommit.brightness(target) < minimumLight
                || CraftEngineBlocks.getCustomBlockState(target) != state) return;
        var below = target.getRelative(BlockFace.DOWN);
        if (!CropGrowthCommit.resident(plugin, below) || !SoilRuleSupport.matches(below, soils)) return;
        if (ThreadLocalRandom.current().nextFloat() < chance)
            CropGrowthCommit.apply(plugin, target, state.with(age, state.get(age) + 1));
    }
}
