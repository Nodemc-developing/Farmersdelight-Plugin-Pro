package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSnapshotPublicationTest {
    @Test void workerDraftCannotExposeAPartialGeneration() throws Exception {
        Object pot = new Object(), tags = new Object();
        RuntimeSnapshotPublication.publish(pot, "old", "old");
        RuntimeSnapshotPublication.publish(tags, "old", "old");
        CountDownLatch staged = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                RecipePublicationTransaction.run(() -> {
                    RuntimeSnapshotPublication.publish(pot, "old", "new");
                    assertEquals("new", RuntimeSnapshotPublication.get(pot, "fallback"));
                    staged.countDown();
                    try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
                    RuntimeSnapshotPublication.publish(tags, "old", "new");
                });
            } catch (Throwable error) { failure.set(error); staged.countDown(); }
        });
        worker.start();
        assertTrue(staged.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try (var scope = RuntimeSnapshotPublication.readScope()) {
            assertEquals("old", RuntimeSnapshotPublication.get(pot, "fallback"));
            release.countDown(); worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals("old", RuntimeSnapshotPublication.get(tags, "fallback"));
        } finally { release.countDown(); }
        assertNull(failure.get());
        assertEquals("new", RuntimeSnapshotPublication.get(pot, "fallback"));
        assertEquals("new", RuntimeSnapshotPublication.get(tags, "fallback"));
        RuntimeSnapshotPublication.remove(pot); RuntimeSnapshotPublication.remove(tags);
    }

    @Test void failedDraftLeavesReadersOnTheCompletePreviousCatalog() {
        Object pot = new Object(), tags = new Object();
        RuntimeSnapshotPublication.publish(pot, "old", "old");
        RuntimeSnapshotPublication.publish(tags, "old", "old");
        assertThrows(IllegalArgumentException.class, () -> RecipePublicationTransaction.run(() -> {
            RuntimeSnapshotPublication.publish(pot, "old", "bad");
            RuntimeSnapshotPublication.publish(tags, "old", "bad");
            throw new IllegalArgumentException("reject");
        }));
        assertEquals("old", RuntimeSnapshotPublication.get(pot, "fallback"));
        assertEquals("old", RuntimeSnapshotPublication.get(tags, "fallback"));
        RuntimeSnapshotPublication.remove(pot); RuntimeSnapshotPublication.remove(tags);
    }

    @Test void callbacksRunOnceAfterCompleteCommitAndOutsideTheCallersPinnedReadScope() throws Exception {
        Object pot = new Object(), tags = new Object(), wake = new Object();
        RuntimeSnapshotPublication.publish(pot, "old", "old");
        RuntimeSnapshotPublication.publish(tags, "old", "old");
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> observed = new AtomicReference<>();
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        try (var pinned = RuntimeSnapshotPublication.readScope()) {
            RecipePublicationTransaction.run(() -> {
                RuntimeSnapshotPublication.publish(pot, "old", "new");
                RuntimeSnapshotPublication.afterCommit(wake, () -> fail("Superseded callback must not run"));
                RuntimeSnapshotPublication.afterCommit(wake, () -> {
                    assertFalse(RuntimeSnapshotPublication.isStaging());
                    assertEquals("new", RuntimeSnapshotPublication.get(pot, "fallback"));
                    Thread observer = new Thread(() -> {
                        try (var scope = RuntimeSnapshotPublication.readScope()) {
                            observed.set(RuntimeSnapshotPublication.get(pot, "fallback") + "/"
                                    + RuntimeSnapshotPublication.get(tags, "fallback"));
                        } catch (Throwable failure) { observerFailure.set(failure); }
                    });
                    observer.start();
                    try { observer.join(5000); } catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                    assertFalse(observer.isAlive());
                    calls.incrementAndGet();
                });
                assertEquals(0, calls.get());
                RuntimeSnapshotPublication.publish(tags, "old", "new");
            });
            assertEquals("old", RuntimeSnapshotPublication.get(pot, "fallback"));
        }
        assertNull(observerFailure.get());
        assertEquals("new/new", observed.get());
        assertEquals(1, calls.get());
        RuntimeSnapshotPublication.remove(pot); RuntimeSnapshotPublication.remove(tags);
    }

    @Test void callbackFailureDoesNotUndoTheCommittedCatalogOrSkipOtherCallbacks() {
        Object owner = new Object(); AtomicInteger calls = new AtomicInteger();
        RuntimeSnapshotPublication.publish(owner, "old", "old");
        assertDoesNotThrow(() -> RecipePublicationTransaction.run(() -> {
            RuntimeSnapshotPublication.publish(owner, "old", "new");
            RuntimeSnapshotPublication.afterCommit(new Object(), () -> { throw new IllegalStateException("callback rejected"); });
            RuntimeSnapshotPublication.afterCommit(new Object(), calls::incrementAndGet);
        }));
        assertEquals("new", RuntimeSnapshotPublication.get(owner, "fallback"));
        assertEquals(1, calls.get());
        assertFalse(RuntimeSnapshotPublication.isStaging());
        RuntimeSnapshotPublication.remove(owner);
    }

    @Test void failedDraftOnlyRunsRecoveryCallbacksAfterTheRecoveryCatalogCommits() {
        Object pot = new Object(), tags = new Object(), wake = new Object();
        RuntimeSnapshotPublication.publish(pot, "old", "old");
        RuntimeSnapshotPublication.publish(tags, "old", "old");
        AtomicInteger badCalls = new AtomicInteger(), recoveries = new AtomicInteger();
        AtomicReference<String> observed = new AtomicReference<>();
        assertThrows(IllegalArgumentException.class, () -> RecipePublicationTransaction.run(() -> {
            RuntimeSnapshotPublication.publish(pot, "old", "bad");
            RuntimeSnapshotPublication.afterCommit(wake, badCalls::incrementAndGet);
            RuntimeSnapshotPublication.publish(tags, "old", "bad");
            throw new IllegalArgumentException("reject");
        }, () -> {
            RuntimeSnapshotPublication.publish(tags, "bad", "restored-tags");
            RuntimeSnapshotPublication.afterCommit(wake, () -> {
                assertFalse(RuntimeSnapshotPublication.isStaging());
                observed.set(RuntimeSnapshotPublication.get(pot, "fallback") + "/"
                        + RuntimeSnapshotPublication.get(tags, "fallback"));
                recoveries.incrementAndGet();
            });
            RuntimeSnapshotPublication.publish(pot, "bad", "restored-pot");
        }));
        assertEquals(0, badCalls.get());
        assertEquals(1, recoveries.get());
        assertEquals("restored-pot/restored-tags", observed.get());
        RuntimeSnapshotPublication.remove(pot); RuntimeSnapshotPublication.remove(tags);
    }
}
