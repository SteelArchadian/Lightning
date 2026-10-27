package dev.lightning.analysis;

import dev.lightning.capture.EventFile;
import dev.lightning.capture.EventKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Replays the event file into, per thread: an aggregated section tree, one row per frame or
 * tick with its breakdown, GPU durations, full detail of the slowest frames/ticks, and JFR
 * stack samples attributed to the section that was open when each was taken.
 */
public final class SpanDigest implements EventFile.Visitor {
    /** Sections up to this depth are kept in every frame/tick row; deeper ones only in spikes. */
    static final int BREAKDOWN_DEPTH = 7;
    static final int SPIKES_PER_THREAD = 24;
    static final int SPIKE_MAX_SPANS = 40_000;
    private static final int RING = 1 << 16;

    public final List<String> names = new ArrayList<>();
    public final Map<Integer, ThreadSpans> threads = new HashMap<>();
    private final Map<Long, JfrDigest.Samples> samplesByThread;
    public long events;
    /** Latest event time seen (used to bound a recovered capture). */
    public long lastTime = Long.MIN_VALUE;
    static final int GPU_BREAKDOWN_DEPTH = 8;

    public SpanDigest(List<String> knownNames, Map<Long, JfrDigest.Samples> samplesByThread) {
        names.addAll(knownNames);
        this.samplesByThread = samplesByThread;
    }

    /** Columnar frame/tick rows. */
    public static final class Ticks {
        int size;
        long[] start = new long[1024];
        long[] duration = new long[1024];
        long[] gpu = new long[1024];
        int[] breakdownStart = new int[1025];
        int breakdownSize;
        int[] breakdownNode = new int[8192];
        long[] breakdownNanos = new long[8192];
        // GPU breakdown arrives later than the row, as (tick, node, nanos) triples.
        int gpuSize;
        int[] gpuTick = new int[4096];
        int[] gpuNode = new int[4096];
        long[] gpuNanos = new long[4096];

        int open(long time) {
            if (size == start.length) {
                int n = size * 2;
                start = Arrays.copyOf(start, n);
                duration = Arrays.copyOf(duration, n);
                gpu = Arrays.copyOf(gpu, n);
                breakdownStart = Arrays.copyOf(breakdownStart, n + 1);
            }
            start[size] = time;
            duration[size] = -1;
            gpu[size] = -1;
            breakdownStart[size] = breakdownSize;
            return size++;
        }

        void breakdown(int node, long nanos) {
            if (breakdownSize == breakdownNode.length) {
                breakdownNode = Arrays.copyOf(breakdownNode, breakdownSize * 2);
                breakdownNanos = Arrays.copyOf(breakdownNanos, breakdownSize * 2);
            }
            breakdownNode[breakdownSize] = node;
            breakdownNanos[breakdownSize] = nanos;
            breakdownSize++;
        }

        void gpuBreakdown(int tick, int node, long nanos) {
            if (gpuSize == gpuTick.length) {
                gpuTick = Arrays.copyOf(gpuTick, gpuSize * 2);
                gpuNode = Arrays.copyOf(gpuNode, gpuSize * 2);
                gpuNanos = Arrays.copyOf(gpuNanos, gpuSize * 2);
            }
            gpuTick[gpuSize] = tick;
            gpuNode[gpuSize] = node;
            gpuNanos[gpuSize] = nanos;
            gpuSize++;
        }

        public int size() {
            return size;
        }

        public long duration(int i) {
            return duration[i];
        }

        public long start(int i) {
            return start[i];
        }

        public long gpu(int i) {
            return gpu[i];
        }
    }

    /** Full record of one slow frame or tick. */
    public static final class Spike {
        int tick;
        long start, duration;
        int[] node, depth;
        long[] spanStart, spanDuration;
        int spans;
        boolean truncated;
        int[] sampleLeaf = new int[0];
        int[] sampleNode = new int[0];
        long[] sampleTime = new long[0];
    }

    public static final class ThreadSpans {
        public final int index;
        public long threadId;
        public String name = "?";
        public final SpanTree tree = new SpanTree();
        public final Ticks ticks = new Ticks();
        final PriorityQueue<Spike> spikes = new PriorityQueue<>((a, b) -> Long.compare(a.duration, b.duration));
        /** (section node << 32 | stack leaf) → sample count. */
        public final Map<Long, Integer> sectionStacks = new HashMap<>();
        public long unmatchedPops, losses, lostTicks, samplesAttributed;

        // replay state
        int depth;
        int[] nodeStack = new int[260];
        long[] startStack = new long[260];
        boolean inTick;
        int tick = -1;
        long tickStart;
        // per-tick breakdown accumulation (node → slot) with generation stamps
        int[] slotOf = new int[256];
        int[] slotGen = new int[256];
        int gen = 1;
        int[] slotNode = new int[64];
        long[] slotNanos = new long[64];
        int slots;
        // spans of the tick in progress
        int spanCount;
        int[] spanNode = new int[1024], spanDepth = new int[1024];
        long[] spanStart = new long[1024], spanDur = new long[1024];
        boolean spanOverflow;
        int tickSampleFrom;
        // GPU result routing
        final long[] ringSeq = new long[RING];
        final int[] ringNode = new int[RING];
        final int[] ringTick = new int[RING];
        long lastSeq;
        // samples
        JfrDigest.Samples samples;
        int samplePtr;

        ThreadSpans(int index) {
            this.index = index;
            Arrays.fill(ringSeq, -1);
        }

        public List<Spike> spikes() {
            List<Spike> list = new ArrayList<>(spikes);
            list.sort((a, b) -> Long.compare(b.duration, a.duration));
            return list;
        }
    }

    @Override
    public void track(int track, long threadId, String name) {
        ThreadSpans t = thread(track);
        t.threadId = threadId;
        t.name = name;
        t.samples = samplesByThread.get(threadId);
    }

    @Override
    public void name(int id, String name) {
        while (names.size() <= id) names.add("?");
        names.set(id, name);
    }

    private ThreadSpans thread(int index) {
        return threads.computeIfAbsent(index, ThreadSpans::new);
    }

    @Override
    public void event(int track, long seq, int kind, int payload, long time) {
        events++;
        if (kind != EventKind.GPU && time > lastTime) lastTime = time;
        ThreadSpans t = thread(track);
        t.lastSeq = seq;
        if (kind != EventKind.GPU) drainSamples(t, time);
        switch (kind) {
            case EventKind.PUSH -> push(t, seq, payload, time);
            case EventKind.POP -> pop(t, time);
            case EventKind.TICK_START -> tickStart(t, seq, time);
            case EventKind.TICK_END -> tickEnd(t, time);
            case EventKind.GPU -> gpu(t, seq, payload, time);
            case EventKind.LOSS -> loss(t);
            default -> {}
        }
    }

    /** Attributes any remaining samples after the last event. */
    public void finish() {
        for (ThreadSpans t : threads.values()) {
            SpanTree tree = t.tree;
            for (int i = 1; i < tree.size; i++) {
                if (tree.gpuCalls[i] > 0 && tree.gpuCalls[i] < tree.calls[i]) {
                    tree.gpuTotal[i] = Math.round((double) tree.gpuTotal[i] * tree.calls[i] / tree.gpuCalls[i]);
                }
            }
            drainSamples(t, Long.MAX_VALUE);
            if (t.inTick) {
                // The capture ended mid-frame: keep the row out of the statistics.
                t.ticks.duration[t.tick] = -1;
                t.ticks.breakdownStart[t.tick + 1] = t.ticks.breakdownSize;
                t.inTick = false;
            }
        }
    }

    private void push(ThreadSpans t, long seq, int nameId, long time) {
        int parent = t.depth == 0 ? 0 : t.nodeStack[t.depth - 1];
        int node = t.tree.child(parent, nameId);
        if (t.depth < t.nodeStack.length) {
            t.nodeStack[t.depth] = node;
            t.startStack[t.depth] = time;
        }
        t.depth++;
        int r = (int) (seq & (RING - 1));
        t.ringSeq[r] = seq;
        t.ringNode[r] = node;
        t.ringTick[r] = t.inTick ? t.tick : -1;
    }

    private void pop(ThreadSpans t, long time) {
        if (t.depth == 0) {
            t.unmatchedPops++;
            return;
        }
        t.depth--;
        if (t.depth >= t.nodeStack.length) return;
        int node = t.nodeStack[t.depth];
        long start = t.startStack[t.depth];
        long nanos = time - start;
        t.tree.record(node, nanos);
        if (t.inTick) {
            if (t.depth < BREAKDOWN_DEPTH) accumulate(t, node, nanos);
            if (t.spanCount < SPIKE_MAX_SPANS) {
                if (t.spanCount == t.spanNode.length) {
                    int n = t.spanCount * 2;
                    t.spanNode = Arrays.copyOf(t.spanNode, n);
                    t.spanDepth = Arrays.copyOf(t.spanDepth, n);
                    t.spanStart = Arrays.copyOf(t.spanStart, n);
                    t.spanDur = Arrays.copyOf(t.spanDur, n);
                }
                t.spanNode[t.spanCount] = node;
                t.spanDepth[t.spanCount] = t.depth;
                t.spanStart[t.spanCount] = start;
                t.spanDur[t.spanCount] = nanos;
                t.spanCount++;
            } else {
                t.spanOverflow = true;
            }
        }
    }

    private void accumulate(ThreadSpans t, int node, long nanos) {
        if (node >= t.slotOf.length) {
            int n = Math.max(node + 1, t.slotOf.length * 2);
            t.slotOf = Arrays.copyOf(t.slotOf, n);
            t.slotGen = Arrays.copyOf(t.slotGen, n);
        }
        if (t.slotGen[node] == t.gen) {
            t.slotNanos[t.slotOf[node]] += nanos;
            return;
        }
        if (t.slots == t.slotNode.length) {
            t.slotNode = Arrays.copyOf(t.slotNode, t.slots * 2);
            t.slotNanos = Arrays.copyOf(t.slotNanos, t.slots * 2);
        }
        t.slotGen[node] = t.gen;
        t.slotOf[node] = t.slots;
        t.slotNode[t.slots] = node;
        t.slotNanos[t.slots] = nanos;
        t.slots++;
    }

    private void closeOpen(ThreadSpans t, long time) {
        while (t.depth > 0) pop(t, time);
    }

    private void tickStart(ThreadSpans t, long seq, long time) {
        if (t.inTick) tickEnd(t, time);
        closeOpen(t, time);
        t.inTick = true;
        t.tickStart = time;
        t.tick = t.ticks.open(time);
        t.spanCount = 0;
        t.spanOverflow = false;
        t.slots = 0;
        t.gen++;
        t.tickSampleFrom = t.samplePtr;
        int r = (int) (seq & (RING - 1));
        t.ringSeq[r] = seq;
        t.ringNode[r] = -1;
        t.ringTick[r] = t.tick;
    }

    private void tickEnd(ThreadSpans t, long time) {
        if (!t.inTick) return;
        closeOpen(t, time);
        long duration = time - t.tickStart;
        Ticks ticks = t.ticks;
        ticks.duration[t.tick] = duration;
        for (int i = 0; i < t.slots; i++) ticks.breakdown(t.slotNode[i], t.slotNanos[i]);
        ticks.breakdownStart[t.tick + 1] = ticks.breakdownSize;
        considerSpike(t, duration);
        t.inTick = false;
    }

    private void considerSpike(ThreadSpans t, long duration) {
        if (t.spikes.size() >= SPIKES_PER_THREAD && t.spikes.peek().duration >= duration) return;
        Spike s = new Spike();
        s.tick = t.tick;
        s.start = t.tickStart;
        s.duration = duration;
        s.spans = t.spanCount;
        s.node = Arrays.copyOf(t.spanNode, t.spanCount);
        s.depth = Arrays.copyOf(t.spanDepth, t.spanCount);
        s.spanStart = Arrays.copyOf(t.spanStart, t.spanCount);
        s.spanDuration = Arrays.copyOf(t.spanDur, t.spanCount);
        s.truncated = t.spanOverflow;
        if (t.samples != null) {
            int from = t.tickSampleFrom, to = t.samplePtr;
            int n = Math.max(0, to - from);
            s.sampleLeaf = Arrays.copyOfRange(t.samples.leaf, from, from + n);
            s.sampleTime = Arrays.copyOfRange(t.samples.time, from, from + n);
            s.sampleNode = new int[n];
            // Section for each sample: innermost span of this tick containing it.
            for (int i = 0; i < n; i++) s.sampleNode[i] = innermost(s, s.sampleTime[i]);
        }
        t.spikes.add(s);
        if (t.spikes.size() > SPIKES_PER_THREAD) t.spikes.poll();
    }

    private static int innermost(Spike s, long time) {
        int best = 0, bestDepth = -1;
        for (int i = 0; i < s.spans; i++) {
            if (s.spanStart[i] <= time && time < s.spanStart[i] + s.spanDuration[i] && s.depth[i] > bestDepth) {
                best = s.node[i];
                bestDepth = s.depth[i];
            }
        }
        return best;
    }

    private void gpu(ThreadSpans t, long seq, int payload, long nanos) {
        long low = payload & 0xFFFFFFFFL;
        long target = (seq & ~0xFFFFFFFFL) | low;
        if (target > seq) target -= 1L << 32;
        int r = (int) (target & (RING - 1));
        if (t.ringSeq[r] != target) return;
        int node = t.ringNode[r];
        int tick = t.ringTick[r];
        if (node < 0) {
            if (tick >= 0) t.ticks.gpu[tick] = nanos;
            return;
        }
        t.tree.recordGpu(node, nanos);
        if (tick >= 0 && t.tree.depth[node] < GPU_BREAKDOWN_DEPTH) t.ticks.gpuBreakdown(tick, node, nanos);
    }

    private void loss(ThreadSpans t) {
        t.losses++;
        t.depth = 0;
        if (t.inTick) {
            t.ticks.duration[t.tick] = -2;
            t.ticks.breakdownStart[t.tick + 1] = t.ticks.breakdownSize;
            t.lostTicks++;
            t.inTick = false;
        }
    }

    private void drainSamples(ThreadSpans t, long time) {
        JfrDigest.Samples s = t.samples;
        if (s == null) return;
        while (t.samplePtr < s.size && s.time[t.samplePtr] < time) {
            int node = t.depth == 0 ? 0 : t.nodeStack[Math.min(t.depth, t.nodeStack.length) - 1];
            t.tree.samples[node]++;
            t.samplesAttributed++;
            long key = ((long) node << 32) | (s.leaf[t.samplePtr] & 0xFFFFFFFFL);
            t.sectionStacks.merge(key, 1, Integer::sum);
            t.samplePtr++;
        }
    }
}
