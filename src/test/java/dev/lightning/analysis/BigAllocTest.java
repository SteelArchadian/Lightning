package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Very large allocations recorded by JFR (outside thread buffers) come back by site, small ones dropped. */
class BigAllocTest {
    @TempDir
    Path dir;

    static volatile Object keep;

    @Test
    void namesTheSiteOfVeryLargeAllocations() throws Exception {
        Path file = dir.resolve("big.jfr");
        try (Recording r = new Recording()) {
            r.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
            r.start();
            for (int i = 0; i < 3; i++) keep = new byte[(int) (JfrDigest.humongousThreshold() + (1 << 20))];
            for (int i = 0; i < 50; i++) keep = new long[1 << 14]; // 128 KB: outside buffers maybe, never large
            r.stop();
            r.dump(file);
        }
        JfrDigest d = JfrDigest.read(file, 0, new ModIndex(), Set.of());
        assertTrue(d.outsideTlabSeen);
        List<JfrDigest.BigAlloc> big = d.bigAllocations();
        assertEquals(1, big.size(), big.toString());
        assertEquals("byte[]", big.get(0).type());
        assertEquals(3, big.get(0).count());
        assertTrue(big.get(0).site().contains("BigAllocTest"), big.get(0).site());
    }
}
