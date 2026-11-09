package dev.lightning.analysis;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * results.json: the headline numbers of a capture in a fixed shape, for comparing runs and for
 * tools such as benchmark mods. Milliseconds and MB unless a key says otherwise.
 *
 * <pre>
 * schema_version 2
 * { schema_version, format, id, label, startedAt, mods,
 *   overhead: { gameThreadPct, backgroundCores }, modules: [parts recorded], options: "start options",
 *   io?: { totalMs, gameThreadMs }, loading?: { phases, steps, reloads, classLoading, threads, ... },
 *   whole: Phase, phases: { name: Phase, ... }, phaseOrder: [names], listeners[{thread, event (method that posted it), listener, mod, samples, threadPct, ms: per frame on Render thread, per tick on Server thread, else per second}], slowFrames{verdict, pools[], topSections[], sections[], methods[], memory{}, menuScreens{}},
 *   findings: [{severity, title}], contenders: [{title, size}], memorySnapshot? }
 * Phase = { seconds, fps, frameP50/P95/P99/Max, gpuFrameP50, tickP50/P95/Max, ticksOver50ms,
 *           allocationStalls, allocationStallMs, allocMBps, gcPauseMs, gcLongestPauseMs,
 *           liveSetMBp50, liveSetMBmax, heapMaxMB, coresByThreadPool{},
 *           topModsCpu[{mod, sharePct}], gpuMsPerFrame[{section, ms}], allocByMod[{mod, MBps}], allocOnStackByMod[{mod, MBps}], ownModsCpu[{mod, sharePct}],
 *           gpuUntimedMs, gpuQueueLagP50/P95, gpuFrameGapP50, spikes[{atSeconds, ms, section}] }
 * </pre>
 */
final class Results {
    static final int SCHEMA_VERSION = 2;

    private final Report r;
    private final Map<Integer, Set<String>> ownersOfLeaf = new HashMap<>();

    private Results(Report r) {
        this.r = r;
    }

    static byte[] write(Report r) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(new Results(r).root()).getBytes(StandardCharsets.UTF_8);
    }

    private JsonObject root() {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("format", "lightning-results");
        root.addProperty("id", r.capture.id);
        root.addProperty("label", r.capture.label);
        root.addProperty("startedAt", r.capture.startEpochMillis);
        root.addProperty("mods", r.data.environment().has("modCount") ? r.data.environment().get("modCount").getAsInt() : 0);
        JsonObject overhead = new JsonObject();
        overhead.addProperty("gameThreadPct", round(r.gameThreadOverheadPct(), 2));
        overhead.addProperty("backgroundCores", round(r.backgroundCores(), 2));
        root.add("overhead", overhead);
        // What this run recorded, so scripts comparing runs can check they compare like with like.
        JsonArray modules = new JsonArray();
        for (String m : r.capture.options.modules()) modules.add(m);
        root.add("modules", modules);
        root.addProperty("options", dev.lightning.capture.CaptureOptions.toArgs(r.capture.options));
        JsonObject run = runHeader();
        root.add("run", run);
        // Per-mod cost in absolute units for comparing the same scenario on two machines.
        if (r.jfr != null) root.add("crosspc", CrossPc.build(r, run));
        if (r.jfr != null && !r.jfr.io.isEmpty()) {
            long total = 0, game = 0;
            for (long[] v : r.jfr.io.values()) {
                total += v[1];
                game += v[3];
            }
            JsonObject io = new JsonObject();
            io.addProperty("totalMs", round(total / 1e6, 1));
            io.addProperty("gameThreadMs", round(game / 1e6, 1));
            root.add("io", io);
        }
        root.add("whole", stat(Phases.whole(r)));
        SlowFrames slow = r.slow();
        if (slow != null) root.add("slowFrames", slowFrames(slow));
        SlowFrames slowTicks = r.slowTicks();
        if (slowTicks != null) root.add("slowTicks", slowFrames(slowTicks));
        java.util.List<Listeners.Row> listeners = Listeners.of(r);
        if (!listeners.isEmpty()) {
            JsonArray a = new JsonArray();
            for (Listeners.Row l : listeners) {
                JsonObject x = new JsonObject();
                x.addProperty("thread", l.thread());
                x.addProperty("event", l.event());
                x.addProperty("listener", l.listener());
                x.addProperty("mod", l.mod());
                x.addProperty("samples", l.samples());
                x.addProperty("threadPct", round(l.threadPct(), 2));
                x.addProperty("ms", round(l.ms(), 3));
                a.add(x);
            }
            root.add("listeners", a);
        }
        JsonObject phases = new JsonObject();
        JsonArray order = new JsonArray();
        Map<String, Integer> seen = new HashMap<>();
        for (Phases.Stat s : Phases.marked(r)) {
            String key = s.label();
            int n = seen.merge(key, 1, Integer::sum);
            if (n > 1) key = key + " #" + n; // a phase name used twice keeps both intervals
            phases.add(key, stat(s));
            order.add(key);
        }
        root.add("phases", phases);
        root.add("phaseOrder", order);
        LoadDigest load = r.load();
        if (load != null) {
            // Loading summary without the per-thread timelines, which only the viewer needs.
            JsonObject l = load.json.deepCopy();
            if (l.has("threads")) for (var t : l.getAsJsonArray("threads")) t.getAsJsonObject().remove("timeline");
            root.add("loading", l);
        }
        JsonArray findings = new JsonArray();
        for (Finding f : Findings.compute(r)) {
            JsonObject o = new JsonObject();
            o.addProperty("severity", f.severity());
            o.addProperty("area", f.area());
            o.addProperty("title", f.title());
            findings.add(o);
        }
        root.add("findings", findings);
        JsonArray contenders = new JsonArray();
        for (Contenders.Contender c : Contenders.compute(r)) {
            JsonObject o = new JsonObject();
            o.addProperty("title", c.title());
            o.addProperty("size", c.size());
            contenders.add(o);
        }
        root.add("contenders", contenders);
        if (r.jfr != null && !r.jfr.gcs.isEmpty()) {
            // The collection with the longest pause, with G1's detail when recorded (0.5.16+).
            JfrDigest.Gc worst = r.jfr.gcs.stream().max((a, b) -> Long.compare(a.longestPause(), b.longestPause())).get();
            JsonObject g = new JsonObject();
            g.addProperty("gcId", worst.gcId());
            g.addProperty("atSeconds", round((worst.time() - r.capture.startNanos) / 1e9, 2));
            g.addProperty("name", worst.name());
            g.addProperty("cause", worst.cause());
            g.addProperty("pauseMs", ms(worst.longestPause()));
            JfrDigest.GcDetail d = r.jfr.gcDetails.get(worst.gcId());
            if (d != null) {
                g.addProperty("type", d.type);
                g.addProperty("evacuationFailed", d.evacuationFailed);
                g.addProperty("evacuationFailedObjects", d.failedObjects);
                g.addProperty("evacuationFailedMB", round(d.failedBytes / 1048576.0, 2));
                g.addProperty("slowestPhase", d.slowestPhase);
                g.addProperty("slowestPhaseMs", ms(d.slowestPhaseNanos));
                if (d.tenuring >= 0) g.addProperty("tenuringThreshold", d.tenuring);
                JsonObject refs = new JsonObject();
                d.references.forEach(refs::addProperty);
                g.add("references", refs);
            }
            root.add("gcLongest", g);
            int failed = 0;
            for (JfrDigest.GcDetail x : r.jfr.gcDetails.values()) if (x.evacuationFailed) failed++;
            if (!r.jfr.gcDetails.isEmpty()) root.addProperty("gcEvacuationFailures", failed);
        }
        dev.lightning.capture.OtherProcesses.Usage other = r.capture.otherPrograms;
        if (other != null) {
            // Other programs' CPU over the capture (0.5.21+): a busy machine makes every number worse.
            JsonObject m = new JsonObject();
            m.addProperty("processors", other.processors());
            m.addProperty("gameCores", round(other.ownCores(), 2));
            m.addProperty("otherCores", round(other.otherCores(), 2));
            m.addProperty("otherJavaPrograms", other.otherJava());
            JsonArray top = new JsonArray();
            for (var o : other.others()) {
                if (o.cores() < 0.05 || top.size() == 10) break;
                JsonObject x = new JsonObject();
                x.addProperty("name", o.name());
                x.addProperty("count", o.count());
                x.addProperty("cores", round(o.cores(), 2));
                top.add(x);
            }
            m.add("otherPrograms", top);
            root.add("machineLoad", m);
        }
        if (r.jfr != null && !r.jfr.safepoints.isEmpty()) {
            // Time to reach each safepoint (0.5.18+): not part of any GC pause or VM-operation duration.
            JsonObject sp = new JsonObject();
            long total = 0, max = 0;
            int maxRunning = 0;
            long[] bins = new long[5]; // <1, 1-10, 10-50, 50-200, >=200 ms
            for (JfrDigest.SafepointSync p : r.jfr.safepoints) {
                total += p.duration();
                max = Math.max(max, p.duration());
                maxRunning = Math.max(maxRunning, p.running());
                long ms = p.duration() / 1_000_000L;
                bins[ms < 1 ? 0 : ms < 10 ? 1 : ms < 50 ? 2 : ms < 200 ? 3 : 4]++;
            }
            sp.addProperty("count", r.jfr.safepoints.size());
            sp.addProperty("syncTotalMs", ms(total));
            sp.addProperty("syncMaxMs", ms(max));
            sp.addProperty("maxRunningThreads", maxRunning);
            JsonObject hist = new JsonObject();
            String[] names = {"under1ms", "1to10ms", "10to50ms", "50to200ms", "over200ms"};
            for (int i = 0; i < 5; i++) hist.addProperty(names[i], bins[i]);
            sp.add("histogram", hist);
            JsonArray longest = new JsonArray();
            r.jfr.safepoints.stream().sorted((a, b) -> Long.compare(b.duration(), a.duration())).limit(5).forEach(p -> {
                JsonObject o = new JsonObject();
                o.addProperty("atSeconds", round((p.time() - r.capture.startNanos) / 1e9, 2));
                o.addProperty("ms", ms(p.duration()));
                o.addProperty("runningThreads", p.running());
                o.addProperty("iterations", p.iterations());
                JsonObject sampled = new JsonObject();
                r.jfr.poolsBetween(p.time(), p.time() + p.duration()).forEach(sampled::addProperty);
                o.add("sampledDuring", sampled);
                longest.add(o);
            });
            sp.add("longest", longest);
            root.add("safepoints", sp);
        }
        if (r.jfr != null && r.jfr.outsideTlabSeen) {
            // -Dlightning.bigAllocs=true: allocations at or over half a G1 region (humongous), by site.
            JsonObject big = new JsonObject();
            big.addProperty("thresholdMB", round(r.jfr.bigThreshold / 1048576.0, 1));
            JsonArray sites = new JsonArray();
            for (JfrDigest.BigAlloc a : r.jfr.bigAllocations()) {
                if (sites.size() == 30) break;
                JsonObject o = new JsonObject();
                o.addProperty("type", a.type());
                o.addProperty("mod", a.mod());
                o.addProperty("site", a.site());
                o.addProperty("count", a.count());
                o.addProperty("totalMB", round(a.bytes() / 1048576.0, 1));
                o.addProperty("largestMB", round(a.largest() / 1048576.0, 1));
                sites.add(o);
            }
            big.add("sites", sites);
            root.add("bigAllocations", big);
        }
        if (r.data.heap() != null) {
            JsonObject heap = new JsonObject();
            heap.addProperty("liveMB", r.data.heap().totalBytes >> 20);
            JsonObject owners = new JsonObject();
            for (var e : r.data.heap().owners()) {
                if (e.getValue()[0] < (1 << 20)) break;
                owners.addProperty(e.getKey(), e.getValue()[0] >> 20);
            }
            heap.add("byOwnerMB", owners);
            // Plain arrays split by allocating code, from JFR old-object samples (estimates).
            JsonArray arrays = new JsonArray();
            for (ArrayOwners.Row a : r.arrayOwners()) {
                if (a.bytes() < (1 << 20) || arrays.size() == 30) break;
                JsonObject o = new JsonObject();
                o.addProperty("type", a.type());
                o.addProperty("mod", a.mod());
                o.addProperty("site", a.site());
                o.addProperty("estMB", a.bytes() >> 20);
                o.addProperty("samples", a.samples());
                arrays.add(o);
            }
            if (!arrays.isEmpty()) heap.add("arraysBySite", arrays);
            root.add("memorySnapshot", heap);
        }
        return root;
    }

    /** What the run was measured on, so results from different settings can't be mixed up. */
    private JsonObject runHeader() {
        JsonObject env = r.data.environment();
        JsonObject run = new JsonObject();
        for (String key : new String[] {"shaderPack", "shadersEnabled", "renderDistance", "simulationDistance", "fpsLimit", "vsync", "graphics",
                "gpuRenderer", "maxHeapMb", "modCount", "context"}) {
            if (env.has(key)) run.add(key, env.get(key));
        }
        if (env.has("framebufferWidth")) run.addProperty("resolution", env.get("framebufferWidth").getAsInt() + "x" + env.get("framebufferHeight").getAsInt());
        if (env.has("shaderOptions")) {
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(env.get("shaderOptions").getAsString().getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < 6; i++) hex.append(String.format("%02x", d[i]));
                run.addProperty("shaderOptionsHash", hex.toString());
            } catch (java.security.NoSuchAlgorithmException ignored) {
                // Every JVM has SHA-1.
            }
        } else if (env.has("shaderPack")) {
            run.addProperty("shaderOptionsHash", "default");
        }
        return run;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static double ms(double nanos) {
        return Math.round(nanos / 1e4) / 100.0;
    }

    private JsonObject stat(Phases.Stat s) {
        JsonObject o = new JsonObject();
        o.addProperty("seconds", round(s.seconds(), 1));
        if (s.frames().count() > 0) {
            o.addProperty("fps", round(s.fps(), 1));
            o.addProperty("frameP50", ms(s.frames().p50()));
            o.addProperty("frameP95", ms(s.frames().p95()));
            o.addProperty("frameP99", ms(s.frames().p99()));
            o.addProperty("frameMax", ms(s.frames().max()));
            o.addProperty("frameMean", ms(s.frames().mean()));
            lows(o, s.from(), s.to());
        }
        if (s.frameGpu().count() > 0) o.addProperty("gpuFrameP50", ms(s.frameGpu().p50()));
        if (s.serverWork().count() > 0) {
            o.addProperty("tickP50", ms(s.serverWork().p50()));
            o.addProperty("tickP95", ms(s.serverWork().p95()));
            o.addProperty("tickMax", ms(s.serverWork().max()));
            o.addProperty("ticksOver50ms", s.ticksOver50());
        }
        o.addProperty("allocationStalls", s.stalls());
        o.addProperty("allocationStallMs", ms(s.stallNanos()));
        o.addProperty("allocMBps", Math.round(s.allocMBps()));
        o.addProperty("gcPauseMs", ms(s.gcPauseNanos()));
        o.addProperty("gcLongestPauseMs", ms(s.gcLongestNanos()));
        o.addProperty("liveSetMBp50", s.liveP50() >> 20);
        o.addProperty("liveSetMBmax", s.liveMax() >> 20);
        o.addProperty("heapMaxMB", s.heapMax() >> 20);
        JsonObject pools = new JsonObject();
        s.poolCores().entrySet().stream().limit(15).forEach(e -> pools.addProperty(e.getKey(), round(e.getValue(), 2)));
        o.add("coresByThreadPool", pools);
        o.add("topModsCpu", topModsCpu(s.from(), s.to()));
        o.add("ownModsCpu", ownModsCpu(s.from(), s.to()));
        o.add("gpuMsPerFrame", gpuSections(s.from(), s.to()));
        renderSections(o, s.from(), s.to());
        gpuCoverage(o, s.frameGpu(), s.from(), s.to());
        o.add("allocByMod", allocByMod(s.from(), s.to(), false));
        o.add("allocOnStackByMod", allocByMod(s.from(), s.to(), true));
        o.add("spikes", spikes(s.from(), s.to()));
        return o;
    }

    private Set<String> owners(int leaf) {
        return ownersOfLeaf.computeIfAbsent(leaf, l -> {
            Set<String> out = new HashSet<>();
            FlameTree cpu = r.jfr.cpu;
            for (int x = l; x > 0 && cpu.parent(x) > 0; x = cpu.parent(x)) {
                String o = r.jfr.frames.owner(cpu.frame(x));
                if (!JfrDigest.isBaseOwner(o)) out.add(o);
            }
            return out;
        });
    }

    /**
     * Share of all CPU samples in the interval whose running code (innermost frame outside the JDK and
     * libraries) belongs to each owner. Unlike topModsCpu, a mod that only wraps or hooks vanilla code
     * is not charged for the vanilla work under it.
     */
    private JsonArray ownModsCpu(long from, long to) {
        JsonArray out = new JsonArray();
        if (r.jfr == null) return out;
        JfrDigest.Samples s = r.jfr.allSamples;
        Map<Integer, String> ownerOfLeaf = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (int i = 0; i < s.size; i++) {
            if (s.time[i] < from || s.time[i] >= to) continue;
            total++;
            counts.merge(ownerOfLeaf.computeIfAbsent(s.leaf[i], l -> JfrDigest.ownCode(r.jfr.cpu, r.jfr.frames, l)), 1, Integer::sum);
        }
        final int all = Math.max(1, total);
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15).forEach(e -> {
            JsonObject m = new JsonObject();
            m.addProperty("mod", e.getKey());
            m.addProperty("sharePct", round(100.0 * e.getValue() / all, 2));
            out.add(m);
        });
        return out;
    }

    /** Share of all CPU samples in the interval that had each mod's code on the stack. */
    private JsonArray topModsCpu(long from, long to) {
        JsonArray out = new JsonArray();
        if (r.jfr == null) return out;
        JfrDigest.Samples s = r.jfr.allSamples;
        Map<String, Integer> counts = new HashMap<>();
        int total = 0;
        for (int i = 0; i < s.size; i++) {
            if (s.time[i] < from || s.time[i] >= to) continue;
            total++;
            for (String o : owners(s.leaf[i])) counts.merge(o, 1, Integer::sum);
        }
        final int all = Math.max(1, total);
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15).forEach(e -> {
            JsonObject m = new JsonObject();
            m.addProperty("mod", e.getKey());
            m.addProperty("sharePct", round(100.0 * e.getValue() / all, 2));
            out.add(m);
        });
        return out;
    }

    /** GPU ms per frame for each render section and shader pass, averaged over the frames measured in the interval. */
    private JsonArray gpuSections(long from, long to) {
        JsonArray out = new JsonArray();
        SpanDigest.ThreadSpans t = r.render;
        if (t == null) return out;
        SpanDigest.Ticks k = t.ticks;
        Map<Integer, Long> byNode = new HashMap<>();
        Set<Integer> frames = new HashSet<>();
        for (int i = 0; i < k.gpuSize; i++) {
            int tick = k.gpuTick[i];
            long start = k.start[tick];
            if (start < from || start >= to) continue;
            frames.add(tick);
            byNode.merge(k.gpuNode[i], k.gpuNanos[i], Long::sum);
        }
        int measured = Math.max(1, frames.size());
        byNode.entrySet().stream().sorted(Map.Entry.<Integer, Long>comparingByValue().reversed()).limit(25).forEach(e -> {
            JsonObject m = new JsonObject();
            m.addProperty("section", r.path(t, e.getKey()));
            m.addProperty("ms", ms((double) e.getValue() / measured));
            out.add(m);
        });
        return out;
    }

    /**
     * Render-thread sections with CPU and GPU time per frame: mean, p50 and p95 (inclusive; a
     * section includes the sections inside it). CPU covers every frame, GPU the GPU-timed frames.
     * Also how often the frame waited for the GPU (CPU time in the buffer swap).
     */
    private void renderSections(JsonObject o, long from, long to) {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null) return;
        SpanDigest.Ticks k = t.ticks;
        List<Integer> frames = new java.util.ArrayList<>();
        for (int i = 0; i < k.size(); i++) if (k.start[i] >= from && k.start[i] < to && k.duration[i] >= 0) frames.add(i);
        if (frames.isEmpty()) return;
        int nodes = t.tree.size;
        long[] cpuTotal = new long[nodes];
        for (int i : frames) for (int b = k.breakdownStart[i]; b < k.breakdownStart[i + 1]; b++) cpuTotal[k.breakdownNode[b]] += k.breakdownNanos[b];
        Map<Integer, Integer> gpuFrameIndex = new HashMap<>();
        long[] gpuTotal = new long[nodes];
        java.util.Set<Integer> inWindow = new HashSet<>(frames);
        for (int g = 0; g < k.gpuSize; g++) {
            if (!inWindow.contains(k.gpuTick[g])) continue;
            gpuFrameIndex.putIfAbsent(k.gpuTick[g], gpuFrameIndex.size());
            gpuTotal[k.gpuNode[g]] += k.gpuNanos[g];
        }
        int f = frames.size(), gf = gpuFrameIndex.size();
        // The sections worth listing: at least 0.005 ms per frame of CPU or GPU, biggest first, at most 150.
        Integer[] order = new Integer[nodes];
        for (int n = 0; n < nodes; n++) order[n] = n;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(Math.max(gpuTotal[b] / (double) Math.max(1, gf), cpuTotal[b] / (double) f),
                Math.max(gpuTotal[a] / (double) Math.max(1, gf), cpuTotal[a] / (double) f)));
        List<Integer> chosen = new java.util.ArrayList<>();
        for (int n : order) {
            if (n == 0) continue;
            if (Math.max(cpuTotal[n] / (double) f, gf == 0 ? 0 : gpuTotal[n] / (double) gf) < 5_000) break;
            chosen.add(n);
            if (chosen.size() == 150) break;
        }
        Map<Integer, Integer> slot = new HashMap<>();
        for (int c = 0; c < chosen.size(); c++) slot.put(chosen.get(c), c);
        // Percentiles from at most 40,000 evenly spaced frames, so long captures stay small in memory.
        int stride = Math.max(1, (f + 39_999) / 40_000);
        int pf = (f + stride - 1) / stride;
        long[][] cpuPer = new long[chosen.size()][pf];
        long[][] gpuPer = new long[chosen.size()][Math.max(1, Math.min(gf, 40_000))];
        for (int fi = 0; fi < f; fi += stride) {
            int i = frames.get(fi);
            for (int b = k.breakdownStart[i]; b < k.breakdownStart[i + 1]; b++) {
                Integer c = slot.get(k.breakdownNode[b]);
                if (c != null) cpuPer[c][fi / stride] += k.breakdownNanos[b];
            }
        }
        for (int g = 0; g < k.gpuSize; g++) {
            Integer gi = gpuFrameIndex.get(k.gpuTick[g]);
            if (gi == null || gi >= 40_000) continue;
            Integer c = slot.get(k.gpuNode[g]);
            if (c != null) gpuPer[c][gi] += k.gpuNanos[g];
        }
        JsonArray out = new JsonArray();
        for (int c = 0; c < chosen.size(); c++) {
            int n = chosen.get(c);
            JsonObject x = new JsonObject();
            x.addProperty("section", r.path(t, n));
            Stats cpu = Stats.of(cpuPer[c], pf);
            x.addProperty("cpuMs", ms(cpuTotal[n] / (double) f));
            x.addProperty("cpuP50", ms(cpu.p50()));
            x.addProperty("cpuP95", ms(cpu.p95()));
            if (gf > 0 && gpuTotal[n] > 0) {
                Stats gpu = Stats.of(gpuPer[c], Math.min(gf, 40_000));
                x.addProperty("gpuMs", ms(gpuTotal[n] / (double) gf));
                x.addProperty("gpuP50", ms(gpu.p50()));
                x.addProperty("gpuP95", ms(gpu.p95()));
            }
            out.add(x);
        }
        o.add("renderSections", out);
        o.addProperty("gpuTimedFrames", gf);
        // Frames that waited for the GPU: the buffer swap (updateDisplay) blocks while the GPU is behind.
        int swap = r.node(t, "updateDisplay");
        if (swap > 0) {
            long[] wait = new long[f];
            int bound = 0;
            for (int fi = 0; fi < f; fi++) {
                int i = frames.get(fi);
                for (int b = k.breakdownStart[i]; b < k.breakdownStart[i + 1]; b++) if (k.breakdownNode[b] == swap) wait[fi] += k.breakdownNanos[b];
                if (wait[fi] > 0.25 * k.duration[i] && wait[fi] > 1_000_000L) bound++;
            }
            Stats w = Stats.of(wait, f);
            o.addProperty("swapWaitP50", ms(w.p50()));
            o.addProperty("gpuBoundPct", round(100.0 * bound / f, 1));
        }
    }

    /**
     * How much of the GPU frame the sections explain, and whether the GPU runs behind the CPU:
     * gpuUntimedMs = mean GPU frame span minus the top-level sections' GPU time; gpuQueueLagP50 = how
     * long after submission the GPU reached a frame's first command (near 0: the GPU waits for the
     * CPU; large: GPU-bound); gpuFrameGapP50 = GPU time between one frame span's end and the next
     * one's start (the swap, present and anything else outside the frame's sections, or idle).
     */
    private void gpuCoverage(JsonObject o, Stats frameGpu, long from, long to) {
        if (frameGpu.count() > 0 && o.has("renderSections")) {
            double timed = 0;
            for (var e : o.getAsJsonArray("renderSections")) {
                JsonObject x = e.getAsJsonObject();
                if (x.has("gpuMs") && !x.get("section").getAsString().contains(" › ")) timed += x.get("gpuMs").getAsDouble();
            }
            if (timed > 0) o.addProperty("gpuUntimedMs", ms(Math.max(0, frameGpu.mean() - timed * 1e6)));
        }
        var gpu = r.gpu();
        if (gpu == null || gpu.lagCount() == 0) return;
        long[] lag = new long[gpu.lagCount()], gap = new long[gpu.lagCount()];
        int n = 0;
        for (int i = 0; i < gpu.lagCount(); i++) {
            long t = gpu.lagCpuTime(i);
            if (t < from || t >= to) continue;
            lag[n] = gpu.queueLagNanos(i);
            gap[n] = gpu.frameGapNanos(i);
            n++;
        }
        if (n == 0) return;
        Stats l = Stats.of(lag, n), g = Stats.of(gap, n);
        o.addProperty("gpuQueueLagP50", ms(l.p50()));
        o.addProperty("gpuQueueLagP95", ms(l.p95()));
        if (g.count() > 0) o.addProperty("gpuFrameGapP50", ms(g.p50()));
    }

    /** Slow gameplay frames vs normal ones (see SlowFrames). */
    private static JsonObject slowFrames(SlowFrames s) {
        JsonObject o = new JsonObject();
        o.addProperty("screensRecorded", s.screensKnown);
        o.addProperty("playFrames", s.playFrames);
        o.addProperty("menuFrames", s.menuFrames);
        o.addProperty("menuSlowFrames", s.menuSlow);
        JsonObject screens = new JsonObject();
        s.menuScreens.forEach((k, v) -> {
            JsonObject x = new JsonObject();
            x.addProperty("frames", v[0]);
            x.addProperty("slow", v[1]);
            screens.add(k, x);
        });
        o.add("menuScreens", screens);
        o.addProperty("playP50", ms(s.playP50));
        o.addProperty("slowOverMs", ms(s.threshold));
        o.addProperty("normalUnderMs", ms(s.normalLimit));
        o.addProperty("slow", s.slowCount);
        o.addProperty("normal", s.normalCount);
        o.addProperty("slowMeanMs", round(s.slowMeanMs, 2));
        o.addProperty("normalMeanMs", round(s.normalMeanMs, 2));
        o.addProperty("verdict", s.verdict);
        JsonArray pools = new JsonArray();
        for (SlowFrames.Pool p : s.pools) {
            JsonObject x = new JsonObject();
            x.addProperty("pool", p.pool());
            if (p.coresSlow() >= 0) {
                x.addProperty("coresSlowSeconds", round(p.coresSlow(), 2));
                x.addProperty("coresCalmSeconds", round(p.coresCalm(), 2));
                x.addProperty("extraCores", round(p.extraCores(), 2));
            }
            if (!Double.isNaN(p.sampleRatio())) x.addProperty("sampleRatio", round(p.sampleRatio(), 2));
            pools.add(x);
        }
        o.add("pools", pools);
        o.add("topSections", diffs(s.topSections));
        o.add("sections", diffs(s.sections));
        o.add("methods", diffs(s.methods));
        JsonObject mem = new JsonObject();
        if (s.slowInGcPct >= 0) {
            mem.addProperty("slowInGcPct", round(s.slowInGcPct, 1));
            mem.addProperty("normalInGcPct", round(s.normalInGcPct, 1));
        }
        if (s.slowInVmOpPct >= 0) {
            mem.addProperty("slowInVmPausePct", round(s.slowInVmOpPct, 1));
            mem.addProperty("normalInVmPausePct", round(s.normalInVmOpPct, 1));
            if (s.slowInSyncPct >= 0) {
                mem.addProperty("slowInSafepointSyncPct", round(s.slowInSyncPct, 1));
                mem.addProperty("normalInSafepointSyncPct", round(s.normalInSyncPct, 1));
            }
        }
        if (s.allocSlowMBps >= 0) {
            mem.addProperty("allocSlowMBps", Math.round(s.allocSlowMBps));
            mem.addProperty("allocNormalMBps", Math.round(s.allocNormalMBps));
        }
        if (s.heapSlowPct >= 0) {
            mem.addProperty("heapSlowPct", round(s.heapSlowPct, 1));
            mem.addProperty("heapNormalPct", round(s.heapNormalPct, 1));
        }
        o.add("memory", mem);
        return o;
    }

    private static JsonArray diffs(java.util.List<SlowFrames.Diff> list) {
        JsonArray out = new JsonArray();
        for (SlowFrames.Diff d : list) {
            JsonObject x = new JsonObject();
            x.addProperty("name", d.name());
            if (!d.owner().isEmpty()) x.addProperty("owner", d.owner());
            x.addProperty("slowMs", round(d.slowMs(), 3));
            x.addProperty("normalMs", round(d.normalMs(), 3));
            x.addProperty("extraMs", round(d.extraMs(), 3));
            if (d.slowPct() >= 0) {
                x.addProperty("slowFramesPct", round(d.slowPct(), 1));
                x.addProperty("normalFramesPct", round(d.normalPct(), 1));
            }
            out.add(x);
        }
        return out;
    }

    /**
     * 1% and 0.1% lows: 1000 / (mean frame time of the slowest 1% / 0.1% of frames), in FPS. The
     * slowest ceil(1% of frames) frames are used, at least one.
     */
    private void lows(JsonObject o, long from, long to) {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null) return;
        long[] d = new long[t.ticks.size()];
        int n = 0;
        for (int i = 0; i < t.ticks.size(); i++) {
            if (t.ticks.start[i] >= from && t.ticks.start[i] < to && t.ticks.duration[i] >= 0) d[n++] = t.ticks.duration[i];
        }
        if (n == 0) return;
        long[] v = java.util.Arrays.copyOf(d, n);
        java.util.Arrays.sort(v);
        for (double share : new double[] {0.01, 0.001}) {
            int k = Math.max(1, (int) Math.ceil(share * n));
            double sum = 0;
            for (int i = n - k; i < n; i++) sum += v[i];
            o.addProperty(share == 0.01 ? "low1PctFps" : "low01PctFps", round(1e9 / (sum / k), 1));
        }
    }

    /** onStack=false: by owner of the allocating code; true: by innermost add-on mod anywhere on the stack. */
    private JsonArray allocByMod(long from, long to, boolean onStack) {
        JsonArray out = new JsonArray();
        if (r.jfr == null) return out;
        JfrDigest j = r.jfr;
        long[] bytes = new long[j.allocModNames.size()];
        for (int i = 0; i < j.allocCount; i++) {
            int mod = onStack ? j.allocStackMod[i] : j.allocMod[i];
            if (mod >= 0 && j.allocTime[i] >= from && j.allocTime[i] < to) bytes[mod] += j.allocWeightAt[i];
        }
        double seconds = Math.max(1e-9, (to - from) / 1e9);
        Integer[] order = new Integer[bytes.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(bytes[b], bytes[a]));
        for (int i = 0; i < Math.min(15, order.length); i++) {
            if (bytes[order[i]] == 0) break;
            JsonObject m = new JsonObject();
            m.addProperty("mod", j.allocModNames.get(order[i]));
            m.addProperty("MBps", round(bytes[order[i]] / seconds / 1048576.0, 1));
            out.add(m);
        }
        return out;
    }

    /** The three slowest frames in the interval and the section that took most of each. */
    private JsonArray spikes(long from, long to) {
        JsonArray out = new JsonArray();
        SpanDigest.ThreadSpans t = r.render;
        if (t == null) return out;
        List<SpanDigest.Spike> list = t.spikes();
        int shown = 0;
        for (SpanDigest.Spike s : list) {
            if (s.start < from || s.start >= to) continue;
            int best = -1, bestDepth = -1;
            for (int i = 0; i < s.spans; i++) {
                if (s.spanDuration[i] >= s.duration * 0.5 && s.depth[i] > bestDepth) {
                    best = s.node[i];
                    bestDepth = s.depth[i];
                }
            }
            JsonObject m = new JsonObject();
            m.addProperty("atSeconds", round((s.start - r.capture.startNanos) / 1e9, 2));
            m.addProperty("ms", ms(s.duration));
            m.addProperty("section", best < 0 ? "(spread over many sections)" : r.path(t, best));
            out.add(m);
            if (++shown == 3) break;
        }
        return out;
    }
}
