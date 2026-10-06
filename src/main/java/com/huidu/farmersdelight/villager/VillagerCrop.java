package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.RopeBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.BushBlockBehavior;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.plugin.context.ContextHolder;
import net.momirealms.craftengine.core.plugin.context.EventTrigger;
import net.momirealms.craftengine.core.plugin.context.PlayerOptionalContext;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Cancellable;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.WorldPosition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Portable crop operations. A complete change is authorized and rechecked before any loot is emitted. */
public final class VillagerCrop {
    private static final ThreadLocal<List<Change>> CONTROLLED_CHANGES = new ThreadLocal<>();
    enum Mode { BREAK, PICK, TALL, RESET }

    interface Access {
        boolean resident(Block block);
        ImmutableBlockState state(Block block);
        BlockData data(ImmutableBlockState state);
        BlockData air();
        boolean place(Block block, ImmutableBlockState state);
        boolean remove(Block block);
        void drop(Block block, List<ItemStack> items);
        default boolean bushSurvives(Block target, ImmutableBlockState state, BushBlockBehavior bush) {
            Object nativeState = state.customBlockState().minecraftState();
            return bush.canSurvive(BlockStateUtils.getBlockOwner(nativeState), new Object[]{nativeState,
                    BukkitAdaptor.adapt(target.getWorld()).minecraftWorld(), LocationUtils.toBlockPos(target.getX(), target.getY(), target.getZ())});
        }
        default boolean cropSurvives(Block target, ImmutableBlockState state, CropBlockBehavior crop) {
            Object nativeState = state.customBlockState().minecraftState();
            return crop.canSurvive(BlockStateUtils.getBlockOwner(nativeState), new Object[]{nativeState,
                    BukkitAdaptor.adapt(target.getWorld()).minecraftWorld(), LocationUtils.toBlockPos(target.getX(), target.getY(), target.getZ())});
        }
    }

    static Access access(FarmersDelightPlugin plugin) {
        return new Access() {
            public boolean resident(Block b) {
                return b != null && b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)
                        && plugin.scheduler().isOwnedByCurrentRegion(b.getLocation());
            }
            public ImmutableBlockState state(Block b) {
                ImmutableBlockState state = CustomBlockUtils.getStateIfResident(b);
                return state == null || state.isEmpty() ? null : state;
            }
            public BlockData data(ImmutableBlockState state) { return CraftEngineBlocks.getBukkitBlockData(state); }
            public BlockData air() { return Bukkit.createBlockData(Material.AIR); }
            public boolean place(Block b, ImmutableBlockState state) { return CraftEngineBlocks.place(b.getLocation(), state, false); }
            public boolean remove(Block b) { return CraftEngineBlocks.remove(b); }
            public void drop(Block b, List<ItemStack> items) {
                for (ItemStack item : items) if (!item.isEmpty())
                    b.getWorld().dropItemNaturally(b.getLocation().add(.5, .5, .5), item.clone());
            }
        };
    }

    private final String id;
    private final String seed;
    private final Set<String> accepted;
    private final BlockDefinition definition;
    private final BlockDefinition planting;
    private final Material vanilla;
    private final Mode mode;
    private final SoilRuleSupport.SoilRules soils;
    private final boolean water;
    private final boolean allowHarvest;
    private final boolean allowPlant;
    private final Access access;
    private final ManagedCropBlockBehavior managed;
    private final TallCropBlockBehavior tall;
    private final TomatoVineBlockBehavior tomato;
    private final CropBlockBehavior plantingCrop;
    private final BushBlockBehavior plantingBush;
    private final VillagerCropRegistry.CustomCrops bridge;
    private final Object customConfig;

    VillagerCrop(String id, String seed, BlockDefinition definition, BlockDefinition planting,
                 Material vanilla, Mode mode, Set<String> accepted, SoilRuleSupport.SoilRules soils,
                 boolean water, boolean allowHarvest, boolean allowPlant, Access access) {
        this.id = id; this.seed = seed; this.definition = definition; this.planting = planting;
        this.vanilla = vanilla; this.mode = mode; this.accepted = Set.copyOf(accepted); this.soils = soils;
        this.water = water; this.allowHarvest = allowHarvest; this.allowPlant = allowPlant; this.access = access;
        managed = definition == null ? null : ManagedCropBlockBehavior.byId(definition.id());
        tall = definition == null ? null : TallCropBlockBehavior.getBehavior(definition.id());
        tomato = definition == null ? null : TomatoVineBlockBehavior.getBehavior(definition.id());
        plantingCrop = planting == null ? null : CustomBlockUtils.getBehavior(planting.defaultState(), CropBlockBehavior.class);
        plantingBush = planting == null ? null : CustomBlockUtils.getBehavior(planting.defaultState(), BushBlockBehavior.class);
        bridge = null; customConfig = null;
    }

    VillagerCrop(String id, String seed, VillagerCropRegistry.CustomCrops bridge, Object config, Access access) {
        this.id = id; this.seed = seed; this.bridge = bridge; this.customConfig = config; this.access = access;
        accepted = Set.of(); definition = planting = null; vanilla = null; mode = Mode.BREAK;
        soils = null; water = false; allowHarvest = allowPlant = true; managed = null; tall = null; tomato = null;
        plantingCrop = null; plantingBush = null;
    }

    public String id() { return id; }
    public String seed() { return seed; }
    Set<String> acceptedIds() { return accepted; }
    public boolean isVanilla() { return vanilla != null; }
    boolean isCustomCrops() { return bridge != null; }

    public boolean accepts(ImmutableBlockState state) {
        String value = CustomBlockUtils.getId(state);
        return value != null && accepted.contains(value);
    }

    public boolean isMature(ImmutableBlockState state) {
        if (!allowHarvest || !accepts(state)) return false;
        if (mode == Mode.TALL && !isUpper(state)) return false;
        if (managed != null) return managed.isMature(state);
        if (tomato != null) return tomato.isHarvestReady(state);
        if (tall != null) {
            Property<Integer> age = tallAge(state);
            return age != null && isUpper(state) && state.get(age) >= tall.getMaxAgeUpper();
        }
        Property<Integer> age = ageProperty(state.owner().value());
        return age != null && (mode != Mode.TALL || upper(state)) && state.get(age) >= maximum(age);
    }

    public boolean isMature(Block block) {
        if (!allowHarvest || !access.resident(block)) return false;
        if (bridge != null) {
            var state = bridge.state(block);
            return state != null && state.config() == customConfig && state.point() >= state.maximum();
        }
        if (vanilla != null) return block.getType() == vanilla && access.state(block) == null
                && block.getBlockData() instanceof Ageable age && age.getAge() >= age.getMaximumAge();
        return isMature(access.state(block));
    }

    public ImmutableBlockState plantingState() {
        if (planting == null || !allowPlant) return null;
        if (managed != null && planting == definition) return managed.plantingState();
        ImmutableBlockState state = planting.defaultState();
        Property<Integer> age = ageProperty(planting);
        if (age != null) state = state.with(age, minimum(age));
        if (tall != null && planting == definition) {
            var cfg = tall.villagerSettings();
            state = ImmutableBlockState.with(state, cfg.halfProperty(), cfg.halfLowerValue());
            return cfg.supportingProperty() == null ? state : state.with(cfg.supportingProperty(), false);
        }
        state = withNamed(state, "half", "lower");
        return withNamed(state, "supporting", "false");
    }

    public boolean canPlantAt(Block target) {
        if (!allowPlant || !access.resident(target)) return false;
        if (bridge != null) return bridge.canPlant(target, customConfig);
        if (access.state(target) != null) return false;
        Block below = target.getRelative(BlockFace.DOWN);
        if (!access.resident(below)) return false;
        if (water) {
            Block above = target.getRelative(BlockFace.UP);
            if (!access.resident(above) || !RiceCropRules.isSourceWater(target) || above.getType() == Material.WATER) return false;
        } else if (!isAir(target.getType())) return false;
        if (managed != null && !managed.canPlantAt(target)) return false;
        if (tall != null && !RiceCropRules.isValidSoil(below, definition.id())) return false;
        if (plantingCrop != null && !access.cropSurvives(target, plantingState(), plantingCrop)) return false;
        if (vanilla != null && (below.getType() != Material.FARMLAND
                || target.getLightLevel() < 8 && target.getLightFromSky() < 15)) return false;
        if (plantingBush != null) {
            // CE's bush predicate reads only its supporting column; guard its complete bounded stack first.
            int depth = plantingBush.stackable && plantingBush.maxHeight > 1 ? plantingBush.maxHeight : 1;
            if (depth > 256) return false;
            for (int n = 1; n <= depth; ++n) if (!access.resident(target.getRelative(BlockFace.DOWN, n))) return false;
            if (!access.bushSurvives(target, plantingState(), plantingBush)) return false;
        }
        if (soils != null && SoilRuleSupport.matches(below, soils)) return true;
        return managed != null;
    }

    public static final class Plant {
        private final VillagerCrop crop;
        private final Villager villager;
        private final Change change;
        private Object customToken;
        private boolean used;
        private boolean committed;
        private Plant(VillagerCrop crop, Villager villager, Change change) {
            this.crop = crop; this.villager = villager; this.change = change;
        }
    }

    public Plant preparePlant(Block target, Villager villager) {
        if (!canPlantAt(target)) return null;
        ImmutableBlockState state = plantingState();
        BlockData data;
        if (bridge != null) data = bridge.initialData(customConfig);
        else if (vanilla != null) data = Bukkit.createBlockData(vanilla);
        else data = state == null ? null : access.data(state);
        return data == null ? null : new Plant(this, villager, change(target, state, data));
    }

    public boolean plant(Block target, Villager villager, Consumer<EntityChangeBlockEvent> events) {
        return plant(preparePlant(target, villager), events);
    }

    public boolean plant(Plant plan, Consumer<EntityChangeBlockEvent> events) {
        if (plan == null || plan.crop != this || plan.used) return false;
        plan.used = true;
        if (!authorize(List.of(plan.change), plan.villager, events) || !canPlantAt(plan.change.block)) return false;
        if (bridge != null) {
            plan.customToken = bridge.plant(plan.change.block, customConfig);
            plan.committed = plan.customToken != null;
        } else plan.committed = commit(List.of(plan.change));
        return plan.committed;
    }

    /** Reverses only this successful placement while its exact initial state still owns the cell. */
    public boolean rollbackPlant(Plant plan) {
        if (plan == null || plan.crop != this || !plan.committed || !access.resident(plan.change.block)) return false;
        boolean undone;
        if (bridge != null) undone = bridge.rollback(plan.change.block, plan.customToken);
        else undone = restore(plan.change);
        if (undone) plan.committed = false;
        return undone;
    }

    public static final class Harvest {
        private final VillagerCrop crop;
        private final Block block;
        private final Villager villager;
        private final ImmutableBlockState state;
        private final List<Change> changes;
        private final List<ItemStack> drops;
        private final ContextHolder.Builder context;
        private final EventTrigger trigger;
        private final Object customToken;
        private boolean used;
        Harvest(VillagerCrop crop, Block block, Villager villager, ImmutableBlockState state,
                List<Change> changes, List<ItemStack> drops, ContextHolder.Builder context,
                EventTrigger trigger, Object customToken) {
            this.crop = crop; this.block = block; this.villager = villager; this.state = state;
            this.changes = List.copyOf(changes); this.drops = copies(drops); this.context = context;
            this.trigger = trigger; this.customToken = customToken;
        }
        public List<ItemStack> drops() { return copies(drops); }
    }

    public Harvest prepareHarvest(Block block, Villager villager) {
        if (!isMature(block)) return null;
        if (bridge != null) return new Harvest(this, block, villager, null,
                List.of(change(block, null, access.air())), List.of(), null, null, bridge.state(block));
        if (vanilla != null) return new Harvest(this, block, villager, null,
                List.of(change(block, null, access.air())), new ArrayList<>(block.getDrops()), null, null, null);
        ImmutableBlockState state = access.state(block);
        List<Change> changes = new ArrayList<>();
        EventTrigger trigger = EventTrigger.BLOCK_BREAK;
        ImmutableBlockState reset = null;
        if (mode == Mode.TALL || tall != null) {
            Block lower = block.getRelative(BlockFace.DOWN);
            if (!access.resident(lower)) return null;
            ImmutableBlockState lowerState = access.state(lower);
            if (!accepts(lowerState) || isUpper(lowerState)) return null;
            Property<Integer> age = ageProperty(lowerState.owner().value());
            int lowerMax = tall != null ? tall.getMaxAgeLower() : managed != null ? managed.options().maximumAge() : maximum(age);
            ImmutableBlockState lowerReset = withNamed(lowerState.with(age, Math.max(minimum(age), lowerMax - 1)), "half", "lower");
            lowerReset = withNamed(lowerReset, "supporting", "false");
            if (tall != null) {
                var cfg = tall.villagerSettings();
                lowerReset = ImmutableBlockState.with(lowerReset, cfg.halfProperty(), cfg.halfLowerValue());
                if (cfg.supportingProperty() != null) lowerReset = lowerReset.with(cfg.supportingProperty(), false);
            } else if (managed != null && managed.supportProperty() != null) lowerReset = lowerReset.with(managed.supportProperty(), false);
            changes.add(change(lower, lowerReset, access.data(lowerReset)));
        } else if (mode == Mode.PICK || mode == Mode.RESET) {
            Property<Integer> age = ageProperty(state.owner().value());
            reset = state.with(age, minimum(age));
            if (mode == Mode.PICK && !state.owner().value().eventFunctions(EventTrigger.RIGHT_CLICK).isEmpty()) trigger = EventTrigger.RIGHT_CLICK;
        }
        if (managed != null && managed.shape() == ManagedCropBlockBehavior.Shape.DOUBLE && mode != Mode.TALL && !isUpper(state)) {
            Block other = block.getRelative(BlockFace.UP);
            if (!access.resident(other)) return null;
            ImmutableBlockState partner = access.state(other);
            if (accepts(partner)) {
                if (!isUpper(partner)) return null;
                changes.add(change(other, null, access.air()));
            } else changes.add(change(other, partner, other.getBlockData()));
            if (reset != null && managed.supportProperty() != null) reset = reset.with(managed.supportProperty(), false);
        }
        changes.add(change(block, reset, reset == null ? access.air() : access.data(reset)));
        ContextHolder.Builder context = context(block, state, villager);
        var world = BukkitAdaptor.adapt(block.getWorld());
        List<ItemStack> drops = trigger == EventTrigger.RIGHT_CLICK ? List.of()
                : state.getDrops(context.build(), world, null).stream().map(item -> item.platformItem())
                .filter(ItemStack.class::isInstance).map(ItemStack.class::cast).map(ItemStack::clone).toList();
        return new Harvest(this, block, villager, state, changes, drops, context, trigger, null);
    }

    /** Owns both the committed world change and all loot; callers must not spawn the plan's drops again. */
    public boolean harvest(Harvest plan, Consumer<EntityChangeBlockEvent> events) {
        if (plan == null || plan.crop != this || plan.used) return false;
        plan.used = true;
        if (!authorize(plan.changes, plan.villager, events)) return false;
        if (bridge != null) return bridge.harvest(plan.block, plan.customToken);
        if (!commit(plan.changes)) return false;
        access.drop(plan.block, plan.drops);
        if (plan.context != null) {
            // Supplying the original CUSTOM_BLOCK_STATE preserves ripe loot conditions after the mutation.
            var context = PlayerOptionalContext.of(null, plan.context);
            for (var function : plan.state.owner().value().eventFunctions(plan.trigger)) function.run(context);
        }
        return true;
    }

    public boolean bonemeal(Block block, Villager villager, Consumer<EntityChangeBlockEvent> events) {
        if (!access.resident(block) || bridge != null) return false;
        List<Change> changes = new ArrayList<>();
        if (vanilla != null) {
            if (block.getType() != vanilla || access.state(block) != null || !(block.getBlockData() instanceof Ageable age)
                    || age.getAge() >= age.getMaximumAge()) return false;
            Ageable next = (Ageable) age.clone();
            next.setAge(Math.min(next.getMaximumAge(), next.getAge() + (vanilla == Material.BEETROOTS ? 1 : ThreadLocalRandom.current().nextInt(2, 6))));
            changes.add(change(block, null, next));
        } else {
            ImmutableBlockState state = access.state(block);
            if (!accepts(state)) return false;
            if (managed != null) managedGrowth(block, state, changes);
            else if (tall != null) tallGrowth(block, state, changes);
            else tomatoGrowth(block, state, changes);
        }
        if (changes.isEmpty() || !authorize(changes, villager, events) || !commit(changes)) return false;
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(.5, .5, .5), 6, .2, .2, .2);
        return true;
    }

    private void managedGrowth(Block block, ImmutableBlockState state, List<Change> changes) {
        var options = managed.options();
        if (!options.bonemeal()) return;
        Property<Integer> age = managed.ageProperty();
        int bonus = managed.bonemealAgeBonus();
        int max = isUpper(state) ? options.upperMaximumAge() : options.maximumAge();
        int next = Math.min(max, state.get(age) + bonus);
        ImmutableBlockState updated = state.with(age, next);
        if (managed.shape() == ManagedCropBlockBehavior.Shape.DOUBLE) {
            Block other = block.getRelative(isUpper(state) ? BlockFace.DOWN : BlockFace.UP);
            if (!access.resident(other)) return;
            ImmutableBlockState partner = access.state(other);
            if (options.synchronizeAges() && accepts(partner)) {
                int partnerMax = isUpper(partner) ? options.upperMaximumAge() : options.maximumAge();
                addGrowth(changes, other, partner.with(age, Math.min(partnerMax, next)));
            }
            if (!isUpper(state) && next >= options.maximumAge()) {
                if (partner == null && isAir(other.getType())) {
                    ImmutableBlockState top = withNamed(updated.with(age, options.upperMinimumAge()), "half", "upper");
                    addGrowth(changes, other, top);
                    if (managed.supportProperty() != null) updated = updated.with(managed.supportProperty(), true);
                } else if (accepts(partner) && !options.synchronizeAges() && !options.upperIndependent())
                    addGrowth(changes, other, partner.with(age, Math.min(options.upperMaximumAge(), partner.get(age) + bonus)));
            }
        } else if (managed.shape() == ManagedCropBlockBehavior.Shape.ROPE && next >= options.ropeMinimumAge()) {
            Block above = block.getRelative(BlockFace.UP);
            if (!access.resident(above)) return;
            ImmutableBlockState rope = access.state(above);
            int height = columnHeight(block, id, options.ropeMaximumHeight());
            if (height < 0) return;
            if (options.ropeBlock().equals(CustomBlockUtils.getId(rope)) && height < options.ropeMaximumHeight())
                addGrowth(changes, above, definition.defaultState().with(age, options.ropeAge()).with(managed.supportProperty(), true));
        }
        addGrowth(changes, block, updated);
    }

    private void tallGrowth(Block block, ImmutableBlockState state, List<Change> changes) {
        var cfg = tall.villagerSettings();
        if (!cfg.isBoneMealTarget()) return;
        int bonus = Math.max(0, cfg.boneMealAgeBonus().getInt());
        if (isUpper(state)) {
            Property<Integer> age = tallAge(state);
            if (age != null) addGrowth(changes, block, state.with(age, Math.min(cfg.maxAgeUpper(), state.get(age) + bonus)));
            return;
        }
        Block above = block.getRelative(BlockFace.UP);
        if (!access.resident(above)) return;
        ImmutableBlockState partner = access.state(above);
        int raw = tall.getAge(state) + bonus;
        ImmutableBlockState lower = state.with(cfg.ageProperty(), Math.min(cfg.maxAgeLower(), raw));
        if (raw >= cfg.maxAgeLower()) {
            if (cfg.supportingProperty() != null) lower = lower.with(cfg.supportingProperty(), true);
            if (partner == null && isAir(above.getType())) {
                BlockDefinition topDefinition = cfg.upperBlockId() == null ? definition : CraftEngineBlocks.byId(cfg.upperBlockId());
                if (topDefinition == null) return;
                int first = cfg.boneMealOverflow() ? Math.max(0, raw - cfg.maxAgeLower() - 1) : 0;
                ImmutableBlockState top = topDefinition.defaultState();
                Property<Integer> topAge = tallAge(top);
                if (topAge == null) return;
                top = top.with(topAge, Math.min(cfg.maxAgeUpper(), first));
                top = withNamed(top, cfg.halfProperty().name(), String.valueOf(cfg.halfUpperValue()).toLowerCase(java.util.Locale.ROOT));
                addGrowth(changes, above, top);
            } else if (accepts(partner) && isUpper(partner) && (tall.getAge(state) >= cfg.maxAgeLower() || cfg.boneMealOverflow())) {
                Property<Integer> topAge = tallAge(partner);
                if (topAge != null) addGrowth(changes, above, partner.with(topAge, Math.min(cfg.maxAgeUpper(), partner.get(topAge) + bonus)));
            }
        }
        addGrowth(changes, block, lower);
    }

    private void tomatoGrowth(Block block, ImmutableBlockState state, List<Change> changes) {
        TomatoVineBlockBehavior vine = TomatoVineBlockBehavior.getBehavior(state.owner().value().id());
        Property<Integer> age = ageProperty(state.owner().value());
        if (age == null) return;
        var random = ThreadLocalRandom.current();
        if (vine != null) {
            var cfg = vine.villagerSettings();
            if (state.owner().value().id().equals(cfg.buddingBlockId())) {
                int raw = state.get(age) + random.nextInt(cfg.bonemealBonusMin(), cfg.bonemealBonusMax() + 1);
                if (raw <= cfg.buddingMaxAge()) addGrowth(changes, block, state.with(age, raw));
                else {
                    BlockDefinition ground = CraftEngineBlocks.byId(cfg.tomatoesBlockId());
                    if (ground != null) addGrowth(changes, block, ground.defaultState().with(ageProperty(ground), Math.min(cfg.tomatoesMaxAge(), raw - cfg.buddingMaxAge() - 1)));
                }
                return;
            }
            if (state.owner().value().id().equals(cfg.tomatoesBlockId()) && state.get(age) >= cfg.tomatoesMaxAge()) {
                Block above = block.getRelative(BlockFace.UP);
                if (!access.resident(above)) return;
                ImmutableBlockState partner = access.state(above);
                if (cfg.cropOnRopeBlockId().toString().equals(CustomBlockUtils.getId(partner)) && partner.get(ageProperty(partner.owner().value())) < cfg.hangingMaxAge()) {
                    Property<Integer> topAge = ageProperty(partner.owner().value());
                    addGrowth(changes, above, partner.with(topAge, partner.get(topAge) + 1));
                    return;
                }
            }
            if (state.get(age) >= cfg.matureAge() && block.getLightLevel() >= cfg.minLight()
                    && (state.get(age) >= cfg.tomatoesMaxAge() || random.nextFloat() < cfg.bonemealClimbChance())) {
                Block above = block.getRelative(BlockFace.UP);
                if (!access.resident(above)) return;
                ImmutableBlockState rope = access.state(above);
                int height = columnHeight(block, state.owner().value().id().toString(), vine.villagerMaximumHeight());
                if (height < 0) return;
                if (height < vine.villagerMaximumHeight() && CustomBlockUtils.hasBehavior(rope, RopeBlockBehavior.class)) {
                    BlockDefinition hanging = CraftEngineBlocks.byId(cfg.cropOnRopeBlockId());
                    if (hanging != null) addGrowth(changes, above, hanging.defaultState().with(ageProperty(hanging), 0));
                }
            }
        }
        CropBlockBehavior crop = CustomBlockUtils.getBehavior(state, CropBlockBehavior.class);
        if (crop != null && crop.isBoneMealTarget && !crop.isMaxAge(state))
            addGrowth(changes, block, state.with(crop.ageProperty, Math.min(crop.ageProperty.max, state.get(crop.ageProperty) + Math.max(0, crop.boneMealBonus.getInt()))));
    }

    private int columnHeight(Block block, String sameId, int maximum) {
        Block current = block;
        for (int height = 1; height <= maximum; ++height) {
            Block below = current.getRelative(BlockFace.DOWN);
            if (!access.resident(below)) return -1;
            if (!sameId.equals(CustomBlockUtils.getId(access.state(below)))) return height;
            current = below;
        }
        return maximum;
    }

    private void addGrowth(List<Change> changes, Block block, ImmutableBlockState next) {
        if (access.state(block) != next) changes.add(change(block, next, access.data(next)));
    }

    record Change(Block block, ImmutableBlockState before, BlockData beforeData,
                  ImmutableBlockState after, BlockData afterData) { }

    private Change change(Block block, ImmutableBlockState after, BlockData data) {
        return new Change(block, access.state(block), block.getBlockData().clone(), after, data.clone());
    }

    boolean authorize(List<Change> changes, Villager villager, Consumer<EntityChangeBlockEvent> events) {
        for (Change change : changes) if (!matchesBefore(change)) return false;
        for (Change change : changes) {
            if (unchanged(change)) continue;
            EntityChangeBlockEvent event = new EntityChangeBlockEvent(villager, change.block, change.afterData.clone());
            events.accept(event);
            if (event.isCancelled()) return false;
        }
        for (Change change : changes) if (!matchesBefore(change)) return false;
        return true;
    }

    private boolean matchesBefore(Change change) {
        return access.resident(change.block) && access.state(change.block) == change.before
                && change.beforeData.equals(change.block.getBlockData());
    }

    private boolean matchesAfter(Change change) {
        return access.resident(change.block) && access.state(change.block) == change.after
                && (change.after != null || change.afterData.equals(change.block.getBlockData()));
    }

    boolean commit(List<Change> changes) {
        List<Change> previous = CONTROLLED_CHANGES.get();
        CONTROLLED_CHANGES.set(changes);
        try { return commitControlled(changes); }
        finally {
            if (previous == null) CONTROLLED_CHANGES.remove(); else CONTROLLED_CHANGES.set(previous);
        }
    }

    private boolean commitControlled(List<Change> changes) {
        for (Change change : changes) if (!matchesBefore(change)) return false;
        List<Change> completed = new ArrayList<>();
        for (Change change : changes) {
            if (unchanged(change)) continue;
            if (!matchesBefore(change)) {
                for (int n = completed.size() - 1; n >= 0; --n) restore(completed.get(n));
                return false;
            }
            boolean success;
            if (change.after != null) success = access.place(change.block, change.after);
            else if (change.before != null) success = access.remove(change.block);
            else { change.block.setBlockData(change.afterData, false); success = true; }
            if (success && matchesAfter(change)) completed.add(change);
            else {
                // Revert only cells still containing our replacement, never a callback's newer edit.
                if (matchesAfter(change)) restore(change);
                for (int n = completed.size() - 1; n >= 0; --n) restore(completed.get(n));
                return false;
            }
        }
        for (Change change : changes) if (!matchesAfter(change)) {
            for (int n = completed.size() - 1; n >= 0; --n) restore(completed.get(n));
            return false;
        }
        return true;
    }

    private static boolean unchanged(Change change) {
        return change.before == change.after && change.beforeData.equals(change.afterData);
    }

    private boolean restore(Change change) {
        List<Change> previous = CONTROLLED_CHANGES.get();
        if (previous == null) CONTROLLED_CHANGES.set(List.of(change));
        try { return restoreControlled(change); }
        finally { if (previous == null) CONTROLLED_CHANGES.remove(); }
    }

    private boolean restoreControlled(Change change) {
        if (!matchesAfter(change)) return false;
        if (change.before != null) return access.place(change.block, change.before) && matchesBefore(change);
        if (change.after != null && !access.remove(change.block)) return false;
        change.block.setBlockData(change.beforeData, false);
        return matchesBefore(change);
    }

    /** Only the already-authorized crop states in this synchronous transaction skip their automatic partner edits. */
    public static boolean controlsRemoval(Block block, ImmutableBlockState previous) {
        List<Change> changes = CONTROLLED_CHANGES.get();
        if (changes == null || previous == null) return false;
        for (Change change : changes) if ((previous == change.before || previous == change.after)
                && block.getWorld() == change.block.getWorld() && block.getX() == change.block.getX()
                && block.getY() == change.block.getY() && block.getZ() == change.block.getZ()) return true;
        return false;
    }

    static Property<Integer> ageProperty(BlockDefinition definition) {
        if (definition == null) return null;
        ManagedCropBlockBehavior managed = ManagedCropBlockBehavior.byId(definition.id());
        if (managed != null) return managed.ageProperty();
        TallCropBlockBehavior tall = TallCropBlockBehavior.getBehavior(definition.id());
        if (tall != null) return tall.villagerSettings().ageProperty();
        CropBlockBehavior crop = CustomBlockUtils.getBehavior(definition.defaultState(), CropBlockBehavior.class);
        if (crop != null) return crop.ageProperty;
        for (String name : List.of("age", "growth", "stage")) {
            Property<?> property = definition.getProperty(name);
            if (property != null && !property.possibleValues().isEmpty()
                    && property.possibleValues().stream().allMatch(Integer.class::isInstance)) {
                @SuppressWarnings("unchecked") Property<Integer> numeric = (Property<Integer>) property;
                return numeric;
            }
        }
        return null;
    }

    static boolean isAir(Material material) { return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR; }

    private static int maximum(Property<Integer> property) { return property.possibleValues().stream().mapToInt(Integer::intValue).max().orElse(0); }
    private static int minimum(Property<Integer> property) { return property.possibleValues().stream().mapToInt(Integer::intValue).min().orElse(0); }
    private static boolean upper(ImmutableBlockState state) {
        if (state == null) return false;
        for (Property<?> property : state.getProperties()) if (property.name().equals("half"))
            return "upper".equalsIgnoreCase(String.valueOf(state.getNullable(property)));
        return false;
    }
    private boolean isUpper(ImmutableBlockState state) {
        if (state == null) return false;
        if (tall != null) {
            var cfg = tall.villagerSettings();
            for (Property<?> property : state.getProperties()) if (property.name().equals(cfg.halfProperty().name()))
                return String.valueOf(cfg.halfUpperValue()).equalsIgnoreCase(String.valueOf(state.getNullable(property)));
            return false;
        }
        if (managed != null && managed.halfProperty() != null)
            return "upper".equalsIgnoreCase(String.valueOf(state.getNullable(managed.halfProperty())));
        return upper(state);
    }
    private Property<Integer> tallAge(ImmutableBlockState state) {
        for (Property<?> property : state.getProperties()) if (property.name().equals(tall.villagerSettings().ageProperty().name())) {
            if (!property.possibleValues().stream().allMatch(Integer.class::isInstance)) return null;
            @SuppressWarnings("unchecked") Property<Integer> age = (Property<Integer>) property;
            return age;
        }
        return null;
    }
    private static ImmutableBlockState withNamed(ImmutableBlockState state, String name, String value) {
        Property<?> property = state.getProperty(name);
        if (property == null) return state;
        Object candidate = property.valueByName(value);
        return candidate == null ? state : ImmutableBlockState.with(state, property, candidate);
    }
    private static List<ItemStack> copies(List<ItemStack> items) { return items.stream().map(ItemStack::clone).toList(); }

    private static ContextHolder.Builder context(Block block, ImmutableBlockState state, Villager villager) {
        var world = BukkitAdaptor.adapt(block.getWorld());
        var position = new WorldPosition(world, block.getX() + .5, block.getY() + .5, block.getZ() + .5);
        var builder = ContextHolder.builder().withParameter(DirectContextParameters.BLOCK, BukkitAdaptor.adapt(block))
                .withParameter(DirectContextParameters.POSITION, position)
                .withParameter(DirectContextParameters.CUSTOM_BLOCK_STATE, state)
                .withParameter(DirectContextParameters.EVENT, Cancellable.dummy())
                .withParameter(DirectContextParameters.HAND, InteractionHand.MAIN_HAND);
        if (villager != null) builder.withOptionalParameter(DirectContextParameters.THIS_ENTITY, BukkitAdaptor.adapt(villager));
        return builder;
    }
}
