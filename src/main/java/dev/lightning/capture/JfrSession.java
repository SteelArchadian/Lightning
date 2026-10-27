package dev.lightning.capture;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;

/**
 * The JVM-level half of a capture: statistical stack samples, allocation samples, GC and
 * safepoint activity, and lock/park blocking. Every JDK event is switched off first and only
 * the listed ones are switched back on, so nothing costly is enabled by accident (for example
 * file/socket I/O events, which make the JDK retransform core classes when first enabled).
 */
final class JfrSession {
    private final Recording recording;
    Path file;
    final long setupNanos;
    /** A recording started with the JVM (-XX:StartFlightRecording), used for the time before Lightning loaded. */
    private final Recording startup;
    /** When the JVM-started recording begins (nanoTime), or 0 when there is none. */
    long startupFromNanos;

    private JfrSession(Recording recording, Path file, long setupNanos, Recording startup) {
        this.recording = recording;
        this.file = file;
        this.setupNanos = setupNanos;
        this.startup = startup;
    }

    /**
     * The recording started with the JVM for Lightning ({@code -XX:StartFlightRecording=name=lightning,...}),
     * or null. Does not start JFR itself: without that flag JFR is not initialised yet and this is cheap.
     */
    static Recording startupRecording() {
        try {
            if (!FlightRecorder.isInitialized()) return null;
            for (Recording r : FlightRecorder.getFlightRecorder().getRecordings()) {
                if (r.getState() == jdk.jfr.RecordingState.RUNNING && r.getName() != null && r.getName().equalsIgnoreCase("lightning")) return r;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // No JFR in this runtime.
        }
        return null;
    }

    /** Whether the time before Lightning loaded is covered by a JVM-started recording. */
    boolean coversStartup() {
        return startup != null;
    }

    static JfrSession start(Path file, CaptureOptions options) throws Exception {
        long t0 = System.nanoTime();
        if (!FlightRecorder.isAvailable()) throw new IllegalStateException("This Java runtime does not provide JFR");
        Recording recording = new Recording();
        try {
            recording.setName("Lightning");
            recording.setToDisk(true);
            recording.setDestination(file);
            recording.setMaxAge(Duration.ofSeconds(CaptureOptions.MAX_SECONDS + 60));
            recording.setMaxSize(512L << 20);
            recording.setSettings(settings(options));
            recording.start();
        } catch (Exception | Error e) {
            recording.close();
            throw e;
        }
        Recording startup = options.loading() ? startupRecording() : null;
        JfrSession session = new JfrSession(recording, file, System.nanoTime() - t0, startup);
        if (startup != null && startup.getStartTime() != null) {
            java.time.Instant s = startup.getStartTime();
            session.startupFromNanos = s.getEpochSecond() * 1_000_000_000L + s.getNano() - (System.currentTimeMillis() * 1_000_000L - System.nanoTime());
        }
        return session;
    }

    static Map<String, String> settings(CaptureOptions options) {
        Map<String, String> s = new HashMap<>();
        for (EventType type : FlightRecorder.getFlightRecorder().getEventTypes()) s.put(type.getName() + "#enabled", "false");
        String period = options.sampleIntervalMillis() + " ms";

        if (options.cpu()) {
            enable(s, "jdk.ExecutionSample", true);
            s.put("jdk.ExecutionSample#period", period);
        }
        if (options.cpu() && options.nativeSamples()) {
            enable(s, "jdk.NativeMethodSample", true);
            s.put("jdk.NativeMethodSample#period", period);
        }
        if (options.allocations()) {
            enable(s, "jdk.ObjectAllocationSample", true);
            s.put("jdk.ObjectAllocationSample#throttle", "150/s");
        }

        if (options.gc()) {
            // Memory management: collections, their pauses, and threads stalled waiting for memory.
            enable(s, "jdk.GarbageCollection", false);
            s.put("jdk.GarbageCollection#threshold", "0 ms");
            enable(s, "jdk.GCPhasePause", false);
            s.put("jdk.GCPhasePause#threshold", "0 ms");
            enable(s, "jdk.ZAllocationStall", true);
            s.put("jdk.ZAllocationStall#threshold", "0 ms");
            enable(s, "jdk.GCHeapSummary", false);

            // Whole-JVM pauses that are not GC (deoptimisation, thread dumps, class redefinition).
            enable(s, "jdk.ExecuteVMOperation", false);
            s.put("jdk.ExecuteVMOperation#threshold", "1 ms");
            enable(s, "jdk.SafepointBegin", false);
            s.put("jdk.SafepointBegin#threshold", "1 ms");
        }

        if (options.cpu() || options.loading()) {
            // Blocking on locks and parks long enough to matter for a frame or tick.
            enable(s, "jdk.JavaMonitorEnter", true);
            s.put("jdk.JavaMonitorEnter#threshold", "5 ms");
            enable(s, "jdk.ThreadPark", true);
            s.put("jdk.ThreadPark#threshold", "10 ms");
        }
        if (options.threads() || options.cpu() || options.loading()) {
            // Per-thread CPU once a second. jdk.CPULoad is left off: on Windows it reads performance
            // counters, which is slow; process CPU is the sum of the per-thread values.
            for (String periodic : new String[] {"jdk.ThreadCPULoad", "jdk.ResidentSetSize", "jdk.JavaThreadStatistics"}) {
                enable(s, periodic, false);
                s.put(periodic + "#period", "1 s");
            }
        }

        if (options.io()) {
            // File and network reads/writes slow enough to matter (the JDK instruments these
            // classes when first enabled, a one-off cost when the capture starts).
            for (String e : new String[] {"jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite"}) {
                enable(s, e, true);
                s.put(e + "#threshold", "1 ms");
            }
        }

        if (options.loading()) {
            // Loading: how long each class took to load (including its transformation by mixins
            // and coremods) and to initialise.
            enable(s, "jdk.ClassLoad", false);
            s.put("jdk.ClassLoad#threshold", "0 ms");
            enable(s, "jdk.ClassDefine", false);
            enable(s, "jdk.Compilation", false);
            s.put("jdk.Compilation#threshold", "100 ms");
        }

        enable(s, "jdk.DataLoss", false);
        enable(s, "jdk.GCConfiguration", false);
        s.put("jdk.GCConfiguration#period", "beginChunk");
        enable(s, "jdk.CPUInformation", false);
        s.put("jdk.CPUInformation#period", "beginChunk");
        return s;
    }

    private static void enable(Map<String, String> s, String event, boolean stackTrace) {
        s.put(event + "#enabled", "true");
        s.put(event + "#stackTrace", Boolean.toString(stackTrace));
    }

    /**
     * Stops recording and writes the file. With a JVM-started recording, that recording is dumped
     * instead (recordings share their data, so the dump also holds Lightning's own events) and
     * then closed, since it only existed for Lightning.
     */
    void stop() {
        try {
            if (startup != null) {
                Path dump = file.resolveSibling("startup.jfr");
                try {
                    startup.dump(dump);
                    file = dump;
                } catch (java.io.IOException | RuntimeException e) {
                    // Fall back to Lightning's own recording, which starts later.
                }
            }
            recording.stop();
        } finally {
            recording.close();
            if (startup != null) startup.close();
        }
    }

    void cancel() {
        recording.close();
        if (startup != null) startup.close();
    }
}
