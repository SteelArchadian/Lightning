package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lightning.capture.EventFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class SpanDigestProbe {
    public static void assertReadable(Path events) throws Exception {
        SpanDigest digest = new SpanDigest(List.of(), Map.of());
        EventFile.read(events, digest);
        digest.finish();
        SpanDigest.ThreadSpans t = digest.threads.values().iterator().next();
        assertTrue(t.losses > 0, "loss marker not seen");
        assertTrue(t.ticks.size() > 1000, "ticks after loss not recovered");
    }
}
