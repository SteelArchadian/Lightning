package dev.lightning.analysis;

import dev.lightning.capture.Capture;
import dev.lightning.capture.MetricsSampler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a capture at its {@code /lightning mark} points and summarises each part. Also used for
 * the whole capture, so results.json has one uniform shape for comparing runs.
 */
public final class Phases {
    public record Stat(String label, long from, long to, Stats frames, Stats frameGpu, Stats serverWork, int ticksOver50,
                       int stalls, long stallNanos, double allocMBps, long gcPauseNanos, long gcLongestNanos,
                       long liveP50, long liveMax, long heapMax, Map<String, Double> poolCores) {
        public double seconds() {
            return (to - from) / 1e9;
        }

        public double fps() {
            return frames.count() / Math.max(1e-9, seconds());
        }
    }

    private Phases() {}

    public static Stat whole(Report r) {
        return of(r, "whole capture", r.capture.startNanos, r.capture.stopNanos());
    }

    public static List<Stat> marked(Report r) {
        List<Capture.Mark> marks = new ArrayList<>(r.capture.marks());
        // Loading steps read back from the logs are added after the fact, out of order.
        marks.sort(java.util.Comparator.comparingLong(Capture.Mark::nanos));
        List<Stat> out = new ArrayList<>();
        if (marks.isEmpty()) return out;
        long start = r.capture.startNanos;
        String label = "(before first mark)";
        for (Capture.Mark m : marks) {
            // An empty lead-in before the first mark is dropped; named phases are always kept.
            boolean leadIn = label.equals("(before first mark)");
            if (m.nanos() > start && (!leadIn || m.nanos() - start > 100_000_000L)) out.add(of(r, label, start, m.nanos()));
            start = m.nanos();
            label = m.label();
        }
        out.add(of(r, label, start, r.capture.stopNanos()));
        return out;
    }

    static Stat of(Report r, String label, long from, long to) {
        Stats frames = Stats.EMPTY, gpu = Stats.EMPTY, work = Stats.EMPTY;
        if (r.render != null) {
            frames = within(r.render.ticks.start, r.render.ticks.duration, r.render.ticks.size(), from, to);
            gpu = within(r.render.ticks.start, r.render.ticks.gpu, r.render.ticks.size(), from, to);
        }
        int over = 0;
        if (r.server != null) {
            work = within(r.server.ticks.start, r.serverWorkPerTick, r.server.ticks.size(), from, to);
            for (int i = 0; i < r.server.ticks.size(); i++) {
                long s = r.server.ticks.start[i];
                if (s >= from && s < to && r.serverWorkPerTick[i] > 50_000_000L) over++;
            }
        }
        int stalls = 0;
        long stallNanos = 0, pauses = 0, longest = 0;
        double alloc = 0;
        Map<String, Double> pools = new LinkedHashMap<>();
        JfrDigest j = r.jfr;
        if (j != null) {
            for (JfrDigest.Pause p : j.allocationStalls) {
                if (p.time() >= from && p.time() < to) {
                    stalls++;
                    stallNanos += p.duration();
                }
            }
            for (JfrDigest.Gc gc : j.gcs) {
                if (gc.time() >= from && gc.time() < to) {
                    pauses += gc.sumOfPauses();
                    longest = Math.max(longest, gc.longestPause());
                }
            }
            long bytes = 0;
            for (var e : j.allocPerSecond.subMap(from / 1_000_000_000L, true, to / 1_000_000_000L, true).entrySet()) bytes += e.getValue();
            alloc = bytes / Math.max(1e-9, (to - from) / 1e9) / 1048576.0;
            // Average cores per thread pool, from per-thread CPU load events inside the phase.
            Map<String, double[]> acc = new LinkedHashMap<>();
            for (JfrDigest.ThreadCpuPoint p : j.threadCpuPoints) {
                if (p.time() < from || p.time() >= to) continue;
                double[] a = acc.computeIfAbsent(p.group(), k -> new double[1]);
                a[0] += p.cores();
            }
            double secs = Math.max(1, Math.round((to - from) / 1e9));
            acc.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                    .forEach(e -> pools.put(e.getKey(), e.getValue()[0] / secs));
        }
        long liveP50 = 0, liveMax = 0, heapMax = 0;
        List<Long> live = new ArrayList<>();
        for (MetricsSampler.Sample m : r.data.metrics()) {
            heapMax = Math.max(heapMax, m.heapMax());
            if (m.time() >= from && m.time() <= to && m.liveAfterGc() > 0) live.add(m.liveAfterGc());
        }
        if (!live.isEmpty()) {
            live.sort(Long::compare);
            liveP50 = live.get(live.size() / 2);
            liveMax = live.get(live.size() - 1);
        }
        return new Stat(label, from, to, frames, gpu, work, over, stalls, stallNanos, alloc, pauses, longest, liveP50, liveMax, heapMax, pools);
    }

    private static Stats within(long[] start, long[] values, int size, long from, long to) {
        long[] v = new long[size];
        int n = 0;
        for (int i = 0; i < size; i++) if (start[i] >= from && start[i] < to) v[n++] = values[i];
        return Stats.of(v, n);
    }
}
