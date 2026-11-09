package dev.lightning.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPU used by other programs during a capture, from two snapshots of every process's total CPU
 * time (start and end). A machine shared with other heavy programs (another game, a build)
 * starves the game's threads: frames and safepoints get slow for reasons outside the game.
 * Processes whose CPU time can't be read (other users', protected) are left out.
 */
public final class OtherProcesses {
    /** One other program over the capture: name, how many ran under that name, cores used on average. */
    public record Row(String name, int count, double cores) {}

    /** Result: own process cores, other programs (most cores first), logical processors. */
    public record Usage(double ownCores, List<Row> others, int processors, double seconds) {
        public double otherCores() {
            double s = 0;
            for (Row r : others) s += r.cores();
            return s;
        }

        /** Other Java programs (often other games) using at least half a core each on average. */
        public int otherJava() {
            int n = 0;
            for (Row r : others) if (r.name().startsWith("java") && r.cores() >= 0.5 * r.count()) n += r.count();
            return n;
        }
    }

    private final long nanos = System.nanoTime();
    private final Map<Long, long[]> cpu = new HashMap<>(); // pid → {cpu nanos, start epoch millis}
    private final Map<Long, String> names = new HashMap<>();

    private OtherProcesses() {}

    /** Snapshot of every readable process's CPU time; never throws. */
    public static OtherProcesses snapshot() {
        OtherProcesses s = new OtherProcesses();
        try {
            ProcessHandle.allProcesses().forEach(p -> {
                try {
                    ProcessHandle.Info info = p.info();
                    var total = info.totalCpuDuration();
                    if (total.isEmpty()) return;
                    long started = info.startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
                    s.cpu.put(p.pid(), new long[] {total.get().toNanos(), started});
                    s.names.put(p.pid(), info.command().map(OtherProcesses::exeName).orElse("?"));
                } catch (RuntimeException e) {
                    // a process that ended or can't be read
                }
            });
        } catch (RuntimeException | LinkageError e) {
            // no process listing on this platform
        }
        return s;
    }

    static String exeName(String command) {
        String n = command.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }

    /** CPU between two snapshots; processes in both (same start time) or started in between. */
    public static Usage between(OtherProcesses start, OtherProcesses end) {
        double seconds = Math.max(1e-3, (end.nanos - start.nanos) / 1e9);
        long self = ProcessHandle.current().pid();
        double own = 0;
        Map<String, double[]> byName = new HashMap<>();
        for (var e : end.cpu.entrySet()) {
            long[] b = e.getValue();
            long[] a = start.cpu.get(e.getKey());
            long used = a != null && a[1] == b[1] ? b[0] - a[0] : b[1] >= start.startMillis() ? b[0] : -1;
            if (used <= 0) continue;
            double cores = used / 1e9 / seconds;
            if (e.getKey() == self) {
                own = cores;
                continue;
            }
            double[] agg = byName.computeIfAbsent(end.names.getOrDefault(e.getKey(), "?"), k -> new double[2]);
            agg[0]++;
            agg[1] += cores;
        }
        List<Row> rows = new ArrayList<>();
        byName.forEach((name, agg) -> rows.add(new Row(name, (int) agg[0], agg[1])));
        rows.sort((x, y) -> Double.compare(y.cores(), x.cores()));
        return new Usage(own, rows, Runtime.getRuntime().availableProcessors(), seconds);
    }

    private long startMillis() {
        return System.currentTimeMillis() - (System.nanoTime() - nanos) / 1_000_000L;
    }
}
