package dev.lightning.analysis;

import com.google.gson.JsonObject;
import dev.lightning.capture.Capture;
import dev.lightning.capture.LogCapture;
import dev.lightning.capture.MetricsSampler;
import java.nio.file.Path;
import java.util.List;

/** Everything a finished capture left behind, handed to the analyzer. */
public record CaptureData(
        Capture capture,
        Path eventsFile,
        Path jfrFile,
        long jfrSetupNanos,
        String jfrError,
        List<MetricsSampler.Sample> metrics,
        LogCapture.Snapshot logs,
        JsonObject environment,
        long eventBytes,
        long jfrThreadCpuNanos,
        HeapHistogram heap,
        String deepMemory,
        dev.lightning.capture.LoadSnapshot load) {}
