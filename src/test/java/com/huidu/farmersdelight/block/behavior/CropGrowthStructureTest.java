package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.property.BooleanProperty;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.EnumProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CropGrowthStructureTest {
    private static final Property<Integer> AGE = IntegerProperty.create("age", 0, 4, 0);
    private static final Property<DoubleBlockHalf> HALF = EnumProperty.create("half", DoubleBlockHalf.class,
            List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
    private static final Property<Boolean> ROPE = BooleanProperty.create("ropelogged", false);

    @Test void listenersCanAdjustAgeWithoutChangingThePlannedCropStructure() {
        Map<Property<?>, Comparable<?>> plan = Map.of(AGE, 3, HALF, DoubleBlockHalf.UPPER, ROPE, false);
        assertTrue(CropGrowthCommit.preservesStructure(plan, Map.of(AGE, 4, HALF, DoubleBlockHalf.UPPER, ROPE, false)));
        assertFalse(CropGrowthCommit.preservesStructure(plan, Map.of(AGE, 3, HALF, DoubleBlockHalf.LOWER, ROPE, false)));
        assertFalse(CropGrowthCommit.preservesStructure(plan, Map.of(AGE, 3, HALF, DoubleBlockHalf.UPPER, ROPE, true)));
        assertFalse(CropGrowthCommit.preservesStructure(plan, Map.of(AGE, 3, HALF, DoubleBlockHalf.UPPER)));
    }
}
