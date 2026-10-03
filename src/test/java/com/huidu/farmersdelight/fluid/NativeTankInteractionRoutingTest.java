package com.huidu.farmersdelight.fluid;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class NativeTankInteractionRoutingTest {
    @Test void aRealNativeTankDelegationDoesNotInspectOrClaimTheHeldSoakingInput() throws Exception {
        withServer(true, () -> {
            var bridge = bridge(new Access(true));
            var manager = allocate(FluidRecipeManager.class); field(FluidRecipeManager.class, "bridge").set(manager, bridge);
            var listener = new FluidRecipeListener(null, manager);
            var player = proxy(Player.class, (method, arguments) -> { throw new AssertionError("Held recipe path was entered: " + method); });
            AtomicBoolean claimed = new AtomicBoolean();
            Method handle = FluidRecipeListener.class.getDeclaredMethod("handleInteraction", Player.class, Location.class, boolean.class, Runnable.class);
            handle.setAccessible(true);
            handle.invoke(listener, player, location(), false, (Runnable) () -> claimed.set(true));
            assertFalse(claimed.get());
        });
    }

    @Test void externalProvidersKeepTheirHandPathAndOffOwnerQueriesDoNotReachTheAdapter() throws Exception {
        Access external = new Access(false);
        withServer(true, () -> {
            var bridge = bridge(external);
            assertFalse(bridge.nativeTankAt(location()));
            assertTrue(bridge.storageAt(location()));
        });
        Access inaccessible = new Access(true);
        withServer(false, () -> {
            assertFalse(bridge(inaccessible).nativeTankAt(location()));
            assertEquals(0, inaccessible.nativeQueries);
        });
        assertFalse(new ExternalAccess().isNativeTank(location()));
    }

    private static final class Access extends ExternalAccess {
        private final boolean nativeTank;
        int nativeQueries;
        private Access(boolean nativeTank) { this.nativeTank = nativeTank; }
        @Override public boolean isNativeTank(Location location) { nativeQueries++; return nativeTank; }
    }
    private static class ExternalAccess implements FluidCoreAccess {
        public boolean storageAt(Location location) { return true; }
        public void validate(FluidRecipeSpec recipe) { }
        public void attach(FluidRecipeManager manager) { }
        public void close() { }
        public FluidCoreBridge.Outcome process(Player player, int slot, Location location, FluidRecipeSpec recipe, boolean simulate) {
            throw new AssertionError("Routing must not process a hand recipe");
        }
    }
    private static FluidCoreBridge bridge(FluidCoreAccess access) throws Exception {
        var bridge = new FluidCoreBridge(allocate(FarmersDelightPlugin.class));
        field(FluidCoreBridge.class, "api").set(bridge, access);
        field(FluidCoreBridge.class, "boundPlugin").set(bridge, enabledPlugin);
        return bridge;
    }
    private static Plugin enabledPlugin;
    private static void withServer(boolean owned, CheckedRunnable task) throws Exception {
        Field server = field(Bukkit.class, "server"); Object previous = server.get(null);
        enabledPlugin = proxy(Plugin.class, (method, arguments) -> switch (method.getName()) {
            case "isEnabled" -> true;
            default -> throw new AssertionError("Unexpected plugin call " + method);
        });
        PluginManager manager = proxy(PluginManager.class, (method, arguments) -> switch (method.getName()) {
            case "getPlugin" -> enabledPlugin;
            default -> throw new AssertionError("Unexpected plugin-manager call " + method);
        });
        server.set(null, proxy(Server.class, (method, arguments) -> switch (method.getName()) {
            case "getPluginManager" -> manager;
            case "isOwnedByCurrentRegion" -> owned;
            default -> throw new AssertionError("Unexpected server call " + method);
        }));
        try { task.run(); } finally { server.set(null, previous); enabledPlugin = null; }
    }
    private static Location location() {
        return new Location(proxy(World.class, (method, arguments) -> { throw new AssertionError("World was queried: " + method); }), 1, 64, 1);
    }
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Object instance = field(unsafe, "theUnsafe").get(null);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance, type));
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T> T proxy(Class<T> type, Calls handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (instance, method, arguments) -> handler.invoke(method, arguments)));
    }
    @FunctionalInterface private interface Calls { Object invoke(Method method, Object[] arguments) throws Throwable; }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
