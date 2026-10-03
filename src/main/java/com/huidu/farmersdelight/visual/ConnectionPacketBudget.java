package com.huidu.farmersdelight.visual;

/** Each connection has an independent limit; time is supplied by the caller for reproducible tests. */
final class ConnectionPacketBudget {
    private final long windowNanos;
    private long window = Long.MIN_VALUE;
    private int used;
    ConnectionPacketBudget(long windowNanos) {
        if (windowNanos <= 0) throw new IllegalArgumentException("Packet budget window must be positive");
        this.windowNanos = windowNanos;
    }
    synchronized boolean acquire(long now, int limit) {
        long current = now / windowNanos;
        if (window != current) { window = current; used = 0; }
        if (limit <= 0 || used >= limit) return false;
        used++;
        return true;
    }
}
