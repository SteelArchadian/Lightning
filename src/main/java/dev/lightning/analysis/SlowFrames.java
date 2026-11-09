package dev.lightning.analysis;

import dev.lightning.capture.MetricsSampler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Why gameplay frames are slow when no single section explains it: slow frames compared with
 * normal ones on everything else that was happening at the same moment.
 *
 * <ul>
 *   <li>Menu frames (a screen or overlay open) are set apart first: menus run at their own frame cap
 *       and may open windows, which is not gameplay lag.</li>
 *   <li>Background thread pools: CPU in cores during seconds with slow frames vs seconds without
 *       (jdk.ThreadCPULoad, once a second), and how much more often each pool is sampled inside slow
 *       frames than inside normal ones (CPU samples, frame resolution).</li>
 *   <li>Memory: share of slow vs normal frames overlapping a garbage collection or a JVM pause,
 *       allocation rate and heap fullness during them.</li>
 *   <li>Render thread: the sections and the running methods that take more time in slow frames
 *       (a differential profile). Method time is each method's share of the frame's CPU samples
 *       times the frame time, so lost samples don't bias it.</li>
 * </ul>
 */
final class SlowFrames {
    record Pool(String pool, double coresSlow, double coresCalm, double sampleRatio) {
        double extraCores() {
            return coresSlow - coresCalm;
        }
    }

    /**
     * Time per slow vs normal frame. slowPct/normalPct: share of those frames the section ran in
     * at all (-1 for methods); a section present in most slow frames and few normal ones is a
     * periodic step (client tick, chunk upload) that makes the frames it lands in slow.
     */
    record Diff(String name, String owner, double slowMs, double normalMs, double slowPct, double normalPct) {
        Diff(String name, String owner, double slowMs, double normalMs) {
            this(name, owner, slowMs, normalMs, -1, -1);
        }

        double extraMs() {
            return slowMs - normalMs;
        }

        /** True when the section mostly runs only in slow frames rather than running longer in them. */
        boolean periodic() {
            return slowPct >= 0 && slowPct >= 50 && normalPct <= 0.4 * slowPct;
        }
    }

    /** Top-level step that runs in most slow frames and few normal ones, or null. */
    Diff periodicStep;

    /** Frames tagged with an open screen, by screen (class simple name), and how many of those were slow. */
    final Map<String, int[]> menuScreens = new LinkedHashMap<>();
    int playFrames, menuFrames, menuSlow, slowCount, normalCount;
    boolean screensKnown;
    long threshold, normalLimit, playP50;
    double slowMeanMs, normalMeanMs;
    final List<Pool> pools = new ArrayList<>();
    final List<Diff> sections = new ArrayList<>();
    final List<Diff> topSections = new ArrayList<>();
    final List<Diff> methods = new ArrayList<>();
    double slowInGcPct = -1, normalInGcPct = -1, slowInVmOpPct = -1, normalInVmOpPct = -1, slowInSyncPct = -1, normalInSyncPct = -1;
    double allocSlowMBps = -1, allocNormalMBps = -1, heapSlowPct = -1, heapNormalPct = -1;
    String verdict = "";

    private final Report r;
    private final SpanDigest.ThreadSpans t;
    /** Per render frame: 1 slow, 2 normal, 0 neither (in between, menu, or unfinished). */
    private byte[] kind;

    /** True for the server-thread variant: ticks judged by their work (the idle wait left out). */
    final boolean server;

    private SlowFrames(Report r, boolean server) {
        this.r = r;
        this.server = server;
        this.t = server ? r.server : r.render;
    }

    /** Null when there is no render thread or too few gameplay frames. */
    static SlowFrames of(Report r) {
        return of(r, false);
    }

    /** Slow server ticks vs normal ones, or null when there is no server thread or too few ticks. */
    static SlowFrames ofServer(Report r) {
        return of(r, true);
    }

    private static SlowFrames of(Report r, boolean server) {
        SpanDigest.ThreadSpans th = server ? r.server : r.render;
        if (th == null || th.ticks.size() < 60) return null;
        SlowFrames s = new SlowFrames(r, server);
        if (!s.classify()) return null;
        s.sections();
        if (r.jfr != null) {
            s.pools();
            s.methods();
            s.memory();
        }
        s.verdict();
        return s;
    }

    /** Frame time, or a server tick's work (its duration minus the wait for the next tick). */
    private long dur(int i) {
        if (!server) return t.ticks.duration(i);
        return i < r.serverWorkPerTick.length ? r.serverWorkPerTick[i] : -1;
    }

    static String screenLabel(Report r, int payload) {
        String name = r.name(payload - 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private boolean classify() {
        SpanDigest.Ticks k = t.ticks;
        int n = k.size();
        screensKnown = t.screensKnown;
        long[] play = new long[n];
        int p = 0;
        for (int i = 0; i < n; i++) {
            if (dur(i) < 0) continue;
            if (k.screen(i) != 0) {
                menuFrames++;
                continue;
            }
            play[p++] = dur(i);
        }
        playFrames = p;
        if (p < 60) return false;
        Stats st = Stats.of(play, p);
        playP50 = st.p50();
        threshold = Math.max((long) (playP50 * 1.8), playP50 + 4_000_000L);
        normalLimit = (long) (playP50 * 1.2);
        kind = new byte[n];
        double slowSum = 0, normalSum = 0;
        for (int i = 0; i < n; i++) {
            long d = dur(i);
            if (d < 0) continue;
            if (k.screen(i) != 0) {
                int[] c = menuScreens.computeIfAbsent(screenLabel(r, k.screen(i)), x -> new int[2]);
                c[0]++;
                if (d > threshold) {
                    c[1]++;
                    menuSlow++;
                }
                continue;
            }
            if (d > threshold) {
                kind[i] = 1;
                slowCount++;
                slowSum += d;
            } else if (d <= normalLimit) {
                kind[i] = 2;
                normalCount++;
                normalSum += d;
            }
        }
        slowMeanMs = slowCount == 0 ? 0 : slowSum / slowCount / 1e6;
        normalMeanMs = normalCount == 0 ? 0 : normalSum / normalCount / 1e6;
        return slowCount > 0 && normalCount > 0;
    }

    /** Sections with more time in slow frames, by own extra (extra not explained by sections inside them). */
    private void sections() {
        SpanDigest.Ticks k = t.ticks;
        // Per node: [slow nanos, normal nanos, slow frames present, normal frames present, last frame seen].
        Map<Integer, double[]> perNode = new HashMap<>();
        for (int i = 0; i < k.size(); i++) {
            if (kind[i] == 0) continue;
            int slot = kind[i] == 1 ? 0 : 1;
            for (int b = k.breakdownStart[i]; b < k.breakdownStart[i + 1]; b++) {
                double[] v = perNode.computeIfAbsent(k.breakdownNode[b], x -> new double[] {0, 0, 0, 0, -1});
                v[slot] += k.breakdownNanos[b];
                if (v[4] != i) {
                    v[4] = i;
                    v[2 + slot]++;
                }
            }
        }
        Map<Integer, Double> extra = new HashMap<>();
        for (var e : perNode.entrySet()) {
            double[] v = e.getValue();
            extra.put(e.getKey(), v[0] / slowCount - v[1] / normalCount);
        }
        Map<Integer, Double> own = new HashMap<>(extra);
        for (var e : extra.entrySet()) {
            int parent = t.tree.parent[e.getKey()];
            if (parent > 0 && own.containsKey(parent)) own.merge(parent, -e.getValue(), Double::sum);
        }
        List<Integer> nodes = new ArrayList<>(own.keySet());
        nodes.sort((a, b) -> Double.compare(own.get(b), own.get(a)));
        for (int node : nodes) {
            if (own.get(node) < 0.3e6 || sections.size() == 10) break;
            double[] v = perNode.get(node);
            sections.add(diff(r.path(t, node), v));
        }
        for (int node : perNode.keySet()) {
            if (t.tree.parent[node] != 0) continue;
            double[] v = perNode.get(node);
            topSections.add(diff(r.name(t.tree.name[node]), v));
        }
        topSections.sort((a, b) -> Double.compare(b.extraMs(), a.extraMs()));
        for (Diff d : topSections) {
            if (d.extraMs() < 0.3) break;
            if (d.periodic()) {
                periodicStep = d;
                break;
            }
        }
    }

    private Diff diff(String name, double[] v) {
        return new Diff(name, "", v[0] / slowCount / 1e6, v[1] / normalCount / 1e6, 100.0 * v[2] / slowCount, 100.0 * v[3] / normalCount);
    }

    /**
     * Background pools: cores in seconds with several slow frames vs seconds without any, and how
     * much more often the pool is sampled inside slow frames than inside normal ones.
     */
    private void pools() {
        JfrDigest j = r.jfr;
        SpanDigest.Ticks k = t.ticks;
        // Seconds (by frame end) → slow frames, menu frames, all frames.
        Map<Long, int[]> seconds = new HashMap<>();
        for (int i = 0; i < k.size(); i++) {
            if (dur(i) < 0) continue;
            int[] c = seconds.computeIfAbsent(Math.floorDiv(k.start(i) + dur(i), 1_000_000_000L), x -> new int[3]);
            if (kind[i] == 1) c[0]++;
            if (k.screen(i) != 0) c[1]++;
            c[2]++;
        }
        // ThreadCPULoad at time T covers the second before T.
        Map<String, double[]> cores = new HashMap<>();
        Map<Long, Map<String, Double>> bySecond = new HashMap<>();
        for (JfrDigest.ThreadCpuPoint pt : j.threadCpuPoints) {
            if (pt.group().startsWith(t.name)) continue;
            long sec = Math.floorDiv(pt.time(), 1_000_000_000L) - 1;
            bySecond.computeIfAbsent(sec, x -> new HashMap<>()).merge(pt.group(), pt.cores(), Double::sum);
        }
        // Seconds of gameplay ranked by their share of slow frames: the worst third against the best third.
        List<Long> ranked = new ArrayList<>();
        for (long sec : bySecond.keySet()) {
            int[] c = seconds.get(sec);
            if (c != null && c[2] > 0 && c[1] * 2 <= c[2]) ranked.add(sec);
        }
        ranked.sort((a, b) -> Double.compare((double) seconds.get(b)[0] / seconds.get(b)[2], (double) seconds.get(a)[0] / seconds.get(a)[2]));
        int third = ranked.size() / 3, slowSecs = 0, calmSecs = 0;
        for (int i = 0; i < ranked.size() && third >= 2; i++) {
            int which = i < third ? 0 : i >= ranked.size() - third ? 1 : -1;
            if (which < 0) continue;
            int[] c = seconds.get(ranked.get(i));
            // The worst seconds must actually have slow frames, and more of them than the best seconds.
            if (which == 0 && c[0] == 0) continue;
            if (which == 0) slowSecs++;
            else calmSecs++;
            for (var g : bySecond.get(ranked.get(i)).entrySet()) cores.computeIfAbsent(g.getKey(), x -> new double[2])[which] += g.getValue();
        }
        // Sample rates inside slow and normal frame windows.
        JfrDigest.Samples s = j.allSamples;
        double[] slowHits = new double[j.poolNames.size()], normalHits = new double[j.poolNames.size()];
        double slowSeconds = 0, normalSeconds = 0;
        int from = 0;
        for (int i = 0; i < k.size(); i++) {
            if (kind[i] == 0) continue;
            long a = k.start(i), b = a + dur(i);
            if (kind[i] == 1) slowSeconds += dur(i) / 1e9;
            else normalSeconds += dur(i) / 1e9;
            while (from < s.size && s.time[from] < a) from++;
            for (int x = from; x < s.size && s.time[x] < b; x++) {
                if (kind[i] == 1) slowHits[s.pool[x]]++;
                else normalHits[s.pool[x]]++;
            }
        }
        Map<String, Double> ratio = new HashMap<>();
        for (int p = 0; p < j.poolNames.size(); p++) {
            double slowRate = slowSeconds == 0 ? 0 : slowHits[p] / slowSeconds, normalRate = normalSeconds == 0 ? 0 : normalHits[p] / normalSeconds;
            if (slowHits[p] + normalHits[p] < 5) continue;
            ratio.put(j.poolNames.get(p), normalRate == 0 ? (slowRate > 0 ? 99 : 1) : slowRate / normalRate);
        }
        if (slowSecs == 0 || calmSecs == 0) {
            // No per-second contrast (slow frames everywhere or nowhere): keep the frame-window ratios only.
            for (var e : ratio.entrySet()) {
                if (!e.getKey().startsWith(t.name)) pools.add(new Pool(e.getKey(), -1, -1, e.getValue()));
            }
            pools.sort((a, b) -> Double.compare(b.sampleRatio(), a.sampleRatio()));
        } else {
            for (var e : cores.entrySet()) {
                double slow = e.getValue()[0] / slowSecs, calm = e.getValue()[1] / calmSecs;
                if (Math.max(slow, calm) < 0.05) continue;
                pools.add(new Pool(e.getKey(), slow, calm, ratio.getOrDefault(e.getKey(), Double.NaN)));
            }
            pools.sort((a, b) -> Double.compare(b.extraCores(), a.extraCores()));
        }
        while (pools.size() > 12) pools.remove(pools.size() - 1);
    }

    /** Render-thread methods (running code) with more time per slow frame than per normal frame. */
    private void methods() {
        JfrDigest j = r.jfr;
        JfrDigest.Samples s = t.samples;
        if (s == null || s.size == 0) return;
        SpanDigest.Ticks k = t.ticks;
        Map<Integer, Integer> labelOfLeaf = new HashMap<>();
        Map<Integer, long[]> hits = new HashMap<>();
        long slowTotal = 0, normalTotal = 0;
        int from = 0;
        for (int i = 0; i < k.size(); i++) {
            if (kind[i] == 0) continue;
            long a = k.start(i), b = a + dur(i);
            while (from < s.size && s.time[from] < a) from++;
            for (int x = from; x < s.size && s.time[x] < b; x++) {
                int frame = labelOfLeaf.computeIfAbsent(s.leaf[x], leaf -> {
                    int own = JfrDigest.ownFrame(j.cpu, j.frames, leaf);
                    return own >= 0 ? own : j.cpu.frame(leaf);
                });
                long[] h = hits.computeIfAbsent(frame, f -> new long[2]);
                if (kind[i] == 1) {
                    h[0]++;
                    slowTotal++;
                } else {
                    h[1]++;
                    normalTotal++;
                }
            }
        }
        if (slowTotal < 10 || normalTotal < 10) return;
        for (var e : hits.entrySet()) {
            double slowMs = slowMeanMs * e.getValue()[0] / slowTotal, normalMs = normalMeanMs * e.getValue()[1] / normalTotal;
            if (slowMs - normalMs < 0.15) continue;
            methods.add(new Diff(j.frames.label(e.getKey()), j.frames.owner(e.getKey()), slowMs, normalMs));
        }
        methods.sort((a, b) -> Double.compare(b.extraMs(), a.extraMs()));
        while (methods.size() > 12) methods.remove(methods.size() - 1);
    }

    static final long SYNC_NOTABLE = 5_000_000L;

    private void memory() {
        JfrDigest j = r.jfr;
        SpanDigest.Ticks k = t.ticks;
        long[][] gc = new long[j.gcs.size()][];
        for (int i = 0; i < gc.length; i++) gc[i] = new long[] {j.gcs.get(i).time(), j.gcs.get(i).time() + Math.max(1, j.gcs.get(i).duration())};
        // Only real safepoints stop the game; handshakes (ZGC's in particular) pause one thread at a time.
        long[][] vm = j.vmOperations.stream().filter(JfrDigest.Pause::stopsAll).map(p -> new long[] {p.time(), p.time() + p.duration()}).toArray(long[][]::new);
        Arrays.sort(gc, (a, b) -> Long.compare(a[0], b[0]));
        Arrays.sort(vm, (a, b) -> Long.compare(a[0], b[0]));
        // Waits of 5 ms or more for every thread to reach a safepoint (shorter ones overlap everything).
        long[][] sync = j.safepoints.stream().filter(p -> p.duration() >= SYNC_NOTABLE).map(p -> new long[] {p.time(), p.time() + p.duration()}).toArray(long[][]::new);
        // Allocation samples in time order.
        int na = j.allocCount;
        Integer[] order = new Integer[na];
        for (int i = 0; i < na; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Long.compare(j.allocTime[a], j.allocTime[b]));
        List<MetricsSampler.Sample> metrics = r.data.metrics();
        int[] inGc = new int[2], inVm = new int[2], inSync = new int[2], heapN = new int[2];
        double[] allocBytes = new double[2], secs = new double[2], heap = new double[2];
        int ap = 0, mp = 0;
        for (int i = 0; i < k.size(); i++) {
            if (kind[i] == 0) continue;
            int w = kind[i] == 1 ? 0 : 1;
            long a = k.start(i), b = a + dur(i);
            if (overlaps(gc, a, b)) inGc[w]++;
            if (overlaps(vm, a, b)) inVm[w]++;
            if (overlaps(sync, a, b)) inSync[w]++;
            secs[w] += (b - a) / 1e9;
            while (ap < na && j.allocTime[order[ap]] < a) ap++;
            for (int x = ap; x < na && j.allocTime[order[x]] < b; x++) allocBytes[w] += j.allocWeightAt[order[x]];
            while (mp + 1 < metrics.size() && metrics.get(mp + 1).time() <= a) mp++;
            if (!metrics.isEmpty() && metrics.get(mp).time() <= a && metrics.get(mp).heapMax() > 0) {
                heap[w] += 100.0 * metrics.get(mp).heapUsed() / metrics.get(mp).heapMax();
                heapN[w]++;
            }
        }
        if (gc.length > 0) {
            slowInGcPct = 100.0 * inGc[0] / slowCount;
            normalInGcPct = 100.0 * inGc[1] / normalCount;
        }
        slowInVmOpPct = 100.0 * inVm[0] / slowCount;
        normalInVmOpPct = 100.0 * inVm[1] / normalCount;
        if (!j.safepoints.isEmpty()) {
            slowInSyncPct = 100.0 * inSync[0] / slowCount;
            normalInSyncPct = 100.0 * inSync[1] / normalCount;
        }
        if (na > 0) {
            allocSlowMBps = allocBytes[0] / Math.max(1e-9, secs[0]) / 1048576.0;
            allocNormalMBps = allocBytes[1] / Math.max(1e-9, secs[1]) / 1048576.0;
        }
        if (heapN[0] > 0 && heapN[1] > 0) {
            heapSlowPct = heap[0] / heapN[0];
            heapNormalPct = heap[1] / heapN[1];
        }
    }

    /** True if [a, b) overlaps any interval in the start-sorted list. */
    private static boolean overlaps(long[][] spans, long a, long b) {
        int lo = 0, hi = spans.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (spans[mid][0] < b) lo = mid + 1;
            else hi = mid;
        }
        // Spans starting before b; GC cycles are short relative to the capture, so a short look back is enough.
        for (int i = lo - 1; i >= 0 && i >= lo - 64; i--) if (spans[i][1] > a) return true;
        return false;
    }

    private void verdict() {
        List<String> busy = new ArrayList<>();
        for (Pool p : pools) {
            if (p.pool().startsWith("Lightning") || p.pool().startsWith("JFR")) continue;
            boolean cores = p.coresSlow() >= 0 && p.extraCores() >= 0.4 && p.coresSlow() >= 1.25 * p.coresCalm();
            boolean samples = p.coresSlow() < 0 && p.sampleRatio() >= 1.5;
            if (!cores && !samples) continue;
            busy.add(p.coresSlow() >= 0
                    ? String.format(Locale.ROOT, "%s (%.1f cores vs %.1f)", p.pool(), p.coresSlow(), p.coresCalm())
                    : String.format(Locale.ROOT, "%s (%.1f× as active)", p.pool(), p.sampleRatio()));
            if (busy.size() == 3) break;
        }
        List<String> also = new ArrayList<>();
        if (slowInGcPct >= 0 && slowInGcPct - normalInGcPct >= 15) {
            also.add(String.format(Locale.ROOT, "a garbage collection is running (%.0f%% of slow frames vs %.0f%% of normal ones)", slowInGcPct, normalInGcPct));
        }
        if (slowInVmOpPct - normalInVmOpPct >= 10) {
            also.add(String.format(Locale.ROOT, "the JVM pauses all threads (%.0f%% of slow frames vs %.0f%%)", slowInVmOpPct, normalInVmOpPct));
        }
        if (slowInSyncPct >= 0 && slowInSyncPct - normalInSyncPct >= 10) {
            also.add(String.format(Locale.ROOT, "the JVM waits for threads to reach a safepoint (%.0f%% of slow frames vs %.0f%%)", slowInSyncPct, normalInSyncPct));
        }
        if (allocSlowMBps > 0 && allocNormalMBps > 0 && allocSlowMBps >= 1.5 * allocNormalMBps && allocSlowMBps - allocNormalMBps > 100) {
            also.add(String.format(Locale.ROOT, "memory is allocated faster (%.0f MB/s vs %.0f)", allocSlowMBps, allocNormalMBps));
        }
        StringBuilder v = new StringBuilder();
        if (!busy.isEmpty()) v.append("Slow frames happen while ").append(String.join(", ", busy)).append(busy.size() == 1 ? " is" : " are").append(" busy");
        if (!also.isEmpty()) v.append(v.length() == 0 ? "Slow frames happen while " : " and while ").append(String.join(" and ", also));
        if (v.length() > 0) v.append('.');
        if (periodicStep != null) {
            v.append(v.length() == 0 ? "" : " ").append(String.format(Locale.ROOT,
                    "Most slow frames are the ones that also run %s (in %.0f%% of slow frames vs %.0f%% of normal ones), so how long that step takes sets the stutter.",
                    periodicStep.name().equals("tick") ? "the client tick (20 times a second)" : Contenders.friendly(periodicStep.name()),
                    periodicStep.slowPct(), periodicStep.normalPct()));
        }
        if (heapSlowPct >= 85) v.append(String.format(Locale.ROOT, " The heap was %.0f%% full during slow frames.", heapSlowPct));
        verdict = v.toString().trim();
        if (server) verdict = forTicks(verdict);
    }

    /** Frame wording turned into server-tick wording, for the server-thread variant. */
    static String forTicks(String text) {
        return text.replace("Slow frames", "Slow server ticks").replace("slow frames", "slow ticks").replace("slow frame", "slow tick")
                .replace("normal frames", "normal ticks").replace("gameplay frames", "server ticks").replace("normal ones", "normal ticks")
                .replace("render-thread", "server-thread").replace("render thread", "server thread").replace("gameplay frame", "server tick");
    }

    /** Sections with the most extra time, as "name +X ms", for one-line summaries. */
    String sectionLine(List<Diff> list, int max) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Diff d : list) {
            if (d.extraMs() < 0.3) break;
            if (shown > 0) b.append(", ");
            b.append(Contenders.friendly(d.name())).append(String.format(Locale.ROOT, " +%.1f ms", d.extraMs()));
            if (d.periodic()) b.append(String.format(Locale.ROOT, " (runs in %.0f%% of slow frames, %.0f%% of normal)", d.slowPct(), d.normalPct()));
            if (++shown == max) break;
        }
        return b.toString();
    }

    String methodLine(int max) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (Diff d : methods) {
            if (shown > 0) b.append("; ");
            b.append(d.name()).append(" [").append(d.owner()).append("]").append(String.format(Locale.ROOT, " +%.2f ms", d.extraMs()));
            if (++shown == max) break;
        }
        return b.toString();
    }
}
