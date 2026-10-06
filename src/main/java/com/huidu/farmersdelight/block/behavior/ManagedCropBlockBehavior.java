package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** A bounded crop state machine; random ticking never reads an unloaded or foreign region. */
public final class ManagedCropBlockBehavior extends FarmersDelightBlockBehavior implements BonemealableBlock, RandomTickBlock {
    public enum Shape { SINGLE, DOUBLE, ROPE }
    private record Soil(CropBehaviorOptions.Soil options, SoilRuleSupport.SoilRules matcher) { }
    private static final Map<Key, ManagedCropBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private final Property<Integer> age;
    private final Property<?> half;
    private final Property<Boolean> support;
    private final Shape shape;
    private final CropBehaviorOptions options;
    private final List<Soil> soils;
    private final NumberProvider bonemealBonus;
    private final FarmersDelightPlugin plugin;
    private final boolean randomTickEnabled;

    private ManagedCropBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, ConfigSection config, Shape shape) {
        super(block);
        this.plugin = plugin;
        this.shape = shape;
        randomTickEnabled = config.getBoolean(new String[]{"random-ticking", "random_ticking"}, true);
        age = BlockBehaviorFactory.getProperty(config.path(), block, "age", Integer.class);
        int max = age.possibleValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
        Map<String, Object> configured = new java.util.LinkedHashMap<>(config.values());
        if (shape == Shape.DOUBLE && CropBehaviorOptions.get(configured, "max_age") == null) configured.put("max_age", Math.min(3, max));
        options = CropBehaviorOptions.parse(configured, max);
        half = shape == Shape.DOUBLE ? block.getProperty("half") : null;
        if (shape == Shape.DOUBLE && (half == null || half.valueByName("lower") == null || half.valueByName("upper") == null))
            throw new IllegalArgumentException("double crop requires lower/upper half values");
        String supportName = config.getString(shape == Shape.ROPE ? "ropelogged_property" : "supporting", shape == Shape.ROPE ? "ropelogged" : "");
        support = supportName.isBlank() || shape == Shape.DOUBLE && !block.hasProperty(supportName) ? null
                : BlockBehaviorFactory.getProperty(config.path(), block, supportName, Boolean.class);
        if (shape == Shape.ROPE && support == null) throw new IllegalArgumentException("roped crop requires a boolean rope property");
        bonemealBonus = config.getNumber(new String[]{"bone_meal_age_bonus", "bone-meal-age-bonus"}, ConfigConstants.CONSTANT_ONE);
        soils = options.soils().stream().map(row -> {
            Map<String, Object> descriptor = row.block().startsWith("#")
                    ? Map.of("bottom-block-tags", List.of(row.block().substring(1))) : Map.of("bottom-blocks", List.of(row.block()));
            return new Soil(row, SoilRuleSupport.parseSoilRules(descriptor));
        }).toList();
        BEHAVIORS.put(block.id(), this);
    }

    public static BlockBehaviorFactory<ManagedCropBlockBehavior> factory(Shape shape) {
        return factory(null, shape);
    }
    public static BlockBehaviorFactory<ManagedCropBlockBehavior> factory(FarmersDelightPlugin plugin, Shape shape) {
        return (block, config) -> new ManagedCropBlockBehavior(plugin, block, config, shape);
    }
    public static ManagedCropBlockBehavior byId(Key id) { return BEHAVIORS.get(id); }
    public static void clear() { BEHAVIORS.clear(); }
    public CropBehaviorOptions options() { return options; }
    public Shape shape() { return shape; }
    public Property<Integer> ageProperty() { return age; }
    public Property<?> halfProperty() { return half; }
    public Property<Boolean> supportProperty() { return support; }
    public int bonemealAgeBonus() { return Math.max(0, bonemealBonus.getInt()); }
    public ImmutableBlockState plantingState() {
        ImmutableBlockState state = block().defaultState().with(age, 0);
        if (half != null) state = ImmutableBlockState.with(state, half, half.valueByName("lower"));
        return support == null ? state : state.with(support, false);
    }
    public record Harvest(ImmutableBlockState expected, ImmutableBlockState expectedPartner,
                          List<org.bukkit.inventory.ItemStack> drops) { }
    public Harvest prepareHarvest(Block crop, org.bukkit.entity.Entity collector) {
        if (!resident(crop)) return null;
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(crop);
        if (!isMature(state)) return null;
        ImmutableBlockState partner = null;
        if (shape == Shape.DOUBLE && !upper(state)) {
            Block top = crop.getRelative(BlockFace.UP);
            if (!resident(top)) return null;
            ImmutableBlockState value = CraftEngineBlocks.getCustomBlockState(top);
            if (same(value) && upper(value)) partner = value;
        }
        var world = net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(crop.getWorld());
        var position = new net.momirealms.craftengine.core.world.WorldPosition(world, crop.getX() + .5, crop.getY() + .5, crop.getZ() + .5);
        var context = net.momirealms.craftengine.core.plugin.context.ContextHolder.builder()
                .withParameter(net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters.POSITION, position)
                .withParameter(net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters.BLOCK,
                        net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(crop));
        if (collector != null) context.withOptionalParameter(net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters.THIS_ENTITY,
                net.momirealms.craftengine.bukkit.api.BukkitAdaptor.adapt(collector));
        var drops = state.getDrops(context.build(), world, null).stream()
                .map(item -> item.platformItem()).filter(org.bukkit.inventory.ItemStack.class::isInstance)
                .map(org.bukkit.inventory.ItemStack.class::cast).map(org.bukkit.inventory.ItemStack::clone).toList();
        return new Harvest(state, partner, drops);
    }
    public boolean commitHarvest(Block crop, Harvest harvest) {
        if (harvest == null || !resident(crop) || CraftEngineBlocks.getCustomBlockState(crop) != harvest.expected()) return false;
        Block partner = shape == Shape.DOUBLE && !upper(harvest.expected()) ? crop.getRelative(BlockFace.UP) : null;
        if (partner != null) {
            if (!resident(partner)) return false;
            ImmutableBlockState actual = CraftEngineBlocks.getCustomBlockState(partner);
            if (harvest.expectedPartner() != null ? actual != harvest.expectedPartner() : same(actual)) return false;
        }
        ImmutableBlockState reset = resetState(harvest.expected());
        boolean removePartner = partner != null && harvest.expectedPartner() != null;
        if (removePartner && !CraftEngineBlocks.remove(partner)) return false;
        if (!CraftEngineBlocks.place(crop.getLocation(), reset, false)) {
            CraftEngineBlocks.place(crop.getLocation(), harvest.expected(), false);
            if (removePartner) CraftEngineBlocks.place(partner.getLocation(), harvest.expectedPartner(), false);
            return false;
        }
        return true;
    }
    public boolean isMature(ImmutableBlockState state) { return same(state) && state.get(age) >= maximum(state); }
    public ImmutableBlockState resetState(ImmutableBlockState state) { return state.with(age, 0); }
    public boolean canPlantAt(Block target) { return resident(target) && soil(target.getRelative(BlockFace.DOWN)) != null && target.getLightLevel() >= options.spawnLight(); }
    private boolean same(ImmutableBlockState state) { return state != null && !state.isEmpty() && state.owner().value().id().equals(block().id()); }
    private boolean upper(ImmutableBlockState state) { return half != null && "upper".equalsIgnoreCase(String.valueOf(state.getNullable(half))); }
    private int maximum(ImmutableBlockState state) { return upper(state) ? options.upperMaximumAge() : options.maximumAge(); }
    private boolean resident(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && (plugin == null || plugin.scheduler().isOwnedByCurrentRegion(block.getLocation()));
    }
    private Soil soil(Block target) {
        if (!resident(target)) return null;
        for (Soil candidate : soils) if (SoilRuleSupport.matches(target, candidate.matcher())) return candidate;
        return null;
    }
    private Block root(Block target, ImmutableBlockState state) {
        if (upper(state)) return target.getRelative(BlockFace.DOWN);
        if (shape == Shape.ROPE && Boolean.TRUE.equals(state.get(support))) {
            Block current = target;
            for (int n = 0; n < options.ropeMaximumHeight(); ++n) {
                Block lower = current.getRelative(BlockFace.DOWN);
                if (!resident(lower)) return current;
                ImmutableBlockState value = CraftEngineBlocks.getCustomBlockState(lower);
                if (!same(value)) return current;
                current = lower;
            }
            return current;
        }
        return target;
    }
    @Override public boolean isPathFindable(Object block, Object[] arguments) { return true; }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        if (!(context.getLevel().platformWorld() instanceof World world)) return null;
        BlockPos pos = context.getClickedPos();
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!canPlantAt(target)) return null;
        if (options.water() && target.getType() != Material.WATER) return null;
        ImmutableBlockState result = state.with(age, 0);
        return half == null ? result : ImmutableBlockState.with(result, half, half.valueByName("lower"));
    }
    @Override public boolean canRandomlyTick(ImmutableBlockState state) {
        if (!randomTickEnabled || !same(state)) return false;
        if (upper(state)) return options.upperIndependent() && !isMature(state);
        // Mature lower halves and vine segments still drive growth into the cell above.
        return shape != Shape.SINGLE || !isMature(state);
    }
    @Override public void randomTick(Object nativeBlock, Object[] arguments) {
        if (!FarmersDelightPlugin.isEnabled0() || !randomTickEnabled || arguments.length < 3) return;
        World world = CraftEngineAdapter.toWorld(arguments[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(arguments[2]);
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(arguments[0]).orElse(null);
        if (world == null || pos == null || !canRandomlyTick(state)) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!resident(target) || target.getLightLevel() < options.light()) return;
        Soil row = soil(root(target, state).getRelative(BlockFace.DOWN));
        if (row == null) return;
        if (upper(state) && !options.upperIndependent()) return;
        float chance = row.options().growthModifier() < 0 ? VanillaCropGrowth.chance(vanillaSpeed(target))
                : Math.min(1, options.speed() * row.options().growthModifier());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (row.options().bonemealChance() > 0 && random.nextFloat() < row.options().bonemealChance())
            grow(target, state, Math.max(1, bonemealBonus.getInt()));
        else if (random.nextFloat() < chance) grow(target, state, 1);
    }
    private float vanillaSpeed(Block target) {
        float[] factors = new float[9];
        boolean horizontal = false, vertical = false, diagonal = false;
        for (int index = 0; index < 9; ++index) {
            int dx = index / 3 - 1, dz = index % 3 - 1;
            Block below = target.getWorld().getBlockAt(target.getX() + dx, target.getY() - 1, target.getZ() + dz);
            if (soil(below) != null) factors[index] = below.getBlockData() instanceof Farmland farm && farm.getMoisture() > 0 ? 3 : 1;
            if (dx == 0 && dz == 0) continue;
            Block neighbor = below.getRelative(BlockFace.UP);
            if (!resident(neighbor) || !same(CraftEngineBlocks.getCustomBlockState(neighbor))) continue;
            if (dx == 0) vertical = true;
            else if (dz == 0) horizontal = true;
            else diagonal = true;
        }
        return VanillaCropGrowth.growthSpeed(factors, horizontal, vertical, diagonal);
    }
    private boolean grow(Block target, ImmutableBlockState current, int bonus) {
        if (!resident(target) || !same(current)) return false;
        int before = current.get(age), next = CropBehaviorOptions.incrementAge(before, bonus, maximum(current));
        boolean changed = next != before;
        ImmutableBlockState updated = current.with(age, next);
        if (changed && !CraftEngineBlocks.place(target.getLocation(), updated, false)) return false;
        if (shape == Shape.DOUBLE && options.synchronizeAges()) {
            Block other = target.getRelative(upper(current) ? BlockFace.DOWN : BlockFace.UP);
            if (resident(other)) {
                ImmutableBlockState otherState = CraftEngineBlocks.getCustomBlockState(other);
                if (same(otherState)) CraftEngineBlocks.place(other.getLocation(), otherState.with(age,
                        Math.min(maximum(otherState), next)), false);
            }
        }
        if (shape == Shape.DOUBLE && !upper(current) && next >= options.maximumAge()) {
            Block top = target.getRelative(BlockFace.UP);
            if (!resident(top)) return changed;
            ImmutableBlockState topState = CraftEngineBlocks.getCustomBlockState(top);
            if (top.getType().isAir()) {
                int initial = Math.min(options.upperMaximumAge(), Math.max(0, options.upperMinimumAge()));
                topState = ImmutableBlockState.with(updated.with(age, initial), half, half.valueByName("upper"));
                boolean added = CraftEngineBlocks.place(top.getLocation(), topState, false);
                changed |= added;
                if (added && support != null) CraftEngineBlocks.place(target.getLocation(), updated.with(support, true), false);
            } else if (same(topState) && !options.synchronizeAges() && !options.upperIndependent()) {
                changed |= grow(top, topState, bonus);
            }
        } else if (shape == Shape.ROPE && next >= options.ropeMinimumAge()) {
            Block top = target.getRelative(BlockFace.UP);
            Block bottom = root(target, current);
            if (resident(top) && target.getY() - bottom.getY() + 1 < options.ropeMaximumHeight()) {
                ImmutableBlockState rope = CraftEngineBlocks.getCustomBlockState(top);
                if (rope != null && !rope.isEmpty() && options.ropeBlock().equals(rope.owner().value().id().toString())) {
                    changed |= CraftEngineBlocks.place(top.getLocation(), block().defaultState().with(age,
                            Math.min(options.maximumAge(), Math.max(0, options.ropeAge()))).with(support, true), false);
                }
            }
        }
        return changed;
    }
    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null || !(context.getPlayer().platformPlayer() instanceof org.bukkit.entity.Player player)) return InteractionResult.PASS;
        var held = ItemUtils.getItemInHand(player, context.getHand());
        if (held == null || held.getType() != Material.BONE_MEAL || !options.bonemeal()) return InteractionResult.PASS;
        BlockPos pos = context.getClickedPos();
        Block target = player.getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
        if (!ProtectionCompat.canBuild(player, target, (String) null)) return InteractionResult.FAIL;
        if (!grow(target, state, bonemealBonus.getInt())) return InteractionResult.PASS;
        if (player.getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
        playBonemealEffect(player.getWorld(), pos.x(), pos.y(), pos.z());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
    @Override public boolean isValidBonemealTarget(Object nativeBlock, Object[] args) {
        if (!options.bonemeal() || args.length < 3) return false;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[2]).orElse(null);
        if (!same(state)) return false;
        if (!isMature(state)) return true;
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (world == null || pos == null || shape == Shape.SINGLE || upper(state)) return false;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!resident(target)) return false;
        Block next = target.getRelative(BlockFace.UP);
        if (!resident(next)) return false;
        if (shape == Shape.DOUBLE) {
            if (next.getType().isAir()) return true;
            ImmutableBlockState topState = CraftEngineBlocks.getCustomBlockState(next);
            return !options.synchronizeAges() && !options.upperIndependent() && same(topState) && !isMature(topState);
        }
        ImmutableBlockState rope = CraftEngineBlocks.getCustomBlockState(next);
        return target.getY() - root(target, state).getY() + 1 < options.ropeMaximumHeight()
                && rope != null && !rope.isEmpty() && options.ropeBlock().equals(rope.owner().value().id().toString());
    }
    @Override public boolean isBonemealSuccess(Object nativeBlock, Object[] args) { return options.bonemeal(); }
    @Override public void performBonemeal(Object nativeBlock, Object[] args) {
        if (args.length < 4) return;
        World world = CraftEngineAdapter.toWorld(args[0]); BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[3]).orElse(null);
        if (world != null && pos != null && same(state)) grow(world.getBlockAt(pos.x(), pos.y(), pos.z()), state, bonemealBonus.getInt());
    }
    @Override public void neighborChanged(Object nativeBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]); BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!resident(target)) return;
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(target);
        if (!same(state)) return;
        Block bottom = root(target, state);
        if (!resident(bottom.getRelative(BlockFace.DOWN))) return;
        if ((upper(state) && !same(CraftEngineBlocks.getCustomBlockState(bottom))) || soil(bottom.getRelative(BlockFace.DOWN)) == null) target.breakNaturally();
    }
    @Override public Object updateShape(Object nativeBlock, Object[] args) {
        if (args.length >= 7) {
            if (options.water()) net.momirealms.craftengine.proxy.minecraft.world.level.ScheduledTickAccessProxy.INSTANCE.scheduleTick$1(
                    args[2], args[3], net.momirealms.craftengine.proxy.minecraft.world.level.material.FluidsProxy.WATER, 5);
            neighborChanged(nativeBlock, new Object[]{args[0], args[1], args[3]});
        }
        return args[0];
    }
    @Override public void affectNeighborsAfterRemoval(Object nativeBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]); BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        ImmutableBlockState old = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (world == null || pos == null || !same(old)) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (com.huidu.farmersdelight.villager.VillagerCrop.controlsRemoval(target, old)) return;
        Block partner = target.getRelative(BlockFace.UP);
        if (shape == Shape.DOUBLE && !upper(old) && resident(partner)) {
            ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
            if (same(partnerState) && upper(partnerState)) CraftEngineBlocks.remove(partner);
        }
        if (shape == Shape.DOUBLE && upper(old)) {
            Block lower = target.getRelative(BlockFace.DOWN);
            if (resident(lower)) {
                ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lower);
                if (same(lowerState)) {
                    lowerState = lowerState.with(age, Math.max(0, options.maximumAge() - 1));
                    if (support != null) lowerState = lowerState.with(support, false);
                    CraftEngineBlocks.place(lower.getLocation(), lowerState, false);
                }
            }
        }
        if (shape == Shape.ROPE && Boolean.TRUE.equals(old.get(support))) {
            BlockDefinition rope = CraftEngineBlocks.byId(Key.of(options.ropeBlock()));
            if (rope != null && plugin != null) plugin.scheduler().runLaterAt(target.getLocation(), () -> {
                if (resident(target) && target.getType().isAir()) CraftEngineBlocks.place(target.getLocation(), rope.defaultState(), false);
            }, 1);
        }
    }
}
