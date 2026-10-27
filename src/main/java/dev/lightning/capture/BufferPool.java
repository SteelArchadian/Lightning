package dev.lightning.capture;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Recycles event buffers so a capture allocates a bounded amount of memory. */
final class BufferPool {
    static final int BUFFER_LONGS = 1 << 13; // 4096 events, 64 KiB

    private final ConcurrentLinkedQueue<long[]> free = new ConcurrentLinkedQueue<>();
    private final AtomicInteger allocated = new AtomicInteger();
    private final int maxBuffers;

    BufferPool(int maxBuffers) {
        this.maxBuffers = maxBuffers;
    }

    /** Returns a buffer, or null when the memory budget is spent. */
    long[] take() {
        long[] b = free.poll();
        if (b != null) return b;
        while (true) {
            int n = allocated.get();
            if (n >= maxBuffers) return null;
            if (allocated.compareAndSet(n, n + 1)) return new long[BUFFER_LONGS];
        }
    }

    void give(long[] buffer) {
        if (buffer.length == BUFFER_LONGS) free.offer(buffer);
    }

    int allocated() {
        return allocated.get();
    }
}
