package dev.lightning.analysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Aggregated section tree of one thread: exact call counts and durations per call path. */
public final class SpanTree {
    public static final int MAX_NODES = 50_000;
    /** Log-scale latency histogram: two buckets per power of two, from 1 µs. */
    static final int BUCKETS = 48;

    int size = 1;
    int[] parent = new int[256];
    int[] name = new int[256];
    int[] depth = new int[256];
    long[] calls = new long[256];
    long[] total = new long[256];
    long[] childTotal = new long[256];
    long[] max = new long[256];
    long[] gpuTotal = new long[256];
    long[] gpuCalls = new long[256];
    long[] gpuMax = new long[256];
    long[] samples = new long[256];
    int[] hist = new int[256 * BUCKETS];
    private final Map<Long, Integer> children = new HashMap<>();
    long overflow;

    SpanTree() {
        parent[0] = -1;
        name[0] = -1;
        depth[0] = -1;
    }

    public int size() {
        return size;
    }

    int child(int node, int nameId) {
        long key = ((long) node << 32) | (nameId & 0xFFFFFFFFL);
        Integer existing = children.get(key);
        if (existing != null) return existing;
        if (size >= MAX_NODES) {
            overflow++;
            return node;
        }
        if (size == parent.length) grow(size * 2);
        int id = size++;
        parent[id] = node;
        name[id] = nameId;
        depth[id] = depth[node] + 1;
        children.put(key, id);
        return id;
    }

    private void grow(int n) {
        parent = Arrays.copyOf(parent, n);
        name = Arrays.copyOf(name, n);
        depth = Arrays.copyOf(depth, n);
        calls = Arrays.copyOf(calls, n);
        total = Arrays.copyOf(total, n);
        childTotal = Arrays.copyOf(childTotal, n);
        max = Arrays.copyOf(max, n);
        gpuTotal = Arrays.copyOf(gpuTotal, n);
        gpuCalls = Arrays.copyOf(gpuCalls, n);
        gpuMax = Arrays.copyOf(gpuMax, n);
        samples = Arrays.copyOf(samples, n);
        hist = Arrays.copyOf(hist, n * BUCKETS);
    }

    void record(int node, long nanos) {
        calls[node]++;
        total[node] += nanos;
        if (nanos > max[node]) max[node] = nanos;
        int p = parent[node];
        if (p > 0) childTotal[p] += nanos;
        hist[node * BUCKETS + bucket(nanos)]++;
    }

    void recordGpu(int node, long nanos) {
        gpuCalls[node]++;
        gpuTotal[node] += nanos;
        if (nanos > gpuMax[node]) gpuMax[node] = nanos;
    }

    static int bucket(long nanos) {
        long micros = Math.max(1, nanos / 1000);
        int log = 63 - Long.numberOfLeadingZeros(micros);
        int half = log > 0 ? (int) ((micros >> (log - 1)) & 1) : 0;
        return Math.min(BUCKETS - 1, log * 2 + half);
    }

    /** Upper bound of a bucket in nanoseconds. */
    static long bucketLimit(int bucket) {
        int log = bucket / 2;
        long base = 1L << log;
        long upper = (bucket % 2 == 0) ? base + (base >> 1) : base * 2;
        return Math.max(1, upper) * 1000;
    }

    /** Approximate percentile (upper bucket bound), in nanoseconds. */
    long percentile(int node, double q) {
        long n = calls[node];
        if (n == 0) return 0;
        long target = (long) Math.ceil(n * q);
        long seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            seen += hist[node * BUCKETS + b];
            if (seen >= target) return Math.min(bucketLimit(b), max[node]);
        }
        return max[node];
    }

    public long self(int node) {
        return total[node] - childTotal[node];
    }
}
