package dev.lightning.capture;

import com.google.gson.JsonObject;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import dev.lightning.export.Exporter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts, stops and saves captures. All slow work (JFR setup, file I/O, analysis) runs on the
 * recorder's own thread, never on the game threads.
 */
public final class Recorder {
    private static final Logger LOG = LoggerFactory.getLogger("Lightning");

    public enum State { IDLE, STARTING, RECORDING, SAVING }

    public interface Listener {
        void started(String message);

        void saved(Exporter.Result result);

        void failed(String message);
    }

    private final Path root;
    private final Supplier<JsonObject> environment;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Lightning recorder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Thread workerThread;

    private volatile State state = State.IDLE;
    private Capture capture;
    private CaptureOptions options;
    private Listener listener;
    private WriterThread writer;
    private JfrSession jfr;
    private String jfrError;
    private MetricsSampler metrics;
    private ScheduledFuture<?> metricsTask, timeoutTask, windowsTask;
    private Path tmp;
    private Map<Long, Long> jfrThreadCpuAtStart = Map.of();
    private volatile Exporter.Result latest;

    public Recorder(Path root, Supplier<JsonObject> environment) {
        this.root = root;
        this.environment = environment;
        try {
            this.workerThread = worker.submit(Thread::currentThread).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Marks the start of a named phase in the running capture. Returns false when not recording. */
    public synchronized boolean mark(String label) {
        Capture c = capture;
        if (state == State.STARTING) {
            // Recording is still being set up: keep the mark and its time for the capture.
            pendingMarks.add(new Capture.Mark(System.nanoTime(), label));
            return true;
        }
        if (state != State.RECORDING || c == null) return false;
        c.mark(label);
        return true;
    }

    private final java.util.List<Capture.Mark> pendingMarks = new java.util.ArrayList<>();
    private Path outputDir;
    private boolean exportViewer = true;
    private String label = "";

    /**
     * Takes a snapshot of what is filling memory, by mod, on the recorder thread. Writes a text
     * report next to the captures and passes its first line and path to {@code done}.
     */
    public void heapSnapshot(java.util.function.BiConsumer<String, Path> done, java.util.function.Consumer<String> failed) {
        worker.execute(() -> {
            try {
                dev.lightning.analysis.HeapHistogram h = dev.lightning.analysis.HeapHistogram.take(dev.lightning.analysis.ModIndex.fromLoadedMods());
                Files.createDirectories(capturesDirectory());
                Path out = capturesDirectory().resolve("memory-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
                Files.writeString(out, h.text());
                StringBuilder top = new StringBuilder();
                int n = 0;
                for (var e : h.owners()) {
                    if (n++ == 4) break;
                    top.append(n > 1 ? ", " : "").append(e.getKey()).append(' ').append(e.getValue()[0] >> 20).append(" MB");
                }
                done.accept(String.format(java.util.Locale.ROOT, "%d MB in use: %s", h.totalBytes >> 20, top), out);
            } catch (Exception | Error e) {
                LOG.warn("Memory snapshot failed", e);
                failed.accept(e.toString());
            }
        });
    }

    /**
     * Full memory breakdown: writes a heap dump (pauses the game for a few seconds), then analyses
     * it in a separate Java process so the game's own memory is never used for the analysis.
     * The dump is deleted afterwards unless {@code keepDump}.
     */
    public void heapDeep(boolean keepDump, java.util.function.Consumer<String> progress,
                         java.util.function.BiConsumer<String, Path> done, java.util.function.Consumer<String> failed) {
        worker.execute(() -> {
            try {
                Path report = deepAnalysis(keepDump, progress);
                done.accept(firstOwners(Files.readString(report)), report);
            } catch (Exception | Error e) {
                LOG.warn("Deep memory analysis failed", e);
                failed.accept(e.getMessage());
            }
        });
    }

    /** Dump + out-of-process analysis; returns the report file. Runs on the calling thread. */
    private Path deepAnalysis(boolean keepDump, java.util.function.Consumer<String> progress) throws Exception {
        {
            Path dump = null;
            try {
                Path dir = root.resolve("memory");
                Files.createDirectories(dir);
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                dump = dir.resolve("heap-" + stamp + ".hprof");
                long t0 = System.nanoTime();
                java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class)
                        .dumpHeap(dump.toString(), true);
                long size = Files.size(dump);
                progress.accept(String.format(java.util.Locale.ROOT, "Memory dump written (%d MB, game paused %.1f s). Analysing in the background…",
                        size >> 20, (System.nanoTime() - t0) / 1e9));
                Path modMap = dir.resolve("modmap-" + stamp + ".txt");
                dev.lightning.heap.ModMap.save(dev.lightning.analysis.ModIndex.fromLoadedMods(), modMap);
                Path report = dir.resolve("memory-owners-" + stamp + ".txt");
                Path log = dir.resolve("analysis-" + stamp + ".log");
                Process process = new ProcessBuilder(analyzerCommand(size, dump, report, modMap))
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                int exit = process.waitFor();
                Files.deleteIfExists(modMap);
                if (exit != 0 || !Files.exists(report)) throw new java.io.IOException("analysis failed (exit " + exit + "), see " + log);
                Files.deleteIfExists(log);
                if (!keepDump) Files.deleteIfExists(dump);
                return report;
            } catch (Exception | Error e) {
                if (dump != null && Files.exists(dump)) throw new java.io.IOException(e.getMessage() + " (dump kept at " + dump + ")", e);
                throw e;
            }
        }
    }

    /** java -Xmx… -cp <this mod> dev.lightning.heap.HeapAnalyzer, sized from the dump and free memory. */
    private static java.util.List<String> analyzerCommand(long dumpBytes, Path dump, Path report, Path modMap) {
        String exe = ProcessHandle.current().info().command().orElse("java");
        if (exe.endsWith("javaw.exe")) exe = exe.substring(0, exe.length() - "javaw.exe".length()) + "java.exe";
        long free = Long.MAX_VALUE;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            free = os.getFreeMemorySize();
        }
        long want = Math.max(2L << 30, (long) (dumpBytes * 1.6));
        long heap = Math.max(1L << 30, Math.min(want, free - (1L << 30)));
        String classpath;
        try {
            classpath = net.neoforged.fml.ModList.get().getModFileById("lightning").getFile().getFilePath().toString();
        } catch (RuntimeException | LinkageError e) {
            classpath = System.getProperty("java.class.path");
        }
        return java.util.List.of(exe, "-Xmx" + (heap >> 20) + "m", "-XX:+UseParallelGC", "-cp", classpath,
                "dev.lightning.heap.HeapAnalyzer", dump.toString(), report.toString(), modMap.toString());
    }

    /** "voxy 1100 MB, minecraft 900 MB, …" from the report's owner table. */
    private static String firstOwners(String report) {
        StringBuilder b = new StringBuilder();
        boolean table = false;
        int shown = 0;
        for (String line : report.split("\\R")) {
            if (line.startsWith("## Memory by owner")) {
                table = true;
                continue;
            }
            if (!table || line.startsWith("owner")) continue;
            if (line.isBlank() || line.startsWith("##")) break;
            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length < 2) continue;
            String[] cols = line.trim().split("\\s+");
            String mb = "";
            for (int i = 0; i < cols.length; i++) if (cols[i].matches("[0-9.]+") && i + 1 < cols.length && cols[i + 1].endsWith("%")) mb = cols[i];
            b.append(shown > 0 ? ", " : "").append(parts[0]).append(' ').append(mb.isEmpty() ? "" : Math.round(Double.parseDouble(mb)) + " MB");
            if (++shown == 5) break;
        }
        return b.toString();
    }

    /** Turns captures left unfinished by a crash into recovered reports, on the recorder thread. */
    public void recoverPending(java.util.function.Consumer<Exporter.Result> done) {
        worker.execute(() -> {
            for (Path dir : Recovery.pending(root)) {
                // The capture running right now (a start-up capture) is not a leftover.
                if (state != State.IDLE && dir.equals(tmp)) continue;
                try {
                    Exporter.Result result = Recovery.recover(dir, capturesDirectory());
                    LOG.info("Recovered unfinished capture to {}", result.archive());
                    done.accept(result);
                } catch (Exception | Error e) {
                    LOG.warn("Could not recover the unfinished capture in " + dir + "; it is left in place", e);
                }
            }
        });
    }

    public State state() {
        return state;
    }

    public Exporter.Result latest() {
        return latest;
    }

    public Path capturesDirectory() {
        return root.resolve("captures");
    }

    public String status() {
        Capture c = capture;
        return switch (state) {
            case IDLE -> "Not recording.";
            case STARTING -> "Starting…";
            case SAVING -> "Saving the last capture…";
            case RECORDING -> {
                double seconds = (System.nanoTime() - c.startNanos) / 1e9;
                yield String.format(java.util.Locale.ROOT, "Recording for %.0f s%s.", seconds,
                        options.timeoutSeconds() > 0 ? " of " + options.timeoutSeconds() : "");
            }
        };
    }

    public synchronized boolean start(CaptureOptions options, Listener listener) {
        return start(options, listener, null, true, "");
    }

    /**
     * Starts a capture. {@code outputDir} null = the normal captures folder; {@code exportViewer}
     * false skips the HTML report (the archive still holds the data).
     */
    public synchronized boolean start(CaptureOptions options, Listener listener, Path outputDir, boolean exportViewer, String label) {
        return start(options, listener, outputDir, exportViewer, label, 0);
    }

    /**
     * A loading capture (start-up or world join): {@code startAtNanos} is when its timeline begins,
     * which for start-up is the JVM's start, before Lightning existed. The time before Lightning
     * loaded comes from a JVM-started JFR recording and the game's log files.
     */
    public synchronized boolean startLoading(CaptureOptions options, Listener listener, long startAtNanos, String label) {
        return start(options, listener, Automation.outputDir(), Automation.html(), label, startAtNanos);
    }

    private long startAtNanos;
    private WallSampler wall;

    private synchronized boolean start(CaptureOptions options, Listener listener, Path outputDir, boolean exportViewer, String label, long startAt) {
        if (state != State.IDLE) return false;
        this.startAtNanos = startAt;
        state = State.STARTING;
        this.outputDir = outputDir;
        this.exportViewer = exportViewer;
        this.label = label == null ? "" : label;
        pendingMarks.clear();
        this.options = options;
        this.listener = listener;
        worker.execute(this::begin);
        return true;
    }

    /**
     * The game is closing: a capture still recording is stopped and saved now (blocking up to
     * {@code timeoutMillis}), instead of being left half-written for crash recovery. Scripted runs
     * that quit the game while recording get a complete report this way.
     */
    public void saveBeforeExit(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (state == State.STARTING && System.currentTimeMillis() < deadline) sleepQuietly(20);
        if (state == State.RECORDING) {
            LOG.info("Lightning: the game is closing while recording; saving the capture first");
            stop(false);
        }
        while (state == State.SAVING && System.currentTimeMillis() < deadline) sleepQuietly(50);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized boolean stop(boolean cancel) {
        if (state != State.RECORDING) return false;
        state = State.SAVING;
        worker.execute(() -> finish(cancel));
        return true;
    }

    /** Milliseconds spent in each start-up step of the last capture, for the report. */
    private final java.util.LinkedHashMap<String, Long> startSteps = new java.util.LinkedHashMap<>();
    private long stepAt;

    private void step(String name) {
        long now = System.nanoTime();
        startSteps.put(name, (now - stepAt) / 1_000_000);
        stepAt = now;
    }

    public java.util.Map<String, Long> startSteps() {
        return startSteps;
    }

    private void begin() {
        startSteps.clear();
        stepAt = System.nanoTime();
        try {
            String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            tmp = root.resolve("tmp").resolve(id);
            Files.createDirectories(tmp);
            step("directories");
            jfr = null;
            jfrError = null;
            if (options.jfr()) {
                try {
                    jfr = JfrSession.start(tmp.resolve("recording.jfr"), options);
                    step("jfr");
                    jfrThreadCpuAtStart = jfrThreadCpu();
                    step("jfrThreadCpu");
                } catch (Exception | LinkageError e) {
                    jfrError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    LOG.warn("JFR unavailable; continuing without JVM sampling", e);
                }
            }
            Capture c = new Capture(id, options, startAtNanos);
            c.label = label;
            c.eventCostNanos = calibrateEventCost();
            c.marksFile = tmp.resolve("marks.txt");
            writeCaptureInfo(c);
            synchronized (this) {
                for (Capture.Mark m : pendingMarks) c.markAt(Math.max(m.nanos(), c.startNanos), m.label());
                pendingMarks.clear();
            }
            if (options.loading()) {
                // Loading phases that already happened belong to this capture too.
                for (LoadTimeline.Milestone m : LoadTimeline.milestones()) {
                    if (m.phase() && m.nanos() >= c.startNanos) c.markAt(m.nanos(), m.name());
                }
            }
            writer = new WriterThread(c, tmp.resolve("events.bin"));
            writer.start();
            step("writer");
            metrics = new MetricsSampler();
            metrics.trackOwnThread(workerThread);
            metrics.trackOwnThread(writer);
            wall = null;
            if (options.loading()) {
                wall = new WallSampler();
                wall.start();
                metrics.trackOwnThread(wall.thread());
            }
            LogCapture logs = LogCapture.install();
            if (logs != null) logs.begin(c);
            step("metricsAndLogs");
            capture = c;
            Capture.ACTIVE = c;
            metrics.sample();
            step("firstSample");
            LOG.info("Lightning capture {} started: {}", id, startSteps);
            metricsTask = worker.scheduleAtFixedRate(metrics::sample, 1, 1, TimeUnit.SECONDS);
            windowsTask = null;
            int window = Automation.windowSeconds();
            if (window > 0) {
                // Fixed-length windows as phases ("window 01 (0-30 s)"), for comparing parts of a scripted run.
                int[] n = {0};
                windowsTask = worker.scheduleAtFixedRate(() -> {
                    int i = n[0]++;
                    c.mark(String.format(java.util.Locale.ROOT, "window %02d (%d-%d s)", i + 1, i * window, (i + 1) * window));
                }, 0, window, TimeUnit.SECONDS);
            }
            if (options.timeoutSeconds() > 0) {
                timeoutTask = worker.schedule(() -> stop(false), options.timeoutSeconds(), TimeUnit.SECONDS);
            } else {
                timeoutTask = worker.schedule(() -> stop(false), CaptureOptions.MAX_SECONDS, TimeUnit.SECONDS);
            }
            state = State.RECORDING;
            String limit = options.timeoutSeconds() > 0 ? " for " + options.timeoutSeconds() + " s" : "";
            listener.started("⚡ Recording" + limit + ": " + options.describe() + (jfrError != null ? " (Java code sampling unavailable)" : "")
                    + ". Stop with /lightning profiler stop.");
        } catch (Exception | Error e) {
            LOG.error("Could not start capture", e);
            abort();
            listener.failed("⚡ Could not start: " + e.getMessage());
        }
    }

    private void finish(boolean cancel) {
        Capture c = capture;
        try {
            Capture.ACTIVE = null;
            c.stopNanos = System.nanoTime();
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (metricsTask != null) metricsTask.cancel(false);
            if (windowsTask != null) windowsTask.cancel(false);
            if (wall != null) wall.stop();
            metrics.sample();
            // Let threads that were inside a hook leave it before their buffers are collected.
            Thread.sleep(100);
            for (Track t : c.tracks()) t.flushFinal();
            writer.finish();
            LogCapture logs = LogCapture.install();
            LogCapture.Snapshot logSnapshot = logs == null ? null : logs.snapshot();
            if (logs != null) logs.end();
            Map<String, Long> jfrCpu = new HashMap<>();
            if (jfr != null) {
                long jfrCpuNanos = jfrThreadCpuDelta();
                jfrCpu.put("jfrThreads", jfrCpuNanos);
                if (cancel) jfr.cancel();
                else jfr.stop();
            }
            if (cancel) {
                deleteTree(tmp);
                capture = null;
                state = State.IDLE;
                listener.failed("⚡ Capture cancelled.");
                return;
            }
            dev.lightning.analysis.HeapHistogram heap = null;
            if (options.heap()) {
                try {
                    heap = dev.lightning.analysis.HeapHistogram.take(dev.lightning.analysis.ModIndex.fromLoadedMods());
                } catch (Exception | Error e) {
                    LOG.warn("Memory snapshot failed", e);
                }
            }
            String deep = null;
            if (options.deepMemory()) {
                try {
                    listener.started("⚡ Recording done. Now checking what holds memory — the game will freeze for a few seconds…");
                    Path report = deepAnalysis(false, listener::started);
                    deep = Files.readString(report);
                } catch (Exception | Error e) {
                    LOG.warn("Deep memory analysis failed", e);
                    deep = "The memory breakdown failed: " + e.getMessage();
                }
            }
            LoadSnapshot load = options.loading() ? loadSnapshot(c, logs) : null;
            if (logs != null && load != null) logSnapshot = logs.snapshot();
            CaptureData data = new CaptureData(c, tmp.resolve("events.bin"), jfr == null ? null : jfr.file,
                    jfr == null ? 0 : jfr.setupNanos, jfrError, metrics.samples(), logSnapshot, environment.get(),
                    writer.bytesWritten(), jfrCpu.getOrDefault("jfrThreads", -1L), heap, deep, load);
            Analyzer.Result analysis = Analyzer.analyze(data);
            Exporter.Result result = Exporter.export(analysis, outputDir != null ? outputDir : capturesDirectory(), exportViewer);
            latest = result;
            deleteTree(tmp);
            capture = null;
            state = State.IDLE;
            listener.saved(result);
        } catch (Exception | Error e) {
            LOG.error("Could not save capture " + (c == null ? "" : c.id) + "; raw data kept in " + tmp, e);
            capture = null;
            state = State.IDLE;
            listener.failed("⚡ Could not save the capture: " + e.getMessage());
        }
    }

    /**
     * Everything a loading capture adds: the loading steps (including those read back from the
     * game's logs, from before Lightning loaded), reload listener times, wall-clock samples and
     * which mods' mixins changed which classes.
     */
    private LoadSnapshot loadSnapshot(Capture c, LogCapture logs) {
        java.util.List<LoadTimeline.Milestone> steps = new java.util.ArrayList<>(LoadTimeline.milestones());
        java.util.List<EarlyLog.Line> early = java.util.List.of();
        if (c.startNanos < LoadTimeline.LIGHTNING_LOADED_NANOS) {
            early = EarlyLog.read(root.getParent().resolve("logs"), c.stopNanos);
            java.util.Set<String> names = new java.util.HashSet<>();
            for (LoadTimeline.Milestone m : steps) names.add(m.name());
            java.util.List<LoadTimeline.Milestone> fromLogs = new java.util.ArrayList<>(EarlyLog.steps(early));
            // Mod construction is logged at debug level only; without it, Lightning's own
            // construction (one of the mods being built) marks the step.
            if (fromLogs.stream().noneMatch(m -> m.name().equals("Building mods"))) {
                fromLogs.add(new LoadTimeline.Milestone(LoadTimeline.LIGHTNING_LOADED_NANOS, "Building mods", true));
            }
            for (LoadTimeline.Milestone m : fromLogs) {
                if (m.nanos() < c.startNanos || !names.add(m.name())) continue;
                steps.add(m);
                if (m.phase()) c.markAt(m.nanos(), m.name());
            }
            steps.sort(java.util.Comparator.comparingLong(LoadTimeline.Milestone::nanos));
            // Warnings and errors from before Lightning was listening.
            if (logs != null) {
                for (EarlyLog.Line line : early) {
                    if (line.nanos() < LoadTimeline.LIGHTNING_LOADED_NANOS && (line.level().equals("WARN") || line.level().equals("ERROR") || line.level().equals("FATAL"))) {
                        logs.addEarly(line.level(), line.logger(), line.thread(), line.message(), line.nanos());
                    }
                }
            }
        }
        MixinScan mixins = MixinScan.collect(dev.lightning.analysis.ModIndex.fromLoadedMods()::ownerOfClass);
        java.util.List<LoadTimeline.Reload> reloads = new java.util.ArrayList<>();
        for (LoadTimeline.Reload r : LoadTimeline.reloads()) if (r.endNanos() >= c.startNanos && r.startNanos() <= c.stopNanos) reloads.add(r);
        return new LoadSnapshot(steps, reloads, wall, mixins, jfr != null && jfr.coversStartup(), jfr == null ? 0 : jfr.startupFromNanos,
                LoadTimeline.LIGHTNING_LOADED_NANOS, early.size());
    }

    private void abort() {
        Capture.ACTIVE = null;
        if (wall != null) wall.stop();
        if (jfr != null) jfr.cancel();
        if (writer != null) {
            try {
                writer.finish();
            } catch (Exception ignored) {
            }
        }
        capture = null;
        state = State.IDLE;
    }

    /** Cost of one recorded event (two clock reads and two stores), measured on this machine. */
    private static double calibrateEventCost() {
        long[] buf = new long[8192];
        long sink = 0;
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                int p = (i * 2) & 8190;
                buf[p] = System.nanoTime();
                buf[p + 1] = i;
            }
            best = Math.min(best, System.nanoTime() - t0);
            sink += buf[2];
        }
        return sink == 42 ? 0 : best / 100_000.0;
    }

    /** Header of the running capture on disk, so it can be rebuilt after a crash. */
    private void writeCaptureInfo(Capture c) throws IOException {
        JsonObject info = new JsonObject();
        info.addProperty("id", c.id);
        info.addProperty("label", c.label);
        info.addProperty("startNanos", c.startNanos);
        info.addProperty("startEpochMillis", c.startEpochMillis);
        info.addProperty("epochOffsetNanos", c.epochOffsetNanos);
        info.addProperty("options", CaptureOptions.toArgs(c.options));
        info.addProperty("outputDir", outputDir == null ? "" : outputDir.toString());
        try {
            info.add("environment", environment.get());
        } catch (RuntimeException e) {
            info.add("environment", new JsonObject());
        }
        Files.writeString(tmp.resolve("capture.json"), info.toString());
    }

    /** CPU time of JFR's own threads; reported as part of Lightning's overhead. */
    private static Map<Long, Long> jfrThreadCpu() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Map<Long, Long> out = new HashMap<>();
        if (!bean.isThreadCpuTimeSupported()) return out;
        // Depth 0: names only, no stack walk and no safepoint.
        for (java.lang.management.ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds(), 0)) {
            if (info != null && info.getThreadName().startsWith("JFR")) {
                out.put(info.getThreadId(), Math.max(0, bean.getThreadCpuTime(info.getThreadId())));
            }
        }
        return out;
    }

    private long jfrThreadCpuDelta() {
        long total = 0;
        for (Map.Entry<Long, Long> e : jfrThreadCpu().entrySet()) {
            total += e.getValue() - jfrThreadCpuAtStart.getOrDefault(e.getKey(), 0L);
        }
        return total;
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public void shutdown() {
        if (state == State.RECORDING) Capture.ACTIVE = null;
        worker.shutdownNow();
    }
}
