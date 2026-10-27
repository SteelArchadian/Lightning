package dev.lightning.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Reads a real recording given with -Dlightning.jfrRepro=<path>; skipped otherwise. */
class JfrReproTest {
    @Test
    void readsRecording() throws Exception {
        String path = System.getProperty("lightning.jfrRepro", "");
        Assumptions.assumeTrue(!path.isEmpty() && Files.exists(Path.of(path)));
        JfrDigest d = JfrDigest.read(Path.of(path), 0, new ModIndex(), Set.of(Long.getLong("lightning.jfrReproThread", -1L)));
        double secs = (d.lastEvent - d.firstEvent) / 1e9;
        java.util.Map<String, double[]> pools = new java.util.TreeMap<>();
        for (var c : d.threadCpu.values()) { double[] a = pools.computeIfAbsent(c.group, k -> new double[2]); a[0] += c.sum / secs * Runtime.getRuntime().availableProcessors(); a[1]++; }
        double total = 0; for (double[] a : pools.values()) total += a[0];
        System.out.println("cores by pool total=" + String.format("%.1f", total));
        pools.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(6)
                .forEach(e -> System.out.println("  " + e.getKey() + " x" + (int) e.getValue()[1] + " " + String.format("%.2f", e.getValue()[0])));
        System.out.println("samples=" + d.executionSamples + " allocs=" + d.allocByClass.size() + " gcs=" + d.gcs.size() + " gameThreadBlocking=" + d.blocking.size() + " groups=" + d.blockingByGroup.keySet().stream().filter(k -> k.startsWith("Server")).toList());
    }
}
