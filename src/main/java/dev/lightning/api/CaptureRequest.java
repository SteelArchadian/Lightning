package dev.lightning.api;

import java.nio.file.Path;

/**
 * What to record.
 *
 * @param label          name stored in the report and results.json
 * @param outputDir      where the .lightning file (and .results.json beside it) goes; null = {@code <game>/lightning/captures}
 * @param quiet          no chat messages
 * @param jfrSampling    JVM sampling (which mods' code uses CPU and memory)
 * @param gpuSections    GPU time per render section and shader pass (false = whole-frame GPU time only)
 * @param heapAtEnd      full memory breakdown by mod after the capture (game freezes a few seconds; needs free disk)
 * @param exportViewer   also write the interactive HTML report
 * @param timeoutSeconds stop by itself after this long; 0 = until {@link CaptureHandle#stop()}
 */
public record CaptureRequest(String label, Path outputDir, boolean quiet, boolean jfrSampling, boolean gpuSections,
                             boolean heapAtEnd, boolean exportViewer, int timeoutSeconds) {
    public static CaptureRequest defaults(String label) {
        return new CaptureRequest(label, null, false, true, true, false, true, 0);
    }
}
