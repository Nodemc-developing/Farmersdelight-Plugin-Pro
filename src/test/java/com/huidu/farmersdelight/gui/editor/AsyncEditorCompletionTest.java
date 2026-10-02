package com.huidu.farmersdelight.gui.editor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AsyncEditorCompletionTest {
    @Test void workerCompletionDoesNotChangeTheMenuUntilTheOwnerRunsIt() {
        Thread owner = Thread.currentThread();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
        List<Boolean> results = new ArrayList<>();
        AsyncEditorCompletion.await(future, scheduled::add, result -> {
            assertSame(owner, Thread.currentThread());
            results.add(result);
        });
        CompletableFuture.runAsync(() -> future.complete(true)).join();
        assertTrue(results.isEmpty());
        assertEquals(1, scheduled.size());
        scheduled.remove().run();
        assertEquals(List.of(true), results);
        assertFalse(future.complete(false));
        assertTrue(scheduled.isEmpty());
    }

    @Test void fileFailureAndFalseResultReportFailureOnTheOwner() {
        for (CompletableFuture<Boolean> future : List.of(CompletableFuture.completedFuture(false),
                CompletableFuture.<Boolean>failedFuture(new IllegalStateException("Disk write rejected")))) {
            Queue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
            List<Boolean> results = new ArrayList<>();
            AsyncEditorCompletion.await(future, scheduled::add, results::add);
            assertTrue(results.isEmpty());
            scheduled.remove().run();
            assertEquals(List.of(false), results);
        }
    }

    @Test void retiredOwnerCannotFallBackToUpdatingTheMenuFromTheWorker() {
        AtomicBoolean changedMenu = new AtomicBoolean();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        AsyncEditorCompletion.await(future, action -> { throw new RejectedExecutionException("Retired player"); },
                result -> changedMenu.set(true));
        assertDoesNotThrow(() -> CompletableFuture.runAsync(() -> future.complete(true)).join());
        assertFalse(changedMenu.get());
    }
}
