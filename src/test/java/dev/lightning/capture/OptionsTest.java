package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptionsTest {
    @Test
    void suggestsUnusedOptionsAndValues() {
        assertTrue(CaptureOptions.suggest("").stream().anyMatch(o -> o.name().equals("--timeout")));
        assertFalse(CaptureOptions.suggest("--no-gpu ").stream().anyMatch(o -> o.name().equals("--no-gpu")));
        assertEquals("30", CaptureOptions.suggest("--timeout ").get(0).name());
    }

    @Test
    void parsesFriendlyNames() {
        CaptureOptions o = CaptureOptions.parse("--no-java --timeout 60");
        assertFalse(o.jfr());
        assertEquals(60, o.timeoutSeconds());
        assertTrue(CaptureOptions.defaults().describe().contains("GPU"));
    }

    @Test
    void onlyChoosesExactlyTheParts() {
        CaptureOptions shader = CaptureOptions.parse("--only gpu,render,cpu");
        assertEquals(java.util.List.of("gpu", "render", "cpu"), shader.modules());
        assertFalse(shader.ticks() || shader.world() || shader.allocations() || shader.gc() || shader.heap());
        assertTrue(shader.jfr(), "cpu sampling needs JFR");
        CaptureOptions noJfr = CaptureOptions.parse("--only gpu,ticks");
        assertFalse(noJfr.jfr(), "no JVM part chosen, so JFR stays off");
        assertTrue(noJfr.render(), "GPU timing rides on render sections");
        // Round trip for crash recovery.
        assertEquals(shader.modules(), CaptureOptions.parse(CaptureOptions.toArgs(shader)).modules());
        assertTrue(CaptureOptions.parse("--only io").io());
        assertEquals(1, CaptureOptions.parse("--gpu-every=1 --only=gpu,render").gpuEvery());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CaptureOptions.parse("--only gpu,storage"));
    }
}
