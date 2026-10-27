package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A capture with only some parts recorded still analyses, and says which parts it has. */
class ModulesTest {
    @TempDir
    Path dir;

    @Test
    void renderOnlyCaptureWithoutJfr() throws Exception {
        CaptureOptions options = CaptureOptions.parse("--only render");
        assertTrue(!options.jfr());
        Capture capture = new Capture("render-only", options);
        WriterThread writer = new WriterThread(capture, dir.resolve("events.bin"));
        writer.start();
        Capture.ACTIVE = capture;
        Thread render = new Thread(() -> {
            for (int frame = 0; frame < 60; frame++) {
                Track t = Capture.track();
                t.tickStart();
                t.push("gameRenderer");
                long end = System.nanoTime() + 1_000_000;
                while (System.nanoTime() < end) Thread.onSpinWait();
                t.pop();
                t.tickEnd();
            }
        }, "Render thread");
        render.start();
        render.join();
        Capture.ACTIVE = null;
        capture.stopNanos = System.nanoTime();
        for (Track t : capture.tracks()) t.flushFinal();
        writer.finish();

        CaptureData data = new CaptureData(capture, dir.resolve("events.bin"), null, 0, null, List.of(), null, new JsonObject(),
                writer.bytesWritten(), -1, null, null, null);
        Analyzer.Result result = Analyzer.analyze(data);
        JsonObject results = JsonParser.parseString(new String(result.results(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("[\"render\"]", results.getAsJsonArray("modules").toString());
        assertTrue(results.getAsJsonObject("whole").get("fps").getAsDouble() > 0);
        assertTrue(result.summary().contains("## Frames (render thread)"));
    }
}
