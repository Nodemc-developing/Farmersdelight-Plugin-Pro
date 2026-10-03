package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import io.netty.channel.Channel;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundBundlePacketProxy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/** Source-thread capture, bounded connection mailboxes, and Netty-thread bundle delivery. */
public final class ParticleDispatcher implements Listener {
    private static final long MAX_AGE_NANOS = 250_000_000L;
    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager network;
    private final EffectAudience audience = new EffectAudience();
    private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();
    private final Map<UUID, FallbackDelivery> fallbacks = new ConcurrentHashMap<>();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder sentPackets = new LongAdder();
    private final LongAdder sentBundles = new LongAdder();
    private final LongAdder sentFallback = new LongAdder();
    private final LongAdder discarded = new LongAdder();
    private final LongAdder builtPackets = new LongAdder();
    private final LongAdder admissionRejected = new LongAdder();
    private final java.util.concurrent.atomic.AtomicInteger connectionQueuePeak = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile ParticlePacketFactory factory;
    private volatile boolean stopped;
    private volatile int connectionPacketBudget = 64;
    private record DensityKey(UUID world, int x, int z, String kind) { }
    private record SourceKey(int x, int y, int z) { }
    private record DensitySettings(int stoveThreshold, double stoveFloor, int potThreshold, double potFloor,
                                   int soundThreshold, double soundFloor) { }
    private volatile DensitySettings densitySettings;
    private final Map<DensityKey, DensityThrottle<SourceKey>> density = new ConcurrentHashMap<>();
    private final PluginTask refreshTask;
    private final java.util.Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    record Emission(Object packet, UUID world, double x, double y, double z,
                            double rangeSquared, long session, long created) {
        boolean visible(EffectAudience.Position position, long now) {
            return position != null && position.session() == session && now - created <= MAX_AGE_NANOS
                    && position.inRange(world, x, y, z, rangeSquared);
        }
    }

    private record FallbackEmission(Emission gate, Particle particle, double x, double y, double z,
                                    int count, double ox, double oy, double oz, double speed) { }

    private final class FallbackDelivery {
        final Player player;
        final BoundedPacketMailbox<FallbackEmission> mailbox;
        final ConnectionPacketBudget budget = new ConnectionPacketBudget(200_000_000L);
        final ConnectionPacketBudget admission = new ConnectionPacketBudget(200_000_000L);

        FallbackDelivery(Player player) {
            this.player = player;
            this.mailbox = new BoundedPacketMailbox<>(128, 64,
                    task -> {
                        try {
                            plugin.scheduler().runForEntity(player, task, this::retire);
                        } catch (RuntimeException stoppedScheduler) {
                            throw new java.util.concurrent.RejectedExecutionException("Entity scheduler unavailable", stoppedScheduler);
                        }
                    }, this::send);
        }

        void retire() { mailbox.close(); }

        void send(List<FallbackEmission> batch) {
            if (stopped || !player.isOnline()) { discarded.add(batch.size()); return; }
            EffectAudience.Position cached = audience.get(player.getUniqueId());
            if (cached == null) { discarded.add(batch.size()); return; }
            Location location = player.getLocation();
            EffectAudience.Position current = new EffectAudience.Position(cached.session(),
                    location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
            long now = System.nanoTime();
            for (FallbackEmission emission : batch) {
                if (!emission.gate().visible(current, now) || !budget.acquire(now, connectionPacketBudget)) { discarded.increment(); continue; }
                try {
                    player.spawnParticle(emission.particle(), emission.x(), emission.y(), emission.z(),
                            emission.count(), emission.ox(), emission.oy(), emission.oz(), emission.speed(), null, false);
                    sentFallback.increment();
                } catch (RuntimeException | LinkageError unsupported) {
                    warn(unsupported);
                    retire();
                    return;
                }
            }
        }
    }

    private final class Delivery {
        final NetWorkUser user;
        final Channel channel;
        final BoundedPacketMailbox<Emission> mailbox;
        final ConnectionPacketBudget budget = new ConnectionPacketBudget(200_000_000L);
        final ConnectionPacketBudget admission = new ConnectionPacketBudget(200_000_000L);

        Delivery(UUID player, NetWorkUser user) {
            this.user = user;
            this.channel = user.nettyChannel();
            this.mailbox = new BoundedPacketMailbox<>(128, 64, channel.eventLoop(), batch -> {
                if (stopped || !channel.isActive() || !channel.isWritable()) { discarded.add(batch.size()); return; }
                List<Object> packets = new ArrayList<>(batch.size());
                long now = System.nanoTime();
                EffectAudience.Position position = audience.get(player);
                for (Emission emission : batch) if (emission.visible(position, now) && budget.acquire(now, connectionPacketBudget)) packets.add(emission.packet());
                discarded.add(batch.size() - packets.size());
                if (packets.isEmpty()) return;
                try {
                    Object packet = packets.size() == 1 ? packets.getFirst()
                            : ClientboundBundlePacketProxy.INSTANCE.newInstance(List.copyOf(packets));
                    // Already on this connection's event loop; preserve CraftEngine's packet processing.
                    user.sendPacket(packet, false);
                    sentPackets.add(packets.size());
                    if (packets.size() > 1) sentBundles.increment();
                } catch (RuntimeException | LinkageError failure) {
                    factory = null;
                    warn(failure);
                }
            });
        }
    }

    public ParticleDispatcher(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        this.network = craftEngine == null ? null : craftEngine.networkManager();
        try {
            if (network == null) throw new IllegalStateException("CraftEngine network is unavailable");
            this.factory = new ParticlePacketFactory();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            warn(unsupported);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) plugin.scheduler().runForEntity(player, () -> capture(player));
        // Vehicle movement does not necessarily fire PlayerMoveEvent. Keep idle/riding snapshots current,
        // with at most one outstanding owner task per player if a region is slow.
        refreshTask = plugin.scheduler().runRepeating(() -> {
            if (stopped) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                if (!refreshing.add(id)) continue;
                try {
                    plugin.scheduler().runForEntity(player, () -> {
                        try { capture(player); } finally { refreshing.remove(id); }
                    }, () -> refreshing.remove(id));
                } catch (RuntimeException stopping) {
                    refreshing.remove(id);
                }
            }
        }, 10L, 10L);
    }

    public void reloadConfig() {
        connectionPacketBudget = Math.max(1, plugin.getConfigInt(64, "performance.connection-particle-packet-budget"));
        densitySettings = new DensitySettings(
                plugin.getConfigInt(8, "particle-throttle.stove-threshold", "particle_throttle.stove_threshold"),
                plugin.getConfigDouble(.1, "particle-throttle.stove-max-rate", "particle_throttle.stove_max_rate"),
                plugin.getConfigInt(4, "particle-throttle.cooking-pot-threshold", "particle_throttle.cooking_pot_threshold"),
                plugin.getConfigDouble(.05, "particle-throttle.cooking-pot-max-rate", "particle_throttle.cooking_pot_max_rate"),
                plugin.getConfigInt(4, "particle-throttle.ambient-sound-threshold", "particle_throttle.ambient_sound_threshold"),
                plugin.getConfigDouble(.001, "particle-throttle.ambient-sound-max-rate", "particle_throttle.ambient_sound_max_rate"));
        density.clear();
    }

    public double potDensityRate(int count, boolean ambientSound) {
        DensitySettings settings = densitySettings;
        return DensityThrottle.rate(count, ambientSound ? settings.soundThreshold() : settings.potThreshold(),
                ambientSound ? settings.soundFloor() : settings.potFloor());
    }

    public boolean allowDensity(String station, Location source, boolean ambientSound) {
        if (source == null || source.getWorld() == null || stopped) return false;
        DensitySettings settings = densitySettings;
        int threshold = ambientSound ? settings.soundThreshold() : settings.stoveThreshold();
        double floor = ambientSound ? settings.soundFloor() : settings.stoveFloor();
        DensityKey key = new DensityKey(source.getWorld().getUID(), source.getBlockX() >> 4, source.getBlockZ() >> 4,
                ambientSound ? "ambient" : station);
        double rate = density.computeIfAbsent(key, ignored -> new DensityThrottle<>()).observe(
                new SourceKey(source.getBlockX(), source.getBlockY(), source.getBlockZ()), System.nanoTime(), threshold, floor);
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble() < rate;
    }

    public void cleanupDensityChunk(UUID world, int chunkX, int chunkZ) {
        density.keySet().removeIf(key -> key.world().equals(world) && key.x() == chunkX && key.z() == chunkZ);
    }
    public void cleanupDensityWorld(UUID world) { density.keySet().removeIf(key -> key.world().equals(world)); }

    public boolean isNearby(Player player, Location source, double rangeSquared) {
        EffectAudience.Position position = audience.get(player.getUniqueId());
        return position != null && source.getWorld() != null && position.inRange(source.getWorld().getUID(),
                source.getX(), source.getY(), source.getZ(), rangeSquared);
    }

    public void spawn(List<Player> viewers, Location source, double rangeSquared, Particle particle,
                      double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
        if (stopped || viewers.isEmpty() || source.getWorld() == null) return;
        ParticlePacketFactory current = factory;
        if (current == null || particle.getDataType() != Void.class) {
            fallback(viewers, source, rangeSquared, particle, x, y, z, count, offsetX, offsetY, offsetZ, speed);
            return;
        }
        UUID world = source.getWorld().getUID();
        long now = System.nanoTime();
        record Destination(Delivery delivery, long session) { }
        List<Destination> admitted = null;
        for (Player player : viewers) {
            UUID id = player.getUniqueId();
            EffectAudience.Position position = audience.get(id);
            NetWorkUser user = network.getOnlineUser(id);
            if (position == null || !position.inRange(world, source.getX(), source.getY(), source.getZ(), rangeSquared)
                    || user == null || user.isFakePlayer() || user.nettyChannel() == null
                    || !user.nettyChannel().isActive() || !user.nettyChannel().isWritable()) continue;
            Delivery delivery = deliveries.compute(id, (key, existing) -> {
                if (stopped || audience.get(id) == null) {
                    if (existing != null) existing.mailbox.close();
                    return null;
                }
                if (existing != null && existing.user == user) return existing;
                if (existing != null) existing.mailbox.close();
                return new Delivery(id, user);
            });
            if (stopped || delivery == null) continue;
            if (!delivery.admission.acquire(now, connectionPacketBudget)) { admissionRejected.increment(); continue; }
            if (admitted == null) admitted = new ArrayList<>();
            admitted.add(new Destination(delivery, position.session()));
        }
        if (admitted == null) return;
        Object packet;
        try {
            packet = current.create(particle, x, y, z, count, offsetX, offsetY, offsetZ, speed);
            builtPackets.increment();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            factory = null;
            warn(unsupported);
            fallback(viewers, source, rangeSquared, particle, x, y, z, count, offsetX, offsetY, offsetZ, speed);
            return;
        }
        for (Destination recipient : admitted) {
            Delivery delivery = recipient.delivery();
            countOffer(delivery.mailbox.offer(new Emission(packet, world, source.getX(), source.getY(),
                    source.getZ(), rangeSquared, recipient.session(), now)));
            connectionQueuePeak.accumulateAndGet(delivery.mailbox.peak(), Math::max);
        }
    }

    private void fallback(List<Player> viewers, Location source, double rangeSquared, Particle particle,
                          double x, double y, double z, int count, double ox, double oy, double oz, double speed) {
        // Unsupported protocol bindings keep working through the public API on each recipient's owner.
        if (particle.getDataType() != Void.class) return;
        UUID world = source.getWorld().getUID();
        long now = System.nanoTime();
        for (Player player : viewers) {
            UUID id = player.getUniqueId();
            EffectAudience.Position position = audience.get(id);
            if (stopped || position == null || !position.inRange(world, source.getX(), source.getY(), source.getZ(), rangeSquared)) continue;
            FallbackDelivery delivery = fallbacks.compute(id, (key, previous) -> {
                if (stopped || audience.get(id) == null) {
                    if (previous != null) previous.retire();
                    return null;
                }
                if (previous != null && previous.player == player) return previous;
                if (previous != null) previous.retire();
                return new FallbackDelivery(player);
            });
            if (delivery != null && !stopped) {
                if (!delivery.admission.acquire(now, connectionPacketBudget)) { admissionRejected.increment(); continue; }
                countOffer(delivery.mailbox.offer(new FallbackEmission(
                    new Emission(null, world, source.getX(), source.getY(), source.getZ(), rangeSquared,
                            position.session(), now), particle, x, y, z, count, ox, oy, oz, speed)));
                connectionQueuePeak.accumulateAndGet(delivery.mailbox.peak(), Math::max);
            }
        }
    }

    public record Snapshot(long accepted, long rejected, long packets, long bundles, long fallbackEmissions,
                           long discarded, int connections, int fallbackConnections, boolean packetBinding,
                           long builtPackets, long admissionRejected, int connectionQueuePeak) { }

    public Snapshot snapshot() {
        return new Snapshot(accepted.sum(), rejected.sum(), sentPackets.sum(), sentBundles.sum(), sentFallback.sum(),
                discarded.sum(), deliveries.size(), fallbacks.size(), factory != null,
                builtPackets.sum(), admissionRejected.sum(), connectionQueuePeak.get());
    }

    private void countOffer(boolean offered) {
        if (offered) accepted.increment(); else rejected.increment();
    }

    private void warn(Throwable error) {
        if (warned.compareAndSet(false, true)) I18n.logWarning("visual.particle_packet_fallback", "error", error.getMessage());
    }

    private void capture(Player player) {
        if (!stopped && player.isOnline()) publish(player.getUniqueId(), player.getLocation());
    }

    private void publish(UUID player, Location location) {
        if (!stopped && location != null && location.getWorld() != null) audience.publish(player,
                location.getWorld().getUID(), location.getX(), location.getY(), location.getZ());
    }

    private void invalidate(UUID player) {
        audience.invalidate(player);
        Delivery previous = deliveries.remove(player);
        if (previous != null) previous.mailbox.close();
        FallbackDelivery fallback = fallbacks.remove(player);
        if (fallback != null) fallback.retire();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { invalidate(event.getPlayer().getUniqueId()); capture(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!(event instanceof PlayerTeleportEvent)) publish(event.getPlayer().getUniqueId(), event.getTo());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) { invalidate(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterTeleport(PlayerTeleportEvent event) {
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> capture(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        invalidate(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> capture(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { invalidate(event.getPlayer().getUniqueId()); capture(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { invalidate(event.getPlayer().getUniqueId()); }

    public void close() {
        density.clear();
        stopped = true;
        refreshTask.cancel();
        HandlerList.unregisterAll(this);
        deliveries.values().forEach(delivery -> delivery.mailbox.close());
        deliveries.clear();
        fallbacks.values().forEach(FallbackDelivery::retire);
        fallbacks.clear();
        audience.clear();
        refreshing.clear();
    }
}
