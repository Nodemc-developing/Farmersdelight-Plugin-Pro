package com.huidu.farmersdelight.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.function.AbstractConditionalFunction;
import net.momirealms.craftengine.core.plugin.context.function.FunctionFactory;
import net.momirealms.craftengine.core.plugin.context.number.NumberProvider;
import net.momirealms.craftengine.core.plugin.context.number.ConstantNumberProvider;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Content-pack functions execute all player mutations on the player's current owner. */
public final class ContentEffectFunction extends AbstractConditionalFunction<Context> {
    public enum Action { REMOVE, REMOVE_RANDOM, UPGRADE, CHORUS, ENDERMAN, ADVANCEMENT }
    private final Action action;
    private final String effect;
    private final NumberProvider increment, maximum, duration;
    private final boolean harmfulOnly;
    private final double diameter, damage;
    private final FarmersDelightPlugin plugin;
    private final ContentFunctionOwner owner;

    private ContentEffectFunction(FarmersDelightPlugin plugin, ConfigSection config, java.util.List<net.momirealms.craftengine.core.plugin.context.Condition<Context>> predicates, Action action) {
        super(predicates);
        this.plugin = plugin;
        owner = ContentFunctionOwner.forPlugin(plugin);
        this.action = action;
        effect = config.getString(action == Action.ADVANCEMENT ? "advancement" : "effect", "");
        increment = config.getNumber("increment", ConfigConstants.CONSTANT_ONE);
        maximum = config.getNumber("max_amplifier", ConstantNumberProvider.constant(4));
        duration = config.getNumber("duration", ConfigConstants.CONSTANT_ZERO);
        harmfulOnly = config.getBoolean("harmful_only", false);
        diameter = config.getDouble("diameter", 16);
        damage = config.getDouble("damage", 2);
        if (!Double.isFinite(diameter) || diameter <= 0 || diameter > 128) throw new IllegalArgumentException("diameter must be in (0, 128]");
        if (!Double.isFinite(damage) || damage < 0) throw new IllegalArgumentException("damage must be finite and nonnegative");
    }

    public static Map<String, FunctionFactory<Context, ContentEffectFunction>> factories() {
        return factories(null);
    }
    public static Map<String, FunctionFactory<Context, ContentEffectFunction>> factories(FarmersDelightPlugin plugin) {
        Map<String, FunctionFactory<Context, ContentEffectFunction>> result = new LinkedHashMap<>();
        result.put("remove_effect", factory(plugin, Action.REMOVE));
        result.put("remove_random_effect", factory(plugin, Action.REMOVE_RANDOM));
        result.put("upgrade_effect", factory(plugin, Action.UPGRADE));
        result.put("chorus_teleport", factory(plugin, Action.CHORUS));
        result.put("enderman_gristle_teleport", factory(plugin, Action.ENDERMAN));
        result.put("grant_advancement", factory(plugin, Action.ADVANCEMENT));
        return Map.copyOf(result);
    }

    public static FunctionFactory<Context, ContentEffectFunction> factory(Action action) {
        return factory(null, action);
    }
    public static FunctionFactory<Context, ContentEffectFunction> factory(FarmersDelightPlugin plugin, Action action) {
        return new AbstractFactory<>(CommonConditions::fromConfig) {
            @Override public ContentEffectFunction create(ConfigSection config) {
                return new ContentEffectFunction(plugin, config, getPredicates(config), action);
            }
        };
    }

    @Override protected void runInternal(Context context) {
        Object handle = context.getOptionalParameter(DirectContextParameters.PLAYER).map(p -> p.platformPlayer()).orElse(null);
        if (!(handle instanceof Player player)) return;
        owner.run(player, () -> {
            int inc = increment.getInt(context), max = maximum.getInt(context), ticks = duration.getInt(context);
            switch (action) {
                case ADVANCEMENT -> {
                    String id = effect;
                    int separator = id.indexOf(':');
                    if (separator >= 0) id = id.substring(separator + 1);
                    FarmersDelightAdvancements.award(player, id);
                }
                case REMOVE_RANDOM, REMOVE, UPGRADE -> mutateEffects(player, action, effect, inc, max, ticks, harmfulOnly);
                case CHORUS, ENDERMAN -> safeTeleport(plugin, player, TeleportOrigin.capture(player.getLocation()), plugin.configurationGeneration(), 0);
            }
        });
    }

    /** Called only by the player's owner callback. */
    static void mutateEffects(Player player, Action action, String effect, int increment, int maximum, int ticks, boolean harmfulOnly) {
        if (action == Action.REMOVE_RANDOM) {
            List<Runnable> ordinary = new ArrayList<>(), fallback = new ArrayList<>();
            for (PotionEffect present : player.getActivePotionEffects()) {
                if (!harmfulOnly || isHarmful(present.getType().getKey().getKey()))
                    ordinary.add(() -> player.removePotionEffect(present.getType()));
            }
            for (CustomBuff buff : CustomBuffRegistry.activeBuffs(player)) {
                try {
                    if (harmfulOnly && !buff.isHarmful()) continue;
                    (buff.isLowPriority() ? fallback : ordinary).add(() -> CustomBuffRegistry.clear(player, buff.id()));
                } catch (RuntimeException brokenAddon) { /* Other registered effects remain selectable. */ }
            }
            List<Runnable> choices = ordinary.isEmpty() ? fallback : ordinary;
            if (!choices.isEmpty()) choices.get(ThreadLocalRandom.current().nextInt(choices.size())).run();
            return;
        }
        String id = canonicalEffectId(effect);
        CustomBuff custom = CustomBuffRegistry.byId(id);
        boolean builtin = "farmersdelight:comfort".equals(id) || "farmersdelight:nourishment".equals(id);
        if (builtin || custom != null) {
            if (action == Action.REMOVE) {
                if (custom != null) CustomBuffRegistry.clear(player, id);
                else if ("farmersdelight:comfort".equals(id)) EffectManager.removeComfort(player);
                else EffectManager.removeNourishment(player);
                return;
            }
            if (action != Action.UPGRADE) return;
            try {
                int level = builtin ? ("farmersdelight:comfort".equals(id) ? EffectManager.comfortLevel(player) : EffectManager.nourishmentLevel(player)) : custom.level(player);
                if (level <= 0) return;
                int next = (int) Math.min(Integer.MAX_VALUE, (long) upgradedAmplifier(level - 1, increment, maximum) + 1L);
                if (builtin) {
                    if (EffectManager.replaceEffect(player, id, ticks > 0 ? ticks : EffectManager.effectTicks(player, id), next))
                        CustomBuffRegistry.syncState(player, id);
                } else {
                    int seconds = ticks > 0 ? FoodBuffFunction.ticksToSeconds(ticks) : custom.remainingSeconds(player);
                    if (seconds > 0) CustomBuffRegistry.apply(player, id, next, seconds);
                }
            } catch (RuntimeException brokenAddon) { /* A failed addon reporter does not mutate unrelated effects. */ }
            return;
        }
        NamespacedKey parsed = NamespacedKey.fromString(id);
        PotionEffectType type = parsed == null ? null : Registry.EFFECT.get(parsed);
        if (type == null) return;
        if (action == Action.REMOVE) player.removePotionEffect(type);
        else if (action == Action.UPGRADE) {
            PotionEffect old = player.getPotionEffect(type);
            if (old != null) player.addPotionEffect(new PotionEffect(type, ticks > 0 ? ticks : old.getDuration(),
                    upgradedAmplifier(old.getAmplifier(), increment, maximum), old.isAmbient(), old.hasParticles(), old.hasIcon()));
        }
    }

    static String canonicalEffectId(String id) {
        return switch (id) {
            case "nourishment", "nourishment_effect", "farmersdelight:nourishment", "farmersdelight:nourishment_effect" -> "farmersdelight:nourishment";
            case "comfort", "comfort_effect", "farmersdelight:comfort", "farmersdelight:comfort_effect" -> "farmersdelight:comfort";
            default -> id.indexOf(':') < 0 ? "minecraft:" + id : id;
        };
    }

    static int upgradedAmplifier(int current, int increment, int max) {
        return (int) Math.max(0, Math.min(Math.max(0, max), (long) current + increment));
    }

    static boolean isHarmful(String key) {
        return switch (key) {
            case "blindness", "darkness", "hunger", "nausea", "poison", "slowness", "mining_fatigue", "weakness", "wither", "levitation", "bad_omen", "infested", "oozing", "weaving", "wind_charged" -> true;
            default -> false;
        };
    }

    record TeleportOrigin(UUID world, double x, double y, double z, float yaw, float pitch) {
        static TeleportOrigin capture(Location position) { return new TeleportOrigin(position.getWorld().getUID(), position.getX(), position.getY(), position.getZ(), position.getYaw(), position.getPitch()); }
        boolean matches(Location position) {
            return position.getWorld() != null && world.equals(position.getWorld().getUID())
                    && Double.compare(x, position.getX()) == 0 && Double.compare(y, position.getY()) == 0 && Double.compare(z, position.getZ()) == 0;
        }
    }

    private void safeTeleport(FarmersDelightPlugin plugin, Player player, TeleportOrigin origin, long generation, int attempt) {
        if (attempt >= 8 || !plugin.isEnabled() || plugin.configurationGeneration() != generation || !player.isOnline() || player.isDead() || !origin.matches(player.getLocation())) return;
        Location start = player.getLocation();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location candidate = start.clone().add(random.nextDouble(-diameter / 2, diameter / 2), 0, random.nextDouble(-diameter / 2, diameter / 2));
        var world = candidate.getWorld();
        world.getChunkAtAsync(candidate.getBlockX() >> 4, candidate.getBlockZ() >> 4).whenComplete((chunk, failure) -> {
            if (failure != null || !plugin.isEnabled() || plugin.configurationGeneration() != generation) return;
            plugin.scheduler().runAt(candidate, () -> {
                Location safe = null;
                if (world.getWorldBorder().isInside(candidate)) {
                    int top = Math.min(world.getMaxHeight() - 2, start.getBlockY() + 8);
                    int bottom = Math.max(world.getMinHeight() + 1, start.getBlockY() - 16);
                    for (int y = top; y >= bottom; --y) {
                        Block feet = world.getBlockAt(candidate.getBlockX(), y, candidate.getBlockZ());
                        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
                        Block ground = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
                        if (feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid() && ground.isSolid()
                                && safeTeleportMaterial(feet.getType().name()) && safeTeleportMaterial(head.getType().name()) && safeTeleportMaterial(ground.getType().name())) {
                            safe = new Location(world, feet.getX() + .5, y, feet.getZ() + .5, start.getYaw(), start.getPitch());
                            break;
                        }
                    }
                }
                Location destination = safe;
                plugin.scheduler().runForEntity(player, () -> {
                    if (!plugin.isEnabled() || plugin.configurationGeneration() != generation || !player.isOnline() || player.isDead() || !origin.matches(player.getLocation())) return;
                    if (destination == null) { safeTeleport(plugin, player, origin, generation, attempt + 1); return; }
                    SafeTeleportRequest.submit(player, player.getLocation(), destination, plugin.scheduler().isFolia(),
                            action == Action.ENDERMAN && damage > 0, new SafeTeleportRequest.Access() {
                                public boolean originCurrent() {
                                    return plugin.isEnabled() && plugin.configurationGeneration() == generation && player.isOnline()
                                            && !player.isDead() && origin.matches(player.getLocation());
                                }
                                public boolean arrivalCurrent(Location approved) {
                                    return plugin.isEnabled() && plugin.configurationGeneration() == generation && player.isOnline()
                                            && !player.isDead() && player.getWorld() == approved.getWorld()
                                            && player.getLocation().distanceSquared(approved) < 1;
                                }
                                public void fireEvent(PlayerTeleportEvent event) { Bukkit.getPluginManager().callEvent(event); }
                                public void inspectDestination(Location approved, java.util.function.Consumer<Boolean> completion) {
                                    var targetWorld = approved.getWorld();
                                    targetWorld.getChunkAtAsync(approved.getBlockX() >> 4, approved.getBlockZ() >> 4).whenComplete((targetChunk, error) -> {
                                        if (error != null || !plugin.isEnabled() || plugin.configurationGeneration() != generation) { completion.accept(false); return; }
                                        plugin.scheduler().runAt(approved, () -> completion.accept(isSafeDestination(approved)));
                                    });
                                }
                                public void runOwner(Runnable task) {
                                    if (plugin.isEnabled() && plugin.configurationGeneration() == generation)
                                        plugin.scheduler().runForEntity(player, task);
                                }
                                public java.util.concurrent.CompletableFuture<Boolean> teleport(Location approved) {
                                    return player.teleportAsync(approved, PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT);
                                }
                                public void damage() { player.damage(damage); }
                            });
                });
            });
        });
    }
    /** Read only by the destination's owner, including a listener-approved replacement world. */
    static boolean isSafeDestination(Location target) {
        var world = target.getWorld();
        if (world == null || !world.getWorldBorder().isInside(target)
                || target.getBlockY() <= world.getMinHeight() || target.getBlockY() >= world.getMaxHeight() - 1) return false;
        Block feet = world.getBlockAt(target.getBlockX(), target.getBlockY(), target.getBlockZ());
        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP), ground = feet.getRelative(org.bukkit.block.BlockFace.DOWN);
        return feet.isPassable() && !feet.isLiquid() && head.isPassable() && !head.isLiquid() && ground.isSolid()
                && safeTeleportMaterial(feet.getType().name()) && safeTeleportMaterial(head.getType().name()) && safeTeleportMaterial(ground.getType().name());
    }
    static boolean safeTeleportMaterial(String material) {
        return switch (material) {
            case "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE", "LAVA", "CACTUS", "MAGMA_BLOCK", "SWEET_BERRY_BUSH", "WITHER_ROSE",
                    "POWDER_SNOW", "POINTED_DRIPSTONE", "END_PORTAL", "NETHER_PORTAL", "END_GATEWAY" -> false;
            default -> true;
        };
    }
}
