package dev.lightning.analysis;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Reads the JFR recording once and keeps compact aggregates plus the samples needed for correlation. */
public final class JfrDigest {
    /** The last run of digits in a thread name ("c2me-worker-3" → "c2me-worker-#"). */
    private static final Pattern DIGITS = Pattern.compile("#?\\d+(?=\\D*$)");
    private static final int MAX_STACK = 512;
    private static final int MAX_LISTED = 5_000;

    /** Samples of one thread, in arrival order (sorted later). */
    public static final class Samples {
        long[] time = new long[256];
        int[] leaf = new int[256];
        boolean[] nativeSample = new boolean[256];
        /** Index into {@link JfrDigest#poolNames} of the sampled thread's pool (allSamples only). */
        int[] pool = new int[256];
        int size;

        public int size() {
            return size;
        }

        void add(long t, int node, boolean isNative) {
            add(t, node, isNative, 0);
        }

        void add(long t, int node, boolean isNative, int poolIndex) {
            if (size == time.length) {
                time = java.util.Arrays.copyOf(time, size * 2);
                leaf = java.util.Arrays.copyOf(leaf, size * 2);
                nativeSample = java.util.Arrays.copyOf(nativeSample, size * 2);
                pool = java.util.Arrays.copyOf(pool, size * 2);
            }
            time[size] = t;
            leaf[size] = node;
            nativeSample[size] = isNative;
            pool[size] = poolIndex;
            size++;
        }

        void sort() {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Long.compare(time[a], time[b]));
            long[] t = new long[size];
            int[] l = new int[size];
            boolean[] n = new boolean[size];
            int[] p = new int[size];
            for (int i = 0; i < size; i++) {
                t[i] = time[order[i]];
                l[i] = leaf[order[i]];
                n[i] = nativeSample[order[i]];
                p[i] = pool[order[i]];
            }
            time = t;
            leaf = l;
            nativeSample = n;
            pool = p;
        }
    }

    public record Gc(long time, long duration, String name, String cause, long sumOfPauses, long longestPause, int gcId) {}

    /** G1 detail of one collection: kind, failed evacuation, slowest pause phase, references processed. */
    static final class GcDetail {
        String type = "";
        boolean evacuationFailed;
        /** Objects and bytes that could not be copied (summed: G1 reports one event per worker). */
        long failedObjects, failedBytes;
        String slowestPhase = "";
        long slowestPhaseNanos;
        int tenuring = -1;
        final Map<String, Long> references = new java.util.LinkedHashMap<>();
    }
    final Map<Integer, GcDetail> gcDetails = new HashMap<>();

    private GcDetail gcDetail(RecordedEvent e) {
        return gcDetails.computeIfAbsent(e.getInt("gcId"), k -> new GcDetail());
    }

    public record Pause(long time, long duration, String thread, String kind, String detail, String stack) {
        /** A VM operation at a safepoint: every Java thread is stopped while it runs. */
        public boolean stopsAll() {
            return kind.equals("safepoint");
        }

        /** Requested by a garbage collector thread (ZGC's concurrent handshakes, G1, Shenandoah). */
        public boolean fromGc() {
            return thread.startsWith("Z") || thread.startsWith("G1") || thread.startsWith("Shenandoah") || thread.startsWith("GC Thread");
        }
    }

    /** One safepoint's synchronization: from the request until every Java thread had stopped. */
    public record SafepointSync(long time, long duration, long id, int threads, int running, int iterations) {}
    final List<SafepointSync> safepoints = new ArrayList<>();
    /** safepointId → {threads still running when the sync began (initialThreadCount), iterations}, joined into {@link #safepoints} after reading. */
    private final Map<Long, int[]> syncState = new HashMap<>();

    /**
     * Thread pools with Java samples during a safepoint wait, most samples first (Lightning and JFR
     * left out). The late thread is among them when it was sampled, but samples alone can't single
     * it out (others are caught on their way to the safepoint).
     */
    public Map<String, Integer> poolsBetween(long from, long to) {
        Map<String, Integer> counts = new HashMap<>();
        Samples s = allSamples;
        int lo = 0, hi = s.size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (s.time[mid] < from) lo = mid + 1;
            else hi = mid;
        }
        for (int i = lo; i < s.size && s.time[i] <= to; i++) {
            if (s.nativeSample[i]) continue;
            String pool = poolNames.get(s.pool[i]);
            if (pool.startsWith("Lightning") || pool.startsWith("JFR")) continue;
            counts.merge(pool, 1, Integer::sum);
        }
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        counts.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public record CpuPoint(long time, double jvmUser, double jvmSystem, double machine) {}

    public static final class ThreadCpu {
        public double sum, max;
        public int count;
        public String group;
    }

    public static final class Alloc {
        public long samples, weight;
    }

    final long epochOffset;
    final FrameTable frames;
    final FlameTree cpu = new FlameTree();
    final FlameTree alloc = new FlameTree();
    final Map<Long, Samples> samplesByThread = new HashMap<>();
    final Map<String, ThreadCpu> threadCpu = new HashMap<>();
    final Map<String, Alloc> allocByClass = new HashMap<>();
    final Map<String, Alloc> allocBySite = new HashMap<>();
    /** Allocation charged to the owner of the allocating code (innermost frame outside JDK and libraries). */
    final Map<String, Alloc> allocByMod = new HashMap<>();
    /** Allocation with an add-on mod anywhere on the stack, charged to the innermost such mod (wrappers included). */
    final Map<String, Alloc> allocOnStack = new HashMap<>();
    /** Lock and park time on the game threads (ns, idle server waits excluded), charged to the owner of the waiting code. */
    final Map<String, Long> waitByMod = new HashMap<>();
    /** Second (nanoTime / 1e9) → sampled allocation bytes. */
    final java.util.TreeMap<Long, Long> allocPerSecond = new java.util.TreeMap<>();

    /** Samples of one thread pool (threads grouped by name with numbers removed). */
    public static final class PoolStats {
        public long samples;
        public final Map<String, Long> owners = new HashMap<>();
        public final java.util.TreeMap<Long, Long> perSecond = new java.util.TreeMap<>();
    }

    final Map<String, PoolStats> pools = new HashMap<>();
    /** Pool names by index, for {@link Samples#pool}. */
    final List<String> poolNames = new ArrayList<>();
    private final Map<String, Integer> poolIds = new HashMap<>();

    /** One per-thread CPU reading: time, thread pool, cores used by that thread. */
    public record ThreadCpuPoint(long time, String group, double cores) {}

    final List<ThreadCpuPoint> threadCpuPoints = new ArrayList<>();
    /** Every kept CPU sample (all threads): time and flame leaf. */
    final Samples allSamples = new Samples();
    final List<String> allocModNames = new ArrayList<>();
    private final Map<String, Integer> allocModIds = new HashMap<>();
    long[] allocTime = new long[1024];
    int[] allocMod = new int[1024];
    /** Per sample: index into allocModNames of the innermost add-on mod on the stack, or -1. */
    int[] allocStackMod = new int[1024];
    long[] allocWeightAt = new long[1024];
    int allocCount;
    final List<Gc> gcs = new ArrayList<>();
    final List<Pause> allocationStalls = new ArrayList<>();
    final List<Pause> vmOperations = new ArrayList<>();
    /** Deoptimisations and class definitions (gameplay captures), to explain long VM operations. */
    public record Deopt(long time, String thread, String method, String owner, String reason) {}

    public record ClassDef(long time, String name, String owner) {}

    /** An object still alive when recording stopped, sampled where it was allocated (jdk.OldObjectSample). */
    public record OldSample(String className, long size, String site, String mod) {}
    final List<OldSample> oldSamples = new ArrayList<>();

    final List<Deopt> deopts = new ArrayList<>();
    final List<ClassDef> classDefines = new ArrayList<>();
    private static final int MAX_EXPLAIN = 50_000;
    final List<Pause> blocking = new ArrayList<>();
    final Map<String, long[]> blockingByGroup = new HashMap<>();
    final List<CpuPoint> cpuLoad = new ArrayList<>();
    final List<long[]> rss = new ArrayList<>();
    String youngCollector = "", oldCollector = "", cpuDescription = "";
    int hwThreads;
    long executionSamples, nativeSamples, nativeSamplesSkipped, truncatedStacks, events, dataLossBytes;
    /** Second since the epoch of nanoTime → summed thread CPU load, in cores. */
    final java.util.TreeMap<Long, double[]> processCpu = new java.util.TreeMap<>();
    long allocationWeight;
    /** Cap on one allocation sample's weight, and how many samples / bytes were cut by it. */
    static final long MAX_ALLOC_WEIGHT = 1L << 30;
    public long allocCappedSamples, allocCappedBytes;
    /** File and network waits (io module): "kind|target" → count, total, longest, time on game threads. */
    final Map<String, long[]> io = new HashMap<>();
    /** The slowest file/network waits on game threads, with stacks. */
    final List<Pause> ioOnGameThreads = new ArrayList<>();
    /** Class loads (loading captures only): thread id, start, duration and class name of each. */
    long[] classLoadThread = new long[0], classLoadStart = new long[0], classLoadDuration = new long[0];
    String[] classLoadName = new String[0];
    int classLoads;
    long firstEvent = Long.MAX_VALUE, lastEvent = Long.MIN_VALUE;

    private final Set<Long> gameThreads;
    private final int[] stackBuffer = new int[MAX_STACK + 1];

    private final ModIndex mods;

    private JfrDigest(long epochOffset, ModIndex mods, Set<Long> gameThreads) {
        this.epochOffset = epochOffset;
        this.mods = mods;
        this.frames = new FrameTable(mods);
        this.gameThreads = gameThreads;
    }

    public static JfrDigest read(Path file, long epochOffset, ModIndex mods, Set<Long> gameThreads) throws IOException {
        JfrDigest d = new JfrDigest(epochOffset, mods, gameThreads);
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) d.accept(recording.readEvent());
        }
        for (Samples s : d.samplesByThread.values()) s.sort();
        d.allSamples.sort();
        for (int i = 0; i < d.safepoints.size(); i++) {
            SafepointSync p = d.safepoints.get(i);
            int[] st = d.syncState.get(p.id());
            if (st != null) d.safepoints.set(i, new SafepointSync(p.time(), p.duration(), p.id(), p.threads(), st[0], st[1]));
        }
        d.safepoints.sort((a, b) -> Long.compare(a.time(), b.time()));
        return d;
    }

    /** Converts a JFR timestamp to this JVM's nanoTime. */
    long nano(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano() - epochOffset;
    }

    static String group(String threadName) {
        if (threadName == null) return "(unknown thread)";
        return DIGITS.matcher(threadName).replaceFirst("#");
    }

    private void accept(RecordedEvent e) {
        events++;
        long time = nano(e.getStartTime());
        firstEvent = Math.min(firstEvent, time);
        lastEvent = Math.max(lastEvent, time);
        switch (e.getEventType().getName()) {
            case "jdk.ExecutionSample" -> sample(e, time, false);
            case "jdk.NativeMethodSample" -> sample(e, time, true);
            case "jdk.ObjectAllocationSample" -> allocation(e, time);
            case "jdk.GarbageCollection" -> gcs.add(new Gc(time, e.getDuration().toNanos(), e.getString("name"),
                    e.getString("cause"), e.getDuration("sumOfPauses").toNanos(), e.getDuration("longestPause").toNanos(),
                    e.hasField("gcId") ? e.getInt("gcId") : -1));
            case "jdk.G1GarbageCollection" -> gcDetail(e).type = String.valueOf(e.getString("type"));
            case "jdk.YoungGarbageCollection" -> gcDetail(e).tenuring = e.getInt("tenuringThreshold");
            case "jdk.EvacuationFailed" -> {
                GcDetail g = gcDetail(e);
                g.evacuationFailed = true;
                if (e.getValue("evacuationFailed") instanceof jdk.jfr.consumer.RecordedObject f) {
                    if (f.hasField("objectCount")) g.failedObjects += f.getLong("objectCount");
                    if (f.hasField("totalSize")) g.failedBytes += f.getLong("totalSize");
                }
            }
            case "jdk.GCPhasePauseLevel1" -> {
                GcDetail g = gcDetail(e);
                long d = e.getDuration().toNanos();
                if (d > g.slowestPhaseNanos) {
                    g.slowestPhaseNanos = d;
                    g.slowestPhase = String.valueOf(e.getString("name"));
                }
            }
            case "jdk.GCReferenceStatistics" -> gcDetail(e).references.merge(String.valueOf(e.getString("type")), e.getLong("count"), Long::sum);
            case "jdk.ZAllocationStall" -> {
                if (allocationStalls.size() < MAX_LISTED) {
                    allocationStalls.add(new Pause(time, e.getDuration().toNanos(), threadName(e.getThread()), "allocation stall",
                            e.hasField("type") ? String.valueOf((Object) e.getValue("type")) : "", ""));
                }
            }
            case "jdk.SafepointBegin" -> safepoints.add(new SafepointSync(time, e.getDuration().toNanos(), e.getLong("safepointId"),
                    e.hasField("totalThreadCount") ? e.getInt("totalThreadCount") : -1, -1, -1));
            case "jdk.SafepointStateSynchronization" -> syncState.put(e.getLong("safepointId"),
                    new int[] {e.hasField("initialThreadCount") ? e.getInt("initialThreadCount") : e.getInt("runningThreadCount"), e.getInt("iterations")});
            case "jdk.ExecuteVMOperation" -> {
                if (vmOperations.size() < MAX_LISTED) {
                    vmOperations.add(new Pause(time, e.getDuration().toNanos(), threadName(e.getThread("caller")),
                            e.getBoolean("safepoint") ? "safepoint" : "vm operation", e.getString("operation"), ""));
                }
            }
            case "jdk.JavaMonitorEnter" -> block(e, time, "lock", e.getClass("monitorClass"));
            case "jdk.ThreadPark" -> block(e, time, "park", e.getClass("parkedClass"));
            case "jdk.ThreadCPULoad" -> {
                String name = threadName(e.getThread());
                ThreadCpu cpuStat = threadCpu.computeIfAbsent(name, n -> new ThreadCpu());
                cpuStat.group = group(name);
                double load = e.getFloat("user") + e.getFloat("system");
                processCpu.computeIfAbsent(Math.floorDiv(time, 1_000_000_000L), k -> new double[1])[0] += load * Runtime.getRuntime().availableProcessors();
                cpuStat.sum += load;
                cpuStat.count++;
                cpuStat.max = Math.max(cpuStat.max, load);
                threadCpuPoints.add(new ThreadCpuPoint(time, cpuStat.group, load * Runtime.getRuntime().availableProcessors()));
            }
            case "jdk.CPULoad" -> cpuLoad.add(new CpuPoint(time, e.getFloat("jvmUser"), e.getFloat("jvmSystem"), e.getFloat("machineTotal")));
            case "jdk.ResidentSetSize" -> rss.add(new long[] {time, e.getLong("size")});
            case "jdk.GCConfiguration" -> {
                youngCollector = e.getString("youngCollector");
                oldCollector = e.getString("oldCollector");
            }
            case "jdk.CPUInformation" -> {
                cpuDescription = e.getString("cpu");
                String description = e.getString("description");
                if (description != null) {
                    for (String line : description.split("\\R")) {
                        if (line.startsWith("Brand: ")) cpuDescription = line.substring(7).trim();
                    }
                }
                hwThreads = e.getInt("hwThreads");
            }
            case "jdk.DataLoss" -> dataLossBytes += e.getLong("amount");
            case "jdk.ClassLoad" -> classLoad(e, time);
            case "jdk.Deoptimization" -> {
                if (deopts.size() < MAX_EXPLAIN && e.getValue("method") instanceof jdk.jfr.consumer.RecordedMethod m) {
                    String cls = m.getType() == null ? "?" : m.getType().getName();
                    deopts.add(new Deopt(time, threadName(e.getThread()), cls + "." + m.getName(), mods.ownerOfFrame(cls, m.getName()),
                            e.hasField("reason") ? String.valueOf((Object) e.getValue("reason")) : "?"));
                }
            }
            case "jdk.OldObjectSample" -> oldObject(e);
            case "jdk.ObjectAllocationOutsideTLAB" -> outsideTlab(e);
            case "jdk.ClassDefine" -> {
                RecordedClass c = e.getClass("definedClass");
                if (c != null && classDefines.size() < MAX_EXPLAIN) classDefines.add(new ClassDef(time, c.getName(), mods.ownerOfClass(c.getName())));
            }
            case "jdk.FileRead", "jdk.FileWrite" -> io(e, time, e.getEventType().getName().endsWith("Read") ? "file read" : "file write",
                    e.hasField("path") ? e.getString("path") : "?");
            case "jdk.SocketRead", "jdk.SocketWrite" -> io(e, time, e.getEventType().getName().endsWith("Read") ? "network read" : "network write",
                    (e.hasField("host") && e.getString("host") != null && !e.getString("host").isEmpty() ? e.getString("host") : e.hasField("address") ? e.getString("address") : "?")
                            + (e.hasField("port") ? ":" + e.getInt("port") : ""));
            default -> {}
        }
    }

    private void classLoad(RecordedEvent e, long time) {
        RecordedClass loaded = e.getClass("loadedClass");
        RecordedThread thread = e.getThread();
        if (loaded == null || thread == null) return;
        if (classLoads == classLoadStart.length) {
            int n = Math.max(1024, classLoads * 2);
            classLoadThread = java.util.Arrays.copyOf(classLoadThread, n);
            classLoadStart = java.util.Arrays.copyOf(classLoadStart, n);
            classLoadDuration = java.util.Arrays.copyOf(classLoadDuration, n);
            classLoadName = java.util.Arrays.copyOf(classLoadName, n);
        }
        classLoadThread[classLoads] = thread.getJavaThreadId();
        classLoadStart[classLoads] = time;
        classLoadDuration[classLoads] = e.getDuration().toNanos();
        classLoadName[classLoads] = loaded.getName();
        classLoads++;
    }

    private void io(RecordedEvent e, long time, String kind, String target) {
        long d = e.getDuration().toNanos();
        RecordedThread thread = e.getThread();
        boolean game = thread != null && gameThreads.contains(thread.getJavaThreadId());
        // Group files by folder and extension so thousands of chunk or texture files form one row.
        String key = kind + "|" + (kind.startsWith("file") ? folderOf(target) : target);
        long[] a = io.computeIfAbsent(key, k -> new long[4]);
        a[0]++;
        a[1] += d;
        a[2] = Math.max(a[2], d);
        if (game) a[3] += d;
        if (game && ioOnGameThreads.size() < MAX_LISTED) {
            StringBuilder stack = new StringBuilder();
            RecordedStackTrace trace = e.getStackTrace();
            if (trace != null) {
                int shown = 0;
                for (RecordedFrame frame : trace.getFrames()) {
                    RecordedMethod m = frame.getMethod();
                    if (m == null) continue;
                    stack.append(m.getType().getName()).append('.').append(m.getName()).append(':').append(frame.getLineNumber()).append('\n');
                    if (++shown == 14) break;
                }
            }
            ioOnGameThreads.add(new Pause(time, d, threadName(thread), kind, target, stack.toString()));
        }
    }

    /** "…/region/*.mca" for "C:/…/world/region/r.1.2.mca": the folder, with the file name reduced to its extension. */
    static String folderOf(String path) {
        if (path == null) return "?";
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String name = slash < 0 ? p : p.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return (slash < 0 ? "" : p.substring(0, slash + 1)) + "*" + (dot < 0 ? "" : name.substring(dot));
    }

    private static String threadName(RecordedThread thread) {
        if (thread == null) return "(unknown thread)";
        String name = thread.getJavaName();
        return name != null ? name : thread.getOSName();
    }

    /** Converts a stack to root-first frame ids. Returns the count, or -1 when there is no stack. */
    private int frames(RecordedStackTrace stack) {
        if (stack == null) return -1;
        List<RecordedFrame> list = stack.getFrames();
        int n = Math.min(list.size(), MAX_STACK);
        int k = 0;
        if (stack.isTruncated()) {
            stackBuffer[k++] = frames.special("(stack deeper than JFR's limit; outer frames missing)");
            truncatedStacks++;
        }
        for (int i = n - 1; i >= 0; i--) {
            RecordedMethod method = list.get(i).getMethod();
            if (method == null) continue;
            stackBuffer[k++] = frames.method(method.getType().getName(), method.getName());
        }
        return k;
    }

    private void sample(RecordedEvent e, long time, boolean isNative) {
        RecordedThread thread = e.getThread("sampledThread");
        boolean game = thread != null && gameThreads.contains(thread.getJavaThreadId());
        // A thread "in native code" is often just blocked in an OS wait; only game threads' native
        // samples are informative (for example the render thread inside graphics driver calls).
        if (isNative && !game) {
            nativeSamplesSkipped++;
            return;
        }
        String name = threadName(thread);
        int count = frames(e.getStackTrace());
        if (count < 0) return;
        if (isNative) nativeSamples++;
        else executionSamples++;
        String pool = group(name);
        int leaf = cpu.add(frames.special("thread: " + pool), stackBuffer, count, 1);
        Integer poolIndex = poolIds.get(pool);
        if (poolIndex == null) {
            poolIndex = poolNames.size();
            poolNames.add(pool);
            poolIds.put(pool, poolIndex);
        }
        allSamples.add(time, leaf, isNative, poolIndex);
        // Which mods' code runs on each thread pool (pools are often borrowed by other mods).
        PoolStats ps = pools.computeIfAbsent(pool, k -> new PoolStats());
        ps.samples++;
        ps.perSecond.merge(Math.floorDiv(time, 1_000_000_000L), 1L, Long::sum);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            String owner = frames.owner(stackBuffer[i]);
            if (!isBaseOwner(owner) && seen.add(owner)) ps.owners.merge(owner, 1L, Long::sum);
        }
        if (game) {
            samplesByThread.computeIfAbsent(thread.getJavaThreadId(), k -> new Samples()).add(time, leaf, isNative);
        }
    }

    /** Owners that are the game or plumbing rather than an add-on mod. */
    static boolean isBaseOwner(String owner) {
        return owner.isEmpty() || owner.equals("java") || owner.equals("library") || owner.equals("minecraft")
                || owner.equals("neoforge") || owner.equals("lwjgl") || owner.equals("unknown");
    }

    /**
     * Who an allocation sample is charged to, from its frame owners innermost first: [0] the owner of the
     * allocating code (innermost frame outside the JDK and libraries, else "java"), and [1] the innermost
     * add-on mod anywhere on the stack, or null. [0] is the headline: a mod that only passes calls through
     * (a codec wrapper, a mixin or event hook around vanilla code) is not blamed for vanilla's allocation.
     */
    static String[] chargeAllocation(String[] ownersInnermostFirst) {
        String site = null, onStack = null;
        for (String owner : ownersInnermostFirst) {
            if (site == null && !owner.isEmpty() && !owner.equals("java") && !owner.equals("library")) site = owner;
            if (onStack == null && !isBaseOwner(owner)) onStack = owner;
        }
        return new String[] {site != null ? site : "java", onStack};
    }

    /** One allocating site of very large objects: {count, total bytes, largest bytes}. */
    public record BigAlloc(String type, String site, String mod, long count, long bytes, long largest) {}
    /** (type, site, mod) → {count, bytes, largest}, for allocations at or over {@link #bigThreshold}. */
    final Map<String, long[]> bigAllocs = new HashMap<>();
    /** Half a G1 region (the humongous threshold) in this JVM, else 4 MB. */
    final long bigThreshold = humongousThreshold();
    boolean outsideTlabSeen;

    static long humongousThreshold() {
        try {
            var hs = java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            if (Boolean.parseBoolean(hs.getVMOption("UseG1GC").getValue())) {
                long region = Long.parseLong(hs.getVMOption("G1HeapRegionSize").getValue());
                if (region > 0) return region / 2;
            }
        } catch (RuntimeException | LinkageError e) {
            // not HotSpot, or the flag is missing: use the default below
        }
        return 4L << 20;
    }

    private void outsideTlab(RecordedEvent e) {
        outsideTlabSeen = true;
        long bytes = e.getLong("allocationSize");
        if (bytes < bigThreshold) return;
        RecordedClass type = e.getClass("objectClass");
        String site = "(no stack)", mod = "java";
        int count = frames(e.getStackTrace());
        if (count > 0) {
            site = frames.label(stackBuffer[count - 1]);
            for (int i = count - 1; i >= 0; i--) {
                String owner = frames.owner(stackBuffer[i]);
                if (!owner.isEmpty() && !owner.equals("java") && !owner.equals("library")) {
                    site = frames.label(stackBuffer[i]);
                    mod = owner;
                    break;
                }
            }
        }
        String name = type == null ? "?" : type.getName().startsWith("[") ? HeapHistogram.readable(type.getName()) : type.getName();
        long[] a = bigAllocs.computeIfAbsent(name + '\u0000' + site + '\u0000' + mod, k -> new long[3]);
        a[0]++;
        a[1] += bytes;
        a[2] = Math.max(a[2], bytes);
    }

    /** Large-allocation sites, most bytes first. */
    public List<BigAlloc> bigAllocations() {
        List<BigAlloc> out = new ArrayList<>();
        for (var e : bigAllocs.entrySet()) {
            String[] k = e.getKey().split("\u0000", 3);
            out.add(new BigAlloc(k[0], k[1], k[2], e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        out.sort((x, y) -> Long.compare(y.bytes(), x.bytes()));
        return out;
    }

    private void oldObject(RecordedEvent e) {
        if (oldSamples.size() >= MAX_EXPLAIN || !(e.getValue("object") instanceof jdk.jfr.consumer.RecordedObject object)) return;
        RecordedClass type = object.getClass("type");
        if (type == null) return;
        int count = frames(e.getStackTrace());
        String site = "(no stack)", mod = "java";
        if (count > 0) {
            // The allocating code: innermost frame outside the JDK and libraries, else the innermost frame.
            site = frames.label(stackBuffer[count - 1]);
            for (int i = count - 1; i >= 0; i--) {
                String owner = frames.owner(stackBuffer[i]);
                if (!owner.isEmpty() && !owner.equals("java") && !owner.equals("library")) {
                    site = frames.label(stackBuffer[i]);
                    mod = owner;
                    break;
                }
            }
        }
        long size = e.hasField("objectSize") ? e.getLong("objectSize") : 0;
        if (size <= 0 && e.hasField("arrayElements")) size = arrayBytes(type.getName(), e.getInt("arrayElements"));
        oldSamples.add(new OldSample(type.getName(), size, site, mod));
    }

    /**
     * Size of an array from its element count (Java 21's OldObjectSample has no objectSize): a
     * 16-byte header plus the elements, references at 4 bytes (compressed oops under 32 GB heaps).
     * 0 when the object is not an array.
     */
    static long arrayBytes(String className, int elements) {
        if (elements < 0 || className.length() < 2 || className.charAt(0) != '[') return 0;
        int each = switch (className.charAt(1)) {
            case 'Z', 'B' -> 1;
            case 'C', 'S' -> 2;
            case 'J', 'D' -> 8;
            default -> 4;
        };
        return 16 + (long) elements * each;
    }

    /** The running code's frame at a CPU flame leaf (innermost frame outside the JDK and libraries), or -1. */
    static int ownFrame(FlameTree tree, FrameTable frames, int leaf) {
        for (int x = leaf; x > 0 && tree.parent(x) > 0; x = tree.parent(x)) {
            String owner = frames.owner(tree.frame(x));
            if (!owner.isEmpty() && !owner.equals("java") && !owner.equals("library")) return tree.frame(x);
        }
        return -1;
    }

    /** Owner of the running code at a CPU flame leaf: the innermost frame outside the JDK and libraries. */
    static String ownCode(FlameTree tree, FrameTable frames, int leaf) {
        List<String> owners = new ArrayList<>();
        for (int x = leaf; x > 0 && tree.parent(x) > 0; x = tree.parent(x)) owners.add(frames.owner(tree.frame(x)));
        return chargeAllocation(owners.toArray(new String[0]))[0];
    }

    private int allocModIndex(String name) {
        Integer index = allocModIds.get(name);
        if (index == null) {
            index = allocModNames.size();
            allocModNames.add(name);
            allocModIds.put(name, index);
        }
        return index;
    }

    private void allocation(RecordedEvent e, long time) {
        RecordedClass type = e.getClass("objectClass");
        long weight = e.getLong("weight");
        // The weight is everything the thread allocated since its previous sample. After a long gap
        // one sample can carry gigabytes and pin it all on whatever stack happened to be sampled
        // (seen: 15.9 GB on one PoseStack.<init>), so cap it; capped samples are counted.
        if (weight > MAX_ALLOC_WEIGHT) {
            allocCappedSamples++;
            allocCappedBytes += weight - MAX_ALLOC_WEIGHT;
            weight = MAX_ALLOC_WEIGHT;
        }
        allocationWeight += weight;
        allocPerSecond.merge(Math.floorDiv(time, 1_000_000_000L), weight, Long::sum);
        String typeName = type == null ? "(unknown)" : type.getName();
        Alloc byClass = allocByClass.computeIfAbsent(typeName, k -> new Alloc());
        byClass.samples++;
        byClass.weight += weight;
        RecordedStackTrace stack = e.getStackTrace();
        int count = frames(stack);
        if (count < 0) return;
        alloc.add(frames.special("thread: " + group(threadName(e.getThread("eventThread")))), stackBuffer, count, weight);
        String[] owners = new String[count];
        for (int i = 0; i < count; i++) owners[i] = frames.owner(stackBuffer[count - 1 - i]);
        String[] charged = chargeAllocation(owners);
        String modName = charged[0];
        Alloc byMod = allocByMod.computeIfAbsent(modName, k -> new Alloc());
        byMod.samples++;
        byMod.weight += weight;
        int stackIndex = -1;
        if (charged[1] != null) {
            Alloc onStack = allocOnStack.computeIfAbsent(charged[1], k -> new Alloc());
            onStack.samples++;
            onStack.weight += weight;
            stackIndex = allocModIndex(charged[1]);
        }
        int modIndex = allocModIndex(modName);
        if (allocCount == allocTime.length) {
            allocTime = java.util.Arrays.copyOf(allocTime, allocCount * 2);
            allocMod = java.util.Arrays.copyOf(allocMod, allocCount * 2);
            allocStackMod = java.util.Arrays.copyOf(allocStackMod, allocCount * 2);
            allocWeightAt = java.util.Arrays.copyOf(allocWeightAt, allocCount * 2);
        }
        allocTime[allocCount] = time;
        allocMod[allocCount] = modIndex;
        allocStackMod[allocCount] = stackIndex;
        allocWeightAt[allocCount] = weight;
        allocCount++;
        // The allocation site is the innermost frame outside the JDK and generic libraries.
        String site = null;
        for (RecordedFrame frame : stack.getFrames()) {
            RecordedMethod m = frame.getMethod();
            if (m == null) continue;
            String owner = frames.owner(frames.method(m.getType().getName(), m.getName()));
            if (!owner.equals("java") && !owner.equals("library")) {
                site = m.getType().getName() + "." + m.getName() + ":" + frame.getLineNumber() + " → " + typeName;
                break;
            }
        }
        if (site != null) {
            Alloc bySite = allocBySite.computeIfAbsent(site, k -> new Alloc());
            bySite.samples++;
            bySite.weight += weight;
        }
    }

    private void block(RecordedEvent e, long time, String kind, RecordedClass on) {
        RecordedThread thread = e.getThread();
        String name = threadName(thread);
        long duration = e.getDuration().toNanos();
        String detail = on == null ? "" : on.getName();
        // The server waiting for its next tick is idle, not blocked. Only waitUntilNextTick counts:
        // waits for chunk loads go through the same waitForTasks/managedBlock methods and are real
        // stalls. Matched by name anywhere on the stack because mods (e.g. ModernFix) wrap the wait.
        if (isIdleWait(e.getStackTrace())) kind = "idle wait";
        // The frame limiter sleeping under the FPS cap (or in a menu) is by design, not contention;
        // its share shows under frame pacing. Matched anywhere on the stack: mods wrap the limiter.
        else if (inFrameLimiter(e.getStackTrace())) kind = FRAME_LIMITER;
        // Waits on the render and server threads only: idle worker pools park all the time.
        boolean idle = kind.equals("idle wait") || kind.equals(FRAME_LIMITER);
        if (!idle && e.getStackTrace() != null && thread != null && gameThreads.contains(thread.getJavaThreadId())) {
            List<String> owners = new ArrayList<>();
            for (RecordedFrame frame : e.getStackTrace().getFrames()) {
                RecordedMethod m = frame.getMethod();
                if (m != null) owners.add(frames.owner(frames.method(m.getType().getName(), m.getName())));
            }
            waitByMod.merge(chargeAllocation(owners.toArray(new String[0]))[0], duration, Long::sum);
        }
        long[] agg = blockingByGroup.computeIfAbsent(group(name) + "|" + kind, k -> new long[2]);
        agg[0]++;
        agg[1] += duration;
        if (!idle && thread != null && gameThreads.contains(thread.getJavaThreadId()) && blocking.size() < MAX_LISTED) {
            StringBuilder stack = new StringBuilder();
            RecordedStackTrace trace = e.getStackTrace();
            if (trace != null) {
                int shown = 0;
                for (RecordedFrame frame : trace.getFrames()) {
                    RecordedMethod m = frame.getMethod();
                    if (m == null) continue;
                    stack.append(m.getType().getName()).append('.').append(m.getName()).append(':').append(frame.getLineNumber()).append('\n');
                    if (++shown == 14) break;
                }
            }
            blocking.add(new Pause(time, duration, name, kind, detail, stack.toString()));
        }
    }

    private static final java.util.regex.Pattern IDLE_METHOD = java.util.regex.Pattern.compile(
            "waitUntilNextTick");

    static final String FRAME_LIMITER = "frame limiter";

    private static boolean inFrameLimiter(RecordedStackTrace trace) {
        if (trace == null) return false;
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod m = frame.getMethod();
            if (m != null && m.getName().equals("limitDisplayFPS")) return true;
        }
        return false;
    }

    private static boolean isIdleWait(RecordedStackTrace trace) {
        if (trace == null) return false;
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod m = frame.getMethod();
            if (m != null && IDLE_METHOD.matcher(m.getName()).find()) return true;
        }
        return false;
    }
}
