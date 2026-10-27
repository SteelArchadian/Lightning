package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns measurements into ranked observations. Each states what was measured and how; none
 * claims a cause that the data cannot show. Thresholds are deliberately conservative.
 */
final class Findings {
    private final Report r;
    private final List<Finding> out = new ArrayList<>();

    private Findings(Report r) {
        this.r = r;
    }

    static List<Finding> compute(Report r) {
        Findings f = new Findings(r);
        f.frames();
        f.gpu();
        f.renderSpikes();
        f.server();
        f.world();
        f.memory();
        f.jvmPauses();
        f.blocking();
        f.threadsCpu();
        f.logs();
        f.lightning();
        Map<String, Integer> order = Map.of("high", 0, "medium", 1, "low", 2, "info", 3);
        f.out.sort(Comparator.comparingInt(x -> order.getOrDefault(x.severity(), 9)));
        return f.out;
    }

    private void add(String severity, String area, String title, String detail, String basis, String link) {
        out.add(new Finding(severity, area, title, detail, basis, link));
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // --- frames -----------------------------------------------------------------------------

    private void frames() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frames.count() < 10) return;
        double fps = r.frames.count() / r.seconds();
        long p50 = r.frames.p50();
        // In vanilla these are top-level frame sections; some mods nest them under "render".
        int display = r.node(t, "updateDisplay");
        if (display < 0) display = r.node(t, "render", "updateDisplay");
        int game = r.node(t, "gameRenderer");
        if (game < 0) game = r.node(t, "render", "gameRenderer");
        int clientTick = r.node(t, "tick");
        double n = r.frames.count();
        double displayPer = display < 0 ? 0 : t.tree.total[display] / n;
        double gamePer = game < 0 ? 0 : t.tree.total[game] / n;
        double tickPer = clientTick < 0 ? 0 : t.tree.total[clientTick] / n;
        double meanFrame = r.frames.mean();
        int limit = r.data.environment().has("fpsLimit") ? r.data.environment().get("fpsLimit").getAsInt() : 260;
        boolean vsync = r.data.environment().has("vsync") && r.data.environment().get("vsync").getAsBoolean();

        StringBuilder d = new StringBuilder();
        d.append(String.format(Locale.ROOT, "%.0f frames per second on average; median frame %s, 95th percentile %s, 99th %s, worst %s. ",
                fps, Stats.ms(p50), Stats.ms(r.frames.p95()), Stats.ms(r.frames.p99()), Stats.ms(r.frames.max())));
        d.append(String.format(Locale.ROOT, "Per frame on the render thread: world and GUI rendering %s, client ticks %s, buffer swap and frame limiter %s (%s of the frame).",
                Stats.ms(gamePer), Stats.ms(tickPer), Stats.ms(displayPer), pct(displayPer / meanFrame)));
        if (r.frameGpu.count() > 10) {
            d.append(String.format(Locale.ROOT, " The GPU took a median %s from the start to the end of each frame's commands.", Stats.ms(r.frameGpu.p50())));
        }
        String verdict;
        if (limit < 260 && Math.abs(1000.0 / limit - p50 / 1e6) < 1.5) {
            verdict = "Frame rate is held by the " + limit + " FPS limit";
        } else if (vsync && displayPer / meanFrame > 0.3) {
            verdict = "Frame rate is likely held by VSync";
        } else if (displayPer / meanFrame > 0.35 && r.frameGpu.count() > 10 && r.frameGpu.p50() > 0.8 * p50) {
            verdict = "Frames look GPU-bound";
            d.append(" The render thread spends a large share of each frame waiting in buffer swap while GPU work fills most of the frame.");
        } else if (displayPer / meanFrame < 0.15) {
            verdict = "Frames look CPU-bound on the render thread";
            d.append(" Little time is spent waiting on the GPU; the render thread's own work sets the pace.");
        } else {
            verdict = "Frame pacing is mixed between CPU work and GPU waiting";
        }
        add("info", "frames", verdict, d.toString(), "Exact section timings from Minecraft's profiler markers; GPU times from OpenGL timestamp queries.", "frames");

        // Heaviest render sections by own (self) time.
        List<int[]> heavy = new ArrayList<>();
        for (int i = 1; i < t.tree.size; i++) heavy.add(new int[] {i});
        heavy.sort(Comparator.comparingLong((int[] a) -> -t.tree.self(a[0])));
        StringBuilder top = new StringBuilder();
        int shown = 0;
        for (int[] a : heavy) {
            int node = a[0];
            String path = r.path(t, node);
            if (path.endsWith("updateDisplay") || path.endsWith("yield")) continue;
            double per = t.tree.self(node) / n;
            if (per < 0.05e6) break;
            top.append(String.format(Locale.ROOT, "%s — %s/frame own time", path, Stats.ms(per)));
            if (t.tree.gpuCalls[node] > 0) top.append(String.format(Locale.ROOT, ", GPU %s/frame", Stats.ms((double) t.tree.gpuTotal[node] / n)));
            if (t.tree.samples[node] > 0) top.append(", ").append(t.tree.samples[node]).append(" JVM samples inside");
            top.append("; ");
            if (++shown == 6) break;
        }
        if (shown > 0) {
            add("info", "frames", "Where render-thread time goes", top.toString(),
                    "Own time excludes nested sections. Sections are Minecraft's and mods' profiler markers.", "sections:render");
        }
    }

    /** Framebuffer size and where GPU time goes, section by section. */
    private void gpu() {
        var env = r.data.environment();
        if (env.has("framebufferWidth")) {
            int w = env.get("framebufferWidth").getAsInt(), h = env.get("framebufferHeight").getAsInt();
            if (w < 640 || h < 360) {
                add("high", "frames", "The game was rendering at a tiny size",
                        String.format(Locale.ROOT, "The framebuffer was %d×%d pixels during this capture (window minimised or broken resize?). "
                                + "Frame and GPU numbers from it don't reflect normal play.", w, h),
                        "Main render target size read from the game.", "capture");
            }
        }
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frameGpu.count() < 30) return;
        double frames = r.frames.count();
        // Render-thread CPU work per frame: everything except waiting for the display.
        int display = r.node(t, "updateDisplay");
        if (display < 0) display = r.node(t, "render", "updateDisplay");
        int yield = r.node(t, "yield");
        double cpu = r.frames.mean() - (display < 0 ? 0 : t.tree.total[display] / frames) - (yield < 0 ? 0 : t.tree.total[yield] / frames);
        double gpu = r.frameGpu.p50();
        List<Integer> nodes = new ArrayList<>();
        for (int i = 1; i < t.tree.size(); i++) if (t.tree.gpuCalls[i] > 0) nodes.add(i);
        // Rank by GPU time not explained by GPU-timed children (own GPU time), so a pass isn't hidden by its parent.
        double[] own = new double[t.tree.size()];
        for (int i : nodes) {
            own[i] = t.tree.gpuTotal[i];
            int p = t.tree.parent[i];
            if (p > 0 && t.tree.gpuCalls[p] > 0) own[p] -= t.tree.gpuTotal[i];
        }
        nodes.sort(Comparator.comparingDouble(i -> -own[i]));
        StringBuilder top = new StringBuilder();
        int shown = 0;
        for (int i : nodes) {
            if (own[i] / frames < 0.05e6) break;
            top.append(Contenders.friendly(r.path(t, i))).append(' ').append(Stats.ms(own[i] / frames)).append("; ");
            if (++shown == 8) break;
        }
        boolean gpuBound = gpu > cpu * 1.3;
        String shader = env.has("shaderPack") && env.has("shadersEnabled") && env.get("shadersEnabled").getAsBoolean()
                ? " Shader pack: " + env.get("shaderPack").getAsString() + "." : "";
        add(gpuBound ? "medium" : "info", "frames", gpuBound ? "GPU-bound" : "GPU has headroom",
                String.format(Locale.ROOT, "GPU %s per frame (median) vs %s of render-thread CPU work.%s Biggest GPU steps (own time, per frame): %s",
                        Stats.ms(gpu), Stats.ms(Math.max(0, cpu)), shader, top.length() == 0 ? "none measured" : top),
                "OpenGL timestamp queries around each render section and shader pass, on every " + Math.max(1, r.capture.options.gpuEvery())
                        + (r.capture.options.gpuEvery() == 1 ? "" : "th") + " frame and scaled to per-frame; a step's own time excludes GPU-timed steps inside it."
                        + (r.gpu() != null ? String.format(Locale.ROOT, " The timer calls themselves took %s per frame of render-thread time (included in the CPU figure).",
                        Stats.ms((double) r.gpu().glNanos() / Math.max(1, r.frames.count()))) : ""),
                "sections:render");
    }

    private void renderSpikes() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frames.count() < 30) return;
        long threshold = Math.max((long) (r.frames.p50() * 2.5), r.frames.p50() + 10_000_000L);
        int count = 0;
        for (int i = 0; i < t.ticks.size(); i++) if (t.ticks.duration[i] > threshold) count++;
        if (count == 0) return;
        List<SpanDigest.Spike> spikes = t.spikes();
        StringBuilder d = new StringBuilder();
        d.append(count).append(count == 1 ? " frame took longer than " : " frames took longer than ").append(Stats.ms(threshold))
                .append(String.format(Locale.ROOT, " (%s per minute). ", f1(count / r.seconds() * 60)));
        int shown = 0;
        for (SpanDigest.Spike s : spikes) {
            if (s.duration <= threshold || shown == 3) break;
            d.append("At ").append(f1((s.start - r.capture.startNanos) / 1e9)).append(" s: ").append(Stats.ms(s.duration))
                    .append(", mostly ").append(dominant(t, s)).append(context(s.start, s.start + s.duration)).append(". ");
            shown++;
        }
        String severity = count / r.seconds() * 60 > 30 ? "high" : count / r.seconds() * 60 > 5 ? "medium" : "low";
        add(severity, "frames", "Frame-time spikes", d.toString(),
                "Spike detail is the exact section timeline of each slow frame; overlaps with GC, JVM pauses and logs are coincidences in time, not proof of cause.",
                "spikes:render");
    }

    /** The deepest section that covers most of a spike. */
    private String dominant(SpanDigest.ThreadSpans t, SpanDigest.Spike s) {
        int best = -1;
        int bestDepth = -1;
        long bestDuration = 0;
        for (int i = 0; i < s.spans; i++) {
            if (s.spanDuration[i] >= s.duration * 0.5 && s.depth[i] > bestDepth) {
                best = s.node[i];
                bestDepth = s.depth[i];
                bestDuration = s.spanDuration[i];
            }
        }
        if (best < 0) return "spread over many sections";
        double typical = t.tree.calls[best] == 0 ? 0 : (double) t.tree.total[best] / t.tree.calls[best];
        return r.path(t, best) + " (" + Stats.ms(bestDuration) + ", typically " + Stats.ms(typical) + ")";
    }

    /** JVM events overlapping an interval, as a short phrase. */
    private String context(long from, long to) {
        List<String> parts = new ArrayList<>();
        if (r.jfr != null) {
            long gcPause = 0;
            for (JfrDigest.Gc gc : r.jfr.gcs) if (gc.time() < to && gc.time() + gc.duration() > from) gcPause += gc.longestPause();
            if (gcPause > 1_000_000) parts.add("GC pause " + Stats.ms(gcPause));
            long stall = 0;
            for (JfrDigest.Pause p : r.jfr.allocationStalls) if (p.time() < to && p.time() + p.duration() > from) stall = Math.max(stall, p.duration());
            if (stall > 0) parts.add("allocation stall " + Stats.ms(stall));
            for (JfrDigest.Pause p : r.jfr.vmOperations) {
                if (p.time() < to && p.time() + p.duration() > from && p.duration() > 2_000_000) {
                    parts.add("JVM " + p.detail() + " " + Stats.ms(p.duration()));
                    break;
                }
            }
            for (JfrDigest.Pause p : r.jfr.blocking) {
                if (p.thread().equals("Render thread") && p.time() < to && p.time() + p.duration() > from) {
                    parts.add("render thread blocked (" + p.kind() + " on " + shortClass(p.detail()) + ") " + Stats.ms(p.duration()));
                    break;
                }
            }
        }
        if (r.data.logs() != null) {
            int logs = 0;
            long[] times = r.data.logs().times();
            for (long time : times) if (time >= from && time <= to) logs++;
            if (logs > 0) parts.add(logs + " warning/error log lines");
        }
        return parts.isEmpty() ? "" : "; at the same time: " + String.join(", ", parts);
    }

    private static String shortClass(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    // --- server -----------------------------------------------------------------------------

    private void server() {
        SpanDigest.ThreadSpans t = r.server;
        if (t == null || r.serverWork.count() < 20) return;
        Stats w = r.serverWork;
        int over = 0;
        for (long v : r.serverWorkPerTick) if (v > 50_000_000L) over++;
        String severity = w.p95() > 50_000_000L ? "high" : over > 0 ? "medium" : "info";
        StringBuilder d = new StringBuilder(String.format(Locale.ROOT,
                "Integrated/dedicated server ticks: median %s of work, 95th percentile %s, worst %s. %d of %d ticks exceeded the 50 ms budget.",
                Stats.ms(w.p50()), Stats.ms(w.p95()), Stats.ms(w.max()), over, w.count()));
        // Per-dimension and top sections by own time.
        List<Integer> nodes = new ArrayList<>();
        for (int i = 1; i < t.tree.size; i++) nodes.add(i);
        nodes.sort(Comparator.comparingLong((Integer i) -> -t.tree.self(i)));
        d.append(" Largest own-time sections per tick: ");
        int shown = 0;
        for (int node : nodes) {
            String path = r.path(t, node);
            if (path.startsWith("nextTickWait")) continue;
            d.append(path).append(" ").append(Stats.ms((double) t.tree.self(node) / w.count())).append("; ");
            if (++shown == 5) break;
        }
        add(severity, "server", over > 0 ? "Server ticks over budget" : "Server tick time", d.toString(),
                "Exact timings from the server's profiler markers. Work excludes the idle wait between ticks.", "sections:server");
    }

    // --- world ------------------------------------------------------------------------------

    private void world() {
        for (WorldDigest.Level level : r.world.levels) {
            double seconds = r.seconds();
            long total = level.entityNanos + level.blockEntityNanos;
            if (total < 0.002e9 * seconds) continue; // under 0.2% of a core: not worth a finding
            String side = level.client ? "client " : "";
            StringBuilder d = new StringBuilder(String.format(Locale.ROOT,
                    "%s%s: entities %s per second (%d ticks), block entities %s per second (%d ticks). ",
                    side, level.dimension, Stats.ms(level.entityNanos / seconds), level.entityTicks,
                    Stats.ms(level.blockEntityNanos / seconds), level.blockEntityTicks));
            List<WorldDigest.TypeTotal> types = level.topTypes();
            if (!types.isEmpty()) {
                d.append("Most expensive types: ");
                for (int i = 0; i < Math.min(4, types.size()); i++) {
                    WorldDigest.TypeTotal tt = types.get(i);
                    d.append(String.format(Locale.ROOT, "%s ×%d %s/s (%s of this level); ", tt.type, tt.instances,
                            Stats.ms(tt.nanos / seconds), pct((double) tt.nanos / total)));
                }
            }
            List<WorldDigest.Chunk> chunks = level.topChunks();
            if (!chunks.isEmpty()) {
                WorldDigest.Chunk c = chunks.get(0);
                d.append(String.format(Locale.ROOT, "Costliest chunk %d, %d (blocks %d, %d): %s/s, %s of this level's entity and block-entity time.",
                        c.x, c.z, c.x * 16, c.z * 16, Stats.ms(c.nanos() / seconds), pct((double) c.nanos() / total)));
            }
            double share = total / seconds / 50_000_000.0 / 20; // fraction of a 20 TPS budget
            String severity = level.client ? "low" : share > 0.4 ? "high" : share > 0.15 ? "medium" : "low";
            add(severity, "world", "Entity and block-entity cost in " + level.dimension + (level.client ? " (client)" : ""),
                    d.toString(), "Every tick is timed, not sampled. An entity's chunk is where it last ticked.", "world:" + level.dimension);
            for (WorldDigest.Instance inst : level.topInstances()) {
                if (inst.max() > 20_000_000L) {
                    add("medium", "world", "Single slow " + inst.kind() + " tick",
                            String.format(Locale.ROOT, "%s at %d %d %d in %s took up to %s in one tick (%d ticks, %s total).",
                                    inst.type(), inst.x(), inst.y(), inst.z(), level.dimension, Stats.ms(inst.max()), inst.ticks(), Stats.ms(inst.nanos())),
                            "Exact timing of that one instance.", "world:" + level.dimension);
                    break;
                }
            }
        }
    }

    // --- memory and JVM -----------------------------------------------------------------------

    private void memory() {
        var metrics = r.data.metrics();
        if (metrics.size() >= 3) {
            int nearMax = 0;
            long max = metrics.get(0).heapMax();
            for (var m : metrics) if (max > 0 && m.heapUsed() > 0.9 * max) nearMax++;
            double frac = (double) nearMax / metrics.size();
            if (frac > 0.25) {
                add(frac > 0.6 ? "high" : "medium", "memory", "Heap close to its limit",
                        String.format(Locale.ROOT, "Heap use was above 90%% of the %d MB maximum in %s of the once-a-second readings. "
                                + "The collector has little room, so collections run more often and allocation can stall.", max >> 20, pct(frac)),
                        "Heap readings from the JVM once per second.", "memory");
            }
        }
        liveSet();
        heapSnapshot();
        deepMemory();
        if (r.jfr == null) return;
        long stalled = 0;
        Map<String, Long> byThread = new HashMap<>();
        for (JfrDigest.Pause p : r.jfr.allocationStalls) {
            stalled += p.duration();
            byThread.merge(p.thread(), p.duration(), Long::sum);
        }
        if (stalled > 0) {
            StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "Threads waited %s in total for the garbage collector to free memory. ", Stats.ms(stalled)));
            byThread.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4)
                    .forEach(e -> d.append(e.getKey()).append(" ").append(Stats.ms(e.getValue())).append("; "));
            boolean game = byThread.containsKey("Render thread") || byThread.containsKey("Server thread");
            add(game ? "high" : "medium", "memory", "Allocation stalls", d.toString(),
                    "JFR ZGC allocation-stall events (exact durations).", "memory");
        }
        if (r.jfr.allocationWeight > 0) {
            double rate = r.jfr.allocationWeight / r.seconds() / (1 << 20);
            StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "Estimated allocation rate %.0f MB/s. By mod: ", rate));
            r.jfr.allocByMod.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(6)
                    .forEach(e -> d.append(String.format(Locale.ROOT, "%s %.0f MB/s; ", e.getKey(), e.getValue().weight / r.seconds() / (1 << 20))));
            d.append("Top allocation sites: ");
            r.jfr.allocBySite.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(5)
                    .forEach(e -> d.append(e.getKey()).append(" (").append(pct((double) e.getValue().weight / r.jfr.allocationWeight)).append("); "));
            add(rate > 1500 ? "medium" : "info", "memory", "Allocation pressure", d.toString(),
                    "JFR allocation samples, weighted by bytes: an estimate of where memory is allocated, not of what is retained.", "allocations");
        }
        if (!r.jfr.gcs.isEmpty()) {
            long pauses = 0, longest = 0;
            for (JfrDigest.Gc gc : r.jfr.gcs) {
                pauses += gc.sumOfPauses();
                longest = Math.max(longest, gc.longestPause());
            }
            add(longest > 50_000_000L ? "medium" : "info", "memory", "Garbage collection",
                    String.format(Locale.ROOT, "%d collections (%s per second) by %s/%s; pauses %s in total, longest %s.",
                            r.jfr.gcs.size(), f1(r.jfr.gcs.size() / r.seconds()), r.jfr.youngCollector, r.jfr.oldCollector,
                            Stats.ms(pauses), Stats.ms(longest)),
                    "JFR garbage-collection events.", "memory");
        }
    }

    /** Memory still in use after collections, against the maximum heap. */
    private void liveSet() {
        Phases.Stat w = Phases.whole(r);
        if (w.liveP50() <= 0 || w.heapMax() <= 0) return;
        double share = (double) w.liveP50() / w.heapMax(), peak = (double) w.liveMax() / w.heapMax();
        if (share < 0.6 && peak < 0.8) return;
        String severity = share > 0.75 || w.stalls() > 0 ? "high" : "medium";
        add(severity, "memory", "Memory still in use is close to the limit",
                String.format(Locale.ROOT, "After each clean-up, typically %d MB (up to %d MB) was still in use, out of a %d MB limit (%s, peak %s). "
                                + "New memory was being taken at about %.0f MB/s. With this little room the collector can't keep up, which is when the game "
                                + "has to wait for memory%s. Options: a larger -Xmx, or reducing what is held (see the memory snapshot, /lightning heap).",
                        w.liveP50() >> 20, w.liveMax() >> 20, w.heapMax() >> 20, pct(share), pct(peak), w.allocMBps(),
                        w.stalls() > 0 ? String.format(Locale.ROOT, " (%d such waits here, %s in total)", w.stalls(), Stats.ms(w.stallNanos())) : ""),
                "Heap in use right after collection, read once per second; allocation rate from JFR samples.", "memory");
    }

    private void deepMemory() {
        String text = r.data.deepMemory();
        if (text == null) return;
        StringBuilder d = new StringBuilder();
        boolean table = false;
        int shown = 0;
        for (String line : text.split("\\R")) {
            if (line.startsWith("## Memory by owner")) {
                table = true;
                continue;
            }
            if (!table || line.startsWith("owner")) continue;
            if (line.isBlank() || line.startsWith("##")) break;
            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length >= 2) d.append(parts[0]).append(' ').append(parts[1]).append(" MB; ");
            if (++shown == 8) break;
        }
        add("info", "memory", "Who holds memory", d.length() == 0 ? text.lines().findFirst().orElse("") : d.toString(),
                "Heap dump analysed after the capture: each object is charged to the nearest mod object keeping it alive.", "memory");
    }

    private void heapSnapshot() {
        HeapHistogram h = r.data.heap();
        if (h == null) return;
        StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "%d MB in use after a full clean-up. By owner: ", h.totalBytes >> 20));
        int n = 0;
        for (var e : h.owners()) {
            if (n++ == 8) break;
            d.append(String.format(Locale.ROOT, "%s %d MB (%s); ", e.getKey(), e.getValue()[0] >> 20, pct((double) e.getValue()[0] / h.totalBytes)));
        }
        d.append("Plain arrays can belong to any mod; a class count can't say which.");
        add("info", "memory", "What is filling memory", d.toString(),
                String.format(Locale.ROOT, "JVM class histogram at the end of the capture (paused the game %s).", Stats.ms(h.pauseNanos)), "memory");
    }

    private void jvmPauses() {
        if (r.jfr == null) return;
        Map<String, long[]> byOp = new HashMap<>();
        for (JfrDigest.Pause p : r.jfr.vmOperations) {
            if (p.detail().contains("GC") || p.detail().startsWith("Z")) continue;
            long[] agg = byOp.computeIfAbsent(p.detail(), k -> new long[3]);
            agg[0]++;
            agg[1] += p.duration();
            agg[2] = Math.max(agg[2], p.duration());
        }
        byOp.entrySet().stream().filter(e -> e.getValue()[1] > 20_000_000L)
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(3)
                .forEach(e -> add(e.getValue()[2] > 16_000_000L ? "medium" : "low", "jvm", "JVM pause: " + e.getKey(),
                        String.format(Locale.ROOT, "%d times, %s in total, longest %s. These stop every Java thread, including rendering.%s",
                                e.getValue()[0], Stats.ms(e.getValue()[1]), Stats.ms(e.getValue()[2]),
                                e.getKey().contains("ThreadDump") || e.getKey().contains("GetThreadInfo")
                                        ? " Thread dumps usually come from a profiler or monitoring tool taking stack snapshots." : ""),
                        "JFR VM-operation events (exact durations).", "jvm"));
    }

    private void blocking() {
        if (r.jfr == null) return;
        for (String thread : new String[] {"Render thread", "Server thread"}) {
            long total = 0;
            Map<String, long[]> byDetail = new HashMap<>();
            for (JfrDigest.Pause p : r.jfr.blocking) {
                if (!p.thread().equals(thread)) continue;
                total += p.duration();
                String top = p.stack().isEmpty() ? p.detail() : p.stack().lines().filter(l -> !l.startsWith("java.") && !l.startsWith("jdk."))
                        .findFirst().orElse(p.detail());
                long[] agg = byDetail.computeIfAbsent(p.kind() + " in " + top, k -> new long[2]);
                agg[0]++;
                agg[1] += p.duration();
            }
            if (total < 50_000_000L) continue;
            StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "The %s was blocked for %s (%s of the capture) waiting on locks or other threads. ",
                    thread.toLowerCase(Locale.ROOT), Stats.ms(total), pct(total / (double) r.durationNanos)));
            byDetail.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(3)
                    .forEach(e -> d.append(e.getKey()).append(" — ").append(e.getValue()[0]).append("×, ").append(Stats.ms(e.getValue()[1])).append("; "));
            add(total > 0.05 * r.durationNanos ? "high" : "medium", "jvm", thread + " blocked", d.toString(),
                    "JFR lock and park events over 5–10 ms, with stacks.", "jvm");
        }
    }

    private void threadsCpu() {
        if (r.jfr == null || r.jfr.threadCpu.isEmpty()) return;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<String, double[]> groups = new HashMap<>();
        for (var e : r.jfr.threadCpu.entrySet()) {
            JfrDigest.ThreadCpu c = e.getValue();
            double avgCores = c.sum / Math.max(1.0, r.seconds()) * cores;
            double[] g = groups.computeIfAbsent(c.group, k -> new double[2]);
            g[0] += avgCores;
            g[1]++;
        }
        StringBuilder d = new StringBuilder("Average CPU use by thread group, in cores: ");
        groups.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(8)
                .forEach(e -> d.append(e.getKey()).append(e.getValue()[1] > 1 ? " ×" + (int) e.getValue()[1] : "").append(" ")
                        .append(String.format(Locale.ROOT, "%.2f", e.getValue()[0])).append("; "));
        add("info", "jvm", "CPU by thread", d.toString(), "JFR per-thread CPU load, once per second.", "threads");
        int busy = 0;
        double total = 0;
        for (var e : r.jfr.threadCpu.values()) {
            double avg = e.sum / Math.max(1.0, r.seconds()) * cores;
            total += avg;
            if (avg >= 0.25) busy++;
        }
        if (busy > cores || total > 0.85 * cores) {
            StringBuilder o = new StringBuilder(String.format(Locale.ROOT,
                    "%d threads were busy (over a quarter of a core each) on %d logical CPUs, using %.1f cores on average. "
                            + "When more threads want CPU than there are cores, they take turns, and the game's own threads wait too. Busiest pools: ",
                    busy, cores, total));
            groups.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(5).forEach(e -> {
                o.append(String.format(Locale.ROOT, "%s ×%d %.1f cores", e.getKey(), (int) e.getValue()[1], e.getValue()[0]));
                JfrDigest.PoolStats ps = r.jfr.pools.get(e.getKey());
                if (ps != null && !ps.owners.isEmpty()) {
                    o.append(" (running ");
                    ps.owners.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                            .forEach(x -> o.append(x.getKey()).append(' ').append(pct((double) x.getValue() / ps.samples)).append(", "));
                    o.setLength(o.length() - 2);
                    o.append(')');
                }
                o.append("; ");
            });
            add(total > cores ? "high" : "medium", "jvm", "More busy threads than CPU cores", o.toString(),
                    "JFR per-thread CPU load; which mods run on each pool comes from JVM samples.", "threads");
        }
    }

    private void logs() {
        var logs = r.data.logs();
        if (logs == null || logs.kinds().isEmpty()) return;
        long errors = logs.kinds().stream().filter(k -> !k.level.equals("WARN")).mapToLong(k -> k.count).sum();
        var spam = logs.kinds().stream().filter(k -> k.count >= 50).sorted((a, b) -> b.count - a.count).limit(3).toList();
        for (var k : spam) {
            add(k.count / r.seconds() > 5 ? "medium" : "low", "logs", "Repeated log message from " + k.logger,
                    String.format(Locale.ROOT, "Logged %d times (%s per second): \"%s\". Formatting and writing log lines costs CPU and disk I/O on the thread that logs.",
                            k.count, f1(k.count / r.seconds()), k.message.length() > 160 ? k.message.substring(0, 160) + "…" : k.message),
                    "Exact count of log events during the capture.", "logs");
        }
        if (errors > 0) {
            add("low", "logs", "Errors logged during the capture", errors + " error-level log lines; see the Logs tab for messages and stack traces.",
                    "Log events during the capture.", "logs");
        }
    }

    private void lightning() {
        long events = r.totalEvents();
        long lost = r.lostEvents();
        StringBuilder d = new StringBuilder(String.format(Locale.ROOT, "Recorded %,d section events (%s per second)", events, f1(events / r.seconds())));
        if (r.data.jfrThreadCpuNanos() >= 0) d.append(", JFR's own threads used ").append(Stats.ms(r.data.jfrThreadCpuNanos())).append(" of CPU");
        var gpu = r.gpu();
        if (gpu != null) {
            d.append(String.format(Locale.ROOT, ", GPU timer calls took %s on the render thread (%s per frame)", Stats.ms(gpu.glNanos()),
                    Stats.ms(r.frames.count() == 0 ? 0 : (double) gpu.glNanos() / r.frames.count())));
        }
        d.append(". ");
        if (lost > 0) d.append(String.format(Locale.ROOT, "%,d events were dropped because the writer fell behind; affected frames are excluded. ", lost));
        add(lost > 0 ? "medium" : "info", "lightning", "Recording cost", d.toString(),
                "Lightning's own accounting; see the Capture tab for details.", "capture");
    }
}
