package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeapHistogramTest {
    private static final String SAMPLE = """
            77064:
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:        252243      726712376  [J (java.base@21.0.12.1)
               2:       3468019      654675776  [B (java.base@21.0.12.1)
               3:        100000       40000000  [Lme.cortex.voxy.Section; 
               4:         50000       20000000  me.cortex.voxy.Node 
               5:         10000        1000000  net.minecraft.world.level.chunk.LevelChunk 
            Total       3880262     1441712376
            """;

    @Test
    void groupsByOwnerAndKeepsArraysSeparate() {
        ModIndex mods = new ModIndex();
        mods.putPackage("me.cortex.voxy", "voxy");
        HeapHistogram h = HeapHistogram.parse(SAMPLE, mods, 0, 0);
        assertEquals(5, h.rows.size());
        assertEquals(726712376L + 654675776L, h.byOwner.get("plain arrays (owner unknown)")[0]);
        assertEquals(60000000L, h.byOwner.get("voxy")[0]);
        assertEquals("long[]", HeapHistogram.readable("[J"));
        assertEquals("me.cortex.voxy.Section[]", HeapHistogram.readable("[Lme.cortex.voxy.Section;"));
        assertTrue(h.text().contains("voxy"));
    }

    @Test
    void parsesRealHistogram() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(System.getProperty("lightning.histogramFile", "build/runD-histo.txt"));
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(p));
        HeapHistogram h = HeapHistogram.parse(java.nio.file.Files.readString(p), ModIndex.fromLoadedMods(), 0, 0);
        assertTrue(h.rows.size() > 1000);
        System.out.println(h.text().lines().limit(14).reduce("", (a, b) -> a + "\n" + b));
    }
}
