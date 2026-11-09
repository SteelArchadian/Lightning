package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArrayOwnersTest {
    @Test
    void splitsEachArrayTypeBySampledSites() {
        String text = """
                   1:        100      1000000000  [J (java.base@21)
                   2:        100       400000000  [B (java.base@21)
                   3:         10        50000000  [Lme.cortex.voxy.Section;
                """;
        HeapHistogram h = HeapHistogram.parse(text, new ModIndex(), 0, 0);
        List<JfrDigest.OldSample> samples = List.of(
                new JfrDigest.OldSample("[J", 3000, "VoxelSection.decode", "boundless"),
                new JfrDigest.OldSample("long[]", 1000, "PalettedContainer.<init>", "minecraft"),
                new JfrDigest.OldSample("[B", 0, "NativeImage.read", "minecraft"),
                new JfrDigest.OldSample("[Lme.cortex.voxy.Section;", 10, "x", "voxy"));
        List<ArrayOwners.Row> rows = ArrayOwners.of(h, samples);
        assertEquals(3, rows.size(), rows.toString()); // voxy's own arrays are already attributed
        assertEquals("long[]", rows.get(0).type());
        assertEquals(500000000L, rows.get(0).bytes()); // one sample each: half of long[] each
        assertEquals(500000000L, rows.get(1).bytes());
        assertEquals("byte[]", rows.get(2).type());
        assertEquals(400000000L, rows.get(2).bytes());
    }

    @Test
    void oneLargeBufferDoesNotTakeAShareBySize() {
        // Gate 0514: Lightning's 1 MB buffer was 1 MB of 5.5 MB sampled byte[], so size weighting gave it 250 MB.
        String text = """
                   1:        100      1400000000  [B (java.base@21)
                """;
        HeapHistogram h = HeapHistogram.parse(text, new ModIndex(), 0, 0);
        long buffer = JfrDigest.arrayBytes("[B", 1 << 20);
        assertEquals(16 + (1 << 20), buffer);
        assertEquals(0, JfrDigest.arrayBytes("java.lang.String", 5));
        assertEquals(16 + 8 * 10, JfrDigest.arrayBytes("[J", 10));
        List<JfrDigest.OldSample> samples = new java.util.ArrayList<>();
        samples.add(new JfrDigest.OldSample("[B", buffer, "EventFile$Writer.<init>", "lightning"));
        for (int i = 0; i < 139; i++) samples.add(new JfrDigest.OldSample("[B", 32_000, "VoxelSection.decode", "boundless"));
        List<ArrayOwners.Row> rows = ArrayOwners.of(h, samples);
        ArrayOwners.Row own = rows.stream().filter(r -> r.mod().equals("lightning")).findFirst().orElseThrow();
        assertEquals(10_000_000L, own.bytes()); // 1 of 140 samples
        assertEquals(1_390_000_000L, rows.get(0).bytes());
        // A site never shows less than its samples measured.
        List<ArrayOwners.Row> floor = ArrayOwners.of(HeapHistogram.parse("   1:  1  1000  [B (java.base@21)\n", new ModIndex(), 0, 0),
                List.of(new JfrDigest.OldSample("[B", buffer, "w", "lightning"), new JfrDigest.OldSample("[B", 10, "x", "y")));
        assertEquals(buffer, floor.get(0).bytes());
    }
}
