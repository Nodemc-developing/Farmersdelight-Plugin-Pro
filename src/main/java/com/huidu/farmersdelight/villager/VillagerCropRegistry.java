package com.huidu.farmersdelight.villager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.ManagedCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.registry.BuiltInRegistries;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** A reload-scoped registry. Unsupported or missing dependencies never become ordinary CE crops. */
public final class VillagerCropRegistry {
    private VillagerCropRegistry() { }

    public static final class Registry {
        private final Map<String, VillagerCrop> byId;
        private final Map<String, List<VillagerCrop>> bySeed;
        private final Set<String> pickupDrops;
        private final VillagerCrop.Access access;
        private final CustomCrops customCrops;
        private final Map<String, VillagerCrop> customById;

        Registry(Map<String, VillagerCrop> byId, Map<String, List<VillagerCrop>> bySeed, Set<String> pickupDrops,
                 VillagerCrop.Access access, CustomCrops customCrops, Map<String, VillagerCrop> customById) {
            this.byId = Map.copyOf(byId);
            Map<String, List<VillagerCrop>> seeds = new LinkedHashMap<>();
            bySeed.forEach((key, value) -> seeds.put(key, List.copyOf(value)));
            this.bySeed = Map.copyOf(seeds); this.pickupDrops = Set.copyOf(pickupDrops);
            this.access = access; this.customCrops = customCrops; this.customById = Map.copyOf(customById);
        }
        public Map<String, VillagerCrop> byId() { return byId; }
        public Map<String, List<VillagerCrop>> bySeed() { return bySeed; }
        public Set<String> pickupDrops() { return pickupDrops; }
        public VillagerCrop find(Block block) {
            if (!access.resident(block)) return null;
            if (customCrops != null) {
                var state = customCrops.state(block);
                // A CustomCrops block (including excluded furniture/mixed modes) must not fall through.
                if (state != null) return state.supported() ? customById.get(state.id()) : null;
            }
            String id = CustomBlockUtils.getId(access.state(block));
            if (id != null) return byId.get(id);
            return byId.get(block.getType().getKey().toString());
        }
    }

    public static Registry read(FarmersDelightPlugin plugin) {
        var config = plugin.getConfig();
        VillagerCrop.Access access = VillagerCrop.access(plugin);
        Set<String> disabled = Set.copyOf(config.getStringList("villager.harvest.disabled-crops"));
        List<String> extraSoils = config.getStringList("villager.harvest.extra-soils");
        Map<String, VillagerCrop> crops = new LinkedHashMap<>();
        Map<String, List<VillagerCrop>> seeds = new LinkedHashMap<>();
        Set<String> pickup = new LinkedHashSet<>();
        var explicit = config.getConfigurationSection("villager.harvest.crops");
        if (explicit != null) for (String id : explicit.getKeys(false)) {
            if (disabled.contains(id)) continue;
            ConfigurationSection row = explicit.getConfigurationSection(id);
            if (row == null) throw new IllegalArgumentException("villager.harvest.crops." + id + " must be a mapping");
            VillagerCrop crop = create(id, row, extraSoils, access, plugin.getLogger());
            if (crop != null) register(crop, crops, seeds, pickup, disabled);
        }
        // Managed crop packs declare their own per-crop village flags. Existing explicit entries win.
        for (BlockDefinition definition : BuiltInRegistries.BLOCK) {
            String id = definition.id().toString();
            ManagedCropBlockBehavior managed = ManagedCropBlockBehavior.byId(definition.id());
            if (managed == null || disabled.contains(id) || crops.containsKey(id)
                    || !managed.options().villageHarvest() && !managed.options().villagePlant()) continue;
            Key seed = managed.plantingState().settings().itemId();
            List<String> descriptors = new ArrayList<>(extraSoils);
            managed.options().soils().forEach(soil -> descriptors.add(soil.block()));
            var crop = new VillagerCrop(id, seed == null ? null : seed.toString(), definition, definition, null,
                    VillagerCrop.Mode.RESET, Set.of(id), soils(descriptors), managed.options().water(),
                    managed.options().villageHarvest(), managed.options().villagePlant(), access);
            register(crop, crops, seeds, pickup, disabled);
        }
        var legacyPlants = config.getConfigurationSection("villager.harvest.planting");
        if (legacyPlants != null) for (String seed : legacyPlants.getKeys(false)) {
            String id = legacyPlants.getString(seed);
            VillagerCrop crop = crops.get(id);
            if (crop == null) throw new IllegalArgumentException("villager.harvest.planting." + seed + " refers to an unknown or disabled crop");
            seeds.put(seed, new ArrayList<>(List.of(crop))); pickup.add(seed);
        }
        var drops = config.getConfigurationSection("villager.harvest.harvest-drops");
        if (drops != null) for (String id : drops.getKeys(false)) {
            if (!crops.containsKey(id)) continue;
            for (String drop : drops.getStringList(id)) pickup.add(dropId(drop));
        }
        CustomCrops custom = null;
        Map<String, VillagerCrop> customById = new LinkedHashMap<>();
        if (config.getBoolean("villager.harvest.custom-crops", config.getBoolean("villager.harvest.custom-crops.enabled", false))) {
            custom = CustomCrops.connect(plugin.getLogger(), access);
            if (custom != null) for (var entry : custom.crops().entrySet()) {
                String id = entry.getKey();
                if (disabled.contains(id) || disabled.contains("customcrops:" + id) || !custom.supported(entry.getValue())) continue;
                List<String> cropSeeds = custom.seeds(entry.getValue());
                var crop = new VillagerCrop("customcrops:" + id, cropSeeds.isEmpty() ? null : cropSeeds.getFirst(), custom, entry.getValue(), access);
                customById.put(id, crop); crops.putIfAbsent(crop.id(), crop);
                for (String seed : cropSeeds) { seeds.computeIfAbsent(seed, ignored -> new ArrayList<>()).add(crop); pickup.add(seed); }
            }
        }
        return new Registry(crops, seeds, pickup, access, custom, customById);
    }

    private static VillagerCrop create(String id, ConfigurationSection row, List<String> extra,
                                       VillagerCrop.Access access, Logger logger) {
        BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
        Material vanilla = null;
        if (definition == null && id.startsWith("minecraft:")) {
            vanilla = Material.matchMaterial(id);
            if (vanilla != Material.WHEAT && vanilla != Material.BEETROOTS && vanilla != Material.CARROTS && vanilla != Material.POTATOES) vanilla = null;
        }
        if (definition == null && vanilla == null) { logger.warning("Villager crop " + id + " is unavailable; skipping it until its pack is loaded."); return null; }
        if (definition != null && VillagerCrop.ageProperty(definition) == null) throw new IllegalArgumentException("Villager crop " + id + " requires an integer age/growth/stage property");
        var managed = definition == null ? null : ManagedCropBlockBehavior.byId(definition.id());
        var tall = definition == null ? null : TallCropBlockBehavior.getBehavior(definition.id());
        var tomato = definition == null ? null : TomatoVineBlockBehavior.getBehavior(definition.id());
        String rawMode = row.getString("harvest-mode", managed != null ? "reset" : tall != null ? "tall" : "break");
        VillagerCrop.Mode mode;
        try { mode = VillagerCrop.Mode.valueOf(rawMode.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown harvest-mode for " + id + ": " + rawMode); }
        BlockDefinition plant = definition;
        String plantId = row.getString("plant-block", id);
        if (definition != null) {
            plant = CraftEngineBlocks.byId(Key.of(plantId));
            if (plant == null || VillagerCrop.ageProperty(plant) == null) { logger.warning("Villager crop " + id + " has unavailable planting block " + plantId + "; skipping it."); return null; }
        }
        Set<String> aliases = new LinkedHashSet<>(Set.of(id));
        if (plant != null) aliases.add(plant.id().toString());
        if (tomato != null) {
            var settings = tomato.villagerSettings();
            aliases.add(settings.buddingBlockId().toString()); aliases.add(settings.tomatoesBlockId().toString());
            aliases.add(settings.cropOnRopeBlockId().toString());
        }
        if (tall != null && tall.villagerSettings().upperBlockId() != null) aliases.add(tall.villagerSettings().upperBlockId().toString());
        List<String> descriptors = new ArrayList<>(extra); descriptors.addAll(row.getStringList("soils"));
        if (managed != null) managed.options().soils().forEach(soil -> descriptors.add(soil.block()));
        String fluid = row.getString("water", managed != null && managed.options().water() || tall != null && tall.villagerSettings().requiresWater() ? "minecraft:water" : "");
        if (!fluid.isBlank() && !fluid.equals("minecraft:water")) throw new IllegalArgumentException("Villager crop " + id + " has unsupported planting fluid " + fluid);
        if (fluid.isBlank()) descriptors.add("minecraft:farmland");
        if (tall != null && row.getStringList("soils").isEmpty()) {
            var rules = TallCropBlockBehavior.getSoilRules(definition.id());
            if (rules != null) {
                rules.materials().forEach(material -> descriptors.add(material.getKey().toString()));
                rules.tags().forEach(tag -> descriptors.add("#" + tag));
                descriptors.addAll(rules.customBlockIds()); descriptors.addAll(rules.customStateStrings());
            }
        }
        Key declared = plant == null ? null : plant.defaultState().settings().itemId();
        String seed = row.getString("seed", declared == null ? vanillaSeed(vanilla) : declared.toString());
        return new VillagerCrop(id, seed, definition, plant, vanilla, mode, aliases, soils(descriptors), !fluid.isBlank(),
                managed == null || managed.options().villageHarvest(), managed == null || managed.options().villagePlant(), access);
    }

    private static String vanillaSeed(Material crop) {
        if (crop == null) return null;
        return switch (crop) {
            case WHEAT -> "minecraft:wheat_seeds";
            case BEETROOTS -> "minecraft:beetroot_seeds";
            case CARROTS -> "minecraft:carrot";
            case POTATOES -> "minecraft:potato";
            default -> null;
        };
    }

    private static void register(VillagerCrop crop, Map<String, VillagerCrop> crops, Map<String, List<VillagerCrop>> seeds,
                                 Set<String> pickup, Set<String> disabled) {
        crops.put(crop.id(), crop);
        for (String alias : crop.acceptedIds()) if (!disabled.contains(alias)) crops.putIfAbsent(alias, crop);
        if (crop.seed() != null && !crop.seed().isBlank()) {
            seeds.computeIfAbsent(crop.seed(), ignored -> new ArrayList<>()).add(crop); pickup.add(crop.seed());
        }
        var tall = TallCropBlockBehavior.getBehavior(Key.of(crop.id()));
        if (tall != null) for (Key seed : tall.extraPlantingItems()) {
            seeds.computeIfAbsent(seed.toString(), ignored -> new ArrayList<>()).add(crop); pickup.add(seed.toString());
        }
        switch (crop.id()) {
            case "farmersdelight:wheat" -> pickup.add("minecraft:wheat");
            case "farmersdelight:beetroots" -> pickup.add("minecraft:beetroot");
            case "farmersdelight:cabbages" -> pickup.add("farmersdelight:cabbage");
            case "farmersdelight:tomatoes" -> { pickup.add("farmersdelight:tomato"); pickup.add("farmersdelight:rotten_tomato"); }
            case "farmersdelight:rice" -> pickup.add("farmersdelight:rice_panicle");
            default -> { }
        }
    }

    private static String dropId(String input) {
        int last = input.lastIndexOf(':');
        return last > input.indexOf(':') && input.substring(last + 1).matches("[0-9]+") ? input.substring(0, last) : input;
    }
    static SoilRuleSupport.SoilRules soils(List<String> descriptors) {
        List<String> blocks = new ArrayList<>(), tags = new ArrayList<>();
        for (String text : descriptors) if (text.startsWith("#")) tags.add(text.substring(1)); else blocks.add(text);
        return SoilRuleSupport.parseSoilRules(Map.of("bottom-blocks", blocks, "bottom-block-tags", tags));
    }

    /** Isolated public CustomCrops API bridge. All reflection is linked once per reload, never discovered in scans. */
    static final class CustomCrops {
        private static final Set<String> CONNECTION_WARNINGS = ConcurrentHashMap.newKeySet();
        record State(Object raw, Object config, String id, int point, int maximum, boolean supported) { }
        record Placement(Object raw, Object world, Object pos, BlockData placedData) { }
        private final Logger logger;
        private final VillagerCrop.Access access;
        private final Plugin plugin;
        private final Object api, itemManager, crops, pots, cropMechanic;
        private final Class<?> cropType;
        private final Method adapt, getWorld, loadedState, stateType, cropConfig, cropPoint, cropId,
                maxPoints, stageAt, stageForm, stageId, potWhitelist, potId, seedIds, registryGet,
                blockId, breakCrop, createState, setId, setPoint, addState, removeState, placeBlock, removeBlock;
        private final Object breakReason;
        private final Map<Object, Boolean> supported = new ConcurrentHashMap<>();
        private final Set<String> reported = ConcurrentHashMap.newKeySet();

        static CustomCrops connect(Logger logger, VillagerCrop.Access access) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CustomCrops");
            if (plugin == null || !plugin.isEnabled()) {
                if (CONNECTION_WARNINGS.add("absent")) logger.warning("Villager CustomCrops integration is enabled but CustomCrops is absent or disabled.");
                return null;
            }
            try { return new CustomCrops(plugin, logger, access); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                if (CONNECTION_WARNINGS.add("link:" + failure.getClass().getName() + ":" + failure.getMessage()))
                    logger.warning("Villager CustomCrops integration could not link its BLOCK API: " + failure);
                return null;
            }
        }

        private CustomCrops(Plugin plugin, Logger logger, VillagerCrop.Access access) throws ReflectiveOperationException {
            this.plugin = plugin; this.logger = logger; this.access = access;
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> apiType = load(loader, "CustomCropsAPI");
            api = load(loader, "BukkitCustomCropsAPI").getMethod("get").invoke(null);
            Class<?> pluginType = load(loader, "BukkitCustomCropsPlugin");
            Object instance = pluginType.getMethod("getInstance").invoke(null);
            Method manager = pluginType.getMethod("getItemManager"); itemManager = manager.invoke(instance);
            Class<?> itemType = manager.getReturnType();
            blockId = itemType.getMethod("blockID", Block.class);
            placeBlock = itemType.getMethod("placeBlock", org.bukkit.Location.class, String.class);
            removeBlock = itemType.getMethod("removeBlock", org.bukkit.Location.class);
            Class<?> pos = load(loader, "core.world.Pos3"), state = load(loader, "core.world.CustomCropsBlockState");
            Class<?> world = load(loader, "core.world.CustomCropsWorld");
            cropType = load(loader, "core.block.CropBlock");
            Class<?> cfg = load(loader, "core.mechanic.crop.CropConfig"), stage = load(loader, "core.mechanic.crop.CropStageConfig");
            adapt = apiType.getMethod("adapt", org.bukkit.Location.class); getWorld = apiType.getMethod("getCustomCropsWorld", World.class);
            loadedState = world.getMethod("getLoadedBlockState", pos); stateType = state.getMethod("type");
            cropConfig = cropType.getMethod("config", state); cropPoint = cropType.getMethod("point", state);
            cropId = cfg.getMethod("id"); maxPoints = cfg.getMethod("maxPoints");
            stageAt = cfg.getMethod("stageWithModelByPoint", int.class); stageForm = stage.getMethod("existenceForm"); stageId = stage.getMethod("stageID");
            potWhitelist = cfg.getMethod("potWhitelist"); seedIds = cfg.getMethod("seeds");
            potId = load(loader, "core.mechanic.pot.PotConfig").getMethod("id");
            Class<?> registries = load(loader, "core.Registries"), registry = load(loader, "core.Registry");
            crops = registries.getField("CROP").get(null); pots = registries.getField("ITEM_TO_POT").get(null);
            registryGet = registry.getMethod("get", Object.class);
            Class<?> reason = load(loader, "core.block.BreakReason"); breakReason = reason.getField("BREAK").get(null);
            breakCrop = apiType.getMethod("simulatePlayerBreakCrop", Player.class, EquipmentSlot.class, org.bukkit.Location.class, reason);
            Object mechanicDefinition = load(loader, "core.BuiltInBlockMechanics").getField("CROP").get(null);
            createState = mechanicDefinition.getClass().getMethod("createBlockState");
            cropMechanic = mechanicDefinition;
            setId = cropType.getMethod("id", state, String.class); setPoint = cropType.getMethod("point", state, int.class);
            addState = world.getMethod("addBlockState", pos, state); removeState = world.getMethod("removeBlockState", pos);
        }

        private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName("net.momirealms.customcrops.api." + name, true, loader);
        }
        private void report(String key, String message) { if (reported.add(key)) logger.warning(message); }

        Map<String, Object> crops() {
            Map<String, Object> result = new LinkedHashMap<>();
            try {
                if (!(crops instanceof Iterable<?> values)) throw new IllegalStateException("Crop registry is not iterable");
                for (Object cfg : values) result.put(String.valueOf(cropId.invoke(cfg)), cfg);
            } catch (ReflectiveOperationException | RuntimeException e) { report("registry", "Cannot enumerate CustomCrops BLOCK definitions: " + e); }
            return result;
        }

        List<String> seeds(Object cfg) {
            List<String> result = new ArrayList<>();
            try {
                Object values = seedIds.invoke(cfg);
                if (!(values instanceof Iterable<?> entries)) return List.of();
                for (Object entry : entries) {
                    String value = String.valueOf(entry);
                    // CE and vanilla item ids share the normal FD inventory lookup namespace.
                    String normalized = value.indexOf(':') < 0 ? "minecraft:" + value.toLowerCase(Locale.ROOT) : value;
                    boolean vanilla = normalized.startsWith("minecraft:") && Material.matchMaterial(normalized) != null;
                    if (vanilla || net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(Key.of(normalized)) != null) {
                        if (!result.contains(normalized)) result.add(normalized);
                    } else report("seed:" + value, "CustomCrops seed " + value + " is outside the supported CE/vanilla item providers; planting is skipped.");
                }
            } catch (ReflectiveOperationException | RuntimeException e) { report("seed", "Cannot read CustomCrops seeds: " + e); }
            return List.copyOf(result);
        }

        boolean supported(Object cfg) {
            return supported.computeIfAbsent(cfg, key -> {
                try {
                    int max = ((Number) maxPoints.invoke(key)).intValue();
                    if (max < 0 || max > 65536) throw new IllegalArgumentException("points outside supported bounds");
                    for (int point = 0; point <= max; ++point) {
                        Object stage = stageAt.invoke(key, point);
                        if (stage == null || !"BLOCK".equalsIgnoreCase(String.valueOf(stageForm.invoke(stage)))) {
                            report("mode:" + cropId.invoke(key), "CustomCrops " + cropId.invoke(key) + " is skipped: all stages must use BLOCK mode."); return false;
                        }
                    }
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) { report("stages", "Cannot validate CustomCrops BLOCK stages: " + e); return false; }
            });
        }

        State state(Block block) {
            if (!plugin.isEnabled() || !access.resident(block)) return null;
            try {
                Object world = getWorld.invoke(api, block.getWorld()); if (world == null) return null;
                Optional<?> value = (Optional<?>) loadedState.invoke(world, adapt.invoke(api, block.getLocation()));
                if (value.isEmpty()) return null;
                Object raw = value.get(), type = stateType.invoke(raw);
                if (!cropType.isInstance(type)) return null;
                Object cfg = cropConfig.invoke(type, raw);
                if (cfg == null) return new State(raw, null, "", 0, 0, false);
                return new State(raw, cfg, String.valueOf(cropId.invoke(cfg)), ((Number) cropPoint.invoke(type, raw)).intValue(),
                        ((Number) maxPoints.invoke(cfg)).intValue(), supported(cfg));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                report("state", "CustomCrops loaded crop lookup failed: " + e);
                return new State(null, null, "", 0, 0, false);
            }
        }

        boolean canPlant(Block target, Object cfg) {
            if (!plugin.isEnabled() || !access.resident(target) || !VillagerCrop.isAir(target.getType()) || state(target) != null || !supported(cfg)) return false;
            Block below = target.getRelative(BlockFace.DOWN); if (!access.resident(below)) return false;
            try {
                Object world = getWorld.invoke(api, target.getWorld()); if (world == null) return false;
                if (((Optional<?>) loadedState.invoke(world, adapt.invoke(api, target.getLocation()))).isPresent()) return false;
                Object pot = registryGet.invoke(pots, String.valueOf(blockId.invoke(itemManager, below)));
                if (pot == null) return false;
                Object white = potWhitelist.invoke(cfg);
                return white instanceof Set<?> set && set.contains(potId.invoke(pot)) && initialData(cfg) != null;
            } catch (ReflectiveOperationException | RuntimeException e) { report("soil", "CustomCrops pot lookup failed: " + e); return false; }
        }

        BlockData initialData(Object cfg) {
            try {
                String id = String.valueOf(stageId.invoke(stageAt.invoke(cfg, 0)));
                if (!id.contains(":") || id.startsWith("minecraft:")) return Bukkit.createBlockData(id);
                BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
                if (definition != null) return access.data(definition.defaultState());
                report("provider:" + id, "CustomCrops BLOCK stage " + id + " cannot be resolved to CE/vanilla BlockData; planting is skipped.");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { report("initial", "CustomCrops initial stage lookup failed: " + e); }
            return null;
        }

        Object plant(Block target, Object cfg) {
            if (!canPlant(target, cfg)) return null;
            Object world = null, pos = null, raw = null;
            BlockData desired = initialData(cfg);
            try {
                world = getWorld.invoke(api, target.getWorld()); pos = adapt.invoke(api, target.getLocation());
                raw = createState.invoke(cropMechanic); Object type = stateType.invoke(raw);
                setId.invoke(type, raw, String.valueOf(cropId.invoke(cfg))); setPoint.invoke(type, raw, 0);
                addState.invoke(world, pos, raw);
                placeBlock.invoke(itemManager, target.getLocation(), String.valueOf(stageId.invoke(stageAt.invoke(cfg, 0))));
                State current = state(target);
                if (current != null && current.raw() == raw && current.point() == 0 && desired.equals(target.getBlockData()))
                    return new Placement(raw, world, pos, desired.clone());
                cleanupPlacement(target, world, pos, raw, desired);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                report("plant", "CustomCrops synchronous BLOCK placement failed: " + e);
                try { cleanupPlacement(target, world, pos, raw, desired); }
                catch (ReflectiveOperationException ignored) { }
            }
            return null;
        }

        private void cleanupPlacement(Block target, Object world, Object pos, Object raw, BlockData desired) throws ReflectiveOperationException {
            State current = state(target);
            if (raw == null || current == null || current.raw() != raw) return;
            removeState.invoke(world, pos);
            // Metadata belongs to us; a callback's replacement block does not.
            if (desired != null && desired.equals(target.getBlockData())) removeBlock.invoke(itemManager, target.getLocation());
        }

        boolean rollback(Block target, Object token) {
            if (!(token instanceof Placement p) || !access.resident(target)) return false;
            State current = state(target);
            if (current == null || current.raw() != p.raw() || current.point() != 0 || !p.placedData().equals(target.getBlockData())) return false;
            try { removeState.invoke(p.world(), p.pos()); removeBlock.invoke(itemManager, target.getLocation()); return state(target) == null; }
            catch (ReflectiveOperationException | RuntimeException e) { report("rollback", "CustomCrops plant rollback failed: " + e); return false; }
        }

        boolean harvest(Block target, Object token) {
            if (!(token instanceof State expected) || !access.resident(target)) return false;
            State actual = state(target);
            if (actual == null || actual.raw() != expected.raw() || actual.config() != expected.config() || actual.point() != expected.point()) return false;
            try { breakCrop.invoke(api, null, null, target.getLocation(), breakReason); return state(target) == null; }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) { report("harvest", "CustomCrops native harvest failed: " + e); return false; }
        }
    }
}
