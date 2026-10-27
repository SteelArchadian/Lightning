package dev.lightning.capture;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Once-a-second process metrics. Cheap MXBean reads only: no heap walking, and no CPU-load
 * reading (on Windows that goes through performance counters and costs tens of milliseconds;
 * CPU comes from JFR's per-thread events instead).
 */
public final class MetricsSampler {
    public record Sample(long time, long heapUsed, long heapCommitted, long heapMax, long nonHeapUsed,
                         long directBytes, long directCount,
                         long gcCount, long gcMillis, int threads, long lightningCpuNanos, long liveAfterGc) {}

    private final List<Sample> samples = new ArrayList<>();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<BufferPoolMXBean> buffers = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
    private final List<Thread> ownThreads = new ArrayList<>();
    private final List<java.lang.management.MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    /** Threads whose CPU time counts as Lightning's own overhead. */
    synchronized void trackOwnThread(Thread thread) {
        ownThreads.add(thread);
    }

    synchronized void sample() {
        if (samples.size() >= CaptureOptions.MAX_SECONDS + 120) return;
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        long directBytes = 0, directCount = 0;
        for (BufferPoolMXBean pool : buffers) {
            if (pool.getName().equals("direct")) {
                directBytes = pool.getMemoryUsed();
                directCount = pool.getCount();
            }
        }
        long gcCount = 0, gcMillis = 0;
        for (GarbageCollectorMXBean gc : collectors) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        // Heap still in use right after each pool's last collection: the live set.
        long live = 0;
        for (java.lang.management.MemoryPoolMXBean pool : pools) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after != null) live += after.getUsed();
        }
        long own = 0;
        if (threads.isThreadCpuTimeSupported()) {
            for (Thread t : ownThreads) {
                long cpu = threads.getThreadCpuTime(t.threadId());
                if (cpu > 0) own += cpu;
            }
        }
        samples.add(new Sample(System.nanoTime(), heap.getUsed(), heap.getCommitted(), heap.getMax(), nonHeap.getUsed(),
                directBytes, directCount, gcCount, gcMillis, threads.getThreadCount(), own, live));
    }

    public synchronized List<Sample> samples() {
        return List.copyOf(samples);
    }
}
