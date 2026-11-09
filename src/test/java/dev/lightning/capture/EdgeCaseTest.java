package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.gson.JsonObject;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Captures that real games produce on a bad day: nothing recorded, unbalanced sections, mods' broken section names. */
class EdgeCaseTest {
    @TempDir
    Path dir;

    private Analyzer.Result record(String label, Runnable game) throws Exception {
        return record(label, "Render thread", game);
    }

    private Analyzer.Result record(String label, String threadName, Runnable game) throws Exception {
        CaptureOptions options = CaptureOptions.parse("--no-jfr --gpu off");
        Capture capture = new Capture(label, options);
        WriterThread writer = new WriterThread(capture, dir.resolve(label + ".bin"));
        writer.start();
        Capture.ACTIVE = capture;
        Thread render = new Thread(game, threadName);
        render.start();
        render.join();
        Capture.ACTIVE = null;
        capture.stopNanos = System.nanoTime();
        for (Track t : capture.tracks()) t.flushFinal();
        writer.finish();
        CaptureData data = new CaptureData(capture, dir.resolve(label + ".bin"), null, 0, null,
                List.of(), null, new JsonObject(), writer.bytesWritten(), -1, null, null, null);
        return Analyzer.analyze(data);
    }

    @Test
    void anEmptyCaptureStillMakesAReport() throws Exception {
        assertNotNull(record("empty", () -> {}));
    }

    @Test
    void oneFrameAndUnbalancedSections() throws Exception {
        assertNotNull(record("unbalanced", () -> {
            Track t = Capture.track();
            t.pop(); // stray pop before anything
            t.tickStart();
            t.push("a");
            t.push("b"); // never popped
            t.tickEnd();
            t.pop();
            t.pop();
            t.push("left open at the end");
        }));
    }

    @Test
    void deepNestingPastTheLimit() throws Exception {
        assertNotNull(record("deep", () -> {
            Track t = Capture.track();
            for (int frame = 0; frame < 3; frame++) {
                t.tickStart();
                for (int i = 0; i < 500; i++) t.push("level " + (i % 7));
                for (int i = 0; i < 500; i++) t.pop();
                t.tickEnd();
            }
        }));
    }

    @Test
    void aSectionNameThatThrowsDoesNotEscape() throws Exception {
        assertNotNull(record("throwing", () -> {
            Supplier<String> broken = () -> { throw new IllegalStateException("mod bug"); };
            for (int frame = 0; frame < 5; frame++) {
                SectionProfiler.INSTANCE.startTick();
                assertDoesNotThrow(() -> SectionProfiler.INSTANCE.push(broken));
                SectionProfiler.INSTANCE.pop();
                SectionProfiler.INSTANCE.endTick();
            }
        }));
    }

    @Test
    void dedicatedServerOnly() throws Exception {
        assertNotNull(record("server", "Server thread", () -> {
            Track t = Capture.track();
            for (int tick = 0; tick < 40; tick++) {
                t.tickStart();
                t.push("levels");
                t.pop();
                t.push("nextTickWait");
                t.pop();
                t.tickEnd();
            }
        }));
    }

    @Test
    void theFrameThatLeavesTheWorldIsLeftOut() throws Exception {
        Analyzer.Result r = record("leaving", () -> {
            Track t = Capture.track();
            for (int frame = 0; frame < 20; frame++) {
                t.tickStart();
                if (frame == 19) {
                    t.screen(Track.LEAVING_WORLD);
                    sleep(150); // disconnect and world save
                } else {
                    sleep(2);
                }
                t.tickEnd();
            }
        });
        JsonObject whole = com.google.gson.JsonParser.parseString(new String(r.results(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("whole");
        org.junit.jupiter.api.Assertions.assertTrue(whole.get("frameMax").getAsDouble() < 100, whole.toString());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void phasesWithoutFrames() throws Exception {
        assertNotNull(record("phases", () -> {
            Capture.ACTIVE.mark("first");
            Capture.ACTIVE.mark("second");
            Capture.ACTIVE.mark("second");
        }));
    }
}
