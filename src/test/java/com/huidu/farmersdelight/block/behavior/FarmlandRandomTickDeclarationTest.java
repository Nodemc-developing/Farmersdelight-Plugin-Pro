package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.block.behavior.CompositeBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.DualBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FarmlandRandomTickDeclarationTest {
    private static final Property<Integer> MOISTURE = IntegerProperty.create("moisture", 0, 7, 0);
    private static BlockDefinition block() {
        return (BlockDefinition) Proxy.newProxyInstance(BlockDefinition.class.getClassLoader(), new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> Key.of("test:farmland");
                    case "getProperty" -> "moisture".equals(args[0]) ? MOISTURE : null;
                    case "toString" -> "FarmlandDefinition";
                    default -> throw new UnsupportedOperationException(method.toString());
                });
    }
    private static ConfigSection config(Map<String, Object> values) {
        Map<String, Object> configuration = new LinkedHashMap<>(values);
        // The constructor test has no running registry; unrelated block tags are tested with a host elsewhere.
        configuration.put("solid_above_whitelist", List.of());
        configuration.put("solid_above_blacklist", List.of());
        return ConfigSection.of("behavior", configuration);
    }
    private static List<BlockBehavior> behaviors(Map<String, Object> values) {
        BlockDefinition block = block(); ConfigSection config = config(values);
        return List.of(ManagedFarmlandBlockBehavior.FACTORY.create(block, config),
                RichSoilFarmlandBlockBehavior.FACTORY.create(block, config));
    }

    @Test void bothFarmlandBehaviorsDeclareTheTypedNativeRandomTickCapabilityByDefault() {
        for (BlockBehavior behavior : behaviors(Map.of())) {
            assertInstanceOf(RandomTickBlock.class, behavior);
            assertTrue(((RandomTickBlock) behavior).canRandomlyTick(null));
        }
    }

    @Test void explicitDisableInEitherSpellingPreventsTicksEvenWhenACompositeStillTicks() {
        for (String spelling : List.of("random-ticking", "random_ticking")) {
            List<BlockBehavior> disabled = behaviors(Map.of(spelling, false));
            for (BlockBehavior behavior : disabled) {
                assertFalse(((RandomTickBlock) behavior).canRandomlyTick(null));
                assertDoesNotThrow(() -> behavior.randomTick(null, null), "Disabled behavior must not inspect native tick arguments");
            }
            BlockDefinition block = block();
            CompositeBlockBehavior combined = new CompositeBlockBehavior(block,
                    List.of(disabled.get(0), disabled.get(1), new EnabledTick(block)));
            assertTrue(combined.canRandomlyTick(null));
            assertDoesNotThrow(() -> combined.randomTick(null, null));
        }
    }

    @Test void actualCraftEngineDualAndCompositeAggregationPreservesFarmlandTickRequirements() {
        List<BlockBehavior> enabled = behaviors(Map.of()), disabled = behaviors(Map.of("random-ticking", false));
        BlockDefinition block = block();
        assertTrue(new DualBlockBehavior(block, disabled.get(0), enabled.get(1)).canRandomlyTick(null));
        assertTrue(new CompositeBlockBehavior(block, List.of(disabled.get(0), enabled.get(0), disabled.get(1))).canRandomlyTick(null));
        assertFalse(new DualBlockBehavior(block, disabled.get(0), disabled.get(1)).canRandomlyTick(null));
        assertFalse(new CompositeBlockBehavior(block, List.of(disabled.get(0), disabled.get(1))).canRandomlyTick(null));
    }

    @Test void supportedWaterRangeLimitsLoadAndInvalidRangesRejectTheDefinition() {
        for (String spelling : List.of("water_range", "water-range")) {
            for (int radius : new int[]{0, 4, 16}) {
                assertNotNull(ManagedFarmlandBlockBehavior.FACTORY.create(block(), config(Map.of(spelling, radius))));
            }
            for (int radius : new int[]{-1, 17}) {
                assertThrows(IllegalArgumentException.class,
                        () -> ManagedFarmlandBlockBehavior.FACTORY.create(block(), config(Map.of(spelling, radius))));
            }
        }
    }

    private static final class EnabledTick extends FarmersDelightBlockBehavior implements RandomTickBlock {
        EnabledTick(BlockDefinition definition) { super(definition); }
        @Override public boolean canRandomlyTick(ImmutableBlockState state) { return true; }
        @Override public boolean isPathFindable(Object nativeBlock, Object[] args) { return false; }
    }
}
