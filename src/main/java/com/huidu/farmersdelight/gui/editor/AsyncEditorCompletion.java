package com.huidu.farmersdelight.gui.editor;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Persistence completions only change menus after returning to the viewer's owner. */
final class AsyncEditorCompletion {
    private AsyncEditorCompletion() { }

    static void await(CompletableFuture<Boolean> future, Consumer<Runnable> ownerExecutor,
                      Consumer<Boolean> completion) {
        future.whenComplete((saved, failure) -> {
            try {
                ownerExecutor.accept(() -> completion.accept(failure == null && Boolean.TRUE.equals(saved)));
            } catch (RuntimeException unavailable) {
                // A retired player or stopped plugin has no owner on which to update its former menu.
            }
        });
    }
}
