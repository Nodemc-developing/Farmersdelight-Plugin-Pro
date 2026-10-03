package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;

import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;

/** Native ticker transitions execute on the owning block thread. */
public final class SleepingTickerBridge<C extends BlockEntityController> {
    private final SleepingBlockEntityTicker<C> ticker;
    private volatile boolean sleeping;
    private volatile long sleeps;
    private volatile long wakes;

    private SleepingTickerBridge(BlockEntityTicker<C> delegate) {
        ticker = new SleepingBlockEntityTicker<>(delegate);
    }

    public static boolean isSupported() {
        return true;
    }

    public static <C extends BlockEntityController> SleepingTickerBridge<C> create(BlockEntityTicker<C> delegate) {
        return new SleepingTickerBridge<>(delegate);
    }

    public BlockEntityTicker<C> ticker() { return ticker; }
    public boolean isSleeping() { return sleeping; }
    public long sleepCount() { return sleeps; }
    public long wakeCount() { return wakes; }

    public void sleep() {
        if (!sleeping) {
            ticker.sleep();
            sleeping = true;
            sleeps++;
        }
    }

    public void wakeUp() {
        if (sleeping) {
            ticker.wakeUp();
            sleeping = false;
            wakes++;
        }
    }

}
