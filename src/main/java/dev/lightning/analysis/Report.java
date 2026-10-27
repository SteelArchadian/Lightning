package dev.lightning.analysis;

import dev.lightning.capture.Capture;
import dev.lightning.capture.GpuTimer;
import dev.lightning.capture.Track;
import java.util.ArrayList;
import java.util.List;

/** Everything derived from one capture, shared by the JSON export, the findings and the summary. */
public final class Report {
    public final CaptureData data;
    public final Capture capture;
    public final SpanDigest spans;
    public final JfrDigest jfr;
    public final String jfrReadError;
    public final WorldDigest world;
    public final long durationNanos;
    public final SpanDigest.ThreadSpans render, server;
    public final Stats frames, frameGpu, serverWork;
    /** Per-tick server work: tick duration minus the idle wait before the next tick. */
    public final long[] serverWorkPerTick;
    public long analysisNanos;

    Report(CaptureData data, SpanDigest spans, JfrDigest jfr, String jfrReadError, WorldDigest world) {
        this.data = data;
        this.capture = data.capture();
        this.spans = spans;
        this.jfr = jfr;
        this.jfrReadError = jfrReadError;
        this.world = world;
        this.durationNanos = capture.stopNanos() - capture.startNanos;
        SpanDigest.ThreadSpans r = null, s = null;
        for (SpanDigest.ThreadSpans t : spans.threads.values()) {
            if (t.name.equals("Render thread") && (r == null || t.ticks.size() > r.ticks.size())) r = t;
            if (t.name.equals("Server thread") && (s == null || t.ticks.size() > s.ticks.size())) s = t;
        }
        this.render = r;
        this.server = s;
        this.frames = r == null ? Stats.EMPTY : Stats.of(r.ticks.duration, r.ticks.size());
        this.frameGpu = r == null ? Stats.EMPTY : Stats.of(r.ticks.gpu, r.ticks.size());
        if (s != null) {
            int wait = node(s, "nextTickWait");
            serverWorkPerTick = new long[s.ticks.size()];
            for (int i = 0; i < s.ticks.size(); i++) {
                long d = s.ticks.duration[i];
                if (d < 0) {
                    serverWorkPerTick[i] = -1;
                    continue;
                }
                long waited = 0;
                for (int b = s.ticks.breakdownStart[i]; b < s.ticks.breakdownStart[i + 1]; b++) {
                    if (s.ticks.breakdownNode[b] == wait) waited += s.ticks.breakdownNanos[b];
                }
                serverWorkPerTick[i] = d - waited;
            }
            serverWork = Stats.of(serverWorkPerTick, serverWorkPerTick.length);
        } else {
            serverWorkPerTick = new long[0];
            serverWork = Stats.EMPTY;
        }
    }

    public String name(int nameId) {
        return nameId >= 0 && nameId < spans.names.size() ? spans.names.get(nameId) : "?";
    }

    /** Finds a node by its path of section names from the thread root, or -1. */
    public int node(SpanDigest.ThreadSpans t, String... path) {
        if (t == null) return -1;
        int node = 0;
        outer:
        for (String part : path) {
            for (int i = 1; i < t.tree.size; i++) {
                if (t.tree.parent[i] == node && part.equals(name(t.tree.name[i]))) {
                    node = i;
                    continue outer;
                }
            }
            return -1;
        }
        return node;
    }

    public String path(SpanDigest.ThreadSpans t, int node) {
        List<String> parts = new ArrayList<>();
        for (int n = node; n > 0; n = t.tree.parent[n]) parts.add(name(t.tree.name[n]));
        java.util.Collections.reverse(parts);
        return String.join(" › ", parts);
    }

    public double seconds() {
        return durationNanos / 1e9;
    }

    public long totalEvents() {
        long n = 0;
        for (Track t : capture.tracks()) n += t.emitted();
        return n;
    }

    public long lostEvents() {
        long n = 0;
        for (Track t : capture.tracks()) n += t.lostEvents();
        return n;
    }

    /**
     * Estimated share of the game threads' time (render + server) spent in Lightning's hooks:
     * recorded events at the measured per-event cost, plus GPU timer calls.
     */
    public double gameThreadOverheadPct() {
        long events = 0;
        int gameThreads = 0;
        for (Track t : capture.tracks()) {
            if (t.threadName.equals("Render thread") || t.threadName.equals("Server thread")) {
                events += t.emitted();
                gameThreads++;
            }
        }
        long worldTicks = 0;
        for (WorldDigest.Level l : world.levels) worldTicks += l.entityTicks + l.blockEntityTicks;
        double nanos = events * capture.eventCostNanos + worldTicks * capture.eventCostNanos * 2.5;
        if (gpu() != null) nanos += gpu().glNanos();
        return gameThreads == 0 || durationNanos <= 0 ? 0 : 100.0 * nanos / ((double) durationNanos * gameThreads);
    }

    /** Average CPU cores used by Lightning's own threads and JFR's threads. */
    public double backgroundCores() {
        var m = data.metrics();
        long own = m.size() >= 2 ? m.get(m.size() - 1).lightningCpuNanos() - m.get(0).lightningCpuNanos() : 0;
        long jfrCpu = Math.max(0, data.jfrThreadCpuNanos());
        return durationNanos <= 0 ? 0 : (double) (own + jfrCpu) / durationNanos;
    }

    private LoadDigest load;
    private boolean loadDone;

    /** Loading analysis (start-up and world-join captures), or null. */
    LoadDigest load() {
        if (!loadDone) {
            loadDone = true;
            load = LoadDigest.of(this);
        }
        return load;
    }

    public GpuTimer gpu() {
        return capture.gpuTimer();
    }
}
