package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.bukkit.block.behavior.BushBlockBehavior;
import net.momirealms.craftengine.core.block.BlockStateVariantProvider;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.EnumProperty;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.registry.Holder;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.ResourceKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VillagerCropTest {
    @AfterEach void cleanup() { TallCropBlockBehavior.cleanupAll(); ManagedCropBlockBehavior.clear(); }

    @Test void cancellingEitherHalfLeavesBothCellsAndLootUntouched() {
        var access = new MemoryAccess();
        Cell lower = new Cell("ripe-lower"), upper = new Cell("ripe-upper");
        VillagerCrop crop = crop(access);
        var changes = List.of(change(lower, "regrowing-lower"), change(upper, "air"));
        var plan = plan(crop, upper, changes);
        AtomicInteger called = new AtomicInteger();

        assertFalse(crop.harvest(plan, event -> { if (called.incrementAndGet() == 2) event.setCancelled(true); }));

        assertEquals("ripe-lower", lower.value); assertEquals("ripe-upper", upper.value);
        assertEquals(0, lower.writes + upper.writes); assertEquals(0, access.drops);
    }

    @Test void eventChangingAnEarlierHalfPreventsTheWholeCommit() {
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("upper");
        VillagerCrop crop = crop(access);
        AtomicInteger called = new AtomicInteger();
        assertFalse(crop.harvest(plan(crop, upper, List.of(change(lower, "reset"), change(upper, "air"))), event -> {
            if (called.incrementAndGet() == 2) lower.value = "external-edit";
        }));
        assertEquals("external-edit", lower.value); assertEquals("upper", upper.value);
        assertEquals(0, lower.writes + upper.writes); assertEquals(0, access.drops);
    }

    @Test void failedSecondMutationRestoresTheFirstAndPreservesAnExternalReplacement() {
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("upper");
        VillagerCrop crop = crop(access);
        upper.replaceOnWrite = "new-owner";
        assertFalse(crop.harvest(plan(crop, upper, List.of(change(lower, "reset"), change(upper, "air"))), event -> { }));
        assertEquals("lower", lower.value); assertEquals("new-owner", upper.value);
        assertEquals(0, access.drops);
    }

    @Test void unloadedPartnerRejectsBeforeAnyEventOrDrop() {
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("upper");
        access.unavailable.add(lower.block);
        VillagerCrop crop = crop(access); AtomicInteger called = new AtomicInteger();
        assertFalse(crop.harvest(plan(crop, upper, List.of(change(lower, "reset"), change(upper, "air"))), event -> called.incrementAndGet()));
        assertEquals(0, called.get()); assertEquals(0, upper.writes); assertEquals(0, access.drops);
    }

    @Test void successfulHarvestCannotBeReplayed() {
        var access = new MemoryAccess(); Cell target = new Cell("ripe"); VillagerCrop crop = crop(access);
        var plan = plan(crop, target, List.of(change(target, "air")));
        assertTrue(crop.harvest(plan, event -> { }));
        assertFalse(crop.harvest(plan, event -> fail("a used harvest plan must not fire another event")));
        assertEquals(1, access.drops); assertEquals(1, target.writes);
    }

    @Test void laterNeighborCallbackCannotInvalidateAnEarlierCellAndStillReceiveLoot() {
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("upper");
        upper.onWrite = () -> lower.value = "external-lower";
        VillagerCrop crop = crop(access);
        assertFalse(crop.harvest(plan(crop, upper, List.of(change(lower, "reset"), change(upper, "air"))), event -> { }));
        assertEquals("external-lower", lower.value); assertEquals("upper", upper.value);
        assertEquals(0, access.drops);
    }

    @Test void firstWriteCannotOverwriteASecondCellChangedByItsCallback() {
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("upper");
        lower.onWrite = () -> upper.value = "external-upper";
        VillagerCrop crop = crop(access);
        assertFalse(crop.harvest(plan(crop, upper, List.of(change(lower, "reset"), change(upper, "air"))), event -> { }));
        assertEquals("lower", lower.value); assertEquals("external-upper", upper.value);
        assertEquals(0, upper.writes); assertEquals(0, access.drops);
    }

    @Test void transactionOwnsTheDoubleHalfResetAndClearsItsNativeRemovalScope() throws Exception {
        Property<Integer> age = IntegerProperty.create("age", 0, 3, 0);
        Property<DoubleBlockHalf> half = EnumProperty.create("half", DoubleBlockHalf.class,
                List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
        BlockDefinition definition = statefulDefinition("test:double_control", Map.of("age", age, "half", half));
        ImmutableBlockState ripeLower = definition.defaultState().with(age, 3);
        ImmutableBlockState ripeUpper = ripeLower.with(half, DoubleBlockHalf.UPPER);
        ImmutableBlockState resetLower = ripeLower.with(age, 0);
        Cell lower = new Cell(ripeLower.getPropertiesAsString()), upper = new Cell(ripeUpper.getPropertiesAsString());
        var access = new MemoryAccess(); access.states.put(lower.block, ripeLower); access.states.put(upper.block, ripeUpper);
        AtomicInteger automaticEdits = new AtomicInteger();
        access.removal = (block, previous) -> {
            if (!VillagerCrop.controlsRemoval(block, previous)) {
                automaticEdits.incrementAndGet(); access.states.put(lower.block, ripeLower.with(age, 2));
            }
        };
        VillagerCrop crop = crop(access);
        var changes = List.of(new VillagerCrop.Change(upper.block, ripeUpper, upper.block.getBlockData(), null, data("air")),
                new VillagerCrop.Change(lower.block, ripeLower, lower.block.getBlockData(), resetLower, access.data(resetLower)));

        assertTrue(crop.harvest(plan(crop, upper, changes), event -> { }));

        assertSame(resetLower, access.states.get(lower.block)); assertNull(access.states.get(upper.block));
        assertEquals(0, automaticEdits.get()); assertEquals(1, access.drops);
        assertFalse(VillagerCrop.controlsRemoval(upper.block, ripeUpper));
    }

    @Test void bundledRiceUsesItsUpperAgeBoundAndNeverHarvestsTheSupportingLowerHalf() throws Exception {
        Property<Integer> age = IntegerProperty.create("age", 0, 4, 0);
        Property<DoubleBlockHalf> half = EnumProperty.create("half", DoubleBlockHalf.class,
                List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
        BlockDefinition definition = definition("farmersdelight:rice", Map.of("age", age, "half", half));
        TallCropBlockBehavior.FACTORY.create(definition, ConfigSection.ofRoot(Map.of("max-age.lower", 4, "max-age.upper", 3)));
        VillagerCrop crop = new VillagerCrop("farmersdelight:rice", "farmersdelight:rice", definition, definition,
                null, VillagerCrop.Mode.TALL, Set.of("farmersdelight:rice"), null, true, true, true, new MemoryAccess());

        assertFalse(crop.isMature(state(definition, Map.of(age, 4, half, DoubleBlockHalf.LOWER))));
        assertFalse(crop.isMature(state(definition, Map.of(age, 2, half, DoubleBlockHalf.UPPER))));
        assertTrue(crop.isMature(state(definition, Map.of(age, 3, half, DoubleBlockHalf.UPPER))));
        assertFalse(crop.accepts(null));
    }

    @Test void numericAgeLookupSkipsNonnumericStages() {
        Property<Integer> growth = IntegerProperty.create("growth", 0, 7, 0);
        Property<DoubleBlockHalf> nonNumeric = EnumProperty.create("age", DoubleBlockHalf.class,
                List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
        assertSame(growth, VillagerCrop.ageProperty(definition("test:crop", Map.of("age", nonNumeric, "growth", growth))));
    }

    @Test void tallCropUsesItsDeclaredAgePropertyBeforeGenericNames() {
        Property<Integer> ripeness = IntegerProperty.create("ripeness", 0, 4, 0);
        Property<DoubleBlockHalf> half = EnumProperty.create("half", DoubleBlockHalf.class,
                List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
        BlockDefinition definition = definition("test:custom_age", Map.of("ripeness", ripeness, "half", half));
        TallCropBlockBehavior.FACTORY.create(definition, ConfigSection.ofRoot(Map.of("age-property", "ripeness")));
        assertSame(ripeness, VillagerCrop.ageProperty(definition));
    }

    @Test void doubleCropBonemealAuthorizesTheNewUpperHalfBeforeChangingEitherCell() throws Exception {
        Property<Integer> age = IntegerProperty.create("age", 0, 3, 0);
        Property<DoubleBlockHalf> half = EnumProperty.create("half", DoubleBlockHalf.class,
                List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER), DoubleBlockHalf.LOWER);
        BlockDefinition definition = statefulDefinition("test:double_crop", Map.of("age", age, "half", half));
        ManagedCropBlockBehavior.factory(ManagedCropBlockBehavior.Shape.DOUBLE).create(definition,
                ConfigSection.ofRoot(Map.of("soils", List.of(Map.of("block", "test:soil")),
                        "can_harvest_by_villagers", true, "can_replant_by_villagers", true)));
        var access = new MemoryAccess(); Cell lower = new Cell("lower"), upper = new Cell("air");
        lower.neighbors.put(BlockFace.UP, upper.block);
        ImmutableBlockState before = definition.defaultState().with(age, 2);
        access.states.put(lower.block, before);
        VillagerCrop crop = new VillagerCrop("test:double_crop", "test:seed", definition, definition,
                null, VillagerCrop.Mode.RESET, Set.of("test:double_crop"), null, false, true, true, access);
        AtomicInteger events = new AtomicInteger();

        assertFalse(crop.bonemeal(lower.block, null, event -> { events.incrementAndGet(); event.setCancelled(true); }));

        assertEquals(1, events.get()); assertSame(before, access.states.get(lower.block));
        assertNull(access.states.get(upper.block)); assertEquals(0, lower.writes + upper.writes);
    }

    @Test void waterCropPlantsOnlyInOneSourceCellOnAnAllowedSoil() {
        var access = new MemoryAccess();
        Cell water = new Cell("water"), soil = new Cell("dirt"), above = new Cell("air");
        water.neighbors.put(BlockFace.DOWN, soil.block); water.neighbors.put(BlockFace.UP, above.block);
        water.overrideType = Material.WATER; soil.overrideType = Material.DIRT;
        water.overrideData = waterData(0);
        VillagerCrop crop = new VillagerCrop("test:rice", "test:seed", null, null, null,
                VillagerCrop.Mode.TALL, Set.of(), new com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules(
                        Set.of(Material.DIRT), Set.of(), Set.of(), List.of(), Set.of()),
                true, true, true, access);
        assertTrue(crop.canPlantAt(water.block));
        water.overrideData = waterData(1);
        assertFalse(crop.canPlantAt(water.block));
        water.overrideData = waterData(0); above.overrideType = Material.WATER;
        assertFalse(crop.canPlantAt(water.block));
        above.overrideType = Material.AIR; access.unavailable.add(soil.block);
        assertFalse(crop.canPlantAt(water.block));
    }

    @Test void extraSoilCannotOverrideTheNativeBushSupportPredicate() throws Exception {
        Property<Integer> age = IntegerProperty.create("age", 0, 3, 0);
        BlockDefinition definition = statefulDefinition("test:restricted_crop", Map.of("age", age));
        BushBlockBehavior bush = new BushBlockBehavior(definition, 0, false, false, 1, List.of(),
                net.momirealms.craftengine.core.util.LazyReference.oneTime(Set::of)) { };
        for (ImmutableBlockState state : definition.variantProvider().states()) state.setBehavior(bush);
        var access = new MemoryAccess(); Cell target = new Cell("air"), dirt = new Cell("dirt");
        target.neighbors.put(BlockFace.DOWN, dirt.block); dirt.overrideType = Material.DIRT;
        VillagerCrop crop = new VillagerCrop("test:restricted_crop", "test:seed", definition, definition, null,
                VillagerCrop.Mode.BREAK, Set.of("test:restricted_crop"), new com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules(
                Set.of(Material.DIRT), Set.of(), Set.of(), List.of(), Set.of()), false, true, true, access);
        access.bushPermitted = false;
        assertFalse(crop.canPlantAt(target.block));
        assertEquals(1, access.bushChecks);
        access.bushPermitted = true;
        assertTrue(crop.canPlantAt(target.block));
    }

    private static VillagerCrop crop(MemoryAccess access) {
        return new VillagerCrop("test:crop", "test:seed", null, null, Material.WHEAT,
                VillagerCrop.Mode.BREAK, Set.of(), null, false, true, true, access);
    }
    private static VillagerCrop.Change change(Cell cell, String next) {
        return new VillagerCrop.Change(cell.block, null, data(cell.value), null, data(next));
    }
    private static VillagerCrop.Harvest plan(VillagerCrop crop, Cell block, List<VillagerCrop.Change> changes) {
        return new VillagerCrop.Harvest(crop, block.block, null, null, changes, List.of(), null, null, null);
    }

    private static BlockDefinition definition(String name, Map<String, Property<?>> properties) {
        return (BlockDefinition) Proxy.newProxyInstance(BlockDefinition.class.getClassLoader(), new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> Key.of(name);
                    case "getProperty" -> properties.get(args[0]);
                    case "hasProperty" -> properties.containsKey(args[0]);
                    case "properties" -> properties.values();
                    case "defaultState" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
    private static ImmutableBlockState state(BlockDefinition definition, Map<Property<?>, Comparable<?>> entries) throws Exception {
        var owner = new Holder.Reference<BlockDefinition>(null, ResourceKey.create(Key.of("craftengine:block"), definition.id()), definition);
        var constructor = ImmutableBlockState.class.getDeclaredConstructor(Holder.Reference.class, BlockStateVariantProvider.class, Reference2ObjectArrayMap.class);
        constructor.setAccessible(true);
        var values = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>(); values.putAll(entries);
        return constructor.newInstance(owner, null, values);
    }

    private static BlockDefinition statefulDefinition(String name, Map<String, Property<?>> properties) throws Exception {
        AtomicReference<BlockStateVariantProvider> variants = new AtomicReference<>();
        BlockDefinition block = (BlockDefinition) Proxy.newProxyInstance(BlockDefinition.class.getClassLoader(), new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> Key.of(name);
                    case "getProperty" -> properties.get(args[0]);
                    case "hasProperty" -> properties.containsKey(args[0]);
                    case "properties" -> properties.values();
                    case "defaultState" -> variants.get().getDefaultState();
                    case "variantProvider" -> variants.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var constructor = ImmutableBlockState.class.getDeclaredConstructor(Holder.Reference.class, BlockStateVariantProvider.class, Reference2ObjectArrayMap.class);
        constructor.setAccessible(true);
        var owner = new Holder.Reference<BlockDefinition>(null, ResourceKey.create(Key.of("craftengine:block"), block.id()), block);
        variants.set(new BlockStateVariantProvider(owner, (reference, provider, values) -> {
            try { return constructor.newInstance(reference, provider, values); }
            catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }, properties));
        return block;
    }

    private static Levelled waterData(int level) {
        return (Levelled) Proxy.newProxyInstance(Levelled.class.getClassLoader(), new Class<?>[]{Levelled.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLevel" -> level;
                    case "getMaterial" -> Material.WATER;
                    case "clone" -> waterData(level);
                    default -> null;
                });
    }

    private static BlockData data(String value) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "clone" -> data(value);
                    case "getAsString", "toString" -> value;
                    case "getMaterial" -> value.equals("air") ? Material.AIR : Material.WHEAT;
                    case "equals" -> args[0] instanceof BlockData data && value.equals(data.getAsString());
                    case "hashCode" -> value.hashCode();
                    default -> null;
                });
    }
    private static final class Cell {
        private static final AtomicInteger POSITIONS = new AtomicInteger();
        String value, replaceOnWrite; int writes;
        Runnable onWrite;
        final int y = POSITIONS.incrementAndGet();
        Material overrideType;
        BlockData overrideData;
        final Map<BlockFace, Block> neighbors = new java.util.EnumMap<>(BlockFace.class);
        final Block block;
        Cell(String value) {
            this.value = value;
            block = (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[]{Block.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBlockData" -> overrideData == null ? data(this.value) : overrideData;
                        case "setBlockData" -> {
                            writes++; this.value = replaceOnWrite == null ? ((BlockData) args[0]).getAsString() : replaceOnWrite;
                            if (onWrite != null) onWrite.run(); yield null;
                        }
                        case "getX", "getZ" -> 0;
                        case "getY" -> y;
                        case "getType" -> overrideType == null ? this.value.equals("air") ? Material.AIR : Material.WHEAT : overrideType;
                        case "getRelative" -> neighbors.get(args[0]);
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        default -> null;
                    });
        }
    }
    private static final class MemoryAccess implements VillagerCrop.Access {
        final Set<Block> unavailable = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Block, ImmutableBlockState> states = new IdentityHashMap<>();
        java.util.function.BiConsumer<Block, ImmutableBlockState> removal;
        boolean bushPermitted; int bushChecks;
        int drops;
        public boolean resident(Block block) { return !unavailable.contains(block); }
        public ImmutableBlockState state(Block block) { return states.get(block); }
        public BlockData data(ImmutableBlockState state) { return VillagerCropTest.data(state.getPropertiesAsString()); }
        public BlockData air() { return VillagerCropTest.data("air"); }
        public boolean place(Block block, ImmutableBlockState state) {
            states.put(block, state); block.setBlockData(data(state), false); return true;
        }
        public boolean remove(Block block) {
            ImmutableBlockState previous = states.remove(block); block.setBlockData(air(), false);
            if (removal != null) removal.accept(block, previous); return true;
        }
        public void drop(Block block, List<ItemStack> items) { drops++; }
        public boolean bushSurvives(Block target, ImmutableBlockState state, BushBlockBehavior bush) {
            bushChecks++; return bushPermitted;
        }
    }
}
