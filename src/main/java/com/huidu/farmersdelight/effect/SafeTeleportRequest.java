package com.huidu.farmersdelight.effect;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One approved player-owner request; destination inspection never grants authority over the player. */
final class SafeTeleportRequest {
    interface Access {
        boolean originCurrent();
        boolean arrivalCurrent(Location destination);
        void fireEvent(PlayerTeleportEvent event);
        void inspectDestination(Location destination, Consumer<Boolean> completion);
        void runOwner(Runnable action);
        CompletableFuture<Boolean> teleport(Location destination);
        void damage();
    }

    static void submit(Player player, Location origin, Location safe, boolean folia, boolean damage, Access access) {
        if (!access.originCurrent()) return;
        Location destination = safe.clone();
        if (folia) {
            // The supported Folia native async path does not emit the standard teleport event.
            // Publish the protection decision on the player owner before moving the actual entity.
            PlayerTeleportEvent event = new PlayerTeleportEvent(player, origin.clone(), destination,
                    PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT);
            access.fireEvent(event);
            if (event.isCancelled() || event.getTo() == null || !access.originCurrent()) return;
            destination = event.getTo().clone();
            try { destination.checkFinite(); }
            catch (IllegalArgumentException invalid) { return; }
            if (destination.getWorld() == null) return;
        }
        Location approved = destination;
        AtomicBoolean started = new AtomicBoolean();
        Consumer<Boolean> inspected = valid -> access.runOwner(() -> {
            if (!valid || !access.originCurrent() || !started.compareAndSet(false, true)) return;
            access.teleport(approved).thenAccept(success -> {
                if (success && damage) access.runOwner(() -> {
                    if (access.arrivalCurrent(approved)) access.damage();
                });
            });
        });
        if (folia) access.inspectDestination(approved, inspected);
        else inspected.accept(true);
    }
}
