package dev.lightning.analysis;

import dev.lightning.Lightning;
import dev.lightning.capture.EventFile;
import dev.lightning.capture.Track;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns a finished capture into the report: profile JSON, a Markdown brief and a headline. */
public final class Analyzer {
    /** {@code profileFile}: profile.json, written beside the capture's event file. */
    public record Result(String id, long startEpochMillis, Path profileFile, String summary, Path jfrFile, List<Finding> findings, byte[] results) {
        /** The whole profile in memory; for tests and small captures only. */
        public byte[] profileJson() throws IOException {
            return Files.readAllBytes(profileFile);
        }
    }

    private Analyzer() {}

    public static Result analyze(CaptureData data) throws IOException {
        long t0 = System.nanoTime();
        List<Track> tracks = data.capture().tracks();
        Set<Long> gameThreads = new HashSet<>();
        for (Track t : tracks) gameThreads.add(t.threadId);

        JfrDigest jfr = null;
        String jfrReadError = null;
        if (data.jfrFile() != null && Files.exists(data.jfrFile())) {
            try {
                jfr = JfrDigest.read(data.jfrFile(), data.capture().epochOffsetNanos, ModIndex.fromLoadedMods(), gameThreads);
            } catch (IOException | RuntimeException e) {
                jfrReadError = e.toString();
            }
        }
        SpanDigest spans = new SpanDigest(data.capture().names.snapshot(), jfr == null ? Map.of() : jfr.samplesByThread);
        EventFile.read(data.eventsFile(), spans);
        spans.finish();
        if (data.capture().stopNanos() == 0 && spans.lastTime != Long.MIN_VALUE) data.capture().setStopNanos(spans.lastTime);
        WorldDigest world = WorldDigest.of(tracks);

        Report report = new Report(data, spans, jfr, jfrReadError, world);
        List<Finding> findings = Findings.compute(report);
        report.analysisNanos = System.nanoTime() - t0;
        String summary = Summary.write(report, findings, Lightning.VERSION);
        Path json = data.eventsFile().resolveSibling("profile.json");
        ProfileJson.write(report, findings, Lightning.VERSION, summary, json);
        return new Result(data.capture().id, data.capture().startEpochMillis, json, summary, data.jfrFile(), findings, Results.write(report));
    }
}
