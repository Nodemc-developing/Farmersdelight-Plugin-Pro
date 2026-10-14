package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.BushBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import net.momirealms.craftengine.core.block.behavior.RandomTickBlock;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class TomatoVineBlockBehavior extends FarmersDelightBlockBehavior implements RandomTickBlock, BonemealableBlock {

    @Override
    public boolean canRandomlyTick(ImmutableBlockState state) {
        return true;
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return true;
    }

    @Override
    public boolean isValidBonemealTarget(Object thisBlock, Object[] args) {
        // Always advertise as a valid target so vanilla doesn't reject the bonemeal use early — the
        // sibling crop_block behavior controls the real "is at max age" gate, and our climb attempt
        // has its own guards inside tryClimb (rope present, column height, light).
        return true;
    }

    @Override
    public boolean isBonemealSuccess(Object thisBlock, Object[] args) {
        // Lets the dispatch reach performBonemeal regardless of tryClimb's eventual outcome — if no
        // rope is above, tryClimb is a no-op and the crop_block sibling still bumps the age. Returning
        // false here would only matter for vanilla particles, which CE drives off this method.
        return true;
    }

    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null || !(context.getPlayer().platformPlayer() instanceof org.bukkit.entity.Player player)) return InteractionResult.PASS;
        var item = ItemUtils.getItemInHand(player, context.getHand());
        if (item == null || item.getType() != Material.BONE_MEAL) return InteractionResult.PASS;
        CropBoneMealUse use = CropBoneMealUse.capture(plugin, context);
        if (use == null) return InteractionResult.FAIL;
        BlockPos pos = context.getClickedPos();
        Block target = player.getWorld().getBlockAt(pos.x(), pos.y(), pos.z());
        if (!applyBonemeal(target, state, use)) return InteractionResult.FAIL;
        use.consume();
        refreshColumn(target);
        playBonemealEffect(player.getWorld(), pos.x(), pos.y(), pos.z());
        ItemUtils.swingHand(player, context.getHand());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    @Override public void performBonemeal(Object block, Object[] args) {
        if (args == null || args.length < 4) return;
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[3]).orElse(null);
        if (world == null || pos == null || state == null) return;
        Block target = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (applyBonemeal(target, state, null)) refreshColumn(target);
    }

    private boolean applyBonemeal(Block target, ImmutableBlockState state, CropBoneMealUse use) {
        if (!resident(target) || CraftEngineBlocks.getCustomBlockState(target) != state) return false;
        int range = Math.max(1, config.bonemealBonusMax() - config.bonemealBonusMin() + 1);
        int bonus = config.bonemealBonusMin() + ThreadLocalRandom.current().nextInt(range);
        Map<Block, ImmutableBlockState> changes = new java.util.LinkedHashMap<>();
        Key id = state.owner().value().id();
        Property<Integer> age = BlockBehaviorFactory.getOptionalProperty(state.owner().value(), AGE_PROPERTY, Integer.class);
        if (age == null || bonus < 1) return false;
        int current = state.get(age);
        if (id.equals(config.buddingBlockId())) {
            int next = CropBehaviorOptions.incrementAge(current, bonus, Integer.MAX_VALUE);
            if (next <= config.buddingMaxAge()) changes.put(target, state.with(age, next));
            else {
                BlockDefinition tomatoes = CraftEngineBlocks.byId(config.tomatoesBlockId());
                if (tomatoes == null) return false;
                Property<Integer> newAge = BlockBehaviorFactory.getOptionalProperty(tomatoes, AGE_PROPERTY, Integer.class);
                if (newAge == null) return false;
                changes.put(target, tomatoes.defaultState().with(newAge,
                        Math.min(config.tomatoesMaxAge(), next - config.buddingMaxAge() - 1)));
            }
        } else if (id.equals(config.tomatoesBlockId()) || id.equals(config.cropOnRopeBlockId())) {
            int maximum = id.equals(config.tomatoesBlockId()) ? config.tomatoesMaxAge() : config.hangingMaxAge();
            int next = CropBehaviorOptions.incrementAge(current, bonus, maximum);
            if (next != current) {
                ImmutableBlockState updated = state.with(age, next);
                changes.put(target, updated);
                if (current + (long) bonus > maximum || ThreadLocalRandom.current().nextFloat() < config.bonemealClimbChance())
                    proposeClimb(target, updated, changes);
            } else {
                Block cursor = target;
                ImmutableBlockState cursorState = state;
                for (int height = 0; height < effectiveMaxStackHeight(); height++) {
                    Block above = cursor.getRelative(org.bukkit.block.BlockFace.UP);
                    if (!resident(above)) return false;
                    ImmutableBlockState value = CraftEngineBlocks.getCustomBlockState(above);
                    if (value == null || value.isEmpty() || !config.cropOnRopeBlockId().equals(value.owner().value().id())) {
                        proposeClimb(cursor, cursorState, changes);
                        break;
                    }
                    Property<Integer> aboveAge = BlockBehaviorFactory.getOptionalProperty(value.owner().value(), AGE_PROPERTY, Integer.class);
                    if (aboveAge == null) return false;
                    if (value.get(aboveAge) < config.hangingMaxAge()) {
                        changes.put(above, value.with(aboveAge, CropBehaviorOptions.incrementAge(value.get(aboveAge), bonus, config.hangingMaxAge())));
                        break;
                    }
                    cursor = above;
                    cursorState = value;
                }
            }
        } else return false;
        boolean committed = CropGrowthCommit.apply(plugin, changes, use == null ? null : use.player(),
                use == null ? null : target, use == null ? () -> true : use);
        return committed;
    }

    private void refreshColumn(Block target) {
        for (int height = 0; height <= effectiveMaxStackHeight(); height++) {
            Block cell = target.getRelative(org.bukkit.block.BlockFace.UP, height);
            if (!resident(cell)) return;
            RopeBlockBehavior.refreshAdjacentRopes(cell.getWorld(), new BlockPos(cell.getX(), cell.getY(), cell.getZ()));
        }
    }

    private void proposeClimb(Block target, ImmutableBlockState state, Map<Block, ImmutableBlockState> changes) {
        if (!resident(target) || CropGrowthCommit.brightness(target) < config.minLight()) return;
        Property<Integer> age = BlockBehaviorFactory.getOptionalProperty(state.owner().value(), AGE_PROPERTY, Integer.class);
        if (age == null || state.get(age) < config.matureAge()) return;
        if (countSameIdHeightBelow(target.getWorld(), new BlockPos(target.getX(), target.getY(), target.getZ()), state.owner().value().id()) >= effectiveMaxStackHeight()) return;
        Block above = target.getRelative(org.bukkit.block.BlockFace.UP);
        if (!resident(above) || !CustomBlockUtils.hasBehavior(above, RopeBlockBehavior.class)) return;
        BlockDefinition hanging = CraftEngineBlocks.byId(config.cropOnRopeBlockId());
        if (hanging == null) return;
        Property<Integer> hangingAge = BlockBehaviorFactory.getOptionalProperty(hanging, AGE_PROPERTY, Integer.class);
        if (hangingAge != null) changes.put(above, hanging.defaultState().with(hangingAge, 0));
    }

    public static final String AGE_PROPERTY = "age";

    private static final Map<Key, TomatoVineBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    public record Config(
            Key buddingBlockId,
            Key tomatoesBlockId,
            Key cropOnRopeBlockId,
            Key ropeBlockId,
            int matureAge,
            int minLight,
            int maxStackHeight,
            int buddingMaxAge,
            int tomatoesMaxAge,
            int hangingMaxAge,
            float bonemealClimbChance,
            int bonemealBonusMin,
            int bonemealBonusMax
    ) {}

    private final FarmersDelightPlugin plugin;
    private final Config config;
    // Lazily resolved from the hanging block's sibling bush_block.max-height so the climb cap and the
    // survival cap share ONE authority (the CE bush_block config). -1 = not yet resolved. Recomputed
    // per behavior instance, and instances are rebuilt on /ce reload, so config edits take effect
    // without a stale cache. A benign race just recomputes the same value; volatile guards visibility.
    private volatile int resolvedMaxStackHeight = -1;

    private TomatoVineBlockBehavior(FarmersDelightPlugin plugin, BlockDefinition block, Config config) {
        super(block);
        this.plugin = plugin;
        this.config = config;
    }

    public static final BlockBehaviorFactory<TomatoVineBlockBehavior> FACTORY = factory(null);

    public static BlockBehaviorFactory<TomatoVineBlockBehavior> factory(FarmersDelightPlugin plugin) {
        return (BlockDefinition block, ConfigSection section) -> {
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        // Every stage of the vine is driven by the age of the block the behavior sits on: budding
        // transitions at max age, the ground and hanging blocks gate climbing and harvesting on it.
        // A block that declares this behavior without an int age property aborts its own load here,
        // naming the property, instead of loading a vine that never grows, never climbs and never
        // reports itself harvest-ready. Validation only: the behavior is shared by three block ids
        // and reads whichever block actually occupies a position, so it resolves the age property
        // per state at runtime rather than holding this one handle.
        String path = section != null ? section.path() : Constants.BEHAVIOR_TOMATO_VINE;
        BlockBehaviorFactory.getProperty(path, block, AGE_PROPERTY, Integer.class);
        // Read grouped and flat settings, with grouped values taking precedence.
        // Unknown keys and conflicts produce warnings; invalid value types abort parsing.
        // Use max-stack-height only when the hanging block has no bush_block behavior supplying its height cap.
        TomatoVineSettings settings = TomatoVineSettings.parse(arguments, block.id().toString());
        for (TomatoVineSettings.Warning warning : settings.warnings()) {
            I18n.logWarning(warning.key(), warning.arguments());
        }
        TomatoVineBlockBehavior behavior = new TomatoVineBlockBehavior(plugin, block, new Config(
                Key.of(settings.buddingBlock()), Key.of(settings.tomatoesBlock()),
                Key.of(settings.cropOnRopeBlock()), Key.of(settings.ropeBlock()),
                settings.matureAge(), settings.minLight(), settings.maxStackHeight(),
                settings.buddingMaxAge(), settings.tomatoesMaxAge(), settings.hangingMaxAge(),
                settings.bonemealClimbChance(), settings.bonemealBonusMin(), settings.bonemealBonusMax()
        ));
        BEHAVIORS.put(block.id(), behavior);
        return behavior;
        };
    }

    public static TomatoVineBlockBehavior getBehavior(Key blockId) {
        return blockId == null ? null : BEHAVIORS.get(blockId);
    }

    /** Immutable settings used to prepare villager growth without invoking native handles. */
    public Config villagerSettings() { return config; }
    public int villagerMaximumHeight() { return effectiveMaxStackHeight(); }

    public static void warmAll() {
        for (TomatoVineBlockBehavior behavior : BEHAVIORS.values()) {
            behavior.effectiveMaxStackHeight();
        }
    }

    public boolean isHarvestReady(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return false;
        Key id = state.owner().value().id();
        int max;
        if (id.equals(config.tomatoesBlockId())) {
            max = config.tomatoesMaxAge();
        } else if (id.equals(config.cropOnRopeBlockId())) {
            max = config.hangingMaxAge();
        } else {
            return false;
        }
        Property<Integer> ageProp = BlockBehaviorFactory.getOptionalProperty(
                state.owner().value(), AGE_PROPERTY, Integer.class);
        if (ageProp == null) return false;
        Integer age = state.get(ageProp);
        return age != null && age >= max;
    }

    @Override
    public void randomTick(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!resident(atPos)) return;
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return;
        Key currentBlockId = atState.owner().value().id();
        if (currentBlockId.equals(config.buddingBlockId())) {
            tryGrowPastMaxAge(world, pos, atState, atPos);
        } else {
            tryClimb(world, pos);
        }
    }

    private void tryGrowPastMaxAge(World world, BlockPos pos, ImmutableBlockState state, Block atPos) {
        if (CropGrowthCommit.brightness(atPos) < config.minLight()) return;
        Property<Integer> ageProp = BlockBehaviorFactory.getOptionalProperty(
                state.owner().value(), AGE_PROPERTY, Integer.class);
        if (ageProp == null) return;
        Integer age = state.get(ageProp);
        if (age == null || age < config.buddingMaxAge()) return;
        BlockDefinition tomatoes = CraftEngineBlocks.byId(config.tomatoesBlockId());
        if (tomatoes == null) return;
        ImmutableBlockState newState = tomatoes.defaultState();
        Property<Integer> tomatoesAge = BlockBehaviorFactory.getOptionalProperty(
                tomatoes, AGE_PROPERTY, Integer.class);
        if (tomatoesAge != null) {
            newState = newState.with(tomatoesAge, 0);
        }
        Location loc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        CropGrowthCommit.apply(plugin, world.getBlockAt(pos.x(), pos.y(), pos.z()), newState);
    }

    public void tryClimb(World world, BlockPos pos) {
        if (world == null || pos == null) return;
        Block atPos = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!resident(atPos)) return;
        ImmutableBlockState atState = CraftEngineBlocks.getCustomBlockState(atPos);
        if (atState == null || atState.isEmpty()) return;
        Key currentBlockId = atState.owner().value().id();
        if (config.matureAge() > 0) {
            Property<Integer> ageProperty = BlockBehaviorFactory.getOptionalProperty(
                    atState.owner().value(), AGE_PROPERTY, Integer.class);
            if (ageProperty != null) {
                Integer currentAge = atState.get(ageProperty);
                if (currentAge != null && currentAge < config.matureAge()) return;
            }
        }
        if (CropGrowthCommit.brightness(atPos) < config.minLight()) return;

        int height = countSameIdHeightBelow(world, pos, currentBlockId);
        if (height >= effectiveMaxStackHeight()) return;

        Block aboveTop = world.getBlockAt(pos.x(), pos.y() + 1, pos.z());
        if (!resident(aboveTop)) return;
        if (!CustomBlockUtils.hasBehavior(aboveTop, RopeBlockBehavior.class)) return;

        BlockDefinition cropOnRope = CraftEngineBlocks.byId(config.cropOnRopeBlockId());
        if (cropOnRope == null) return;
        ImmutableBlockState newState = cropOnRope.defaultState();
        Property<Integer> targetAge = BlockBehaviorFactory.getOptionalProperty(
                cropOnRope, AGE_PROPERTY, Integer.class);
        if (targetAge != null) {
            newState = newState.with(targetAge, 0);
        }

        Location placeLoc = new Location(world,
                aboveTop.getX() + 0.5, aboveTop.getY(), aboveTop.getZ() + 0.5);
        boolean placed = CropGrowthCommit.apply(plugin, aboveTop, newState);
        if (placed) {
            RopeBlockBehavior.refreshAdjacentRopes(world,
                    new BlockPos(aboveTop.getX(), aboveTop.getY(), aboveTop.getZ()));
        }
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        if (args.length < 3) return;
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (state == null || state.isEmpty()) return;
        Key removedId = state.owner().value().id();
        if (!removedId.equals(config.cropOnRopeBlockId())) return;

        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;

        BlockDefinition ropeBlock = CraftEngineBlocks.byId(config.ropeBlockId());
        if (ropeBlock == null) return;

        Location placeLoc = new Location(world, pos.x() + 0.5, pos.y(), pos.z() + 0.5);
        Runnable placeRope = () -> {
            if (!resident(world.getBlockAt(pos.x(), pos.y(), pos.z()))) return;
            ImmutableBlockState connected = RopeBlockBehavior.computeConnectionState(
                    ropeBlock.defaultState(), world, pos);
            CraftEngineBlocks.place(placeLoc, connected, true);
            RopeBlockBehavior.refreshAdjacentRopes(world, pos);
        };
        if (plugin != null) {
            plugin.scheduler().runAt(placeLoc, placeRope);
        } else {
            placeRope.run();
        }
    }

    /*
     * Effective climb cap = the hanging block's own bush_block max-height. Reading it from the
     * sibling CE behavior means the range is configured in ONE place (the bush_block YAML) and the
     * climb pre-check can never drift from the survival check — no more "grow a doomed block, then
     * bush_block removes it next tick" flicker. Falls back to the max-stack-height behavior arg if
     * the hanging block has no bush_block (e.g. someone stripped it from the config).
     * The counting in countSameIdHeightBelow is provably equivalent to CE BushBlockBehavior.mayStackOn
     * at the same cap value (for cap >= 2): both allow at most `cap` hanging cells on the column.
     */
    private int effectiveMaxStackHeight() {
        int cached = resolvedMaxStackHeight;
        if (cached > 0) return cached;
        int resolved = config.maxStackHeight();
        BlockDefinition cropOnRope = CraftEngineBlocks.byId(config.cropOnRopeBlockId());
        if (cropOnRope != null) {
            BushBlockBehavior bush = CustomBlockUtils.getBehavior(cropOnRope.defaultState(), BushBlockBehavior.class);
            if (bush != null && bush.maxHeight > 0) {
                resolved = bush.maxHeight;
            }
        }
        resolvedMaxStackHeight = resolved;
        return resolved;
    }

    private int countSameIdHeightBelow(World world, BlockPos pos, Key currentBlockId) {
        int count = 1;
        int y = pos.y() - 1;
        while (y >= world.getMinHeight()) {
            Block lower = world.getBlockAt(pos.x(), y, pos.z());
            if (!resident(lower)) break;
            ImmutableBlockState bs = CraftEngineBlocks.getCustomBlockState(lower);
            if (bs == null || bs.isEmpty()) break;
            if (!bs.owner().value().id().equals(currentBlockId)) break;
            count++;
            y--;
        }
        return count;
    }

    private boolean resident(Block block) { return CropGrowthCommit.resident(plugin, block); }
}
