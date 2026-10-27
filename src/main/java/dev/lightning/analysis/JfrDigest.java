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
        int size;

        void add(long t, int node, boolean isNative) {
            if (size == time.length) {
                time = java.util.Arrays.copyOf(time, size * 2);
                leaf = java.util.Arrays.copyOf(leaf, size * 2);
                nativeSample = java.util.Arrays.copyOf(nativeSample, size * 2);
            }
            time[size] = t;
            leaf[size] = node;
            nativeSample[size] = isNative;
            size++;
        }

        void sort() {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Long.compare(time[a], time[b]));
            long[] t = new long[size];
            int[] l = new int[size];
            boolean[] n = new boolean[size];
            for (int i = 0; i < size; i++) {
                t[i] = time[order[i]];
                l[i] = leaf[order[i]];
                n[i] = nativeSample[order[i]];
            }
            time = t;
            leaf = l;
            nativeSample = n;
        }
    }

    public record Gc(long time, long duration, String name, String cause, long sumOfPauses, long longestPause) {}

    public record Pause(long time, long duration, String thread, String kind, String detail, String stack) {}

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
    final Map<String, Alloc> allocByMod = new HashMap<>();
    /** Second (nanoTime / 1e9) → sampled allocation bytes. */
    final java.util.TreeMap<Long, Long> allocPerSecond = new java.util.TreeMap<>();

    /** Samples of one thread pool (threads grouped by name with numbers removed). */
    public static final class PoolStats {
        public long samples;
        public final Map<String, Long> owners = new HashMap<>();
        public final java.util.TreeMap<Long, Long> perSecond = new java.util.TreeMap<>();
    }

    final Map<String, PoolStats> pools = new HashMap<>();

    /** One per-thread CPU reading: time, thread pool, cores used by that thread. */
    public record ThreadCpuPoint(long time, String group, double cores) {}

    final List<ThreadCpuPoint> threadCpuPoints = new ArrayList<>();
    /** Every kept CPU sample (all threads): time and flame leaf. */
    final Samples allSamples = new Samples();
    final List<String> allocModNames = new ArrayList<>();
    private final Map<String, Integer> allocModIds = new HashMap<>();
    long[] allocTime = new long[1024];
    int[] allocMod = new int[1024];
    long[] allocWeightAt = new long[1024];
    int allocCount;
    final List<Gc> gcs = new ArrayList<>();
    final List<Pause> allocationStalls = new ArrayList<>();
    final List<Pause> vmOperations = new ArrayList<>();
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

    private JfrDigest(long epochOffset, ModIndex mods, Set<Long> gameThreads) {
        this.epochOffset = epochOffset;
        this.frames = new FrameTable(mods);
        this.gameThreads = gameThreads;
    }

    public static JfrDigest read(Path file, long epochOffset, ModIndex mods, Set<Long> gameThreads) throws IOException {
        JfrDigest d = new JfrDigest(epochOffset, mods, gameThreads);
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) d.accept(recording.readEvent());
        }
        for (Samples s : d.samplesByThread.values()) s.sort();
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
                    e.getString("cause"), e.getDuration("sumOfPauses").toNanos(), e.getDuration("longestPause").toNanos()));
            case "jdk.ZAllocationStall" -> {
                if (allocationStalls.size() < MAX_LISTED) {
                    allocationStalls.add(new Pause(time, e.getDuration().toNanos(), threadName(e.getThread()), "allocation stall",
                            e.hasField("type") ? String.valueOf((Object) e.getValue("type")) : "", ""));
                }
            }
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
        allSamples.add(time, leaf, isNative);
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

    private void allocation(RecordedEvent e, long time) {
        RecordedClass type = e.getClass("objectClass");
        long weight = e.getLong("weight");
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
        // Charge the allocation to the innermost add-on mod on the stack; otherwise to the innermost
        // non-JDK owner (usually Minecraft itself).
        String mod = null, fallback = null;
        for (int i = count - 1; i >= 0; i--) {
            String owner = frames.owner(stackBuffer[i]);
            if (mod == null && !isBaseOwner(owner)) mod = owner;
            if (fallback == null && !owner.equals("java") && !owner.equals("library") && !owner.isEmpty()) fallback = owner;
        }
        Alloc byMod = allocByMod.computeIfAbsent(mod != null ? mod : fallback != null ? fallback : "java", k -> new Alloc());
        byMod.samples++;
        byMod.weight += weight;
        String modName = mod != null ? mod : fallback != null ? fallback : "java";
        Integer modIndex = allocModIds.get(modName);
        if (modIndex == null) {
            modIndex = allocModNames.size();
            allocModNames.add(modName);
            allocModIds.put(modName, modIndex);
        }
        if (allocCount == allocTime.length) {
            allocTime = java.util.Arrays.copyOf(allocTime, allocCount * 2);
            allocMod = java.util.Arrays.copyOf(allocMod, allocCount * 2);
            allocWeightAt = java.util.Arrays.copyOf(allocWeightAt, allocCount * 2);
        }
        allocTime[allocCount] = time;
        allocMod[allocCount] = modIndex;
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
        long[] agg = blockingByGroup.computeIfAbsent(group(name) + "|" + kind, k -> new long[2]);
        agg[0]++;
        agg[1] += duration;
        if (!kind.equals("idle wait") && thread != null && gameThreads.contains(thread.getJavaThreadId()) && blocking.size() < MAX_LISTED) {
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

    private static boolean isIdleWait(RecordedStackTrace trace) {
        if (trace == null) return false;
        for (RecordedFrame frame : trace.getFrames()) {
            RecordedMethod m = frame.getMethod();
            if (m != null && IDLE_METHOD.matcher(m.getName()).find()) return true;
        }
        return false;
    }
}
