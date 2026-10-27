package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Markdown brief of the capture, written for an outside reader (a person or a language model)
 * who has not seen the game. It states measurements with their basis and limits and leaves
 * interpretation open where the data does.
 */
final class Summary {
    private final Report r;
    private final StringBuilder out = new StringBuilder(64 * 1024);

    private Summary(Report r) {
        this.r = r;
    }

    static String write(Report r, List<Finding> findings, String version) {
        Summary s = new Summary(r);
        s.header(version);
        s.findings(findings);
        LoadDigest load = r.load();
        if (load != null) s.out.append(load.markdown());
        s.frames();
        s.spikes();
        s.server();
        s.world();
        s.jvm();
        s.memory();
        s.phases();
        s.pools();
        s.heap();
        s.deep();
        s.logs();
        s.overhead();
        return s.out.toString();
    }

    private Summary line(String text) {
        out.append(text).append('\n');
        return this;
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    private String env(String key) {
        var e = r.data.environment();
        return e.has(key) && !e.get(key).isJsonNull() ? e.get(key).getAsString() : "?";
    }

    private void header(String version) {
        line("# Lightning capture " + r.capture.id);
        line("");
        line(f("Captured with Lightning %s for %.1f s. Minecraft %s, NeoForge %s, Java %s (%s), %s mods.",
                version, r.seconds(), env("minecraftVersion"), env("neoForgeVersion"), env("javaVersion"),
                r.jfr == null ? "collector unknown" : r.jfr.youngCollector + "/" + r.jfr.oldCollector, env("modCount")));
        line(f("Machine: %s, %s logical CPUs, GPU %s (%s). Max heap %s MB. Context: %s. FPS limit %s, VSync %s, render distance %s.",
                r.jfr == null || r.jfr.cpuDescription.isEmpty() ? env("os") : r.jfr.cpuDescription.lines().findFirst().orElse(""),
                env("processors"), env("gpuRenderer"), env("gpuVendor"), env("maxHeapMb"), env("context"), env("fpsLimit"), env("vsync"), env("renderDistance")));
        var env = r.data.environment();
        if (env.has("framebufferWidth")) {
            line(f("Framebuffer %sx%s. Shaders: %s.", env.get("framebufferWidth").getAsString(), env.get("framebufferHeight").getAsString(),
                    env.has("shadersEnabled") && env.get("shadersEnabled").getAsBoolean() ? env("shaderPack") : "off"));
            if (env.has("shaderOptions")) line("Shader options changed from the pack's defaults: `" + env.get("shaderOptions").getAsString().replace("\n", "; ").replace("\r", "") + "`");
        }
        line("");
        line("## How these numbers were measured");
        line("- **Sections** are Minecraft's and mods' own profiler markers (e.g. `gameRenderer › level › terrain`), timed exactly on every call. "
                + "\"Own time\" excludes nested sections. Work that nobody marks is counted in the enclosing section's own time.");
        line("- **GPU times** come from OpenGL timestamp queries around sections. A GPU span includes idle gaps if the CPU submitted commands slowly.");
        line(f("- **JVM samples** are statistical stack snapshots (JFR, every %d ms, a few threads per interval). Use them for shares, not exact times. "
                + "Samples taken on the render or server thread are also tied to the section that was open at that moment.", r.capture.options.sampleIntervalMillis()));
        line("- **Entity / block-entity times** are exact for every tick. An entity's chunk is where it last ticked.");
        line("- **Mod ownership** of a method is where its code lives (its jar, or the mod that injected it with Mixin). It is a lead, not a verdict.");
        line("- Coincidence in time (a GC or log burst during a slow frame) is reported as context, not as a cause.");
        line("");
    }

    private void findings(List<Finding> findings) {
        List<Contenders.Contender> top = Contenders.compute(r);
        if (!top.isEmpty()) {
            line("## Biggest contenders (ranked)");
            int i = 1;
            for (Contenders.Contender c : top) line(f("%d. **%s** — %s. %s _Try:_ %s", i++, c.title(), c.size(), c.why(), c.tryThis()));
            line("");
        }
        line("## Findings");
        for (Finding f : findings) {
            line(f("- **[%s] %s** — %s _(%s)_", f.severity(), f.title(), f.detail(), f.basis()));
        }
        line("");
    }

    private void sectionTable(SpanDigest.ThreadSpans t, double per, String unit, int limit) {
        List<Integer> nodes = new ArrayList<>();
        for (int i = 1; i < t.tree.size; i++) nodes.add(i);
        nodes.sort(Comparator.comparingLong((Integer i) -> -t.tree.total[i]));
        line(f("| Section | calls/%s | total ms/%s | own ms/%s | p95 ms | max ms | GPU ms/%s | JVM samples |", unit, unit, unit, unit));
        line("|---|---:|---:|---:|---:|---:|---:|---:|");
        int shown = 0;
        for (int node : nodes) {
            if (t.tree.total[node] / per < 50_000 && shown > 15) break;
            line(f("| %s | %.2f | %.3f | %.3f | %.2f | %.2f | %s | %d |", r.path(t, node), t.tree.calls[node] / per,
                    t.tree.total[node] / per / 1e6, t.tree.self(node) / per / 1e6, t.tree.percentile(node, 0.95) / 1e6, t.tree.max[node] / 1e6,
                    t.tree.gpuCalls[node] > 0 ? f("%.3f", t.tree.gpuTotal[node] / per / 1e6) : "—", t.tree.samples[node]));
            if (++shown == limit) break;
        }
        line("");
    }

    private void frames() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frames.count() == 0) return;
        Stats s = r.frames;
        line("## Frames (render thread)");
        line(f("%d frames, %.1f per second. Frame time: mean %.2f ms, median %.2f, p95 %.2f, p99 %.2f, max %.2f.",
                s.count(), s.count() / r.seconds(), s.mean() / 1e6, s.p50() / 1e6, s.p95() / 1e6, s.p99() / 1e6, s.max() / 1e6));
        if (r.frameGpu.count() > 0) {
            line(f("GPU time per frame (first to last command): median %.2f ms, p95 %.2f, max %.2f (%d frames measured).",
                    r.frameGpu.p50() / 1e6, r.frameGpu.p95() / 1e6, r.frameGpu.max() / 1e6, r.frameGpu.count()));
        }
        line("");
        sectionTable(t, s.count(), "frame", 45);
        sampledInside(t, "render thread");
    }

    /** Top sampled methods per heavy section: links sections to code. */
    private void sampledInside(SpanDigest.ThreadSpans t, String label) {
        if (r.jfr == null || t.sectionStacks.isEmpty()) return;
        Map<Integer, Map<Integer, Integer>> bySection = new HashMap<>();
        for (var e : t.sectionStacks.entrySet()) {
            int section = (int) (e.getKey() >>> 32);
            int leaf = (int) (long) e.getKey();
            bySection.computeIfAbsent(section, k -> new HashMap<>()).merge(r.jfr.cpu.frame(leaf), e.getValue(), Integer::sum);
        }
        line("### Hot methods inside " + label + " sections (JVM samples, leaf frame)");
        bySection.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().values().stream().mapToInt(Integer::intValue).sum(),
                        a.getValue().values().stream().mapToInt(Integer::intValue).sum()))
                .limit(10).forEach(e -> {
                    int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
                    StringBuilder b = new StringBuilder();
                    e.getValue().entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()).limit(5)
                            .forEach(m -> b.append(f("`%s` [%s] %d; ", r.jfr.frames.label(m.getKey()), r.jfr.frames.owner(m.getKey()), m.getValue())));
                    line(f("- **%s** (%d samples): %s", e.getKey() == 0 ? "(outside any section)" : r.path(t, e.getKey()), total, b));
                });
        line("");
    }

    private void spikes() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null) return;
        List<SpanDigest.Spike> spikes = t.spikes();
        if (spikes.isEmpty()) return;
        line("## Slowest frames");
        int shown = 0;
        for (SpanDigest.Spike s : spikes) {
            if (s.duration < r.frames.p50() * 1.5) break;
            line(f("### Frame at %.2f s — %.2f ms%s", (s.start - r.capture.startNanos) / 1e9, s.duration / 1e6,
                    t.ticks.gpu[s.tick] > 0 ? f(" (GPU %.2f ms)", t.ticks.gpu[s.tick] / 1e6) : ""));
            // Largest spans down to depth 6, with how they compare to normal.
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < s.spans; i++) if (s.depth[i] <= 6 && s.spanDuration[i] > Math.max(500_000, s.duration / 50)) idx.add(i);
            idx.sort(Comparator.comparingInt((Integer i) -> s.depth[i]).thenComparingLong(i -> -s.spanDuration[i]));
            for (int i : idx) {
                int node = s.node[i];
                double typical = t.tree.calls[node] == 0 ? 0 : (double) t.tree.total[node] / t.tree.calls[node];
                line(f("%s- %s: %.2f ms (typical %.2f ms)", "  ".repeat(Math.min(s.depth[i], 6)), r.name(t.tree.name[node]), s.spanDuration[i] / 1e6, typical / 1e6));
            }
            if (s.sampleLeaf.length > 0 && r.jfr != null) {
                StringBuilder b = new StringBuilder("JVM samples during this frame: ");
                for (int i = 0; i < s.sampleLeaf.length; i++) {
                    b.append(f("`%s` in %s; ", r.jfr.frames.label(r.jfr.cpu.frame(s.sampleLeaf[i])), s.sampleNode[i] == 0 ? "(no section)" : r.name(t.tree.name[s.sampleNode[i]])));
                }
                line(b.toString());
            }
            line("");
            if (++shown == 5) break;
        }
    }

    private void server() {
        SpanDigest.ThreadSpans t = r.server;
        if (t == null || r.serverWork.count() == 0) return;
        Stats s = r.serverWork;
        line("## Server ticks");
        line(f("%d ticks (%.1f per second). Work per tick (excluding the wait for the next tick): mean %.2f ms, median %.2f, p95 %.2f, p99 %.2f, max %.2f.",
                s.count(), s.count() / r.seconds(), s.mean() / 1e6, s.p50() / 1e6, s.p95() / 1e6, s.p99() / 1e6, s.max() / 1e6));
        line("");
        sectionTable(t, s.count(), "tick", 40);
        sampledInside(t, "server thread");
        for (SpanDigest.ThreadSpans other : r.spans.threads.values()) {
            if (other == r.render || other == r.server || other.tree.size < 3) continue;
            long total = 0;
            for (int i = 1; i < other.tree.size; i++) if (other.tree.parent[i] == 0) total += other.tree.total[i];
            if (total < 0.01 * r.durationNanos) continue;
            line(f("### Thread %s (%.1f%% of the capture inside sections)", other.name, 100.0 * total / r.durationNanos));
            sectionTable(other, r.seconds(), "s", 15);
        }
    }

    private void world() {
        if (r.world.levels.isEmpty()) return;
        line("## Entities and block entities");
        double sec = r.seconds();
        for (WorldDigest.Level level : r.world.levels) {
            line(f("### %s%s — entities %.2f ms/s (%d ticks), block entities %.2f ms/s (%d ticks)", level.dimension, level.client ? " (client)" : "",
                    level.entityNanos / sec / 1e6, level.entityTicks, level.blockEntityNanos / sec / 1e6, level.blockEntityTicks));
            line("| Type | kind | instances | ticks | ms/s | max single tick ms |");
            line("|---|---|---:|---:|---:|---:|");
            for (WorldDigest.TypeTotal t : level.topTypes().stream().limit(15).toList()) {
                line(f("| %s | %s | %d | %d | %.3f | %.2f |", t.type, t.kind, t.instances, t.ticks, t.nanos / sec / 1e6, t.max / 1e6));
            }
            line("");
            line("Costliest chunks (chunk x, z → block x, z):");
            for (WorldDigest.Chunk c : level.topChunks().stream().limit(10).toList()) {
                StringBuilder b = new StringBuilder();
                c.contributors.entrySet().stream().sorted((a, bb) -> Long.compare(bb.getValue()[1], a.getValue()[1])).limit(4)
                        .forEach(e -> b.append(f("%s ×%d %.3f ms/s; ", e.getKey(), e.getValue()[2], e.getValue()[1] / sec / 1e6)));
                line(f("- chunk %d, %d (blocks %d, %d): %.3f ms/s — %s", c.x, c.z, c.x * 16, c.z * 16, c.nanos() / sec / 1e6, b));
            }
            line("");
            line("Costliest single instances:");
            for (WorldDigest.Instance i : level.topInstances().stream().limit(10).toList()) {
                line(f("- %s %s at %d %d %d: %.3f ms/s, max %.2f ms in one tick", i.kind(), i.type(), i.x(), i.y(), i.z(), i.nanos() / sec / 1e6, i.max() / 1e6));
            }
            line("");
        }
    }

    private void jvm() {
        JfrDigest j = r.jfr;
        if (j == null) {
            line("## JVM sampling");
            line("Not recorded: " + (r.data.jfrError() != null ? r.data.jfrError() : r.jfrReadError != null ? r.jfrReadError : "disabled for this capture") + ".");
            line("");
            return;
        }
        line("## JVM CPU samples (all threads)");
        line(f("%d Java samples and %d native-method samples; %d stacks were deeper than JFR's limit and lost their outer frames.",
                j.executionSamples, j.nativeSamples, j.truncatedStacks));
        FlameTree cpu = j.cpu;
        long all = cpu.total(0);
        Map<String, Long> groups = new HashMap<>();
        for (int i = 1; i < cpu.size(); i++) if (cpu.parent(i) == 0) groups.put(j.frames.label(cpu.frame(i)).replace("thread: ", ""), cpu.total(i));
        line("Samples by thread group: " + top(groups, 10, all));
        Map<String, Long> selfByMethod = new HashMap<>();
        Map<String, Long> selfByMod = new HashMap<>();
        Map<String, Long> inclusiveByMod = new HashMap<>();
        for (int i = 1; i < cpu.size(); i++) {
            long self = cpu.self(i);
            if (self == 0 || cpu.parent(i) == 0) continue;
            int frame = cpu.frame(i);
            selfByMethod.merge(j.frames.label(frame) + " [" + j.frames.owner(frame) + "]", self, Long::sum);
            selfByMod.merge(j.frames.owner(frame), self, Long::sum);
            Set<String> owners = new HashSet<>();
            for (int n = i; n > 0 && cpu.parent(n) != 0; n = cpu.parent(n)) {
                String owner = j.frames.owner(cpu.frame(n));
                if (!owner.isEmpty()) owners.add(owner);
            }
            for (String o : owners) inclusiveByMod.merge(o, self, Long::sum);
        }
        line("Own (leaf) samples by owner: " + top(selfByMod, 12, all));
        line("Samples with the owner anywhere on the stack: " + top(inclusiveByMod, 15, all));
        line("");
        line("Top methods by own samples:");
        selfByMethod.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(25)
                .forEach(e -> line(f("- `%s` — %d (%.1f%%)", e.getKey(), e.getValue(), 100.0 * e.getValue() / Math.max(1, all))));
        line("");
        if (!j.threadCpu.isEmpty()) {
            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            Map<String, Double> byGroup = new HashMap<>();
            for (var e : j.threadCpu.values()) byGroup.merge(e.group, e.sum / Math.max(1.0, r.seconds()) * cores, Double::sum);
            StringBuilder b = new StringBuilder("Average CPU in cores by thread group: ");
            byGroup.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(12)
                    .forEach(e -> b.append(f("%s %.2f; ", e.getKey(), e.getValue())));
            line(b.toString());
            line("");
        }
        if (!j.vmOperations.isEmpty()) {
            line("JVM-wide pauses over 1 ms (non-GC shown first):");
            j.vmOperations.stream().sorted((a, b) -> Long.compare(b.duration(), a.duration())).limit(8)
                    .forEach(p -> line(f("- %s at %.2f s: %.2f ms (%s)", p.detail(), (p.time() - r.capture.startNanos) / 1e9, p.duration() / 1e6, p.kind())));
            line("");
        }
        if (!j.blocking.isEmpty()) {
            line("Longest blocking on game threads:");
            j.blocking.stream().sorted((a, b) -> Long.compare(b.duration(), a.duration())).limit(8).forEach(p ->
                    line(f("- %s %s on `%s` for %.2f ms at %.2f s; stack: %s", p.thread(), p.kind(), p.detail(), p.duration() / 1e6,
                            (p.time() - r.capture.startNanos) / 1e9, p.stack().lines().limit(5).reduce((a, b) -> a + " ← " + b).orElse(""))));
            line("");
        }
    }

    private static String top(Map<String, Long> map, int n, long all) {
        StringBuilder b = new StringBuilder();
        map.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(n)
                .forEach(e -> b.append(f("%s %.1f%%; ", e.getKey(), 100.0 * e.getValue() / Math.max(1, all))));
        return b.toString();
    }

    private void memory() {
        line("## Memory");
        var m = r.data.metrics();
        if (!m.isEmpty()) {
            long maxUsed = 0, minUsed = Long.MAX_VALUE;
            for (var s : m) {
                maxUsed = Math.max(maxUsed, s.heapUsed());
                minUsed = Math.min(minUsed, s.heapUsed());
            }
            var last = m.get(m.size() - 1);
            // The JVM's own GC time counters include concurrent work for ZGC and Shenandoah, which
            // does not stop the game, so only JFR's pause measurements are reported as pauses.
            line(f("Heap used %d–%d MB of %d MB max (committed %d MB at the end). Direct buffers %d MB.",
                    minUsed >> 20, maxUsed >> 20, last.heapMax() >> 20, last.heapCommitted() >> 20, last.directBytes() >> 20));
            if (r.jfr != null && !r.jfr.gcs.isEmpty()) {
                long pauses = 0, longest = 0;
                for (JfrDigest.Gc gc : r.jfr.gcs) {
                    pauses += gc.sumOfPauses();
                    longest = Math.max(longest, gc.longestPause());
                }
                line(f("Garbage collections: %d. Time the game was actually paused for them: %.1f ms in total, longest %.2f ms.",
                        r.jfr.gcs.size(), pauses / 1e6, longest / 1e6));
            }
        }
        Phases.Stat whole = Phases.whole(r);
        if (whole.liveP50() > 0) {
            line(f("In use right after each clean-up (the live set): typically %d MB, up to %d MB, of a %d MB limit.",
                    whole.liveP50() >> 20, whole.liveMax() >> 20, whole.heapMax() >> 20));
        }
        JfrDigest j = r.jfr;
        if (j != null) {
            if (!j.allocationStalls.isEmpty()) {
                long total = j.allocationStalls.stream().mapToLong(JfrDigest.Pause::duration).sum();
                line(f("Allocation stalls: %d, %.1f ms in total.", j.allocationStalls.size(), total / 1e6));
            }
            if (j.allocationWeight > 0) {
                line(f("Estimated allocation: %.0f MB/s (sampled, weighted by size).", j.allocationWeight / r.seconds() / (1 << 20)));
                line("Allocation by mod (innermost add-on mod on the stack, else the game):");
                j.allocByMod.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(15)
                        .forEach(e -> line(f("- %s %.0f MB/s (%.1f%%)", e.getKey(), e.getValue().weight / r.seconds() / (1 << 20), 100.0 * e.getValue().weight / j.allocationWeight)));
                line("Top allocated types:");
                j.allocByClass.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(12)
                        .forEach(e -> line(f("- `%s` %.1f%%", e.getKey(), 100.0 * e.getValue().weight / j.allocationWeight)));
                line("Top allocation sites (innermost non-JDK frame → type):");
                j.allocBySite.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().weight, a.getValue().weight)).limit(15)
                        .forEach(e -> line(f("- `%s` %.1f%%", e.getKey(), 100.0 * e.getValue().weight / j.allocationWeight)));
            }
        }
        line("");
    }

    private void phases() {
        List<Phases.Stat> phases = Phases.marked(r);
        if (phases.isEmpty()) return;
        line("## Phases (from /lightning mark)");
        line("| Phase | seconds | FPS | frame p50 / p99 / max ms | tick p50 / p95 ms | ticks >50 ms | memory waits (ms) | alloc MB/s | in use after GC MB |");
        line("|---|---:|---:|---|---|---:|---|---:|---:|");
        for (Phases.Stat s : phases) {
            line(f("| %s | %.0f | %.0f | %.1f / %.1f / %.1f | %.1f / %.1f | %d | %d (%.0f) | %.0f | %d |", s.label(), s.seconds(), s.fps(),
                    s.frames().p50() / 1e6, s.frames().p99() / 1e6, s.frames().max() / 1e6, s.serverWork().p50() / 1e6, s.serverWork().p95() / 1e6,
                    s.ticksOver50(), s.stalls(), s.stallNanos() / 1e6, s.allocMBps(), s.liveP50() >> 20));
        }
        line("");
        line("CPU cores by thread pool, per phase:");
        for (Phases.Stat s : phases) {
            StringBuilder b = new StringBuilder();
            s.poolCores().entrySet().stream().limit(8).forEach(e -> b.append(f("%s %.2f; ", e.getKey(), e.getValue())));
            line("- **" + s.label() + "**: " + b);
        }
        line("");
    }

    private void pools() {
        if (r.jfr == null || r.jfr.pools.isEmpty()) return;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<String, double[]> cpu = new HashMap<>();
        for (var e : r.jfr.threadCpu.values()) {
            double[] c = cpu.computeIfAbsent(e.group, k -> new double[2]);
            c[0] += e.sum / Math.max(1.0, r.seconds()) * cores;
            c[1]++;
        }
        line(f("## Thread pools (%d logical CPUs)", cores));
        line("| Pool | threads | avg cores | JVM samples | mods running on it (share of the pool's samples) |");
        line("|---|---:|---:|---:|---|");
        r.jfr.pools.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().samples, a.getValue().samples)).limit(20).forEach(e -> {
            double[] c = cpu.getOrDefault(e.getKey(), new double[2]);
            StringBuilder owners = new StringBuilder();
            e.getValue().owners.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4)
                    .forEach(o -> owners.append(f("%s %.0f%%; ", o.getKey(), 100.0 * o.getValue() / e.getValue().samples)));
            line(f("| %s | %d | %.2f | %d | %s |", e.getKey(), (int) c[1], c[0], e.getValue().samples, owners));
        });
        line("");
    }

    private void deep() {
        if (r.data.deepMemory() == null) return;
        line("## Who holds memory (full breakdown)");
        line("```");
        line(r.data.deepMemory().strip());
        line("```");
        line("");
    }

    private void heap() {
        HeapHistogram h = r.data.heap();
        if (h == null) return;
        line("## Memory snapshot (what is held, by owner)");
        line(h.text());
    }

    private void logs() {
        var logs = r.data.logs();
        if (logs == null || logs.kinds().isEmpty()) return;
        line("## Warnings and errors (merged repeats)");
        logs.kinds().stream().sorted((a, b) -> b.count - a.count).limit(25).forEach(k -> {
            String msg = k.message.length() > 300 ? k.message.substring(0, 300) + "…" : k.message;
            line(f("- %s ×%d [%s] %s: %s", k.level, k.count, k.thread, k.logger, msg.replace('\n', ' ')));
            if (k.exception != null) line("  - " + k.exception.lines().limit(4).reduce((a, b) -> a + " | " + b).orElse(""));
        });
        if (logs.dropped() > 0) line(f("(%d further distinct messages not kept)", logs.dropped()));
        line("");
    }

    private void overhead() {
        line("## Recording cost and coverage");
        line(f("Section events: %,d (%,d lost). Event file %.1f MB. JFR setup %.1f ms; JFR threads used %.1f ms CPU. Analysis took %.1f s.",
                r.totalEvents(), r.lostEvents(), r.data.eventBytes() / 1048576.0, r.data.jfrSetupNanos() / 1e6,
                Math.max(0, r.data.jfrThreadCpuNanos()) / 1e6, r.analysisNanos / 1e9));
        var gpu = r.gpu();
        if (gpu != null) line(f("GPU timer: %,d GL calls, %.1f ms on the render thread, %,d markers skipped, %,d results.",
                gpu.glCalls(), gpu.glNanos() / 1e6, gpu.skippedMarks(), gpu.resultsRead()));
    }
}
