package dev.lightning.analysis;

import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;
import dev.lightning.capture.CaptureOptions;
import dev.lightning.capture.GpuTimer;
import dev.lightning.capture.SectionProfiler;
import dev.lightning.capture.Track;
import dev.lightning.capture.WorldHooks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Writes the profile as JSON. Times are integers in microseconds; capture-relative where they
 * are points in time. Arrays are columnar to keep the file compact.
 */
final class ProfileJson {
    static final int FORMAT_VERSION = 2;

    private final Report r;
    private final JsonWriter w;

    private ProfileJson(Report r, JsonWriter w) {
        this.r = r;
        this.w = w;
    }

    /** Streams the profile to {@code out}: long captures produce hundreds of MB, which never sit in memory. */
    static void write(Report r, List<Finding> findings, String lightningVersion, String summary, java.nio.file.Path out) throws IOException {
        try (JsonWriter w = new JsonWriter(new java.io.BufferedWriter(new OutputStreamWriter(java.nio.file.Files.newOutputStream(out), StandardCharsets.UTF_8), 1 << 16))) {
            w.setSerializeNulls(false);
            new ProfileJson(r, w).root(findings, lightningVersion, summary);
        }
    }

    private long rel(long nanoTime) {
        return (nanoTime - r.capture.startNanos) / 1000;
    }

    private static long us(long nanos) {
        return nanos / 1000;
    }

    private void root(List<Finding> findings, String version, String summary) throws IOException {
        w.beginObject();
        w.name("format").value("lightning-profile");
        w.name("formatVersion").value(FORMAT_VERSION);
        w.name("lightningVersion").value(version);
        w.name("id").value(r.capture.id);
        w.name("startedAt").value(r.capture.startEpochMillis);
        w.name("durationUs").value(us(r.durationNanos));
        w.name("environment");
        new Gson().toJson(r.data.environment(), w);
        options();
        coverage();
        findings(findings);
        w.name("contenders").beginArray();
        for (Contenders.Contender c : Contenders.compute(r)) {
            w.beginObject().name("title").value(c.title()).name("size").value(c.size()).name("why").value(c.why())
                    .name("try").value(c.tryThis()).name("link").value(c.link()).name("score").value(c.score()).endObject();
        }
        w.endArray();
        w.name("names");
        w.beginArray();
        for (String n : r.spans.names) w.value(n);
        w.endArray();
        threads();
        world();
        jvm();
        metrics();
        logs();
        phases();
        LoadDigest load = r.load();
        if (load != null) {
            w.name("loading");
            new Gson().toJson(load.json, w);
        }
        heap();
        if (r.data.deepMemory() != null) w.name("deepMemory").value(r.data.deepMemory());
        w.name("summaryMarkdown").value(summary);
        w.endObject();
    }

    private void options() throws IOException {
        CaptureOptions o = r.capture.options;
        w.name("options").beginObject();
        w.name("timeoutSeconds").value(o.timeoutSeconds());
        w.name("gpu").value(o.gpu().name().toLowerCase());
        w.name("gpuDepth").value(o.gpuDepth());
        w.name("jfr").value(o.jfr());
        w.name("sampleIntervalMs").value(o.sampleIntervalMillis());
        w.name("nativeSamples").value(o.nativeSamples());
        w.name("allocations").value(o.allocations());
        w.name("world").value(o.world());
        w.endObject();
    }

    private void coverage() throws IOException {
        w.name("coverage").beginObject();
        w.name("profilerBridge").value(SectionProfiler.observed);
        w.name("entityHook").value(WorldHooks.entityHookObserved);
        w.name("irisPasses").value(dev.lightning.capture.CompatHooks.irisObserved);
        w.name("voxyLods").value(dev.lightning.capture.CompatHooks.voxyObserved);
        w.name("blockEntityHook").value(WorldHooks.blockEntityHookObserved);
        w.name("jfr").value(r.jfr != null ? "recorded" : r.data.jfrError() != null ? "unavailable: " + r.data.jfrError()
                : r.jfrReadError != null ? "unreadable: " + r.jfrReadError : "off");
        GpuTimer gpu = r.gpu();
        w.name("gpu").value(gpu != null ? gpu.mode().name().toLowerCase()
                : r.capture.gpuError() != null ? "unavailable: " + r.capture.gpuError()
                : r.capture.options.gpu() == GpuTimer.Mode.OFF ? "off" : "no render thread");
        w.name("limits").beginArray();
        w.value("Section times cover code that Minecraft or a mod marks with the profiler. Unmarked work is counted in the enclosing section's own time.");
        w.value("GPU times are the span between two timestamps in the command stream; they include GPU idle gaps when the CPU submits slowly.");
        w.value("JVM stack samples are statistical (JFR, every " + r.capture.options.sampleIntervalMillis() + " ms, a few threads per interval). Counts indicate share, not exact time.");
        w.value("Entity and block-entity times are exact per tick. Work they trigger elsewhere (e.g. chunk loading) is included in their time.");
        w.value("Mod ownership of a method is where its code lives; it does not by itself mean that mod caused the cost.");
        w.endArray();
        w.endObject();

        w.name("overhead").beginObject();
        w.name("events").value(r.totalEvents());
        w.name("eventsLost").value(r.lostEvents());
        w.name("eventFileBytes").value(r.data.eventBytes());
        w.name("jfrSetupUs").value(us(r.data.jfrSetupNanos()));
        w.name("jfrWarmup").value(dev.lightning.capture.JfrWarmup.status());
        w.name("jfrWarmupUs").value(us(Math.max(0, dev.lightning.capture.JfrWarmup.warmupNanos())));
        w.name("jfrThreadsCpuUs").value(r.data.jfrThreadCpuNanos() < 0 ? -1 : us(r.data.jfrThreadCpuNanos()));
        var metrics = r.data.metrics();
        if (metrics.size() >= 2) {
            w.name("lightningThreadsCpuUs").value(us(metrics.get(metrics.size() - 1).lightningCpuNanos() - metrics.get(0).lightningCpuNanos()));
        }
        if (gpu != null) {
            w.name("gpuQueryCalls").value(gpu.glCalls());
            w.name("gpuQueryUs").value(us(gpu.glNanos()));
            w.name("gpuSkipped").value(gpu.skippedMarks());
            w.name("gpuResults").value(gpu.resultsRead());
        }
        w.name("analysisUs").value(us(r.analysisNanos));
        if (r.jfr != null) {
            w.name("jfrEvents").value(r.jfr.events);
            w.name("jfrDataLossBytes").value(r.jfr.dataLossBytes);
        }
        w.endObject();
    }

    private void findings(List<Finding> findings) throws IOException {
        w.name("findings").beginArray();
        for (Finding f : findings) {
            w.beginObject();
            w.name("severity").value(f.severity());
            w.name("area").value(f.area());
            w.name("title").value(f.title());
            w.name("detail").value(f.detail());
            w.name("basis").value(f.basis());
            w.name("link").value(f.link());
            w.endObject();
        }
        w.endArray();
    }

    // --- threads ----------------------------------------------------------------------------

    private void threads() throws IOException {
        w.name("threads").beginArray();
        List<SpanDigest.ThreadSpans> list = r.spans.threads.values().stream()
                .filter(t -> t.tree.size > 1 || t.ticks.size() > 0)
                .sorted((a, b) -> Long.compare(b.tree.total[0] + total(b), a.tree.total[0] + total(a))).toList();
        for (SpanDigest.ThreadSpans t : list) thread(t);
        w.endArray();
    }

    private static long total(SpanDigest.ThreadSpans t) {
        long sum = 0;
        for (int i = 1; i < t.tree.size; i++) if (t.tree.parent[i] == 0) sum += t.tree.total[i];
        return sum;
    }

    private void thread(SpanDigest.ThreadSpans t) throws IOException {
        w.beginObject();
        w.name("name").value(t.name);
        w.name("role").value(t == r.render ? "render" : t == r.server ? "server" : "other");
        w.name("unmatchedPops").value(t.unmatchedPops);
        w.name("losses").value(t.losses);
        SpanTree tree = t.tree;
        w.name("tree").beginObject();
        intArray("parent", tree.parent, tree.size);
        intArray("name", tree.name, tree.size);
        longArray("calls", tree.calls, tree.size, 1);
        longArray("total", tree.total, tree.size, 1000);
        long[] self = new long[tree.size];
        for (int i = 0; i < tree.size; i++) self[i] = tree.self(i);
        longArray("self", self, tree.size, 1000);
        longArray("max", tree.max, tree.size, 1000);
        long[] p95 = new long[tree.size];
        for (int i = 0; i < tree.size; i++) p95[i] = tree.percentile(i, 0.95);
        longArray("p95", p95, tree.size, 1000);
        longArray("gpu", tree.gpuTotal, tree.size, 1000);
        longArray("gpuCalls", tree.gpuCalls, tree.size, 1);
        longArray("gpuMax", tree.gpuMax, tree.size, 1000);
        longArray("samples", tree.samples, tree.size, 1);
        w.endObject();

        SpanDigest.Ticks ticks = t.ticks;
        if (ticks.size > 0) {
            w.name("ticks").beginObject();
            long[] start = new long[ticks.size];
            for (int i = 0; i < ticks.size; i++) start[i] = rel(ticks.start[i]);
            longArray("start", start, ticks.size, 1);
            longArray("duration", ticks.duration, ticks.size, 1000);
            longArray("gpu", ticks.gpu, ticks.size, 1000);
            intArray("breakdownStart", ticks.breakdownStart, ticks.size + 1);
            intArray("breakdownNode", ticks.breakdownNode, ticks.breakdownSize);
            longArray("breakdownUs", ticks.breakdownNanos, ticks.breakdownSize, 1000);
            intArray("gpuTick", ticks.gpuTick, ticks.gpuSize);
            intArray("gpuNode", ticks.gpuNode, ticks.gpuSize);
            longArray("gpuUs", ticks.gpuNanos, ticks.gpuSize, 1000);
            w.endObject();
            w.name("spikes").beginArray();
            for (SpanDigest.Spike s : t.spikes()) {
                w.beginObject();
                w.name("tick").value(s.tick);
                w.name("start").value(rel(s.start));
                w.name("duration").value(us(s.duration));
                w.name("truncated").value(s.truncated);
                intArray("node", s.node, s.spans);
                intArray("depth", s.depth, s.spans);
                long[] spanStart = new long[s.spans];
                for (int i = 0; i < s.spans; i++) spanStart[i] = (s.spanStart[i] - s.start) / 1000;
                longArray("spanStart", spanStart, s.spans, 1);
                longArray("spanDuration", s.spanDuration, s.spans, 1000);
                int[] leaves = new int[s.sampleLeaf.length];
                for (int i = 0; i < leaves.length; i++) leaves[i] = cpuMap(s.sampleLeaf[i]);
                intArray("sampleLeaf", leaves, leaves.length);
                intArray("sampleNode", s.sampleNode, s.sampleNode.length);
                long[] sampleTime = new long[s.sampleTime.length];
                for (int i = 0; i < sampleTime.length; i++) sampleTime[i] = (s.sampleTime[i] - s.start) / 1000;
                longArray("sampleTime", sampleTime, sampleTime.length, 1);
                w.endObject();
            }
            w.endArray();
        }
        // Stack samples by section: [section node, cpu flame leaf, count] triples.
        w.name("sectionStacks").beginArray();
        for (Map.Entry<Long, Integer> e : t.sectionStacks.entrySet()) {
            w.value(e.getKey() >>> 32);
            w.value(cpuMap((int) (long) e.getKey()));
            w.value(e.getValue());
        }
        w.endArray();
        w.endObject();
    }

    // --- world ------------------------------------------------------------------------------

    private void world() throws IOException {
        w.name("world").beginArray();
        for (WorldDigest.Level level : r.world.levels) {
            w.beginObject();
            w.name("dimension").value(level.dimension);
            w.name("client").value(level.client);
            w.name("entityUs").value(us(level.entityNanos));
            w.name("entityTicks").value(level.entityTicks);
            w.name("blockEntityUs").value(us(level.blockEntityNanos));
            w.name("blockEntityTicks").value(level.blockEntityTicks);
            w.name("chunks").beginArray();
            for (WorldDigest.Chunk c : level.topChunks()) {
                w.beginObject();
                w.name("x").value(c.x).name("z").value(c.z);
                w.name("entityUs").value(us(c.entityNanos)).name("entityTicks").value(c.entityTicks);
                w.name("blockEntityUs").value(us(c.blockEntityNanos)).name("blockEntityTicks").value(c.blockEntityTicks);
                w.name("top").beginArray();
                c.contributors.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(6).forEach(e -> {
                    try {
                        w.beginArray().value(e.getKey()).value(e.getValue()[2]).value(e.getValue()[0]).value(us(e.getValue()[1])).endArray();
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
                w.endArray();
                w.endObject();
            }
            w.endArray();
            w.name("types").beginArray();
            for (WorldDigest.TypeTotal t : level.topTypes()) {
                w.beginObject();
                w.name("type").value(t.type).name("kind").value(t.kind).name("instances").value(t.instances)
                        .name("ticks").value(t.ticks).name("us").value(us(t.nanos)).name("maxUs").value(us(t.max));
                w.endObject();
            }
            w.endArray();
            w.name("instances").beginArray();
            for (WorldDigest.Instance i : level.topInstances()) {
                w.beginObject();
                w.name("type").value(i.type()).name("kind").value(i.kind()).name("x").value(i.x()).name("y").value(i.y()).name("z").value(i.z())
                        .name("ticks").value(i.ticks()).name("us").value(us(i.nanos())).name("maxUs").value(us(i.max()));
                if (i.id() != 0) w.name("entityId").value(i.id());
                w.endObject();
            }
            w.endArray();
            w.endObject();
        }
        w.endArray();
    }

    // --- JVM --------------------------------------------------------------------------------

    private int[] cpuMapping;

    /** Maps an unpruned CPU flame node to the exported (pruned) node. */
    private int cpuMap(int node) {
        if (cpuMapping == null) cpuMapping = prune(r.jfr == null ? null : r.jfr.cpu, 40_000);
        if (cpuMapping == null || node < 0 || node >= cpuMapping.length) return 0;
        return cpuMapping[node];
    }

    /**
     * Keeps the heaviest nodes of a flame tree so the report stays responsive. Returns a mapping
     * from each original node to the kept node it folds into (itself or its nearest kept ancestor).
     */
    static int[] prune(FlameTree tree, int maxNodes) {
        if (tree == null) return null;
        int n = tree.size();
        long threshold = 0;
        if (n > maxNodes) {
            long[] totals = Arrays.copyOf(tree.total, n);
            Arrays.sort(totals);
            threshold = totals[n - maxNodes];
        }
        int[] map = new int[n];
        boolean[] kept = new boolean[n];
        int next = 0;
        // Parents always precede their children, so a parent's fate is known first.
        for (int i = 0; i < n; i++) {
            int p = i == 0 ? -1 : tree.parent(i);
            if (i == 0 || kept[p] && tree.total(i) >= threshold) {
                kept[i] = true;
                map[i] = next++;
            } else {
                map[i] = map[p];
            }
        }
        return map;
    }

    private void flame(String key, FlameTree tree, int[] map) throws IOException {
        int kept = 0;
        for (int i = 0; i < tree.size(); i++) kept = Math.max(kept, map[i] + 1);
        int[] parent = new int[kept], frame = new int[kept];
        long[] total = new long[kept], self = new long[kept];
        boolean[] seen = new boolean[kept];
        for (int i = 0; i < tree.size(); i++) {
            int m = map[i];
            if (!seen[m]) {
                seen[m] = true;
                parent[m] = i == 0 ? -1 : map[tree.parent(i)];
                frame[m] = tree.frame(i);
                total[m] = tree.total(i);
            }
            // Dropped descendants fold their own weight into the kept ancestor.
            self[m] += tree.self(i);
        }
        w.name(key).beginObject();
        intArray("parent", parent, kept);
        intArray("frame", frame, kept);
        longArray("total", total, kept, 1);
        longArray("self", self, kept, 1);
        w.endObject();
    }

    private void jvm() throws IOException {
        w.name("jvm").beginObject();
        JfrDigest j = r.jfr;
        if (j == null) {
            w.endObject();
            return;
        }
        w.name("executionSamples").value(j.executionSamples);
        w.name("nativeSamples").value(j.nativeSamples);
        w.name("nativeSamplesSkipped").value(j.nativeSamplesSkipped);
        w.name("truncatedStacks").value(j.truncatedStacks);
        w.name("youngCollector").value(j.youngCollector);
        w.name("oldCollector").value(j.oldCollector);
        w.name("cpu").value(j.cpuDescription);
        w.name("hwThreads").value(j.hwThreads);
        w.name("allocationBytesEstimate").value(j.allocationWeight);
        w.name("frames").beginObject();
        w.name("label").beginArray();
        for (int i = 0; i < j.frames.size(); i++) w.value(j.frames.label(i));
        w.endArray();
        w.name("owner").beginArray();
        for (int i = 0; i < j.frames.size(); i++) w.value(j.frames.owner(i));
        w.endArray();
        w.endObject();
        flame("cpuTree", j.cpu, cpuMappingOrBuild());
        flame("allocTree", j.alloc, prune(j.alloc, 20_000));
        w.name("allocByClass").beginArray();
        j.allocByClass.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(200).forEach(e -> row(e.getKey(), e.getValue()));
        w.endArray();
        w.name("allocByMod").beginArray();
        j.allocByMod.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(100).forEach(e -> row(e.getKey(), e.getValue()));
        w.endArray();
        w.name("pools").beginArray();
        for (var e : j.pools.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples)).toList()) {
            w.beginObject().name("name").value(e.getKey()).name("samples").value(e.getValue().samples);
            w.name("owners").beginArray();
            e.getValue().owners.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8).forEach(o -> {
                try {
                    w.beginArray().value(o.getKey()).value(o.getValue()).endArray();
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            w.endArray().endObject();
        }
        w.endArray();
        w.name("allocBySite").beginArray();
        j.allocBySite.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(200).forEach(e -> row(e.getKey(), e.getValue()));
        w.endArray();
        w.name("gc").beginArray();
        for (JfrDigest.Gc gc : j.gcs) {
            w.beginArray().value(rel(gc.time())).value(us(gc.duration())).value(us(gc.sumOfPauses())).value(us(gc.longestPause()))
                    .value(gc.name()).value(gc.cause()).endArray();
        }
        w.endArray();
        pauses("allocationStalls", j.allocationStalls);
        pauses("vmOperations", j.vmOperations);
        pauses("blocking", j.blocking);
        if (!j.io.isEmpty()) {
            pauses("ioOnGameThreads", j.ioOnGameThreads);
            // [kind, target, count, totalUs, longestUs, gameThreadUs], biggest total first.
            w.name("io").beginArray();
            for (var e : j.io.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(200).toList()) {
                String[] k = e.getKey().split("\\|", 2);
                long[] v = e.getValue();
                w.beginArray().value(k[0]).value(k[1]).value(v[0]).value(us(v[1])).value(us(v[2])).value(us(v[3])).endArray();
            }
            w.endArray();
        }
        w.name("blockingByGroup").beginArray();
        for (var e : j.blockingByGroup.entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            w.beginArray().value(parts[0]).value(parts[1]).value(e.getValue()[0]).value(us(e.getValue()[1])).endArray();
        }
        w.endArray();
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        w.name("threadCpu").beginArray();
        for (var e : j.threadCpu.entrySet()) {
            JfrDigest.ThreadCpu c = e.getValue();
            w.beginArray().value(e.getKey()).value(c.group).value(c.sum / Math.max(1.0, r.seconds()) * cores).value(c.max * cores).endArray();
        }
        w.endArray();
        w.name("processCpuCores").beginArray();
        for (var e : j.processCpu.entrySet()) w.beginArray().value(rel(e.getKey() * 1_000_000_000L)).value(e.getValue()[0]).endArray();
        w.endArray();
        w.name("rss").beginArray();
        for (long[] p : j.rss) w.beginArray().value(rel(p[0])).value(p[1]).endArray();
        w.endArray();
        w.endObject();
    }

    private int[] cpuMappingOrBuild() {
        cpuMap(0);
        return cpuMapping;
    }

    private void row(String key, JfrDigest.Alloc a) {
        try {
            w.beginArray().value(key).value(a.samples).value(a.weight).endArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void pauses(String key, List<JfrDigest.Pause> list) throws IOException {
        w.name(key).beginArray();
        for (JfrDigest.Pause p : list) {
            w.beginArray().value(rel(p.time())).value(us(p.duration())).value(p.thread()).value(p.kind()).value(p.detail()).value(p.stack()).endArray();
        }
        w.endArray();
    }

    private void phases() throws IOException {
        w.name("marks").beginArray();
        for (var m : r.capture.marks()) w.beginArray().value(rel(m.nanos())).value(m.label()).endArray();
        w.endArray();
        w.name("phases").beginArray();
        for (Phases.Stat s : Phases.marked(r)) {
            w.beginObject().name("label").value(s.label()).name("from").value(rel(s.from())).name("to").value(rel(s.to()));
            w.name("fps").value(s.fps()).name("frameP50").value(us(s.frames().p50())).name("frameP99").value(us(s.frames().p99()))
                    .name("frameMax").value(us(s.frames().max())).name("tickP50").value(us(s.serverWork().p50()))
                    .name("tickP95").value(us(s.serverWork().p95())).name("stalls").value(s.stalls()).name("stallUs").value(us(s.stallNanos()))
                    .name("allocMBps").value(s.allocMBps()).name("liveP50").value(s.liveP50());
            w.endObject();
        }
        w.endArray();
    }

    private void heap() throws IOException {
        HeapHistogram h = r.data.heap();
        if (h == null) return;
        w.name("heapSnapshot").beginObject();
        w.name("totalBytes").value(h.totalBytes).name("pauseUs").value(us(h.pauseNanos));
        w.name("owners").beginArray();
        for (var e : h.owners()) w.beginArray().value(e.getKey()).value(e.getValue()[0]).value(e.getValue()[1]).value(e.getValue()[2]).endArray();
        w.endArray();
        w.name("classes").beginArray();
        for (int i = 0; i < Math.min(300, h.rows.size()); i++) {
            HeapHistogram.Row row = h.rows.get(i);
            w.beginArray().value(HeapHistogram.readable(row.className())).value(row.instances()).value(row.bytes()).value(row.owner()).endArray();
        }
        w.endArray();
        w.endObject();
    }

    // --- metrics and logs -------------------------------------------------------------------

    private void metrics() throws IOException {
        w.name("metrics").beginArray();
        for (var m : r.data.metrics()) {
            w.beginArray().value(rel(m.time())).value(m.heapUsed()).value(m.heapCommitted()).value(m.heapMax()).value(m.nonHeapUsed())
                    .value(m.directBytes()).value(m.gcCount()).value(m.gcMillis()).value(m.threads()).value(m.liveAfterGc()).endArray();
        }
        w.endArray();
    }

    private void logs() throws IOException {
        var logs = r.data.logs();
        w.name("logs").beginObject();
        if (logs != null) {
            w.name("dropped").value(logs.dropped());
            w.name("kinds").beginArray();
            for (var k : logs.kinds()) {
                w.beginObject();
                w.name("level").value(k.level).name("logger").value(k.logger).name("thread").value(k.thread).name("message").value(k.message);
                if (k.exception != null) w.name("exception").value(k.exception);
                w.name("count").value(k.count).name("first").value(rel(k.firstNanos)).name("last").value(rel(k.lastNanos));
                w.endObject();
            }
            w.endArray();
            long[] times = new long[logs.times().length];
            for (int i = 0; i < times.length; i++) times[i] = rel(logs.times()[i]);
            longArray("times", times, times.length, 1);
            intArray("kindIndex", logs.kindIndex(), logs.kindIndex().length);
        }
        w.endObject();
    }

    // --- helpers ----------------------------------------------------------------------------

    private void intArray(String name, int[] values, int size) throws IOException {
        w.name(name).beginArray();
        for (int i = 0; i < size; i++) w.value(values[i]);
        w.endArray();
    }

    /** Writes values divided by {@code divisor}; negative values (unknown) are kept as -1. */
    private void longArray(String name, long[] values, int size, long divisor) throws IOException {
        w.name(name).beginArray();
        for (int i = 0; i < size; i++) w.value(values[i] < 0 ? -1 : values[i] / divisor);
        w.endArray();
    }
}
