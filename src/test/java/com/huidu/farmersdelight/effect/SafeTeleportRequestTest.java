package com.huidu.farmersdelight.effect;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SafeTeleportRequestTest {
    @Test void cancelledOwnerEventStopsBeforeAnyDestinationInspectionOrNativeMove() {
        Fixture fixture = new Fixture(); fixture.listener = event -> event.setCancelled(true);
        fixture.submit(true);
        assertEquals(1, fixture.events); assertEquals(0, fixture.inspections);
        assertEquals(0, fixture.teleports); assertEquals(0, fixture.damage);
        assertTrue(fixture.ownerQueue.isEmpty());
    }

    @Test void aListenerChangedDestinationIsInspectedAndHazardRejectedBeforeMoving() {
        Fixture fixture = new Fixture(); Location redirected = fixture.safe.clone().add(4, 0, 0);
        fixture.ground = Material.SOUL_CAMPFIRE;
        fixture.listener = event -> event.setTo(redirected);
        fixture.submit(true);
        assertEquals(redirected, fixture.inspected);
        fixture.completeInspection(); fixture.drainOwner();
        assertEquals(1, fixture.events); assertEquals(0, fixture.teleports); assertEquals(0, fixture.damage);
    }

    @Test void aSafeListenerChangedDestinationUsesItsActualCoordinatesExactlyOnce() {
        Fixture fixture = new Fixture(); Location redirected = fixture.safe.clone().add(4, 0, 0);
        fixture.listener = event -> event.setTo(redirected);
        fixture.submit(true); fixture.completeInspection(); fixture.drainOwner();
        assertEquals(redirected, fixture.teleported); assertEquals(1, fixture.teleports);
        fixture.future.complete(true); fixture.drainOwner();
        assertEquals(1, fixture.damage); assertEquals(1, fixture.events);
    }

    @Test void aCrossWorldRedirectRetainsTheApprovedWorldThroughTheArrivalDamageDecision() {
        Fixture fixture = new Fixture(), target = new Fixture();
        fixture.listener = event -> event.setTo(target.safe);
        fixture.submit(true);
        target.destinationOwner = true;
        try { fixture.completeInspection(); }
        finally { target.destinationOwner = false; }
        fixture.drainOwner(); fixture.future.complete(true); fixture.drainOwner();
        assertSame(target.world, fixture.inspected.getWorld());
        assertSame(target.world, fixture.teleported.getWorld());
        assertSame(target.world, fixture.arrivalChecked.getWorld());
        assertEquals(1, fixture.damage);
    }

    @Test void aListenerCannotApproveAnUnknownWorldOrNonFiniteDestination() {
        for (boolean missingWorld : new boolean[]{true, false}) {
            Fixture fixture = new Fixture(); fixture.listener = event -> {
                if (missingWorld) event.getTo().setWorld(null);
                else event.getTo().setX(Double.NaN);
            };
            fixture.submit(true);
            assertEquals(1, fixture.events); assertEquals(0, fixture.inspections);
            assertEquals(0, fixture.teleports); assertEquals(0, fixture.damage);
        }
    }

    @Test void lateSourceMovementReloadOrDisableCannotMoveAfterDestinationInspection() {
        for (String lifecycle : new String[]{"source movement", "new generation", "plugin stop"}) {
            Fixture fixture = new Fixture(); fixture.submit(true);
            fixture.current = false; fixture.completeInspection(); fixture.drainOwner();
            assertEquals(0, fixture.teleports, lifecycle); assertEquals(0, fixture.damage, lifecycle);
        }
    }

    @Test void aListenerInvalidatingTheSourceCannotScheduleAStaleApprovedDestination() {
        Fixture fixture = new Fixture(); fixture.listener = event -> fixture.current = false;
        fixture.submit(true);
        assertEquals(1, fixture.events); assertEquals(0, fixture.inspections); assertEquals(0, fixture.teleports);
    }

    @Test void duplicateInspectionDeliveryCannotDuplicateTheNativeRequestOrSuccessDamage() {
        Fixture fixture = new Fixture(); fixture.submit(true);
        fixture.completeInspection(); fixture.completeInspection(); fixture.drainOwner();
        assertEquals(1, fixture.teleports);
        fixture.future.complete(true); fixture.future.complete(true); fixture.drainOwner();
        assertEquals(1, fixture.damage); assertEquals(1, fixture.events);
    }

    @Test void failedNativeTeleportAndStaleArrivalNeverDamageThePlayer() {
        Fixture rejected = new Fixture(); rejected.submit(true); rejected.completeInspection(); rejected.drainOwner();
        rejected.future.complete(false); rejected.drainOwner(); assertEquals(0, rejected.damage);
        Fixture moved = new Fixture(); moved.submit(true); moved.completeInspection(); moved.drainOwner();
        moved.future.complete(true); moved.arrived = false; moved.drainOwner(); assertEquals(0, moved.damage);
    }

    @Test void paperKeepsItsNativeEventPathAndDoesNotPublishASecondPluginEvent() {
        Fixture fixture = new Fixture(); fixture.submit(false); fixture.drainOwner();
        assertEquals(0, fixture.events); assertEquals(0, fixture.inspections); assertEquals(1, fixture.teleports);
        fixture.future.complete(true); fixture.drainOwner(); assertEquals(1, fixture.damage);
    }

    private static final class Fixture implements SafeTeleportRequest.Access {
        final UUID worldId = UUID.randomUUID();
        final WorldBorder border = proxy(WorldBorder.class, (method, arguments) -> method.equals("isInside") ? true : null);
        final World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "getUID" -> worldId;
            case "getWorldBorder" -> border;
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "getBlockAt" -> block((int) arguments[1]);
            case "equals" -> arguments[0] == this.world;
            default -> throw new UnsupportedOperationException(method);
        });
        final Player player = proxy(Player.class, (method, arguments) -> { throw new UnsupportedOperationException(method); });
        final Location start = new Location(world, 1.75, 65, 1.5), safe = new Location(world, 1.5, 65, 1.5);
        final Queue<Runnable> ownerQueue = new ArrayDeque<>();
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        Consumer<PlayerTeleportEvent> listener = event -> { };
        Consumer<Boolean> inspection;
        Material ground = Material.STONE;
        Location inspected, teleported, arrivalChecked;
        boolean current = true, arrived = true, destinationOwner;
        int events, inspections, teleports, damage;
        void submit(boolean folia) { SafeTeleportRequest.submit(player, start, safe, folia, true, this); }
        void completeInspection() {
            destinationOwner = true;
            try { inspection.accept(ContentEffectFunction.isSafeDestination(inspected)); }
            finally { destinationOwner = false; }
        }
        void drainOwner() { while (!ownerQueue.isEmpty()) ownerQueue.remove().run(); }
        private Block block(int y) {
            return proxy(Block.class, (method, arguments) -> {
                assertTrue(destinationOwner, "Live terrain may only be inspected by the destination owner");
                return switch (method) {
                    case "getRelative" -> block(y + ((BlockFace) arguments[0]).getModY());
                    case "isPassable" -> y >= 65;
                    case "isLiquid" -> false;
                    case "isSolid" -> y < 65;
                    case "getType" -> y < 65 ? ground : Material.AIR;
                    default -> throw new UnsupportedOperationException(method);
                };
            });
        }
        public boolean originCurrent() { assertFalse(destinationOwner); return current; }
        public boolean arrivalCurrent(Location destination) { assertFalse(destinationOwner); arrivalChecked = destination; return arrived; }
        public void fireEvent(PlayerTeleportEvent event) {
            assertFalse(destinationOwner); assertSame(player, event.getPlayer());
            assertEquals(PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT, event.getCause());
            events++; listener.accept(event);
        }
        public void inspectDestination(Location destination, Consumer<Boolean> completion) { inspections++; inspected = destination; inspection = completion; }
        public void runOwner(Runnable action) { ownerQueue.add(action); }
        public CompletableFuture<Boolean> teleport(Location destination) { assertFalse(destinationOwner); teleports++; teleported = destination; return future; }
        public void damage() { assertFalse(destinationOwner); damage++; }
    }
    @FunctionalInterface private interface Handler { Object invoke(String method, Object[] arguments); }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, arguments) -> handler.invoke(method.getName(), arguments));
    }
}
