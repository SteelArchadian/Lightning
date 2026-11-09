package dev.lightning.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code crosspc} block of results.json: per-mod cost in absolute units, so the same scenario on
 * two machines can be compared (which mod's share grows on the weaker one). Design and format:
 * C:\mcw\kb\lightning-crosspc-design.md.
 *
 * <p>Per-mod time is self time: each CPU sample is charged to the owner of its running code (the
 * innermost frame outside the JDK and libraries). Render-thread time is the mod's share of render
 * samples times the mean frame time; server time the same with the mean tick time. That keeps it
 * independent of how many samples JFR managed to take on each machine.
 */
final class CrossPc {
    static final int VERSION = 1;
    private static final int SECTIONS_PER_MOD = 10;
    private static final int MODS = 60;
    private static final int MIXINS = 60;

    /** Sample counts by thread group: render, server, workers. */
    private static final class Count {
        long render, server, workers;

        long total() {
            return render + server + workers;
        }
    }

    private CrossPc() {}

    static JsonObject build(Report r, JsonObject run) {
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.add("flags", flags());
        o.add("machine", machine(r, run));
        double seconds = Math.max(1e-9, r.seconds());
        int frames = r.render == null ? 0 : r.render.ticks.size();
        int ticks = r.server == null ? 0 : r.server.ticks.size();
        o.addProperty("seconds", round(seconds, 1));
        o.addProperty("frames", frames);
        o.addProperty("ticks", ticks);
        // Server time per tick is busy time (the wait for the next tick excluded), as in tickP50.
        double frameMs = meanMs(r.render), tickMs = r.serverWork.count() == 0 ? 0 : r.serverWork.mean() / 1e6;
        o.addProperty("frameMeanMs", round(frameMs, 3));
        o.addProperty("tickMeanMs", round(tickMs, 3));
        JfrDigest j = r.jfr;
        if (j == null) return o;

        long renderId = r.render == null ? -1 : r.render.threadId, serverId = r.server == null ? -1 : r.server.threadId;
        JfrDigest.Samples renderSamples = j.samplesByThread.get(renderId), serverSamples = j.samplesByThread.get(serverId);
        Map<String, Count> byMod = new HashMap<>();
        Map<String, Count> byMethod = new HashMap<>();
        Map<String, String> methodOwner = new HashMap<>();
        Count all = new Count();
        // All samples minus the two game threads' = worker threads (chunk builders, C2ME, LOD meshers…).
        Map<Integer, Long> renderLeaves = leaves(renderSamples, r), serverLeaves = leaves(serverSamples, r);
        Map<Integer, Long> allLeaves = leaves(j.allSamples, r);
        for (var e : allLeaves.entrySet()) {
            int leaf = e.getKey();
            long n = e.getValue(), rn = renderLeaves.getOrDefault(leaf, 0L), sn = serverLeaves.getOrDefault(leaf, 0L);
            long wn = Math.max(0, n - rn - sn);
            int frame = JfrDigest.ownFrame(j.cpu, j.frames, leaf);
            String owner = frame < 0 ? "java" : j.frames.owner(frame);
            add(byMod.computeIfAbsent(owner, k -> new Count()), rn, sn, wn);
            add(all, rn, sn, wn);
            if (frame >= 0) {
                String method = j.frames.label(frame);
                methodOwner.putIfAbsent(method, owner);
                add(byMethod.computeIfAbsent(method, k -> new Count()), rn, sn, wn);
            }
        }
        double workerCores = workerCores(j, r);
        o.addProperty("workerCoresTotal", round(workerCores, 2));

        Map<String, List<Map.Entry<String, Count>>> methodsByMod = new HashMap<>();
        for (var e : byMethod.entrySet()) methodsByMod.computeIfAbsent(methodOwner.get(e.getKey()), k -> new ArrayList<>()).add(e);
        JsonArray mods = new JsonArray();
        byMod.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().total(), a.getValue().total())).limit(MODS).forEach(e -> {
            String mod = e.getKey();
            Count c = e.getValue();
            JsonObject m = new JsonObject();
            m.addProperty("mod", mod);
            times(m, c, all, frameMs, tickMs, workerCores);
            m.addProperty("onStackPct", onStackPct(r, mod));
            m.addProperty("allocMBps", mbps(j.allocByMod.get(mod), seconds));
            m.addProperty("allocOnStackMBps", mbps(j.allocOnStack.get(mod), seconds));
            Long wait = j.waitByMod.get(mod);
            m.addProperty("waitMsPerSec", wait == null ? 0 : round(wait / 1e6 / seconds, 2));
            m.add("gpuMsPerFrame", JsonNull.INSTANCE);
            m.add("liveMB", JsonNull.INSTANCE);
            JsonArray sections = new JsonArray();
            methodsByMod.getOrDefault(mod, List.of()).stream().sorted((a, b) -> Long.compare(b.getValue().total(), a.getValue().total()))
                    .limit(SECTIONS_PER_MOD).forEach(s -> {
                        JsonObject x = new JsonObject();
                        x.addProperty("name", s.getKey());
                        times(x, s.getValue(), all, frameMs, tickMs, workerCores);
                        sections.add(x);
                    });
            m.add("sections", sections);
            mods.add(m);
        });
        o.add("mods", mods);

        // Mixin handler methods (merged into their target class) that run as the code on top.
        JsonArray mixins = new JsonArray();
        byMethod.entrySet().stream().filter(e -> isMixinHandler(e.getKey()))
                .sorted((a, b) -> Long.compare(b.getValue().total(), a.getValue().total())).limit(MIXINS).forEach(e -> {
                    JsonObject x = new JsonObject();
                    x.addProperty("mod", methodOwner.get(e.getKey()));
                    x.addProperty("handler", e.getKey());
                    times(x, e.getValue(), all, frameMs, tickMs, workerCores);
                    mixins.add(x);
                });
        o.add("mixins", mixins);
        return o;
    }

    /** Leaf → sample count within the capture. */
    private static Map<Integer, Long> leaves(JfrDigest.Samples s, Report r) {
        Map<Integer, Long> out = new HashMap<>();
        if (s == null) return out;
        long from = r.capture.startNanos, to = r.capture.startNanos + r.durationNanos;
        for (int i = 0; i < s.size; i++) {
            if (s.time[i] >= from && s.time[i] < to) out.merge(s.leaf[i], 1L, Long::sum);
        }
        return out;
    }

    private static void add(Count c, long render, long server, long workers) {
        c.render += render;
        c.server += server;
        c.workers += workers;
    }

    private static void times(JsonObject o, Count c, Count all, double frameMs, double tickMs, double workerCores) {
        o.addProperty("renderMsPerFrame", all.render == 0 ? 0 : round(frameMs * c.render / all.render, 3));
        o.addProperty("renderPct", pct(c.render, all.render));
        o.addProperty("serverMsPerTick", all.server == 0 ? 0 : round(tickMs * c.server / all.server, 3));
        o.addProperty("serverPct", pct(c.server, all.server));
        o.addProperty("workerCores", all.workers == 0 ? 0 : round(workerCores * c.workers / all.workers, 3));
        o.addProperty("workerPct", pct(c.workers, all.workers));
        o.addProperty("sharePct", pct(c.total(), all.total()));
    }

    /** Average CPU in cores of every thread except the two game threads (jdk.ThreadCPULoad, once a second). */
    private static double workerCores(JfrDigest j, Report r) {
        double cores = 0;
        int cpus = j.hwThreads > 0 ? j.hwThreads : Runtime.getRuntime().availableProcessors();
        for (var e : j.threadCpu.entrySet()) {
            if (e.getKey().startsWith("Render thread") || e.getKey().startsWith("Server thread") || e.getValue().count == 0) continue;
            cores += e.getValue().sum / e.getValue().count * cpus;
        }
        return cores;
    }

    private static double onStackPct(Report r, String mod) {
        JfrDigest.Samples s = r.jfr.allSamples;
        long total = 0, hit = 0;
        Map<Integer, Boolean> cache = new HashMap<>();
        for (int i = 0; i < s.size; i++) {
            if (s.time[i] < r.capture.startNanos || s.time[i] >= r.capture.startNanos + r.durationNanos) continue;
            total++;
            if (cache.computeIfAbsent(s.leaf[i], l -> onStack(r.jfr, l, mod))) hit++;
        }
        return pct(hit, total);
    }

    private static boolean onStack(JfrDigest j, int leaf, String mod) {
        for (int x = leaf; x > 0 && j.cpu.parent(x) > 0; x = j.cpu.parent(x)) {
            if (j.frames.owner(j.cpu.frame(x)).equals(mod)) return true;
        }
        return false;
    }

    static boolean isMixinHandler(String label) {
        int dot = label.lastIndexOf('.');
        String method = dot < 0 ? label : label.substring(dot + 1);
        return method.startsWith("handler$") || method.startsWith("redirect$") || method.startsWith("modify$")
                || method.startsWith("wrapOperation$") || method.startsWith("wrapWithCondition$") || method.startsWith("localvar$")
                || method.startsWith("constant$") || method.contains("$mixinextras$");
    }

    private static JsonObject flags() {
        JsonObject f = new JsonObject();
        System.getProperties().stringPropertyNames().stream().filter(k -> k.startsWith("lightning.")).sorted()
                .forEach(k -> f.addProperty(k, System.getProperty(k)));
        return f;
    }

    private static JsonObject machine(Report r, JsonObject run) {
        JsonObject m = new JsonObject();
        m.addProperty("cpu", r.jfr == null ? "" : r.jfr.cpuDescription);
        m.addProperty("hwThreads", r.jfr == null ? Runtime.getRuntime().availableProcessors() : r.jfr.hwThreads);
        long ram = 0;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ram = os.getTotalMemorySize();
        }
        m.addProperty("ramMB", ram >> 20);
        m.addProperty("gpu", run.has("gpuRenderer") ? run.get("gpuRenderer").getAsString() : "");
        m.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.addProperty("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        m.addProperty("heapMaxMB", Runtime.getRuntime().maxMemory() >> 20);
        return m;
    }

    private static double meanMs(SpanDigest.ThreadSpans t) {
        if (t == null || t.ticks.size() == 0) return 0;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < t.ticks.size(); i++) {
            if (t.ticks.duration[i] < 0) continue;
            sum += t.ticks.duration[i];
            n++;
        }
        return n == 0 ? 0 : sum / n / 1e6;
    }

    private static double mbps(JfrDigest.Alloc a, double seconds) {
        return a == null ? 0 : round(a.weight / seconds / 1048576.0, 2);
    }

    private static double pct(long part, long whole) {
        return whole == 0 ? 0 : round(100.0 * part / whole, 2);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
