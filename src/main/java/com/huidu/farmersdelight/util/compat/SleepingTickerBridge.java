package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/** Uses native sleep lists when available; earlier hosts skip sleeping business work. */
public final class SleepingTickerBridge<C extends BlockEntityController> {
    private static final NativeBinding NATIVE = NativeBinding.link();
    private final BlockEntityTicker<C> ticker;
    private final NativeBinding nativeBinding;
    private volatile boolean sleeping;
    private volatile long sleeps;
    private volatile long wakes;

    private SleepingTickerBridge(BlockEntityTicker<C> delegate) {
        this(delegate, NATIVE);
    }

    private SleepingTickerBridge(BlockEntityTicker<C> delegate, NativeBinding nativeBinding) {
        Objects.requireNonNull(delegate, "delegate");
        this.nativeBinding = nativeBinding;
        ticker = nativeBinding == null
                ? (world, pos, state, controller) -> {
                    if (!sleeping) delegate.tick(world, pos, state, controller);
                }
                : nativeBinding.create(delegate);
    }

    public static boolean isSupported() {
        return true;
    }

    public static <C extends BlockEntityController> SleepingTickerBridge<C> create(BlockEntityTicker<C> delegate) {
        return new SleepingTickerBridge<>(delegate);
    }

    static <C extends BlockEntityController> SleepingTickerBridge<C> legacy(BlockEntityTicker<C> delegate) {
        return new SleepingTickerBridge<>(delegate, null);
    }

    public boolean usesNativeSleepList() { return nativeBinding != null; }

    public BlockEntityTicker<C> ticker() { return ticker; }
    public boolean isSleeping() { return sleeping; }
    public long sleepCount() { return sleeps; }
    public long wakeCount() { return wakes; }

    public void sleep() {
        if (!sleeping) {
            if (nativeBinding != null) nativeBinding.transition(nativeBinding.sleep, ticker);
            sleeping = true;
            sleeps++;
        }
    }

    public void wakeUp() {
        if (sleeping) {
            if (nativeBinding != null) nativeBinding.transition(nativeBinding.wake, ticker);
            sleeping = false;
            wakes++;
        }
    }

    private record NativeBinding(MethodHandle constructor, MethodHandle sleep, MethodHandle wake) {
        static NativeBinding link() {
            Class<?> type;
            try {
                type = Class.forName("net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker");
            } catch (ClassNotFoundException olderHost) {
                return null;
            }
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new NativeBinding(
                        lookup.findConstructor(type, MethodType.methodType(void.class, BlockEntityTicker.class))
                                .asType(MethodType.methodType(BlockEntityTicker.class, BlockEntityTicker.class)),
                        lookup.findVirtual(type, "sleep", MethodType.methodType(void.class))
                                .asType(MethodType.methodType(void.class, BlockEntityTicker.class)),
                        lookup.findVirtual(type, "wakeUp", MethodType.methodType(void.class))
                                .asType(MethodType.methodType(void.class, BlockEntityTicker.class)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("CraftEngine sleeping ticker API could not be linked", failure);
            }
        }

        @SuppressWarnings("unchecked")
        <C extends BlockEntityController> BlockEntityTicker<C> create(BlockEntityTicker<C> delegate) {
            try { return (BlockEntityTicker<C>) constructor.invokeExact((BlockEntityTicker) delegate); }
            catch (Throwable failure) { throw new IllegalStateException("Could not create the native sleeping ticker", failure); }
        }

        void transition(MethodHandle method, BlockEntityTicker<?> ticker) {
            try { method.invokeExact((BlockEntityTicker) ticker); }
            catch (Throwable failure) { throw new IllegalStateException("Could not change the native ticker state", failure); }
        }
    }

}
