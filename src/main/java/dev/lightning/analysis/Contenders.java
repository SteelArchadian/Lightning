package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The front page of the report: the few things costing the most, in plain words, ranked.
 * Each has what it is, how big, why it matters and what to try. Scores are rough "share of a
 * budget" numbers (frame time, tick time, memory, CPU) so different kinds can be ranked together.
 */
final class Contenders {
    record Contender(String title, String size, String why, String tryThis, String link, double score) {}

    private final Report r;
    private final List<Contender> out = new ArrayList<>();

    private Contenders(Report r) {
        this.r = r;
    }

    static List<Contender> compute(Report r) {
        Contenders c = new Contenders(r);
        c.frameSpikes();
        c.frameHog();
        c.serverTicks();
        c.worldHogs();
        c.memoryHolders();
        c.memoryPressure();
        c.garbageMakers();
        c.cpuHogs();
        c.logSpam();
        c.out.sort(Comparator.comparingDouble(Contender::score).reversed());
        LoadDigest load = r.load();
        if (load != null) {
            // A loading capture is about loading: its steps come first, frame and tick costs after.
            List<Contender> all = new ArrayList<>(load.contenders);
            all.addAll(c.out);
            return all.size() > 8 ? all.subList(0, 8) : all;
        }
        return c.out.size() > 8 ? c.out.subList(0, 8) : c.out;
    }

    private void add(String title, String size, String why, String tryThis, String link, double score) {
        if (score > 0.02) out.add(new Contender(title, size, why, tryThis, link, score));
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    private static String ms(double nanos) {
        return Stats.ms(nanos);
    }

    // --- frames -------------------------------------------------------------------------------

    private void frameSpikes() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frames.count() < 30) return;
        long threshold = Math.max((long) (r.frames.p50() * 2.5), r.frames.p50() + 10_000_000L);
        int count = 0;
        for (int i = 0; i < t.ticks.size(); i++) if (t.ticks.duration(i) > threshold) count++;
        if (count == 0) return;
        // What the slow frames had in common: the section most often dominating them.
        Map<Integer, Integer> culprits = new HashMap<>();
        for (SpanDigest.Spike s : t.spikes()) {
            if (s.duration <= threshold) continue;
            int best = -1, bestDepth = -1;
            for (int i = 0; i < s.spans; i++) {
                if (s.spanDuration[i] >= s.duration * 0.5 && s.depth[i] > bestDepth) {
                    best = s.node[i];
                    bestDepth = s.depth[i];
                }
            }
            if (best > 0) culprits.merge(best, 1, Integer::sum);
        }
        String culprit = culprits.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> "Most of them were stuck in \"" + friendly(r.path(t, e.getKey())) + "\".").orElse("They had no single common cause.");
        double perMinute = count / r.seconds() * 60;
        add(f("Stutters: %d frames took over %s", count, ms(threshold)),
                f("%.0f per minute, worst %s", perMinute, ms(r.frames.max())),
                "Each one is a visible hitch. " + culprit,
                "Open Frames & ticks and click the tallest bars to see exactly what ran during each stutter.",
                "frames", Math.min(1, perMinute / 30.0) * 0.9);
    }

    private void frameHog() {
        SpanDigest.ThreadSpans t = r.render;
        if (t == null || r.frames.count() < 30) return;
        double n = r.frames.count();
        int best = -1;
        for (int i = 1; i < t.tree.size(); i++) {
            String path = r.path(t, i);
            if (path.endsWith("updateDisplay") || path.endsWith("yield")) continue;
            if (best < 0 || t.tree.self(i) > t.tree.self(best)) best = i;
        }
        if (best < 0) return;
        double per = t.tree.self(best) / n;
        double share = per / r.frames.mean();
        String mods = modsInside(t, best);
        add(f("Biggest piece of every frame: %s", friendly(r.path(t, best))),
                f("%s per frame (%.0f%% of frame time)", ms(per), share * 100),
                "This step runs every frame, so cutting it raises FPS directly." + (mods.isEmpty() ? "" : " Code running here: " + mods + "."),
                "Look at the settings of the mods named here (render distance, detail levels), or compare a capture with one of them removed.",
                "sections:render", share * 0.8);
    }

    // --- server -------------------------------------------------------------------------------

    private void serverTicks() {
        SpanDigest.ThreadSpans t = r.server;
        if (t == null || r.serverWork.count() < 20) return;
        int over = 0;
        for (long v : r.serverWorkPerTick) if (v > 50_000_000L) over++;
        double load = r.serverWork.mean() / 50e6;
        if (load < 0.3 && over == 0) return;
        int best = -1;
        for (int i = 1; i < t.tree.size(); i++) {
            if (r.path(t, i).startsWith("nextTickWait")) continue;
            if (best < 0 || t.tree.self(i) > t.tree.self(best)) best = i;
        }
        String where = best < 0 ? "" : " The biggest part is \"" + friendly(r.path(t, best)) + "\".";
        add(over > 0 ? f("Server lag: %d %s went over the 50 ms limit", over, over == 1 ? "tick" : "ticks") : "The server is working hard",
                f("typical tick %s of 50 ms, worst %s", ms(r.serverWork.p50()), ms(r.serverWork.max())),
                "When ticks run long, mobs, machines and the world slow down or rubber-band." + where,
                "See World for the heaviest mobs, machines and chunks; see Sections (server) for the full tick breakdown.",
                "sections:server", Math.min(1, load) * 0.85 + (over > 0 ? 0.1 : 0));
    }

    private void worldHogs() {
        for (WorldDigest.Level level : r.world.levels) {
            if (level.client) continue;
            List<WorldDigest.TypeTotal> types = level.topTypes();
            if (types.isEmpty()) continue;
            WorldDigest.TypeTotal top = types.get(0);
            double perSecond = top.nanos / r.seconds();
            double share = perSecond / 1e9; // of one core
            String where = "";
            List<WorldDigest.Chunk> chunks = level.topChunks();
            if (!chunks.isEmpty()) {
                WorldDigest.Chunk c = chunks.get(0);
                where = f(" The busiest chunk is at blocks %d, %d in %s.", c.x * 16, c.z * 16, level.dimension);
            }
            add(f("%s ×%d use the most server time", typeName(top.type), top.instances),
                    f("%s per second of server time", ms(perSecond)),
                    f("They run every tick; together they take %.0f%% of the server's time.", perSecond / 1e9 * 100) + where,
                    "Reduce how many there are, spread them out, or check that mod's settings. The World tab lists each one with coordinates.",
                    "world:" + level.dimension, share * 2);
        }
    }

    // --- memory -------------------------------------------------------------------------------

    private void memoryHolders() {
        Phases.Stat w = Phases.whole(r);
        long max = Math.max(1, w.heapMax());
        String deep = r.data.deepMemory();
        List<String[]> owners = new ArrayList<>();
        if (deep != null) {
            boolean table = false;
            for (String line : deep.split("\\R")) {
                if (line.startsWith("## Memory by owner")) {
                    table = true;
                    continue;
                }
                if (!table || line.startsWith("owner")) continue;
                if (line.isBlank() || line.startsWith("##")) break;
                String[] parts = line.trim().split("\\s{2,}");
                if (parts.length >= 2) owners.add(new String[] {parts[0], parts[1]});
            }
        } else if (r.data.heap() != null) {
            for (var e : r.data.heap().owners()) owners.add(new String[] {e.getKey(), String.valueOf(e.getValue()[0] / 1048576.0)});
        }
        int shown = 0;
        for (String[] o : owners) {
            double mb;
            try {
                mb = Double.parseDouble(o[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            String name = o[0];
            boolean base = name.startsWith("JDK") || name.startsWith("Minecraft") || name.startsWith("library") || name.startsWith("NeoForge")
                    || name.startsWith("unattributed") || name.startsWith("plain arrays");
            if (base && !(name.startsWith("JDK") && deep != null && deep.contains("zipfs.ZipFileSystem"))) continue;
            double share = mb * 1048576 / max;
            if (name.startsWith("JDK")) {
                add("Mod files kept open in memory", f("about %.0f MB", mb),
                        "The mod loader keeps the contents of jars packed inside other mods' jars in memory. It's not game data, just space taken from your memory limit.",
                        "A known mod-loader cost; fewer mods that bundle extra jars inside them reduces it.", "memory", share * 1.2);
            } else {
                add(f("%s is holding %.0f MB of memory", name, mb), f("%.0f%% of your %d MB limit", share * 100, max >> 20),
                        "Memory a mod keeps is memory the game can't use; when it runs short, the game freezes to clean up.",
                        "Check that mod's settings for distance, cache or detail options. The Memory tab shows exactly what it holds.",
                        "memory", share * 1.5);
            }
            if (++shown == 3) break;
        }
    }

    private void memoryPressure() {
        Phases.Stat w = Phases.whole(r);
        if (w.heapMax() <= 0) return;
        double share = (double) w.liveP50() / w.heapMax();
        if (w.stalls() > 0 || share > 0.7) {
            add("Running out of memory room", f("%.0f%% of memory still in use after clean-ups%s", share * 100,
                            w.stalls() > 0 ? f("; the game waited for memory %d times (%s)", w.stalls(), ms(w.stallNanos())) : ""),
                    "With little free room, Java has to clean up constantly, and sometimes the game has to stop and wait for it.",
                    "Give the game more memory (-Xmx), or reduce what the biggest memory holders keep.", "memory",
                    Math.max(0, share - 0.5) * 2 + Math.min(0.6, w.stalls() / 50.0));
        }
    }

    private void garbageMakers() {
        if (r.jfr == null || r.jfr.allocByMod.isEmpty()) return;
        var top = r.jfr.allocByMod.entrySet().stream().filter(e -> !JfrDigest.isBaseOwner(e.getKey()))
                .max(Comparator.comparingLong(e -> e.getValue().weight)).orElse(null);
        if (top == null) return;
        double mbs = top.getValue().weight / r.seconds() / 1048576;
        add(f("%s creates the most short-lived memory", top.getKey()), f("about %.0f MB per second", mbs),
                "Every MB created has to be cleaned up later; heavy churn makes clean-ups more frequent.",
                "The Memory tab shows the exact spots in its code. Worth reporting to the mod author if it's large.",
                "allocations", Math.min(1, mbs / 1500));
    }

    // --- CPU and logs -------------------------------------------------------------------------

    private void cpuHogs() {
        if (r.jfr == null || r.jfr.threadCpu.isEmpty()) return;
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        Map<String, Double> pools = new HashMap<>();
        for (var e : r.jfr.threadCpu.values()) pools.merge(e.group, e.sum / Math.max(1.0, r.seconds()) * cores, Double::sum);
        // Game threads have their own entries; Java's own housekeeping (JIT compiler, GC, JFR) and
        // Lightning are not something a player can change.
        var top = pools.entrySet().stream().filter(e -> !e.getKey().equals("Render thread") && !e.getKey().equals("Server thread")
                        && !e.getKey().matches("(?i).*(CompilerThread|GC Thread|G1 |ZWorker|ZDriver|ZDirector|JFR|Lightning|VM Thread|Service Thread|Common-Cleaner|Reference Handler|Finalizer|Signal Dispatcher|DestroyJavaVM).*"))
                .max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null || top.getValue() < 0.5) return;
        JfrDigest.PoolStats ps = r.jfr.pools.get(top.getKey());
        String mods = "";
        if (ps != null && !ps.owners.isEmpty()) {
            mods = ps.owners.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(2)
                    .map(Map.Entry::getKey).reduce((a, b) -> a + " and " + b).orElse("");
        }
        add(f("Background work: \"%s\" threads", top.getKey()),
                f("%.1f CPU cores on average (of %d)", top.getValue(), cores),
                "Background threads compete with the game's own threads for your CPU." + (mods.isEmpty() ? "" : " This work comes from " + mods + "."),
                "Usually world generation or rendering helpers; their mod's settings often have a thread or speed option.",
                "threads", top.getValue() / cores * 1.2);
    }

    private void logSpam() {
        var logs = r.data.logs();
        if (logs == null) return;
        logs.kinds().stream().max(Comparator.comparingInt(k -> k.count)).ifPresent(k -> {
            double perSecond = k.count / r.seconds();
            if (perSecond < 2) return;
            add(f("%s is spamming the log", k.logger.isEmpty() ? "Something" : k.logger), f("%d messages (%.0f per second)", k.count, perSecond),
                    "Writing log lines costs time on whatever thread does it, and it buries real errors.",
                    "See the Logs tab for the message; often a config fix or a known bug in that mod.", "logs", Math.min(0.5, perSecond / 50));
        });
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Mods whose code JVM samples found inside a section. */
    private String modsInside(SpanDigest.ThreadSpans t, int node) {
        if (r.jfr == null) return "";
        Map<String, Integer> owners = new HashMap<>();
        for (var e : t.sectionStacks.entrySet()) {
            if ((int) (e.getKey() >>> 32) != node) continue;
            int leaf = (int) (long) e.getKey();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int x = leaf; x > 0 && r.jfr.cpu.parent(x) > 0; x = r.jfr.cpu.parent(x)) {
                String o = r.jfr.frames.owner(r.jfr.cpu.frame(x));
                if (!JfrDigest.isBaseOwner(o) && seen.add(o)) owners.merge(o, e.getValue(), Integer::sum);
            }
        }
        return owners.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(3)
                .map(Map.Entry::getKey).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** "minecraft:iron_golem" → "Iron golem"; "create:mechanical_press" → "Mechanical press (create)". */
    static String typeName(String id) {
        int colon = id.indexOf(':');
        String ns = colon < 0 ? "" : id.substring(0, colon);
        String name = (colon < 0 ? id : id.substring(colon + 1)).replace('_', ' ').replace('/', ' ');
        if (!name.isEmpty()) name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return ns.isEmpty() || ns.equals("minecraft") ? name : name + " (" + ns + ")";
    }

    /** Minecraft's section paths, made a little friendlier. */
    static String friendly(String path) {
        String p = path.replace("render › gameRenderer", "gameRenderer")
                .replace("gameRenderer › level", "world rendering")
                .replace("gameRenderer › gui", "menus and HUD")
                .replace("gameRenderer", "rendering")
                .replace("tick › levels › ", "")
                .replaceAll("ServerLevel\\[[^]]*] ", "");
        return p.length() > 90 ? "…" + p.substring(p.length() - 88) : p;
    }
}
