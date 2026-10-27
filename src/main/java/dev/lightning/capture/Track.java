package dev.lightning.capture;

import java.util.IdentityHashMap;
import java.util.function.Supplier;

/**
 * Per-thread recording state. Only the owning thread writes to a track while the capture is
 * active; full buffers are handed to the {@link EventWriter} through the capture's queue.
 *
 * <p>The hot path is a nanoTime call plus two array stores. Nothing here allocates except
 * when a buffer fills (one small handoff object) or a new section name is first seen.
 */
public final class Track {
    public static final int MAX_DEPTH = 256;

    final Capture capture;
    public final int index;
    public final Thread thread;
    public final String threadName;
    public final long threadId;

    long[] buf;
    int pos;
    /** Sequence number of the first event in {@link #buf}. */
    long baseSeq;
    long lostEvents;
    private volatile boolean retired;
    private long finalCount;

    /** Events recorded by this thread (valid after the capture stopped). */
    public long emitted() {
        return finalCount;
    }

    public long lostEvents() {
        return lostEvents;
    }

    int depth;
    /** Whether the GPU timer marked the section open at each depth. */
    final boolean[] gpuMarked = new boolean[MAX_DEPTH];

    // Identity cache for section names: most names are string constants.
    private String[] cacheKeys = new String[512];
    private int[] cacheVals = new int[512];
    private int cacheSize;

    private final IdentityHashMap<Class<?>, SupplierStats> suppliers = new IdentityHashMap<>();

    public final WorldStats world;
    final long[] entityStart = new long[16];
    int entityDepth;

    /** Non-null only on the client render thread. */
    GpuTimer gpu;

    Track(Capture capture, int index, Thread thread) {
        this.capture = capture;
        this.index = index;
        this.thread = thread;
        this.threadName = thread.getName();
        this.threadId = thread.threadId();
        this.world = new WorldStats();
        this.buf = capture.pool.take();
    }

    // --- event emission -------------------------------------------------------------------

    long emit(int kind, int payload, long value) {
        long[] b = buf;
        int p = pos;
        if (p >= b.length) {
            b = rotate();
            p = pos;
        }
        b[p] = value;
        b[p + 1] = ((long) kind << 32) | (payload & 0xFFFFFFFFL);
        pos = p + 2;
        return baseSeq + (p >> 1);
    }

    private long[] rotate() {
        if (retired) {
            pos = 0;
            return buf;
        }
        long[] next = capture.pool.take();
        if (next == null) {
            // Writer is behind and the memory budget is spent: drop this buffer's contents and
            // start the reused buffer with a LOSS marker so the analyzer resets this thread.
            lostEvents += pos >> 1;
            baseSeq += pos >> 1;
            buf[0] = System.nanoTime();
            buf[1] = (long) EventKind.LOSS << 32;
            pos = 2;
            return buf;
        }
        capture.publish(this, buf, pos, baseSeq);
        baseSeq += pos >> 1;
        buf = next;
        pos = 0;
        return buf;
    }

    /** Hands the partially filled buffer to the writer. Called once, after recording stopped. */
    void flushFinal() {
        long[] last = buf;
        int length = pos;
        // A thread that fetched this track just before recording stopped may still write a few
        // events; give it a scratch buffer that is never published.
        buf = new long[64];
        pos = 0;
        retired = true;
        finalCount = baseSeq + (length >> 1);
        if (length > 0) capture.publish(this, last, length, baseSeq);
    }

    // --- sections -------------------------------------------------------------------------

    public void push(String name) {
        int id = nameId(name);
        long seq = emit(EventKind.PUSH, id, System.nanoTime());
        int d = depth;
        if (d < MAX_DEPTH) {
            GpuTimer g = gpu;
            gpuMarked[d] = g != null && g.begin(this, d, id, seq);
        }
        depth = d + 1;
    }

    public void push(Supplier<String> supplier) {
        push(supplierName(supplier));
    }

    /** Sections pushed by other mods' hooks (Iris, Voxy), which may not be perfectly balanced. */
    private final boolean[] compat = new boolean[MAX_DEPTH];

    void pushCompat(String name) {
        push(name);
        int d = depth - 1;
        if (d >= 0 && d < MAX_DEPTH) compat[d] = true;
    }

    /** Closes the innermost section only if a hook opened it; a stray end is ignored. */
    void popCompat() {
        int d = depth - 1;
        if (d >= 0 && d < MAX_DEPTH && compat[d]) {
            compat[d] = false;
            popSection();
        }
    }

    /** A Minecraft profiler pop also closes any hook sections left open inside it. */
    public void pop() {
        while (depth > 0 && depth - 1 < MAX_DEPTH && compat[depth - 1]) {
            compat[depth - 1] = false;
            popSection();
        }
        popSection();
    }

    private void popSection() {
        int d = depth - 1;
        if (d >= 0 && d < MAX_DEPTH && gpuMarked[d]) {
            gpuMarked[d] = false;
            gpu.end(this);
        }
        emit(EventKind.POP, 0, System.nanoTime());
        depth = Math.max(0, d);
    }

    public void tickStart() {
        java.util.Arrays.fill(compat, 0, Math.min(depth, MAX_DEPTH), false);
        depth = 0;
        long seq = emit(EventKind.TICK_START, 0, System.nanoTime());
        GpuTimer g = gpu;
        if (g != null) g.frameStart(this, seq);
    }

    public void tickEnd() {
        GpuTimer g = gpu;
        if (g != null) g.frameEnd(this);
        emit(EventKind.TICK_END, 0, System.nanoTime());
        java.util.Arrays.fill(compat, 0, Math.min(depth, MAX_DEPTH), false);
        depth = 0;
    }

    int nameId(String name) {
        if (name == null) name = "(null)";
        String[] keys = cacheKeys;
        int mask = keys.length - 1;
        int i = System.identityHashCode(name) & mask;
        while (true) {
            String k = keys[i];
            if (k == name) return cacheVals[i];
            if (k == null) break;
            i = (i + 1) & mask;
        }
        int id = capture.names.id(name);
        if (cacheSize * 2 >= keys.length) {
            if (keys.length >= 1 << 16) {
                // Unbounded distinct strings: stop caching rather than growing forever.
                return id;
            }
            growCache();
            keys = cacheKeys;
            mask = keys.length - 1;
            i = System.identityHashCode(name) & mask;
            while (keys[i] != null) i = (i + 1) & mask;
        }
        keys[i] = name;
        cacheVals[i] = id;
        cacheSize++;
        return id;
    }

    private void growCache() {
        String[] oldKeys = cacheKeys;
        int[] oldVals = cacheVals;
        cacheKeys = new String[oldKeys.length * 2];
        cacheVals = new int[oldKeys.length * 2];
        int mask = cacheKeys.length - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            String k = oldKeys[j];
            if (k == null) continue;
            int i = System.identityHashCode(k) & mask;
            while (cacheKeys[i] != null) i = (i + 1) & mask;
            cacheKeys[i] = k;
            cacheVals[i] = oldVals[j];
        }
    }

    /**
     * Vanilla passes lambdas for names such as entity types. Evaluating them allocates a string
     * per call, so each lambda class is evaluated only until it has produced a handful of
     * distinct values; after that it is recorded under one stable label. Per-entity and
     * per-block-entity detail comes from the dedicated world hooks instead.
     */
    private String supplierName(Supplier<String> supplier) {
        Class<?> type = supplier.getClass();
        SupplierStats stats = suppliers.get(type);
        if (stats == null) {
            stats = new SupplierStats(type);
            suppliers.put(type, stats);
        }
        if (stats.collapsed) return stats.label;
        String value = supplier.get();
        if (value == null) value = "(null)";
        if (!stats.seen.contains(value)) {
            if (stats.seen.size() >= SupplierStats.LIMIT) {
                stats.collapsed = true;
                return stats.label;
            }
            stats.seen.add(value);
        }
        return value;
    }

    private static final class SupplierStats {
        static final int LIMIT = 24;
        final java.util.HashSet<String> seen = new java.util.HashSet<>();
        final String label;
        boolean collapsed;

        SupplierStats(Class<?> type) {
            String name = type.getName();
            int lambda = name.indexOf("$$Lambda");
            if (lambda > 0) name = name.substring(0, lambda);
            int dot = name.lastIndexOf('.');
            this.label = "(varied names) " + (dot >= 0 ? name.substring(dot + 1) : name);
        }
    }
}
