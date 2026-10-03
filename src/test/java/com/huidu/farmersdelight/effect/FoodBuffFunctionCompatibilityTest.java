package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class FoodBuffFunctionCompatibilityTest {
    @Test void existingFactoriesCaptureTheLivePluginOnceAndProduceWorkingBoundOwnersWithTheirOriginalUnits() throws Exception {
        Field singleton = field(FarmersDelightPlugin.class, "instance"); Object original = singleton.get(null);
        FarmersDelightPlugin captured = allocatePlugin(), later = allocatePlugin();
        try {
            singleton.set(null, captured);
            var secondsFactory = FoodBuffFunction.<Context>factory(FoodBuffFunction.Kind.COMFORT, CommonConditions::fromConfig);
            var ticksFactory = FoodBuffFunction.<Context>ticksFactory(FoodBuffFunction.Kind.NOURISHMENT, CommonConditions::fromConfig);
            singleton.set(null, later);
            var seconds = secondsFactory.create(ConfigSection.ofRoot(new LinkedHashMap<>()));
            var ticks = ticksFactory.create(ConfigSection.ofRoot(new LinkedHashMap<>()));
            assertSame(captured, ownerBinding(seconds)); assertSame(captured, ownerBinding(ticks));
            assertFalse(field(FoodBuffFunction.class, "durationTicks").getBoolean(seconds));
            assertTrue(field(FoodBuffFunction.class, "durationTicks").getBoolean(ticks));
            assertEquals(90, ((NumberProvider) field(FoodBuffFunction.class, "duration").get(seconds)).getInt(null));
            assertEquals(20, ((NumberProvider) field(FoodBuffFunction.class, "duration").get(ticks)).getInt(null));
        } finally { singleton.set(null, original); }
    }
    @Test void injectedFactoriesKeepTheirExplicitPluginWhenTheGlobalInstanceChanges() throws Exception {
        Field singleton = field(FarmersDelightPlugin.class, "instance"); Object original = singleton.get(null);
        FarmersDelightPlugin explicit = allocatePlugin();
        try {
            singleton.set(null, allocatePlugin());
            var factory = FoodBuffFunction.<Context>factory(explicit, FoodBuffFunction.Kind.COMFORT, CommonConditions::fromConfig);
            singleton.set(null, null);
            var function = factory.create(ConfigSection.ofRoot(new LinkedHashMap<>()));
            assertSame(explicit, ownerBinding(function));
        } finally { singleton.set(null, original); }
    }
    private static Object ownerBinding(Object function) throws Exception {
        Object owner = field(FoodBuffFunction.class, "owner").get(function);
        Object access = field(ContentFunctionOwner.class, "access").get(owner);
        for (Field binding : access.getClass().getDeclaredFields()) if (binding.getType() == FarmersDelightPlugin.class) {
            binding.setAccessible(true); return binding.get(access);
        }
        throw new AssertionError("The function owner lost its injected plugin binding");
    }
    private static FarmersDelightPlugin allocatePlugin() throws Exception {
        // Only construction/binding is under test; never call methods on an unstarted JavaPlugin.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field instance = field(unsafe, "theUnsafe");
        return (FarmersDelightPlugin) unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), FarmersDelightPlugin.class);
    }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
}
