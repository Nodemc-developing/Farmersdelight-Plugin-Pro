package com.huidu.farmersdelight;

import com.huidu.farmersdelight.recipe.RecipePublicationTransaction;
import com.huidu.farmersdelight.recipe.RuntimeSnapshotPublication;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineRecipeCommitTest {
    @Test void ceReloadObserversSeeTheCommittedManagersAndIndexOnTheirOwnerTask() {
        Object recipes = new Object(), index = new Object();
        RuntimeSnapshotPublication.publish(recipes, "old", "old");
        RuntimeSnapshotPublication.publish(index, "old", "old");
        var owner = new ArrayDeque<Runnable>();
        var publication = new CompletableFuture<Void>();
        var generation = new AtomicLong(4);
        var calls = new AtomicInteger();
        var ownerActive = new AtomicBoolean();
        CraftEngineReadinessCoordinator.afterRecipeCommit(publication, 4, generation::get, () -> true,
                owner::add, () -> {
                    assertTrue(ownerActive.get());
                    assertFalse(RuntimeSnapshotPublication.isStaging());
                    assertEquals("new", RuntimeSnapshotPublication.get(recipes, "missing"));
                    assertEquals("new", RuntimeSnapshotPublication.get(index, "missing"));
                    calls.incrementAndGet();
                }, failure -> fail(failure));
        try {
            RecipePublicationTransaction.run(() -> {
                RuntimeSnapshotPublication.publish(recipes, "old", "new");
                assertTrue(owner.isEmpty());
                assertEquals(0, calls.get());
                RuntimeSnapshotPublication.publish(index, "old", "new");
            });
            publication.complete(null);
            assertEquals(0, calls.get());
            ownerActive.set(true);
            owner.removeFirst().run();
            assertEquals(1, calls.get());
            assertTrue(owner.isEmpty());
        } finally { RuntimeSnapshotPublication.remove(recipes); RuntimeSnapshotPublication.remove(index); }
    }

    @Test void newerCeReloadSuppressesThePreviousGenerationsQueuedWarmup() {
        var owner = new ArrayDeque<Runnable>();
        var generation = new AtomicLong(1);
        var calls = new AtomicInteger();
        var old = new CompletableFuture<Void>();
        CraftEngineReadinessCoordinator.afterRecipeCommit(old, 1, generation::get, () -> true,
                owner::add, calls::incrementAndGet, failure -> fail(failure));
        old.complete(null);
        generation.incrementAndGet();
        owner.removeFirst().run();
        assertEquals(0, calls.get());
        var current = new CompletableFuture<Void>();
        CraftEngineReadinessCoordinator.afterRecipeCommit(current, 2, generation::get, () -> true,
                owner::add, calls::incrementAndGet, failure -> fail(failure));
        current.complete(null);
        owner.removeFirst().run();
        assertEquals(1, calls.get());
    }

    @Test void publicationFailureRetainsThePreviousApiCatalogAndDoesNotRunWarmup() {
        Object index = new Object();
        RuntimeSnapshotPublication.publish(index, "old", "old");
        var owner = new ArrayDeque<Runnable>();
        var publication = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var reported = new AtomicReference<Throwable>();
        var error = new IllegalArgumentException("Bad recipe item");
        CraftEngineReadinessCoordinator.afterRecipeCommit(publication, 9, () -> 9, () -> true,
                owner::add, calls::incrementAndGet, reported::set);
        try {
            assertSame(error, assertThrows(IllegalArgumentException.class, () -> RecipePublicationTransaction.run(() -> {
                RuntimeSnapshotPublication.publish(index, "old", "partial");
                throw error;
            })));
            publication.completeExceptionally(error);
            owner.removeFirst().run();
            assertSame(error, reported.get());
            assertEquals(0, calls.get());
            assertEquals("old", RuntimeSnapshotPublication.get(index, "missing"));
        } finally { RuntimeSnapshotPublication.remove(index); }
    }

    @Test void disableBeforeOwnerDispatchSuppressesBothSuccessAndFailureObservers() {
        for (boolean failure : new boolean[] {false, true}) {
            var owner = new ArrayDeque<Runnable>();
            var enabled = new AtomicBoolean(true);
            var calls = new AtomicInteger();
            var publication = new CompletableFuture<Void>();
            CraftEngineReadinessCoordinator.afterRecipeCommit(publication, 1, () -> 1, enabled::get,
                    owner::add, calls::incrementAndGet, error -> calls.incrementAndGet());
            if (failure) publication.completeExceptionally(new IllegalStateException("Stopped"));
            else publication.complete(null);
            enabled.set(false);
            owner.removeFirst().run();
            assertEquals(0, calls.get());
        }
    }
}
