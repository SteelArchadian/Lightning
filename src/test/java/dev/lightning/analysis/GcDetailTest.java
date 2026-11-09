package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** G1's per-collection detail events attach to the collection they describe. */
class GcDetailTest {
    @TempDir
    Path dir;

    static volatile Object sink;

    @Test
    void joinsG1DetailToItsCollection() throws Exception {
        Assumptions.assumeTrue(isG1(), "needs G1");
        Path file = dir.resolve("gc.jfr");
        try (Recording r = new Recording()) {
            for (String e : new String[] {"jdk.GarbageCollection", "jdk.G1GarbageCollection", "jdk.YoungGarbageCollection", "jdk.EvacuationFailed",
                    "jdk.GCReferenceStatistics", "jdk.GCPhasePauseLevel1"}) {
                r.enable(e).withThreshold(java.time.Duration.ZERO);
            }
            r.start();
            for (int i = 0; i < 4000; i++) sink = new byte[64 << 10]; // 250 MB of garbage: several young collections
            System.gc();
            r.stop();
            r.dump(file);
        }
        JfrDigest d = JfrDigest.read(file, 0, new ModIndex(), Set.of());
        assertFalse(d.gcs.isEmpty());
        boolean joined = false;
        for (JfrDigest.Gc gc : d.gcs) {
            JfrDigest.GcDetail g = d.gcDetails.get(gc.gcId());
            if (g != null && !g.slowestPhase.isEmpty()) joined = true;
        }
        assertTrue(joined, "no collection got its detail: " + d.gcDetails.keySet());
    }

    private static boolean isG1() {
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) if (gc.getName().startsWith("G1")) return true;
        return false;
    }
}
