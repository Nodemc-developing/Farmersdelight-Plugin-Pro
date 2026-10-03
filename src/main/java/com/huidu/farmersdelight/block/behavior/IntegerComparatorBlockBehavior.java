package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;

public final class IntegerComparatorBlockBehavior extends FarmersDelightBlockBehavior {
    public static final BlockBehaviorFactory<IntegerComparatorBlockBehavior> FACTORY = (block, config) ->
            new IntegerComparatorBlockBehavior(block, BlockBehaviorFactory.getProperty(config.path(), block, config.getString("property", "composting"), Integer.class), config.getBoolean("enabled", true));
    private final Property<Integer> property;
    private final boolean enabled;
    private final int maximum;
    private IntegerComparatorBlockBehavior(BlockDefinition block, Property<Integer> property, boolean enabled) {
        super(block); this.property = property; this.enabled = enabled;
        maximum = property.possibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
    public static int signal(int value, int maximum) { return maximum <= 0 ? 0 : (int) Math.max(0, Math.min(15, (long) value * 15 / maximum)); }
    @Override public boolean isPathFindable(Object block, Object[] args) { return false; }
    @Override public boolean hasAnalogOutputSignal(Object block, Object[] args) { return enabled; }
    @Override public int getAnalogOutputSignal(Object block, Object[] args) {
        if (!enabled || args.length == 0) return 0;
        var state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        return state == null || state.isEmpty() ? 0 : signal(state.get(property), maximum);
    }
}
