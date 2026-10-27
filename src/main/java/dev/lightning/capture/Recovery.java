package dev.lightning.capture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import dev.lightning.export.Exporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds a report from a capture that never finished (the game crashed or was killed). While
 * recording, Lightning keeps the capture header (capture.json), the section events (events.bin,
 * flushed every second) and the phase marks (marks.txt) on disk; this turns them into a normal
 * .lightning archive marked as recovered. JVM samples are included only if their file was written.
 */
public final class Recovery {
    private Recovery() {}

    /** Unfinished captures left in {@code <game>/lightning/tmp}. */
    public static List<Path> pending(Path lightningRoot) {
        List<Path> out = new ArrayList<>();
        Path tmp = lightningRoot.resolve("tmp");
        if (!Files.isDirectory(tmp)) return out;
        try (var dirs = Files.list(tmp)) {
            for (Path d : dirs.toList()) if (isRecoverable(d)) out.add(d);
        } catch (IOException ignored) {
            // Nothing to recover.
        }
        return out;
    }

    public static boolean isRecoverable(Path dir) {
        return Files.isRegularFile(dir.resolve("capture.json")) && Files.isRegularFile(dir.resolve("events.bin"));
    }

    /** Analyses and exports one unfinished capture, then removes its temporary folder. */
    public static Exporter.Result recover(Path dir, Path defaultOutput) throws IOException {
        JsonObject info = JsonParser.parseString(Files.readString(dir.resolve("capture.json"))).getAsJsonObject();
        CaptureOptions options = CaptureOptions.parse(info.get("options").getAsString());
        Capture c = new Capture(info.get("id").getAsString() + "-recovered", options, info.get("startNanos").getAsLong(),
                info.get("startEpochMillis").getAsLong(), info.get("epochOffsetNanos").getAsLong());
        c.label = info.has("label") ? info.get("label").getAsString() : "";
        Path marks = dir.resolve("marks.txt");
        if (Files.isRegularFile(marks)) {
            for (String line : Files.readAllLines(marks)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    c.restoreMark(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1));
                } catch (NumberFormatException ignored) {
                    // Skip a half-written line.
                }
            }
        }
        JsonObject env = info.has("environment") ? info.getAsJsonObject("environment") : new JsonObject();
        env.addProperty("recovered", "This capture did not finish (the game stopped while recording); it was rebuilt from the data saved so far.");
        Path jfr = dir.resolve("recording.jfr");
        Path events = dir.resolve("events.bin");
        CaptureData data = new CaptureData(c, events, Files.isRegularFile(jfr) ? jfr : null, 0, null, List.of(), null, env,
                Files.size(events), -1, null, null, null);
        Analyzer.Result analysis = Analyzer.analyze(data);
        String out = info.has("outputDir") ? info.get("outputDir").getAsString() : "";
        Exporter.Result result = Exporter.export(analysis, out.isEmpty() ? defaultOutput : Path.of(out), true);
        Recorder.deleteTree(dir);
        return result;
    }
}
