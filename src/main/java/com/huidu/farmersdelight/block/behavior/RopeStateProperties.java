package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;

/** A live palette may still contain properties from before the registry holder was rebound. */
public final class RopeStateProperties {
    private RopeStateProperties() { }

    public static <T extends Comparable<T>> Property<T> property(ImmutableBlockState state, String name, Class<T> type) {
        if (state == null) return null;
        Property<T> property = state.getProperty(name);
        return property != null && property.valueClass() == type && state.contains(property) ? property : null;
    }

    public static ImmutableBlockState withBoolean(ImmutableBlockState state, String name, boolean value) {
        Property<Boolean> property = property(state, name, Boolean.class);
        return property == null ? state : state.with(property, value);
    }
}
