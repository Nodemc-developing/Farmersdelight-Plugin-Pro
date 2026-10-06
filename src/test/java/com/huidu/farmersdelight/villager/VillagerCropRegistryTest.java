package com.huidu.farmersdelight.villager;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class VillagerCropRegistryTest {
    @Test void snapshotCannotBeMutatedThroughAnyOfItsIndexes() {
        var access = new ReadAccess();
        var crop = new VillagerCrop("test:crop", "test:seed", null, null, Material.WHEAT,
                VillagerCrop.Mode.BREAK, Set.of(), null, false, true, true, access);
        var mutableSeeds = new ArrayList<>(List.of(crop));
        Map<String, List<VillagerCrop>> source = new LinkedHashMap<>(Map.of("test:seed", mutableSeeds));
        var registry = new VillagerCropRegistry.Registry(Map.of("test:crop", crop), source, Set.of("test:seed"), access, null, Map.of());
        mutableSeeds.clear(); source.clear();

        assertEquals(List.of(crop), registry.bySeed().get("test:seed"));
        assertThrows(UnsupportedOperationException.class, () -> registry.byId().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.bySeed().get("test:seed").clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.pickupDrops().clear());
    }

    @Test void unavailableRegionIsRejectedBeforeAnyWorldLookup() {
        var access = new ReadAccess(); access.resident = false;
        var registry = new VillagerCropRegistry.Registry(Map.of(), Map.of(), Set.of(), access, null, Map.of());
        assertNull(registry.find(null));
        assertEquals(0, access.reads);
    }

    @Test void absentOptionalPluginDoesNotLinkClassesAndReportsOnlyOnce() throws Exception {
        PluginManager plugins = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                (proxy, method, args) -> method.getName().equals("getPlugin") ? null : null);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> method.getName().equals("getPluginManager") ? plugins : null);
        var field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true);
        Object previous = field.get(null);
        Logger logger = Logger.getAnonymousLogger(); logger.setUseParentHandlers(false);
        AtomicInteger warnings = new AtomicInteger();
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { warnings.incrementAndGet(); }
            public void flush() { }
            public void close() { }
        });
        try {
            field.set(null, server);
            assertNull(VillagerCropRegistry.CustomCrops.connect(logger, new ReadAccess()));
            assertNull(VillagerCropRegistry.CustomCrops.connect(logger, new ReadAccess()));
            assertEquals(1, warnings.get());
        } finally { field.set(null, previous); }
    }

    private static final class ReadAccess implements VillagerCrop.Access {
        boolean resident = true; int reads;
        public boolean resident(Block block) { return resident; }
        public ImmutableBlockState state(Block block) { reads++; throw new AssertionError("Unexpected world lookup"); }
        public BlockData data(ImmutableBlockState state) { throw new AssertionError(); }
        public BlockData air() { throw new AssertionError(); }
        public boolean place(Block block, ImmutableBlockState state) { throw new AssertionError(); }
        public boolean remove(Block block) { throw new AssertionError(); }
        public void drop(Block block, List<ItemStack> items) { throw new AssertionError(); }
    }
}
