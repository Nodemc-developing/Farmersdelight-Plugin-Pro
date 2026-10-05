package com.huidu.farmersdelight.registry;

import com.huidu.farmersdelight.block.behavior.CompatibilityMechanicFactories;
import com.huidu.farmersdelight.effect.ContentEffectFunction;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMechanicRegistrationTest {
    @Test void extendedGameplayRemainsRegisteredWithoutAnAliasLayer() {
        BehaviorRegistrar.registerBlockBehaviors();
        BehaviorRegistrar.registerItemBehaviors();
        BehaviorRegistrar.registerFunctions();
        BehaviorRegistrar.registerConditions();
        BehaviorRegistrar.registerLootFunctions();
        CompatibilityMechanicFactories.blockFactories().keySet().forEach(suffix ->
                assertNotNull(BuiltInRegistries.BLOCK_BEHAVIOR_TYPE.getValue(Key.of("farmersdelight:" + suffix)), suffix));
        CompatibilityMechanicFactories.itemFactories().keySet().forEach(suffix ->
                assertNotNull(BuiltInRegistries.ITEM_BEHAVIOR_TYPE.getValue(Key.of("farmersdelight:" + suffix)), suffix));
        ContentEffectFunction.factories().keySet().forEach(suffix ->
                assertNotNull(BuiltInRegistries.COMMON_FUNCTION_TYPE.getValue(Key.of("farmersdelight:" + suffix)), suffix));
        assertNotNull(BuiltInRegistries.COMMON_CONDITION_TYPE.getValue(Key.of("farmersdelight:is_sneaking")));
        assertNotNull(BuiltInRegistries.LOOT_FUNCTION_TYPE.getValue(Key.of("farmersdelight:grant_advancement")));
    }
}
