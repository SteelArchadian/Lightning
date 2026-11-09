package dev.lightning.analysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Prints G1 detail per collection from a real recording given with -Dlightning.jfrRepro=<path>; skipped otherwise. */
class GcReproTest {
    @Test
    void printsDetail() throws Exception {
        String path = System.getProperty("lightning.jfrRepro", "");
        Assumptions.assumeTrue(!path.isEmpty() && Files.exists(Path.of(path)));
        JfrDigest d = JfrDigest.read(Path.of(path), 0, new ModIndex(), Set.of());
        int failed = 0;
        for (JfrDigest.Gc gc : d.gcs) {
            JfrDigest.GcDetail g = d.gcDetails.get(gc.gcId());
            if (g != null && g.evacuationFailed) {
                failed++;
                System.out.printf("gc %d %s %.1f ms failed %d objects %.2f MB%n", gc.gcId(), g.type, gc.longestPause() / 1e6, g.failedObjects, g.failedBytes / 1048576.0);
            }
        }
        System.out.println("collections " + d.gcs.size() + ", evacuation failures " + failed);
        long total = 0, max = 0;
        for (JfrDigest.SafepointSync p : d.safepoints) {
            total += p.duration();
            max = Math.max(max, p.duration());
        }
        System.out.printf("safepoints %d, sync total %.1f ms, max %.1f ms%n", d.safepoints.size(), total / 1e6, max / 1e6);
        for (JfrDigest.SafepointSync p : d.safepoints) {
            if (p.duration() >= 50_000_000L) System.out.printf("wait %.1f ms running %d during %s%n", p.duration() / 1e6, p.running(), d.poolsBetween(p.time(), p.time() + p.duration()));
        }
    }
}
