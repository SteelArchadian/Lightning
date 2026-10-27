package dev.lightning.analysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Call tree built from stack samples, root first. Node 0 is the root; its children are thread
 * groups. Each node counts samples (or bytes, for allocation trees) that passed through it
 * (total) and that ended in it (self).
 */
public final class FlameTree {
    public static final int MAX_NODES = 400_000;

    int size = 1;
    int[] parent = new int[1024];
    int[] frame = new int[1024];
    long[] total = new long[1024];
    long[] self = new long[1024];
    private final Map<Long, Integer> children = new HashMap<>();
    long overflow;

    public FlameTree() {
        parent[0] = -1;
        frame[0] = -1;
    }

    public int size() {
        return size;
    }

    public int parent(int node) {
        return parent[node];
    }

    public int frame(int node) {
        return frame[node];
    }

    public long total(int node) {
        return total[node];
    }

    public long self(int node) {
        return self[node];
    }

    /** Returns the child of {@code node} for {@code frameId}, creating it if needed (or -1 when full). */
    public int child(int node, int frameId) {
        long key = ((long) node << 32) | (frameId & 0xFFFFFFFFL);
        Integer existing = children.get(key);
        if (existing != null) return existing;
        if (size >= MAX_NODES) return -1;
        if (size == parent.length) {
            int n = size * 2;
            parent = Arrays.copyOf(parent, n);
            frame = Arrays.copyOf(frame, n);
            total = Arrays.copyOf(total, n);
            self = Arrays.copyOf(self, n);
        }
        int id = size++;
        parent[id] = node;
        frame[id] = frameId;
        children.put(key, id);
        return id;
    }

    /**
     * Adds one stack, given root-first as frame ids, under a thread-group node.
     * Returns the leaf node.
     */
    public int add(int groupFrameId, int[] framesRootFirst, int count, long weight) {
        int node = child(0, groupFrameId);
        if (node < 0) {
            overflow += weight;
            return 0;
        }
        total[0] += weight;
        total[node] += weight;
        for (int i = 0; i < count; i++) {
            int next = child(node, framesRootFirst[i]);
            if (next < 0) {
                overflow += weight;
                break;
            }
            node = next;
            total[node] += weight;
        }
        self[node] += weight;
        return node;
    }
}
