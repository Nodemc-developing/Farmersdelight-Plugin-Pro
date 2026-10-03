package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GenerationScopedCacheTest {
    @Test void oldScopedLoadersCannotOverwriteACommittedGenerationEvenAfterTheySeeTheNewMap() throws Exception {
        Object owner = new Object(); RuntimeSnapshotPublication.publish(owner, "old", "old");
        var cache = new GenerationScopedCache<String, String>();
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        long oldGeneration = RuntimeSnapshotPublication.generation();
        Thread render = new Thread(() -> {
            try (var scope = RuntimeSnapshotPublication.readScope()) {
                assertEquals("old", cache.computeIfAbsent("same-tag", ignored -> {
                    started.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                    return "old";
                }));
                assertEquals(oldGeneration, RuntimeSnapshotPublication.generation());
                // This render now accesses the replacement map while retaining the earlier recipe scope.
                assertEquals("old-late", cache.computeIfAbsent("same-tag", ignored -> "old-late"));
            } catch (Throwable problem) { failure.set(problem); }
        });
        try {
            render.start(); assertTrue(started.await(5, TimeUnit.SECONDS));
            RuntimeSnapshotPublication.transaction(() -> RuntimeSnapshotPublication.publish(owner, "old", "new"));
            cache.clear();
            assertEquals("new", cache.computeIfAbsent("same-tag", ignored -> "new"));
            release.countDown(); render.join(5000);
            assertFalse(render.isAlive()); assertNull(failure.get());
            assertEquals("new", cache.computeIfAbsent("same-tag", ignored -> fail("New generation must remain cached")));
        } finally { release.countDown(); RuntimeSnapshotPublication.remove(owner); }
    }

    @Test void listDisplayInvalidationReplacesTheMapAndKeepsLateOldScopeKeysSeparate() throws Exception {
        Object owner = new Object(); RuntimeSnapshotPublication.publish(owner, "old", "old");
        ItemStack oldIcon = uninitializedItem(), newIcon = uninitializedItem();
        RecipeViewCache.clearDisplay();
        var before = RecipeViewCache.displayCache();
        String suffix = "pot|group|6|recipe-id|zh_cn";
        try (var oldScope = RuntimeSnapshotPublication.readScope()) {
            String oldKey = RecipeViewCache.scopedDisplayKey(suffix);
            Thread publication = new Thread(() -> {
                RuntimeSnapshotPublication.transaction(() -> RuntimeSnapshotPublication.publish(owner, "old", "new"));
                RecipeViewCache.clearDisplay();
                RecipeViewCache.displayCache().put(RecipeViewCache.scopedDisplayKey(suffix), newIcon);
            });
            publication.start(); publication.join(5000); assertFalse(publication.isAlive());
            assertNotSame(before, RecipeViewCache.displayCache());
            before.put(oldKey, oldIcon);
            RecipeViewCache.displayCache().put(oldKey, oldIcon);
        }
        try {
            assertSame(newIcon, RecipeViewCache.displayCache().get(RecipeViewCache.scopedDisplayKey(suffix)));
        } finally { RecipeViewCache.clearDisplay(); RuntimeSnapshotPublication.remove(owner); }
    }

    private static ItemStack uninitializedItem() throws Exception {
        // These sentinels only enter maps; no item methods or Bukkit initialization are needed.
        Class<?> type = Class.forName("sun.misc.Unsafe");
        var singleton = type.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        return (ItemStack) type.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), ItemStack.class);
    }
}
