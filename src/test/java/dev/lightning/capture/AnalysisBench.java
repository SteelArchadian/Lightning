package dev.lightning.capture;

import com.google.gson.JsonObject;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Analysis time for a long capture (100k frames, ~90 sections each). Run with -Dlightning.bench=true. */
class AnalysisBench {
    @TempDir
    Path dir;

    @Test
    void longCapture() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lightning.bench"));
        CaptureOptions options = CaptureOptions.parse("--no-jfr --gpu off");
        Capture capture = new Capture("bench", options);
        WriterThread writer = new WriterThread(capture, dir.resolve("events.bin"));
        writer.start();
        Capture.ACTIVE = capture;
        String[] names = new String[30];
        for (int i = 0; i < names.length; i++) names[i] = "section " + i;
        Thread render = new Thread(() -> {
            Track t = Capture.track();
            for (int frame = 0; frame < 100_000; frame++) {
                t.tickStart();
                for (int a = 0; a < 30; a++) {
                    t.push(names[a]);
                    t.push(names[(a + 1) % 30]);
                    t.push(names[(a + 2) % 30]);
                    t.pop();
                    t.pop();
                    t.pop();
                }
                t.tickEnd();
                if ((frame & 1023) == 0) Thread.onSpinWait();
            }
        }, "Render thread");
        render.start();
        render.join();
        Capture.ACTIVE = null;
        capture.stopNanos = System.nanoTime();
        for (Track t : capture.tracks()) t.flushFinal();
        writer.finish();
        CaptureData data = new CaptureData(capture, dir.resolve("events.bin"), null, 0, null,
                List.of(), null, new JsonObject(), writer.bytesWritten(), -1, null, null, null);
        for (int run = 0; run < 3; run++) {
            long t0 = System.nanoTime();
            Analyzer.Result r = Analyzer.analyze(data);
            System.out.printf("analysis run %d: %.0f ms, profile.json %.1f MB, events %.1f MB%n", run, (System.nanoTime() - t0) / 1e6,
                    Files.size(r.profileFile()) / 1e6, writer.bytesWritten() / 1e6);
        }
    }
}
