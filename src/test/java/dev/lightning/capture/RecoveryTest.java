package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.export.Exporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A capture whose game "crashed" (never analysed) is rebuilt from its files on disk, phases included. */
class RecoveryTest {
    @TempDir
    Path dir;

    @Test
    void rebuildsUnfinishedCapture() throws Exception {
        Path tmp = dir.resolve("lightning/tmp/crashed");
        Files.createDirectories(tmp);
        CaptureOptions options = CaptureOptions.parse("--no-jfr --gpu off");
        Capture c = new Capture("crashed", options);
        c.marksFile = tmp.resolve("marks.txt");
        WriterThread writer = new WriterThread(c, tmp.resolve("events.bin"));
        writer.start();
        Capture.ACTIVE = c;
        Track[] holder = new Track[1];
        Thread render = new Thread(() -> {
            Track t = Capture.track();
            holder[0] = t;
            for (int frame = 0; frame < 200; frame++) {
                if (frame == 0) c.mark("calm");
                if (frame == 100) c.mark("boss fight");
                t.tickStart();
                t.push("gameRenderer");
                long end = System.nanoTime() + (frame >= 100 ? 2_000_000 : 500_000);
                while (System.nanoTime() < end) Thread.onSpinWait();
                t.pop();
                t.tickEnd();
            }
        }, "Render thread");
        render.start();
        render.join();
        Track t = holder[0];
        Capture.ACTIVE = null;
        // The "crash": the writer has the data on disk, but no stop, no analysis, no export.
        t.flushFinal();
        writer.finish();
        JsonObject info = new JsonObject();
        info.addProperty("id", c.id);
        info.addProperty("label", "crash test");
        info.addProperty("startNanos", c.startNanos);
        info.addProperty("startEpochMillis", c.startEpochMillis);
        info.addProperty("epochOffsetNanos", c.epochOffsetNanos);
        info.addProperty("options", CaptureOptions.toArgs(options));
        info.add("environment", new JsonObject());
        Files.writeString(tmp.resolve("capture.json"), info.toString());

        assertEquals(1, Recovery.pending(dir.resolve("lightning")).size());
        Exporter.Result result = Recovery.recover(tmp, dir.resolve("captures"));
        assertFalse(Files.exists(tmp), "temporary folder should be removed");
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            JsonObject results = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("results.json")).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("crash test", results.get("label").getAsString());
            JsonObject phases = results.getAsJsonObject("phases");
            assertTrue(phases.has("calm") && phases.has("boss fight"), "phases: " + phases.keySet());
            double calm = phases.getAsJsonObject("calm").get("frameP50").getAsDouble();
            double boss = phases.getAsJsonObject("boss fight").get("frameP50").getAsDouble();
            assertTrue(boss > calm * 2, "boss " + boss + " vs calm " + calm);
        }
    }
}
