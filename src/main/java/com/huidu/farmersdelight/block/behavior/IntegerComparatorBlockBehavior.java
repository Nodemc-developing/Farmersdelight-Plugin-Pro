package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;

public final class IntegerComparatorBlockBehavior extends FarmersDelightBlockBehavior {
    public static final BlockBehaviorFactory<IntegerComparatorBlockBehavior> FACTORY = (block, config) ->
            new IntegerComparatorBlockBehavior(block, BlockBehaviorFactory.getProperty(config.path(), block,
                    config.getString("property", "composting"), Integer.class), config.getBoolean("enabled", true),
                    config.getString("mode", "scaled"), config.getInt("total", 4));
    private final Property<Integer> property;
    private final boolean enabled;
    private final int maximum;
    private final boolean remaining;
    private final int total;
    private IntegerComparatorBlockBehavior(BlockDefinition block, Property<Integer> property, boolean enabled,
                                          String mode, int total) {
        super(block); this.property = property; this.enabled = enabled;
        if (!"scaled".equals(mode) && !"remaining".equals(mode)) {
            throw new IllegalArgumentException("integer_comparator mode must be scaled or remaining");
        }
        this.remaining = "remaining".equals(mode);
        this.total = total;
        if (remaining && (total < 1 || total > 15)) {
            throw new IllegalArgumentException("remaining comparator total must be 1..15");
        }
        maximum = property.possibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
    public static int signal(int value, int maximum) { return maximum <= 0 ? 0 : (int) Math.max(0, Math.min(15, (long) value * 15 / maximum)); }
    public static int remainingSignal(int consumed, int total) {
        return (int) Math.max(0, Math.min(15, (long) total - consumed));
    }
    @Override public boolean isPathFindable(Object block, Object[] args) { return false; }
    @Override public boolean hasAnalogOutputSignal(Object block, Object[] args) { return enabled; }
    @Override public int getAnalogOutputSignal(Object block, Object[] args) {
        if (!enabled || args.length == 0) return 0;
        var state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return 0;
        Property<Integer> current = state.getProperty(property.name());
        if (current == null) return 0;
        return remaining ? remainingSignal(state.get(current), total) : signal(state.get(current), maximum);
    }
}
