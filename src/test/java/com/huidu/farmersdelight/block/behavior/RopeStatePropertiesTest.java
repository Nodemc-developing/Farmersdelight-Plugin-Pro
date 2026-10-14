package com.huidu.farmersdelight.block.behavior;

import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.BlockStateVariantProvider;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.BooleanProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.registry.Holder;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RopeStatePropertiesTest {
    @Test void aReboundDefinitionDoesNotReplaceTheOldStatesPropertyIdentity() throws Exception {
        BooleanProperty before = BooleanProperty.create("tied_to_bell", false);
        BooleanProperty after = BooleanProperty.create("tied_to_bell", false);
        Holder.Reference<BlockDefinition> holder = new Holder.Reference<>(null,
                ResourceKey.create(Key.of("craftengine:block"), Key.of("farmersdelight:rope")), definition(before));
        ImmutableBlockState old = provider(holder, Map.of("tied_to_bell", before)).getDefaultState();
        holder.bindValue(definition(after));

        assertSame(after, old.owner().value().getProperty("tied_to_bell"));
        assertFalse(old.contains(after));
        assertSame(before, RopeStateProperties.property(old, "tied_to_bell", Boolean.class));
        ImmutableBlockState updated = RopeStateProperties.withBoolean(old, "tied_to_bell", true);
        assertTrue(updated.get(before));
        assertFalse(old.get(before));
    }

    @Test void missingOrWrongTypedPropertiesAreSkipped() throws Exception {
        BooleanProperty north = BooleanProperty.create("north", false);
        Holder.Reference<BlockDefinition> holder = new Holder.Reference<>(null,
                ResourceKey.create(Key.of("craftengine:block"), Key.of("farmersdelight:rope")), definition(north));
        ImmutableBlockState state = provider(holder, Map.of("north", north)).getDefaultState();
        assertNull(RopeStateProperties.property(state, "north", Integer.class));
        assertNull(RopeStateProperties.property(state, "waterlogged", Boolean.class));
        assertSame(state, RopeStateProperties.withBoolean(state, "waterlogged", true));
    }

    private static BlockDefinition definition(Property<?> property) {
        return (BlockDefinition) Proxy.newProxyInstance(BlockDefinition.class.getClassLoader(), new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> Key.of("farmersdelight:rope");
                    case "getProperty" -> property.name().equals(args[0]) ? property : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BlockStateVariantProvider provider(Holder.Reference<BlockDefinition> holder, Map<String, Property<?>> properties) throws Exception {
        var constructor = ImmutableBlockState.class.getDeclaredConstructor(Holder.Reference.class,
                BlockStateVariantProvider.class, Reference2ObjectArrayMap.class);
        constructor.setAccessible(true);
        return new BlockStateVariantProvider(holder, (owner, variants, values) -> {
            try { return constructor.newInstance(owner, variants, values); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }, properties);
    }
}
