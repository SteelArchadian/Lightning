package dev.lightning.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lightning.capture.Capture;
import dev.lightning.capture.LoadSnapshot;
import dev.lightning.capture.LoadTimeline;
import dev.lightning.capture.WallSampler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loading captures (game start-up, world joins): how long each step took, what each step spent
 * its time on, and in plain words the biggest costs.
 *
 * <p>Sources, best first: Lightning's wall-clock samples of the loading threads (from when
 * Lightning loaded), JFR's class-load events and stack samples (from the JVM's start when a
 * JVM-started recording exists), Minecraft's own reload-listener timings, and the game's log
 * files for the steps before Lightning loaded.
 */
public final class LoadDigest {
    public record Phase(String name, long from, long to) {
        double seconds() {
            return (to - from) / 1e9;
        }
    }

    /** What a loading thread was doing, from its wall-clock samples. */
    static final String[] CATEGORIES = {"Running Java", "Loading classes", "Static setup (class init)", "Waiting",
            "Graphics driver", "Reading files", "Other native code"};
    static final int JAVA = 0, LOADING = 1, CLINIT = 2, WAITING = 3, GPU = 4, FILES = 5, NATIVE = 6;

    private static final long BUCKET = 250_000_000L;

    final Report r;
    final LoadSnapshot load;
    final ModIndex mods;
    final List<Phase> phases = new ArrayList<>();
    final JsonObject json = new JsonObject();
    final List<Contenders.Contender> contenders = new ArrayList<>();
    final long start, stop;
    /** Seconds to the title screen and for the (last) world join, or -1. */
    double toTitleSeconds = -1, joinSeconds = -1, beforeModsSeconds = -1;
    double mainClassLoadSeconds, mainWaitSeconds;
    final Map<String, Double> mixinOwnerSeconds = new HashMap<>();
    final Map<String, Integer> mixinOwnerClasses = new HashMap<>();
    int classesLoaded;

    private LoadDigest(Report r, LoadSnapshot load, ModIndex mods) {
        this.r = r;
        this.load = load;
        this.mods = mods;
        this.start = r.capture.startNanos;
        this.stop = r.capture.stopNanos();
    }

    static LoadDigest of(Report r) {
        LoadSnapshot load = r.data.load();
        if (load == null) return null;
        LoadDigest d = new LoadDigest(r, load, ModIndex.fromLoadedMods());
        d.compute();
        return d;
    }

    private double ms(long nanos) {
        return Math.round(nanos / 1e5) / 10.0;
    }

    private double at(long nanos) {
        return ms(nanos - start);
    }

    private static String f(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    private void compute() {
        boolean startup = start <= LoadTimeline.JVM_START_NANOS + 1_000_000L;
        json.addProperty("kind", startup ? "startup" : "join");
        json.addProperty("jvmStartMs", ms(LoadTimeline.JVM_START_NANOS - start));
        json.addProperty("lightningLoadedMs", at(load.lightningLoadedNanos()));
        steps(startup);
        reloads();
        classLoading();
        wall();
        earlySamples();
        coverage(startup);
        contenders(startup);
    }

    // --- steps ----------------------------------------------------------------------------------

    private void steps(boolean startup) {
        JsonArray steps = new JsonArray();
        List<LoadTimeline.Milestone> marks = new ArrayList<>();
        for (LoadTimeline.Milestone m : load.steps()) {
            if (m.nanos() < start - 1_000_000L || m.nanos() > stop) continue;
            marks.add(m);
            JsonObject o = new JsonObject();
            o.addProperty("name", m.name());
            o.addProperty("atMs", at(m.nanos()));
            o.addProperty("sinceJvmMs", ms(m.nanos() - LoadTimeline.JVM_START_NANOS));
            o.addProperty("phase", m.phase());
            steps.add(o);
        }
        json.add("steps", steps);
        List<LoadTimeline.Milestone> ph = marks.stream().filter(LoadTimeline.Milestone::phase).toList();
        long from = start;
        String name = startup ? "Starting Java" : "Joining";
        for (LoadTimeline.Milestone m : ph) {
            long t = Math.max(m.nanos(), start);
            // A lead-in under 100 ms (the capture began right at this phase) is dropped.
            if (t - from > (phases.isEmpty() ? 100_000_000L : 1_000_000L)) phases.add(new Phase(name, from, t));
            from = t;
            name = m.name();
        }
        if (stop > from) phases.add(new Phase(name, from, stop));
        JsonArray out = new JsonArray();
        for (Phase p : phases) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name);
            o.addProperty("startMs", at(p.from));
            o.addProperty("ms", ms(p.to - p.from));
            out.add(o);
        }
        json.add("phases", out);
        for (LoadTimeline.Milestone m : ph) {
            if (m.name().equals("Title screen") && startup) toTitleSeconds = (m.nanos() - LoadTimeline.JVM_START_NANOS) / 1e9;
            if (m.name().equals("Building mods") && startup) beforeModsSeconds = (m.nanos() - LoadTimeline.JVM_START_NANOS) / 1e9;
        }
        long joinFrom = -1;
        for (LoadTimeline.Milestone m : ph) {
            if (m.name().startsWith("Joining") && joinFrom < 0) joinFrom = m.nanos();
            if (m.name().equals("In world") && joinFrom >= 0) {
                joinSeconds = (m.nanos() - joinFrom) / 1e9;
                joinFrom = -1;
            }
        }
        if (toTitleSeconds >= 0) json.addProperty("toTitleScreenMs", Math.round(toTitleSeconds * 1000));
        if (joinSeconds >= 0) json.addProperty("worldJoinMs", Math.round(joinSeconds * 1000));
        if (beforeModsSeconds >= 0) json.addProperty("beforeModsMs", Math.round(beforeModsSeconds * 1000));
    }

    Phase phaseAt(long t) {
        for (Phase p : phases) if (t >= p.from && t < p.to) return p;
        return null;
    }

    // --- reloads --------------------------------------------------------------------------------

    record SlowListener(String name, String reload, double seconds, double applySeconds) {}

    final List<SlowListener> slowListeners = new ArrayList<>();

    private void reloads() {
        JsonArray out = new JsonArray();
        for (LoadTimeline.Reload reload : load.reloads()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", reload.kind());
            o.addProperty("startMs", at(reload.startNanos()));
            o.addProperty("ms", ms(reload.endNanos() - reload.startNanos()));
            Phase p = phaseAt(reload.startNanos());
            if (p != null) o.addProperty("phase", p.name);
            List<LoadTimeline.Listener> sorted = new ArrayList<>(reload.listeners());
            sorted.sort(Comparator.comparingLong((LoadTimeline.Listener l) -> l.prepareNanos() + l.applyNanos()).reversed());
            JsonArray listeners = new JsonArray();
            long apply = 0;
            for (LoadTimeline.Listener l : sorted) {
                apply += l.applyNanos();
                JsonObject lo = new JsonObject();
                lo.addProperty("name", l.name());
                lo.addProperty("prepareMs", ms(l.prepareNanos()));
                lo.addProperty("applyMs", ms(l.applyNanos()));
                lo.addProperty("ms", ms(l.prepareNanos() + l.applyNanos()));
                listeners.add(lo);
                double s = (l.prepareNanos() + l.applyNanos()) / 1e9;
                if (s >= 0.5) slowListeners.add(new SlowListener(l.name(), reload.kind(), s, l.applyNanos() / 1e9));
            }
            o.addProperty("applyOnMainThreadMs", ms(apply));
            o.add("listeners", listeners);
            out.add(o);
        }
        slowListeners.sort(Comparator.comparingDouble(SlowListener::seconds).reversed());
        json.add("reloads", out);
    }

    // --- class loading (JFR) --------------------------------------------------------------------

    private long mainThreadId() {
        Thread t = Capture.renderThread;
        if (t != null) return t.threadId();
        for (var track : r.capture.tracks()) if (track.threadName.equals("Server thread")) return track.threadId;
        return -1;
    }

    private void classLoading() {
        JfrDigest j = r.jfr;
        if (j == null || j.classLoads == 0) return;
        int n = j.classLoads;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        // Per thread in start order; an enclosing load first, so nested loads find their parent.
        Arrays.sort(order, (a, b) -> {
            int c = Long.compare(j.classLoadThread[a], j.classLoadThread[b]);
            if (c != 0) return c;
            c = Long.compare(j.classLoadStart[a], j.classLoadStart[b]);
            return c != 0 ? c : Long.compare(j.classLoadDuration[b], j.classLoadDuration[a]);
        });
        long[] self = new long[n];
        int[] stack = new int[256];
        int depth = 0;
        long thread = Long.MIN_VALUE;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            if (j.classLoadThread[i] != thread) {
                thread = j.classLoadThread[i];
                depth = 0;
            }
            long s = j.classLoadStart[i];
            while (depth > 0 && s >= j.classLoadStart[stack[depth - 1]] + j.classLoadDuration[stack[depth - 1]]) depth--;
            self[i] = j.classLoadDuration[i];
            if (depth > 0) self[stack[depth - 1]] -= j.classLoadDuration[i];
            if (depth < stack.length) stack[depth++] = i;
        }
        long main = mainThreadId();
        long total = 0, mainTotal = 0;
        Map<String, long[]> byOwner = new HashMap<>();
        Map<String, double[]> byMixinOwner = new HashMap<>();
        Map<String, Long> mainByPhase = new HashMap<>();
        Map<String, Map<String, Integer>> targets = load.mixins() == null ? Map.of() : load.mixins().byTarget;
        int inCapture = 0;
        for (int i = 0; i < n; i++) {
            if (j.classLoadStart[i] < start || j.classLoadStart[i] > stop) {
                self[i] = 0;
                continue;
            }
            inCapture++;
            long t = Math.max(0, self[i]);
            total += t;
            String name = j.classLoadName[i];
            String owner = mods.ownerOfClass(name);
            long[] o = byOwner.computeIfAbsent(owner == null || owner.isEmpty() ? "unknown" : owner, x -> new long[2]);
            o[0]++;
            o[1] += t;
            Map<String, Integer> mixed = targets.get(name);
            if (mixed != null && !mixed.isEmpty()) {
                int weight = 0;
                for (int c : mixed.values()) weight += c;
                for (Map.Entry<String, Integer> e : mixed.entrySet()) {
                    double[] m = byMixinOwner.computeIfAbsent(e.getKey(), x -> new double[2]);
                    m[0]++;
                    m[1] += (double) t * e.getValue() / weight;
                }
            }
            if (j.classLoadThread[i] == main) {
                mainTotal += t;
                Phase p = phaseAt(j.classLoadStart[i]);
                if (p != null) mainByPhase.merge(p.name, t, Long::sum);
            }
        }
        classesLoaded = inCapture;
        mainClassLoadSeconds = Math.max(mainClassLoadSeconds, mainTotal / 1e9);
        JsonObject o = new JsonObject();
        o.addProperty("classes", inCapture);
        o.addProperty("totalMs", ms(total));
        o.addProperty("mainThreadMs", ms(mainTotal));
        o.addProperty("note", "Time to load each class, including its transformation by mixins and coremods, excluding classes it loaded in turn. "
                + "Time shared by several mods' mixins on one class is split by how many mixins each has there.");
        JsonArray owners = new JsonArray();
        byOwner.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1])).limit(25).forEach(e -> {
            JsonObject x = new JsonObject();
            x.addProperty("owner", e.getKey());
            x.addProperty("classes", e.getValue()[0]);
            x.addProperty("ms", ms(e.getValue()[1]));
            owners.add(x);
        });
        o.add("byClassOwner", owners);
        JsonArray mixins = new JsonArray();
        byMixinOwner.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1])).limit(25).forEach(e -> {
            JsonObject x = new JsonObject();
            x.addProperty("mod", e.getKey());
            x.addProperty("classesChanged", (long) e.getValue()[0]);
            x.addProperty("ms", ms((long) e.getValue()[1]));
            mixins.add(x);
            mixinOwnerSeconds.put(e.getKey(), e.getValue()[1] / 1e9);
            mixinOwnerClasses.put(e.getKey(), (int) e.getValue()[0]);
        });
        o.add("byMixinMod", mixins);
        JsonArray slowest = new JsonArray();
        Integer[] bySelf = new Integer[n];
        for (int i = 0; i < n; i++) bySelf[i] = i;
        Arrays.sort(bySelf, (a, b) -> Long.compare(self[b], self[a]));
        for (int k = 0; k < Math.min(30, n); k++) {
            int i = bySelf[k];
            JsonObject x = new JsonObject();
            x.addProperty("class", j.classLoadName[i]);
            x.addProperty("ms", ms(self[i]));
            x.addProperty("atMs", at(j.classLoadStart[i]));
            x.addProperty("mainThread", j.classLoadThread[i] == main);
            Map<String, Integer> mixed = targets.get(j.classLoadName[i]);
            if (mixed != null) x.addProperty("mixinsFrom", String.join(", ", mixed.keySet()));
            slowest.add(x);
        }
        o.add("slowest", slowest);
        JsonObject perPhase = new JsonObject();
        for (Phase p : phases) if (mainByPhase.containsKey(p.name)) perPhase.addProperty(p.name, ms(mainByPhase.get(p.name)));
        o.add("mainThreadByPhase", perPhase);
        json.add("classLoading", o);
    }

    // --- wall-clock samples ---------------------------------------------------------------------

    private int classify(WallSampler w, int sample) {
        for (int node = w.leaf[sample]; node > 0; node = w.nodeParent[node]) {
            String f = w.frames.get(w.nodeFrame[node]);
            if (f.startsWith("java.lang.ClassLoader.defineClass") || f.startsWith("java.lang.ClassLoader.loadClass")
                    || f.startsWith("cpw.mods.cl.") || f.startsWith("cpw.mods.modlauncher.TransformingClassLoader")
                    || f.startsWith("cpw.mods.modlauncher.ClassTransformer") || f.startsWith("org.spongepowered.asm.mixin.transformer.")
                    || f.startsWith("jdk.internal.loader.BuiltinClassLoader.loadClass") || f.startsWith("java.lang.Class.forName")) {
                return LOADING;
            }
            if (f.endsWith(".<clinit>")) return CLINIT;
        }
        byte st = w.state[sample];
        if (st == WallSampler.WAITING || st == WallSampler.BLOCKED) return WAITING;
        if (st == WallSampler.NATIVE) {
            String leaf = w.frames.get(w.nodeFrame[w.leaf[sample]]);
            if (leaf.startsWith("org.lwjgl.")) return GPU;
            if (leaf.startsWith("java.io.") || leaf.startsWith("java.nio.") || leaf.startsWith("sun.nio.") || leaf.startsWith("java.util.zip.")) return FILES;
            return NATIVE;
        }
        return JAVA;
    }

    private static boolean jdkFrame(String f) {
        return f.startsWith("java.") || f.startsWith("jdk.") || f.startsWith("sun.") || f.startsWith("com.sun.");
    }

    /** The innermost frame outside the JDK: what the thread was doing or waiting for. */
    private String culprit(WallSampler w, int sample) {
        for (int node = w.leaf[sample]; node > 0; node = w.nodeParent[node]) {
            String f = w.frames.get(w.nodeFrame[node]);
            if (!jdkFrame(f)) return f;
        }
        return w.frames.get(w.nodeFrame[w.leaf[sample]]);
    }

    /** The innermost add-on mod on the stack, or "" when only the game and libraries are. */
    private String modOnStack(WallSampler w, int sample) {
        for (int node = w.leaf[sample]; node > 0; node = w.nodeParent[node]) {
            String f = w.frames.get(w.nodeFrame[node]);
            int dot = f.lastIndexOf('.');
            String owner = mods.ownerOfFrame(dot < 0 ? f : f.substring(0, dot), dot < 0 ? "" : f.substring(dot + 1));
            if (!JfrDigest.isBaseOwner(owner)) return owner;
        }
        return "";
    }

    private void wall() {
        WallSampler w = load.wall();
        if (w == null || w.size == 0) return;
        int threads = w.threadNames.size();
        // Weight: time until the same thread's next sample (capped), so gaps are not over-counted.
        long[] weight = new long[w.size];
        int[] lastOf = new int[threads];
        Arrays.fill(lastOf, -1);
        for (int i = 0; i < w.size; i++) {
            int t = w.thread[i];
            if (lastOf[t] >= 0) weight[lastOf[t]] = Math.min(w.time[i] - w.time[lastOf[t]], 50_000_000L);
            lastOf[t] = i;
        }
        for (int t = 0; t < threads; t++) if (lastOf[t] >= 0) weight[lastOf[t]] = WallSampler.PERIOD_MILLIS * 1_000_000L;

        JsonArray out = new JsonArray();
        for (int t = 0; t < threads; t++) {
            long[] total = new long[CATEGORIES.length];
            Map<String, long[]> byPhase = new java.util.LinkedHashMap<>();
            List<Map<String, Long>> culprits = new ArrayList<>();
            for (int c = 0; c < CATEGORIES.length; c++) culprits.add(new HashMap<>());
            Map<String, Long> byMod = new HashMap<>();
            Map<String, Map<String, Long>> modByPhase = new HashMap<>();
            Map<Long, long[]> buckets = new java.util.TreeMap<>();
            long all = 0;
            for (int i = 0; i < w.size; i++) {
                if (w.thread[i] != t || w.time[i] < start || w.time[i] > stop) continue;
                int c = classify(w, i);
                long wt = weight[i];
                total[c] += wt;
                all += wt;
                Phase p = phaseAt(w.time[i]);
                String pn = p == null ? "(outside phases)" : p.name;
                byPhase.computeIfAbsent(pn, x -> new long[CATEGORIES.length])[c] += wt;
                culprits.get(c).merge(culprit(w, i), wt, Long::sum);
                String mod = modOnStack(w, i);
                if (!mod.isEmpty()) {
                    byMod.merge(mod, wt, Long::sum);
                    modByPhase.computeIfAbsent(pn, x -> new HashMap<>()).merge(mod, wt, Long::sum);
                }
                buckets.computeIfAbsent((w.time[i] - start) / BUCKET, x -> new long[CATEGORIES.length])[c] += wt;
            }
            if (all == 0) continue;
            String name = w.threadNames.get(t);
            boolean isMain = name.equals("Render thread") || name.equals("main") || (Capture.renderThread == null && name.equals("Server thread"));
            // A helper thread that only waited (an idle mod-loading worker) has nothing to show.
            if (!isMain && total[WAITING] >= 0.95 * all) continue;
            if (isMain) {
                mainWaitSeconds = total[WAITING] / 1e9;
                if (r.jfr == null || r.jfr.classLoads == 0) mainClassLoadSeconds = total[LOADING] / 1e9;
                mainModByPhase = modByPhase;
                mainCulprits = culprits;
            }
            JsonObject o = new JsonObject();
            o.addProperty("thread", name);
            o.addProperty("main", isMain);
            o.addProperty("sampledMs", ms(all));
            JsonObject tot = new JsonObject();
            for (int c = 0; c < CATEGORIES.length; c++) if (total[c] > 0) tot.addProperty(CATEGORIES[c], ms(total[c]));
            o.add("total", tot);
            JsonArray ph = new JsonArray();
            for (Map.Entry<String, long[]> e : byPhase.entrySet()) {
                JsonObject x = new JsonObject();
                x.addProperty("phase", e.getKey());
                for (int c = 0; c < CATEGORIES.length; c++) if (e.getValue()[c] > 0) x.addProperty(CATEGORIES[c], ms(e.getValue()[c]));
                Map<String, Long> pm = modByPhase.get(e.getKey());
                if (pm != null) x.add("mods", top(pm, 6, "mod"));
                ph.add(x);
            }
            o.add("byPhase", ph);
            JsonObject cul = new JsonObject();
            for (int c = 0; c < CATEGORIES.length; c++) if (!culprits.get(c).isEmpty()) cul.add(CATEGORIES[c], top(culprits.get(c), 12, "frame"));
            o.add("what", cul);
            o.add("mods", top(byMod, 20, "mod"));
            if (isMain) {
                JsonArray tl = new JsonArray();
                for (Map.Entry<Long, long[]> e : buckets.entrySet()) {
                    JsonArray row = new JsonArray();
                    row.add(e.getKey() * BUCKET / 1_000_000L);
                    for (long v : e.getValue()) row.add(Math.round(v / 1e6));
                    tl.add(row);
                }
                o.addProperty("bucketMs", BUCKET / 1_000_000L);
                o.add("timeline", tl);
            }
            out.add(o);
        }
        JsonArray cats = new JsonArray();
        for (String c : CATEGORIES) cats.add(c);
        json.add("categories", cats);
        json.add("threads", out);
        json.addProperty("wallSamplerCpuMs", ms(w.cpuNanos));
    }

    Map<String, Map<String, Long>> mainModByPhase = Map.of();
    List<Map<String, Long>> mainCulprits = List.of();

    private JsonArray top(Map<String, Long> m, int limit, String key) {
        JsonArray a = new JsonArray();
        m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(limit).forEach(e -> {
            JsonObject x = new JsonObject();
            x.addProperty(key, e.getKey());
            x.addProperty("ms", ms(e.getValue()));
            a.add(x);
        });
        return a;
    }

    // --- JFR samples before Lightning loaded ----------------------------------------------------

    /** Phase → mod → JFR samples (main thread), for the time before wall samples exist. */
    final Map<String, Map<String, Long>> earlyOwners = new HashMap<>();

    private void earlySamples() {
        JfrDigest j = r.jfr;
        if (j == null) return;
        JfrDigest.Samples s = j.samplesByThread.get(mainThreadId());
        if (s == null) return;
        long until = load.wall() != null && load.wall().size > 0 ? load.lightningLoadedNanos() : stop;
        JsonObject out = new JsonObject();
        Map<String, Long> totals = new HashMap<>();
        for (int i = 0; i < s.size; i++) {
            if (s.time[i] < start || s.time[i] >= until) continue;
            Phase p = phaseAt(s.time[i]);
            if (p == null) continue;
            String owner = "";
            for (int x = s.leaf[i]; x > 0 && j.cpu.parent(x) > 0; x = j.cpu.parent(x)) {
                String o = j.frames.owner(j.cpu.frame(x));
                if (!JfrDigest.isBaseOwner(o)) {
                    owner = o;
                    break;
                }
            }
            if (owner.isEmpty()) owner = "(game, Java & libraries)";
            earlyOwners.computeIfAbsent(p.name, k -> new HashMap<>()).merge(owner, 1L, Long::sum);
            totals.merge(p.name, 1L, Long::sum);
        }
        for (Map.Entry<String, Map<String, Long>> e : earlyOwners.entrySet()) {
            JsonArray a = new JsonArray();
            long total = Math.max(1, totals.get(e.getKey()));
            e.getValue().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(8).forEach(x -> {
                JsonObject o = new JsonObject();
                o.addProperty("owner", x.getKey());
                o.addProperty("samples", x.getValue());
                o.addProperty("sharePct", Math.round(1000.0 * x.getValue() / total) / 10.0);
                a.add(o);
            });
            out.add(e.getKey(), a);
        }
        json.add("mainThreadSamplesByPhase", out);
    }

    // --- coverage -------------------------------------------------------------------------------

    private void coverage(boolean startup) {
        JsonArray notes = new JsonArray();
        if (startup) {
            notes.add(f("Lightning itself loaded %.1f s after Java started. Steps before that are read from the game's log files%s.",
                    (load.lightningLoadedNanos() - LoadTimeline.JVM_START_NANOS) / 1e9,
                    load.jfrFromStartup() ? " and Java's own recorder, which ran from the start" : ""));
            if (!load.jfrFromStartup()) {
                notes.add("For code-level detail from the very first second, add this to the Java arguments: "
                        + "-XX:StartFlightRecording=name=lightning,settings=profile,class-loading=true");
            }
        }
        if (r.jfr != null && r.jfr.classLoads == 0) notes.add("No class-load timings were recorded, so loading game code is estimated from the main thread's samples.");
        if (load.mixins() != null && load.mixins().error != null) notes.add("Could not read which mods' mixins changed which classes: " + load.mixins().error);
        notes.add("Prepare times of reload listeners run in the background, several at once; apply times run on the main thread, one after another.");
        json.add("notes", notes);
    }

    // --- plain-English contenders ---------------------------------------------------------------

    private static String why(String phase) {
        return switch (phase) {
            case "Starting Java" -> "Java itself starting up, before the mod loader runs.";
            case "Starting the mod loader" -> "The mod loader setting up, reading its own libraries and early plugins.";
            case "Finding mods" -> "The mod loader opening every mod file to read its details. Connector also prepares Fabric mods here (slow the first time, cached after).";
            case "Loading Minecraft's code" -> "Minecraft's and the mods' code being loaded and changed by mixins as it is first used.";
            case "Building mods" -> "Each mod's start-up code: creating its blocks, items and settings.";
            case "Loading resources" -> "Textures, models, sounds, fonts and languages from the game, the mods and resource packs, plus mods' setup steps.";
            case "Title screen" -> "Sitting on the title screen.";
            case "Joining: starting the world" -> "Reading the world, loading data packs (recipes, tags, loot tables) and preparing the spawn area.";
            case "Joining: connecting to the server", "Joining: connecting" -> "Connecting and receiving the server's data.";
            case "Joining: loading terrain" -> "Waiting for the chunks around you to arrive and be built for drawing.";
            default -> "";
        };
    }

    private static String tryFor(String phase) {
        return switch (phase) {
            case "Finding mods", "Starting the mod loader" -> "Keep the game on an SSD and leave Connector's cache alone; fewer or smaller mod files help.";
            case "Loading Minecraft's code" -> "Mixin-heavy mods make this longer; see which mods change the most classes below.";
            case "Building mods" -> "The mods listed take longest; check for updates or config options that skip work at start-up.";
            case "Loading resources" -> "Model baking grows with every block and item. Large resource packs add texture time. Mods such as ModernFix (\"dynamic resources\") load models on demand.";
            case "Joining: starting the world" -> "Data-pack heavy mods (recipes, world generation) add time here; the reload listeners below show which.";
            case "Joining: loading terrain" -> "Lower render distance for faster joins, or pre-generate the world so chunks don't have to be created.";
            default -> "See the Loading tab for what this step spent its time on.";
        };
    }

    private String topMods(String phase) {
        Map<String, Long> m = mainModByPhase.getOrDefault(phase, earlyOwners.getOrDefault(phase, Map.of()));
        if (m.isEmpty()) return "";
        boolean samples = !mainModByPhase.containsKey(phase);
        long total = 0;
        for (long v : m.values()) total += v;
        final long all = Math.max(1, total);
        List<String> parts = new ArrayList<>();
        m.entrySet().stream().filter(e -> !e.getKey().startsWith("(")).sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(4)
                .forEach(e -> parts.add(samples ? f("%s (%.0f%% of samples)", e.getKey(), 100.0 * e.getValue() / all) : f("%s %.1f s", e.getKey(), e.getValue() / 1e9)));
        return parts.isEmpty() ? "" : " Busiest mods on the main thread: " + String.join(", ", parts) + ".";
    }

    private void contenders(boolean startup) {
        double total = Math.max(1e-9, (stop - start) / 1e9);
        String whole = startup ? "start-up" : "the join";
        for (Phase p : phases) {
            double s = p.seconds();
            if (p.name.equals("Title screen") || p.name.equals("In world") || s < 2 || s / total < 0.1) continue;
            String extra = "";
            if (p.name.equals("Loading resources") || p.name.startsWith("Joining")) {
                List<String> l = new ArrayList<>();
                for (SlowListener sl : slowListeners) {
                    Phase at = null;
                    for (LoadTimeline.Reload rl : load.reloads()) if (rl.kind().equals(sl.reload())) at = phaseAt(rl.startNanos());
                    if (at == p && l.size() < 4) l.add(f("%s %.1f s", friendlyListener(sl.name()), sl.seconds()));
                }
                if (!l.isEmpty()) extra = " Slowest parts: " + String.join(", ", l) + ".";
            }
            add(f("%s took %.1f s", p.name, s), f("%.0f%% of %s", 100 * s / total, whole), why(p.name) + extra + topMods(p.name), tryFor(p.name), "loading", s / total);
        }
        if (mainClassLoadSeconds >= 2) {
            List<String> l = new ArrayList<>();
            mixinOwnerSeconds.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(4)
                    .forEach(e -> l.add(f("%s (%d classes, ~%.1f s)", e.getKey(), mixinOwnerClasses.getOrDefault(e.getKey(), 0), e.getValue())));
            add(f("Loading game code: %.1f s on the main thread", mainClassLoadSeconds), classesLoaded > 0 ? f("%,d classes", classesLoaded) : "estimated from samples",
                    "Java loads each class the first time it is used, and mixins and coremods rewrite it as it loads. The main thread can't do anything else meanwhile."
                            + (l.isEmpty() ? "" : " Mods whose mixins changed the most loading time: " + String.join(", ", l) + "."),
                    "Mods with many mixins add up here. Updating them, or removing ones you don't need, shortens this.", "loading", 0.8 * mainClassLoadSeconds / total);
        }
        if (mainWaitSeconds >= 3) {
            String what = "";
            if (mainCulprits.size() > WAITING && !mainCulprits.get(WAITING).isEmpty()) {
                List<String> l = new ArrayList<>();
                mainCulprits.get(WAITING).entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(3)
                        .forEach(e -> l.add(f("%s (%.1f s)", e.getKey(), e.getValue() / 1e9)));
                what = " Mostly at: " + String.join(", ", l) + ".";
            }
            add(f("Main thread waited %.1f s", mainWaitSeconds), f("%.0f%% of %s", 100 * mainWaitSeconds / total, whole),
                    "The main thread was idle, waiting for background work to finish (resource loading, the world server, file reads)." + what,
                    "Waiting is a symptom: the other steps show what it waited for.", "loading", 0.5 * mainWaitSeconds / total);
        }
        contenders.sort(Comparator.comparingDouble(Contenders.Contender::score).reversed());
    }

    private void add(String title, String size, String why, String tryThis, String link, double score) {
        contenders.add(new Contenders.Contender(title, size, why, tryThis, link, score));
    }

    /** Reload listener names are class names; a few common ones get a description. */
    static String friendlyListener(String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return switch (simple) {
            case "ModelManager" -> "model baking (ModelManager)";
            case "TextureManager" -> "textures (TextureManager)";
            case "SoundManager" -> "sounds (SoundManager)";
            case "FontManager" -> "fonts (FontManager)";
            case "LanguageManager" -> "languages (LanguageManager)";
            case "ClientModLoader" -> "mods' setup (ClientModLoader)";
            case "RecipeManager" -> "recipes (RecipeManager)";
            case "LootDataManager", "ReloadableServerRegistries" -> "loot tables (" + simple + ")";
            case "ServerAdvancementManager" -> "advancements";
            case "TagManager" -> "tags (TagManager)";
            default -> simple;
        };
    }

    /** One line per phase and the slowest listeners, for the Markdown brief. */
    String markdown() {
        StringBuilder b = new StringBuilder();
        b.append(json.get("kind").getAsString().equals("startup") ? "## Game start-up\n\n" : "## World join\n\n");
        if (toTitleSeconds >= 0) b.append(f("Title screen after %.1f s.%n", toTitleSeconds));
        if (joinSeconds >= 0) b.append(f("World join took %.1f s.%n", joinSeconds));
        b.append("\n| step | starts at | seconds |\n|---|---:|---:|\n");
        for (Phase p : phases) b.append(f("| %s | %.1f s | %.1f |%n", p.name, (p.from - start) / 1e9, p.seconds()));
        if (!slowListeners.isEmpty()) {
            b.append("\nSlowest reload steps:\n\n");
            for (int i = 0; i < Math.min(8, slowListeners.size()); i++) {
                SlowListener sl = slowListeners.get(i);
                b.append(f("- %s (%s): %.1f s, %.1f s of it on the main thread%n", friendlyListener(sl.name()), sl.reload(), sl.seconds(), sl.applySeconds()));
            }
        }
        b.append('\n');
        return b.toString();
    }
}
