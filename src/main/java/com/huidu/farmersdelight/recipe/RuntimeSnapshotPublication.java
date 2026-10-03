package com.huidu.farmersdelight.recipe;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Publishes one immutable recipe catalog; staging and read scopes never cross task boundaries. */
public final class RuntimeSnapshotPublication {
    private record Catalog(Map<Object, Object> slots, long generation) { }
    private static final AtomicReference<Catalog> CURRENT = new AtomicReference<>(new Catalog(Map.of(), 0));
    private static final AtomicReference<Map<Object, Object>> INITIALS = new AtomicReference<>(Map.of());
    private static final ThreadLocal<IdentityHashMap<Object, Object>> DRAFT = new ThreadLocal<>();
    private static final ThreadLocal<Catalog> READ = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<Object, Object>> RECOVERY = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> RESTORING = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<Object, Runnable>> COMMIT_HOOKS = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<Object, Runnable>> RECOVERY_HOOKS = new ThreadLocal<>();
    private static final System.Logger LOGGER = System.getLogger(RuntimeSnapshotPublication.class.getName());

    private RuntimeSnapshotPublication() { }
    public static boolean isStaging() { return DRAFT.get() != null; }
    public static long generation() {
        Catalog captured = READ.get();
        return (captured == null ? CURRENT.get() : captured).generation();
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object owner, T fallback) {
        Map<Object, Object> draft = DRAFT.get();
        if (draft != null && draft.containsKey(owner)) return (T) draft.get(owner);
        Catalog captured = READ.get();
        Object value = (captured == null ? CURRENT.get() : captured).slots().get(owner);
        if (value == null) value = INITIALS.get().get(owner);
        return value == null ? fallback : (T) value;
    }

    /** Previous is enrolled before the compatibility field changes, including the first reload. */
    public static <T> void publish(Object owner, T previous, T next) {
        java.util.Objects.requireNonNull(owner);
        java.util.Objects.requireNonNull(next);
        INITIALS.updateAndGet(initial -> {
            if (initial.containsKey(owner)) return initial;
            IdentityHashMap<Object, Object> copied = new IdentityHashMap<>(initial);
            copied.put(owner, previous);
            return Collections.unmodifiableMap(copied);
        });
        Map<Object, Object> draft = DRAFT.get();
        if (draft != null) {
            CURRENT.updateAndGet(current -> current.slots().containsKey(owner) ? current : replace(current, owner, previous));
            draft.put(owner, next);
            if (Boolean.TRUE.equals(RESTORING.get())) RECOVERY.get().put(owner, next);
        } else CURRENT.updateAndGet(current -> replace(current, owner, next));
    }

    public static void remove(Object owner) {
        INITIALS.updateAndGet(initial -> {
            IdentityHashMap<Object, Object> next = new IdentityHashMap<>(initial); next.remove(owner);
            return Collections.unmodifiableMap(next);
        });
        CURRENT.updateAndGet(current -> {
            IdentityHashMap<Object, Object> next = new IdentityHashMap<>(current.slots());
            next.remove(owner);
            return new Catalog(Collections.unmodifiableMap(next), current.generation() + 1);
        });
    }

    /** Called after plugin-owned workers and consumers stop. */
    public static void clear() { CURRENT.set(new Catalog(Map.of(), 0)); INITIALS.set(Map.of()); clearStaging(); READ.remove(); }

    /** Runs once per identity key after publication; recovery uses a separate set of callbacks. */
    public static void afterCommit(Object key, Runnable action) {
        java.util.Objects.requireNonNull(key);
        java.util.Objects.requireNonNull(action);
        if (DRAFT.get() == null) {
            runHooks(Map.of(key, action));
            return;
        }
        (Boolean.TRUE.equals(RESTORING.get()) ? RECOVERY_HOOKS.get() : COMMIT_HOOKS.get()).put(key, action);
    }

    private static Catalog replace(Catalog current, Object owner, Object next) {
        IdentityHashMap<Object, Object> copy = new IdentityHashMap<>(current.slots());
        copy.put(owner, next);
        return new Catalog(Collections.unmodifiableMap(copy), current.generation() + 1);
    }

    public static void transaction(Runnable publication, Runnable... restore) {
        if (DRAFT.get() != null) {
            try { publication.run(); }
            catch (RuntimeException | Error failure) {
                restore(failure, restore);
                throw failure;
            }
            return;
        }
        IdentityHashMap<Object, Object> draft = new IdentityHashMap<>();
        DRAFT.set(draft);
        RECOVERY.set(new IdentityHashMap<>());
        COMMIT_HOOKS.set(new IdentityHashMap<>());
        RECOVERY_HOOKS.set(new IdentityHashMap<>());
        Map<Object, Runnable> committedHooks = Map.of();
        try {
            publication.run();
            CURRENT.updateAndGet(current -> {
                IdentityHashMap<Object, Object> merged = new IdentityHashMap<>(current.slots());
                merged.putAll(draft);
                return new Catalog(Collections.unmodifiableMap(merged), current.generation() + 1);
            });
            committedHooks = COMMIT_HOOKS.get();
        } catch (RuntimeException | Error failure) {
            restore(failure, restore);
            Map<Object, Object> recovered = RECOVERY.get();
            if (!recovered.isEmpty()) CURRENT.updateAndGet(current -> {
                IdentityHashMap<Object, Object> complete = new IdentityHashMap<>(current.slots());
                complete.putAll(recovered);
                return new Catalog(Collections.unmodifiableMap(complete), current.generation() + 1);
            });
            committedHooks = RECOVERY_HOOKS.get();
            throw failure;
        } finally {
            clearStaging();
            runHooks(committedHooks);
        }
    }

    private static void clearStaging() {
        DRAFT.remove(); RECOVERY.remove(); RESTORING.remove(); COMMIT_HOOKS.remove(); RECOVERY_HOOKS.remove();
    }

    private static void runHooks(Map<Object, Runnable> hooks) {
        if (hooks.isEmpty()) return;
        Catalog previousRead = READ.get();
        READ.remove();
        try {
            for (Runnable action : hooks.values()) {
                try { action.run(); }
                catch (RuntimeException | Error failure) {
                    LOGGER.log(System.Logger.Level.WARNING, "Recipe publication callback failed after catalog commit", failure);
                }
            }
        } finally { if (previousRead == null) READ.remove(); else READ.set(previousRead); }
    }

    private static void restore(Throwable failure, Runnable[] restore) {
        Boolean previous = RESTORING.get();
        RESTORING.set(true);
        try {
            for (int i = restore.length - 1; i >= 0; i--) {
                try { restore[i].run(); }
                catch (RuntimeException | Error recovery) { if (recovery != failure) failure.addSuppressed(recovery); }
            }
        } finally { if (previous == null) RESTORING.remove(); else RESTORING.set(previous); }
    }

    /** Capture once around an operation using more than one recipe manager or tag registry. */
    public static ReadScope readScope() {
        Catalog previous = READ.get();
        if (previous == null) READ.set(CURRENT.get());
        return new ReadScope(previous);
    }

    public static final class ReadScope implements AutoCloseable {
        private final Catalog previous;
        private boolean closed;
        private ReadScope(Catalog previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) READ.remove(); else READ.set(previous);
        }
    }
}
