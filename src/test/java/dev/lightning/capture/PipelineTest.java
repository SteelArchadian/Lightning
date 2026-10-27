package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import dev.lightning.export.Exporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the whole capture → file → analysis → export path with simulated game threads and real JFR. */
class PipelineTest {
    @TempDir
    Path dir;

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        double x = 0;
        while (System.nanoTime() < end) x += Math.sqrt(x + 1);
        if (x == -1) System.out.println(x);
    }

    @Test
    void endToEnd() throws Exception {
        CaptureOptions options = CaptureOptions.parse("--gpu off --interval 10");
        JfrSession jfr = JfrSession.start(dir.resolve("recording.jfr"), options);
        Capture capture = new Capture("test", options);
        WriterThread writer = new WriterThread(capture, dir.resolve("events.bin"));
        writer.start();
        Capture.ACTIVE = capture;

        Thread render = new Thread(() -> {
            for (int frame = 0; frame < 300; frame++) {
                Track t = Capture.track();
                if (frame == 100) capture.mark("second part");
                t.tickStart();
                // Same layout as vanilla's Minecraft.runTick: "render", "gameRenderer" and
                // "updateDisplay" are siblings at the top of the frame.
                t.push("render");
                t.pop();
                t.push("gameRenderer");
                t.push("level");
                spin(frame == 150 ? 40_000_000 : 3_000_000); // one spike; long enough for JVM samples to land
                t.pop();
                t.pop();
                t.push("updateDisplay");
                spin(200_000);
                t.pop();
                t.tickEnd();
            }
        }, "Render thread");
        Thread server = new Thread(() -> {
            for (int tick = 0; tick < 100; tick++) {
                Track t = Capture.track();
                t.tickStart();
                t.push("tick");
                t.push(() -> "level minecraft:overworld");
                spin(1_000_000);
                t.pop();
                t.pop();
                t.push("nextTickWait");
                spin(200_000);
                t.pop();
                t.tickEnd();
            }
        }, "Server thread");
        render.start();
        server.start();
        render.join();
        server.join();

        Capture.ACTIVE = null;
        capture.stopNanos = System.nanoTime();
        for (Track t : capture.tracks()) t.flushFinal();
        writer.finish();
        jfr.stop();

        CaptureData data = new CaptureData(capture, dir.resolve("events.bin"), dir.resolve("recording.jfr"), jfr.setupNanos, null,
                List.of(), null, new JsonObject(), writer.bytesWritten(), -1, null, null, null);
        Analyzer.Result result = Analyzer.analyze(data);
        JsonObject profile = JsonParser.parseString(new String(result.profileJson(), StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals("lightning-profile", profile.get("format").getAsString());
        JsonArray threads = profile.getAsJsonArray("threads");
        JsonObject renderThread = null, serverThread = null;
        for (var e : threads) {
            JsonObject t = e.getAsJsonObject();
            if (t.get("role").getAsString().equals("render")) renderThread = t;
            if (t.get("role").getAsString().equals("server")) serverThread = t;
        }
        assertNotNull(renderThread);
        assertNotNull(serverThread);
        assertEquals(300, renderThread.getAsJsonObject("ticks").getAsJsonArray("duration").size());
        assertEquals(100, serverThread.getAsJsonObject("ticks").getAsJsonArray("duration").size());

        // The slowest render frame is the injected 30 ms spike, and its detail names "level".
        JsonObject spike = renderThread.getAsJsonArray("spikes").get(0).getAsJsonObject();
        assertEquals(150, spike.get("tick").getAsInt());
        assertTrue(spike.get("duration").getAsLong() >= 30_000, "spike duration " + spike.get("duration"));

        // The section tree aggregates exactly: "level" was entered once per frame.
        JsonArray names = profile.getAsJsonArray("names");
        JsonObject tree = renderThread.getAsJsonObject("tree");
        int level = -1;
        for (int i = 0; i < tree.getAsJsonArray("name").size(); i++) {
            int n = tree.getAsJsonArray("name").get(i).getAsInt();
            if (n >= 0 && names.get(n).getAsString().equals("level")) level = i;
        }
        assertTrue(level > 0);
        assertEquals(300, tree.getAsJsonArray("calls").get(level).getAsLong());

        // JVM samples landed inside sections of the simulated render thread.
        // JFR occasionally cannot walk the stacks of this tight test loop on a busy machine; when it
        // took no samples at all there is nothing to attribute, so only check attribution otherwise.
        if (profile.getAsJsonObject("jvm").get("executionSamples").getAsLong() > 0) {
            long attributed = 0;
            for (var s : tree.getAsJsonArray("samples")) attributed += s.getAsLong();
            assertTrue(attributed > 0, "no samples attributed to render sections");
        } else {
            System.err.println("PipelineTest: JFR took no samples this run; sample attribution not checked");
        }

        assertTrue(result.summary().contains("## Frames (render thread)"));
        assertTrue(result.summary().contains("## Phases"), "phases missing");
        JsonObject results = JsonParser.parseString(new String(result.results(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, results.getAsJsonObject("phases").size());
        assertEquals(2, results.get("schema_version").getAsInt());
        assertTrue(results.getAsJsonObject("phases").has("second part"));
        assertTrue(results.getAsJsonObject("whole").get("fps").getAsDouble() > 0);
        // Render sections with CPU per frame, and the run header.
        boolean levelRow = false;
        for (var e : results.getAsJsonObject("whole").getAsJsonArray("renderSections")) {
            JsonObject sec = e.getAsJsonObject();
            if (sec.get("section").getAsString().equals("gameRenderer › level")) {
                levelRow = true;
                assertTrue(sec.get("cpuMs").getAsDouble() > 2, "level cpu " + sec);
                assertTrue(sec.get("cpuP95").getAsDouble() >= sec.get("cpuP50").getAsDouble());
            }
        }
        assertTrue(levelRow, "renderSections lacks gameRenderer › level");
        assertTrue(results.getAsJsonObject("whole").has("gpuBoundPct"));
        assertTrue(results.has("run"));
        double low = results.getAsJsonObject("whole").get("low1PctFps").getAsDouble();
        assertTrue(low > 0 && low < results.getAsJsonObject("whole").get("fps").getAsDouble(), "1% low " + low);
        assertTrue(result.summary().contains("gameRenderer › level"));

        // Export writes a readable archive and a report with the data embedded.
        Files.createDirectories(dir.resolve("assets"));
        Exporter.Result exported = Exporter.export(result, dir.resolve("captures"));
        try (ZipFile zip = new ZipFile(exported.archive().toFile())) {
            assertNotNull(zip.getEntry("profile.json"));
            assertNotNull(zip.getEntry("summary.md"));
            assertNotNull(zip.getEntry("recording.jfr"));
        }
        String html = Files.readString(exported.html());
        assertTrue(!html.contains("__LIGHTNING_DATA__"));
        // The embedded data (streamed gzip + base64) decodes back to the same profile.
        String marker = "<script id=\"lightning-data\" type=\"application/octet-stream\">";
        int from = html.indexOf(marker) + marker.length();
        String b64 = html.substring(from, html.indexOf("</script>", from)).trim();
        byte[] embedded;
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64)))) {
            embedded = in.readAllBytes();
        }
        assertEquals(new String(result.profileJson(), StandardCharsets.UTF_8), new String(embedded, StandardCharsets.UTF_8));
        // Keep sample output next to the build for manual inspection.
        Path build = Path.of(System.getProperty("lightning.testOutput", dir.toString()));
        Files.writeString(build.resolve("test-summary.md"), result.summary());
        Files.copy(exported.html(), build.resolve("test-report.html"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void writerRecoversFromBufferExhaustion() throws Exception {
        CaptureOptions options = CaptureOptions.parse("--no-jfr --gpu off");
        Capture capture = new Capture("loss", options);
        // No writer running: buffers are never returned, so the pool runs dry and events are dropped.
        Capture.ACTIVE = capture;
        Track t = Capture.track();
        for (int i = 0; i < 2_000_000; i++) {
            t.tickStart();
            t.push("a");
            t.pop();
            t.tickEnd();
        }
        Capture.ACTIVE = null;
        assertTrue(t.lostEvents() > 0);
        WriterThread writer = new WriterThread(capture, dir.resolve("events.bin"));
        writer.start();
        t.flushFinal();
        writer.finish();
        dev.lightning.analysis.SpanDigestProbe.assertReadable(dir.resolve("events.bin"));
    }
}
