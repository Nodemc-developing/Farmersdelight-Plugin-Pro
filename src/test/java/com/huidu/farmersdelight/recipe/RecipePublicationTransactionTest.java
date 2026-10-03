package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.util.CommonTagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecipePublicationTransactionTest {
    @AfterEach void clearSources() {
        AdvancedRecipeTags.unregisterSource("rollback-test");
        AdvancedRecipeTags.unregisterSource("unrelated-test");
        CommonTagResolver.unregisterSource("rollback-test");
        CommonTagResolver.unregisterSource("unrelated-test");
    }

    @Test void aThirdStageFailureRestoresBothManagersAndTheirParseDocuments() throws Exception {
        CookingPotRecipeManager pot = new CookingPotRecipeManager(null);
        CuttingBoardRecipeManager board = new CuttingBoardRecipeManager(null);
        Object previousPot = field(pot, "snapshot");
        Object previousBoard = field(board, "snapshot");
        YamlConfiguration oldPotDocument = new YamlConfiguration();
        YamlConfiguration oldBoardDocument = new YamlConfiguration();
        setField(pot, "lastFileDocument", oldPotDocument);
        setField(board, "lastFileDocument", oldBoardDocument);
        RecipeParseCache<CookingPotRecipe> oldPotParsing = new RecipeParseCache<>(null);
        RecipeParseCache<CuttingBoardRecipe> oldBoardParsing = new RecipeParseCache<>(null);
        setField(pot, "parsedRecipes", oldPotParsing);
        setField(board, "parsedRecipes", oldBoardParsing);
        Object candidatePot = copyRecord(previousPot, Map.of("generation", 1L));
        Object candidateBoard = copyRecord(previousBoard, Map.of());
        IllegalStateException failure = new IllegalStateException("third recipe stage failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> RecipePublicationTransaction.run(() -> {
                    setField(pot, "snapshot", candidatePot);
                    setField(pot, "publicationGeneration", 1L);
                    setField(pot, "lastFileDocument", new YamlConfiguration());
                    setField(pot, "parsedRecipes", new RecipeParseCache<>(null));
                    setField(board, "snapshot", candidateBoard);
                    setField(board, "lastFileDocument", new YamlConfiguration());
                    setField(board, "parsedRecipes", new RecipeParseCache<>(null));
                    throw failure;
                }, pot.captureReloadRollback(), board.captureReloadRollback()));

        assertSame(failure, actual);
        assertEquals(0, failure.getSuppressed().length, "All ordinary restoration stages should complete");
        Object restoredPot = field(pot, "snapshot");
        assertNotSame(previousPot, restoredPot);
        assertSame(component(previousPot, "recipes"), component(restoredPot, "recipes"));
        assertSame(component(previousPot, "customRecipes"), component(restoredPot, "customRecipes"));
        assertSame(previousBoard, field(board, "snapshot"));
        assertSame(oldPotDocument, field(pot, "lastFileDocument"));
        assertSame(oldBoardDocument, field(board, "lastFileDocument"));
        assertSame(oldPotParsing, field(pot, "parsedRecipes"));
        assertSame(oldBoardParsing, field(board, "parsedRecipes"));
        assertEquals(2, pot.recipeGeneration(), "Restored definitions need a fresh token for block-entity caches");
        assertEquals(2L, field(pot, "publicationGeneration"),
                "Both the failed publication and its restoration spend a unique generation");
    }

    @Test void oldInFlightMatchCannotRepopulateAReinstatedSnapshot() throws Exception {
        CookingPotRecipeManager manager = new CookingPotRecipeManager(null);
        CookingPotRecipeManager.Snapshot previous = (CookingPotRecipeManager.Snapshot) field(manager, "snapshot");
        Runnable restore = manager.captureReloadRollback();
        CookingPotRecipe result = new CookingPotRecipe("test:stale", List.of(), null, false, null, 0, 20, "", 0);
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var matching = worker.submit(() -> {
                long epoch = (long) field(manager, "matchCacheEpoch");
                scanning.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("match was not released");
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                manager.cacheMatch(previous, epoch, "positive", result);
                manager.cacheMatch(previous, epoch, "negative", null);
            });
            try {
                assertTrue(scanning.await(5, TimeUnit.SECONDS));
                setField(manager, "snapshot", copyRecord(previous, Map.of("generation", 1L)));
                setField(manager, "publicationGeneration", 1L);
                restore.run();
                assertNotSame(previous, field(manager, "snapshot"));
                assertEquals(2, manager.recipeGeneration());
            } finally { finish.countDown(); }
            matching.get(5, TimeUnit.SECONDS);
        }
        assertTrue(previous.recipeCache().isEmpty(), "pre-rollback results must not repopulate the reinstated LRU");
        assertTrue(previous.recipeMisses().isEmpty(), "pre-rollback misses must not suppress valid recipes");
        var restored = (CookingPotRecipeManager.Snapshot) field(manager, "snapshot");
        manager.cacheMatch(restored, (long) field(manager, "matchCacheEpoch"), "fresh", result);
        assertSame(result, previous.recipeCache().get("fresh"), "new scans can populate the restored cache");
    }

    @Test void nbtDependentInputsBypassCachesWhileUnrelatedInputsCanCache() throws Exception {
        var item = new RecipeIngredient.Item(net.momirealms.craftengine.core.util.Key.of("minecraft:paper"));
        var precise = new RecipeIngredient.Item(item.key(), "full-stack-snapshot");
        var nested = new RecipeIngredient.Choice(List.of(item, new RecipeIngredient.Choice(List.of(precise))));
        var normal = new CookingPotRecipe("test:ordinary", List.of(item), null, false, null, 0, 20, "", 0);
        var sensitive = new CookingPotRecipe("test:precise", List.of(nested), null, false, null, 0, 20, "", 0);
        assertFalse(CookingPotRecipeManager.hasNbtSensitiveIngredients(List.of(normal)));
        assertTrue(CookingPotRecipeManager.hasNbtSensitiveIngredients(List.of(normal, sensitive)));
        CookingPotRecipeManager manager = new CookingPotRecipeManager(null);
        var previous = (CookingPotRecipeManager.Snapshot) field(manager, "snapshot");
        var dependencies = NbtMatchDependencies.compile(List.of(normal, sensitive), Map.of());
        var preciseView = (CookingPotRecipeManager.Snapshot) copyRecord(previous, Map.of("nbtDependencies", dependencies));
        setField(manager, "snapshot", preciseView);
        assertTrue(dependencies.dependsOn("minecraft:paper", null));
        manager.cacheMatch(preciseView, 0, null, sensitive);
        manager.cacheMatch(preciseView, 0, null, null);
        assertTrue(preciseView.recipeCache().isEmpty());
        assertTrue(preciseView.recipeMisses().isEmpty());
        assertFalse(dependencies.dependsOn("minecraft:carrot", null));
        manager.cacheMatch(preciseView, 0, "unrelated-carrot", normal);
        assertSame(normal, preciseView.recipeCache().get("unrelated-carrot"));
    }

    @Test void recoveryRunsInReverseOrderAndContinuesAfterARecoveryFailure() {
        List<Integer> restored = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("publication failed");
        IllegalArgumentException recovery = new IllegalArgumentException("second recovery failed");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> RecipePublicationTransaction.run(() -> { throw failure; },
                        () -> restored.add(1),
                        () -> { restored.add(2); throw recovery; },
                        () -> restored.add(3))));

        assertEquals(List.of(3, 2, 1), restored);
        assertArrayEquals(new Throwable[]{recovery}, failure.getSuppressed());
    }

    @Test void successfulPublicationKeepsTheNewState() {
        AtomicInteger state = new AtomicInteger(7);
        RecipePublicationTransaction.run(() -> state.set(9), () -> state.set(7));
        assertEquals(9, state.get());
    }

    @Test void tagRecoveryPreservesUnrelatedRegistrationsAndReverseLookups() {
        AdvancedRecipeTags.registerSource("rollback-test", Map.of("test:food", List.of("minecraft:carrot")));
        CommonTagResolver.registerSource("rollback-test", Map.of("test:food", List.of("minecraft:carrot")));
        Runnable advanced = AdvancedRecipeTags.captureSourceRollback("rollback-test");
        Runnable common = CommonTagResolver.captureSourceRollback("rollback-test");

        assertThrows(IllegalStateException.class, () -> RecipePublicationTransaction.run(() -> {
            AdvancedRecipeTags.registerSource("rollback-test", Map.of("test:food", List.of("minecraft:potato")));
            CommonTagResolver.registerSource("rollback-test", Map.of("test:food", List.of("minecraft:potato")));
            AdvancedRecipeTags.registerSource("unrelated-test", Map.of("test:new", List.of("minecraft:apple")));
            CommonTagResolver.registerSource("unrelated-test", Map.of("test:new", List.of("minecraft:apple")));
            throw new IllegalStateException("recipe stage failed");
        }, advanced, common));

        assertEquals(Set.of("minecraft:carrot"), AdvancedRecipeTags.members("test:food"));
        assertEquals(Set.of("test:food"), AdvancedRecipeTags.tagsForItemId("minecraft:carrot"));
        assertTrue(AdvancedRecipeTags.tagsForItemId("minecraft:potato").isEmpty());
        assertEquals(Set.of("minecraft:apple"), AdvancedRecipeTags.members("test:new"));
        assertEquals(Set.of("minecraft:carrot"), CommonTagResolver.getMembers("test:food"));
        assertEquals(Set.of("test:food"), CommonTagResolver.getTagsForItemId("minecraft:carrot"));
        assertTrue(CommonTagResolver.getTagsForItemId("minecraft:potato").isEmpty());
        assertEquals(Set.of("minecraft:apple"), CommonTagResolver.getMembers("test:new"));
    }

    @Test void aNewTagSourceIsRemovedOnFailure() {
        Runnable advanced = AdvancedRecipeTags.captureSourceRollback("rollback-test");
        Runnable common = CommonTagResolver.captureSourceRollback("rollback-test");
        assertThrows(IllegalStateException.class, () -> RecipePublicationTransaction.run(() -> {
            AdvancedRecipeTags.registerSource("rollback-test", Map.of("test:new", List.of("minecraft:carrot")));
            CommonTagResolver.registerSource("rollback-test", Map.of("test:new", List.of("minecraft:carrot")));
            throw new IllegalStateException("recipe stage failed");
        }, advanced, common));
        assertTrue(AdvancedRecipeTags.members("test:new").isEmpty());
        assertTrue(CommonTagResolver.getMembers("test:new").isEmpty());
    }

    @Test void specialRecipeRecoveryRestoresExactCardInstances() {
        SpecialRecipeRegistry registry = new SpecialRecipeRegistry();
        SpecialRecipeInfo previous = new SpecialRecipeInfo("test:old", "old", "minecraft:carrot",
                List.of(), List.of(), List.of(), false, false, false, List.of());
        registry.register(previous);
        Runnable restore = registry.captureReloadRollback();
        assertThrows(IllegalStateException.class, () -> RecipePublicationTransaction.run(() -> {
            registry.clear();
            registry.register(new SpecialRecipeInfo("test:new", "new", "minecraft:potato",
                    List.of(), List.of(), List.of(), false, false, false, List.of()));
            throw new IllegalStateException("recipe stage failed");
        }, restore));
        assertSame(previous, registry.get("test:old"));
        assertNull(registry.get("test:new"));
    }

    private static Object component(Object record, String name) {
        try {
            var accessor = record.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(record);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Object copyRecord(Object source, Map<String, Object> changes) throws Exception {
        RecordComponent[] components = source.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; ++i) {
            types[i] = components[i].getType();
            var accessor = components[i].getAccessor();
            accessor.setAccessible(true);
            values[i] = changes.containsKey(components[i].getName())
                    ? changes.get(components[i].getName()) : accessor.invoke(source);
        }
        Constructor<?> constructor = source.getClass().getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }
}
