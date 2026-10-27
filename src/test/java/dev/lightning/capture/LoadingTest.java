package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.analysis.Analyzer;
import dev.lightning.analysis.CaptureData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A simulated loading capture: loading steps, a reload with listener times, class loading and a
 * main thread that both works and waits, through analysis to the report's loading section.
 */
class LoadingTest {
    @TempDir
    Path dir;

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        double x = 0;
        while (System.nanoTime() < end) x += Math.sqrt(x + 1);
        if (x == -1) System.out.println(x);
    }

    @Test
    void loadingCaptureIsAnalysed() throws Exception {
        CaptureOptions options = CaptureOptions.parse("--loading --gpu off --interval 10");
        JfrSession jfr = JfrSession.start(dir.resolve("recording.jfr"), options);
        Capture capture = new Capture("loading", options);
        WriterThread writer = new WriterThread(capture, dir.resolve("events.bin"));
        writer.start();
        Capture.ACTIVE = capture;
        Thread[] holder = new Thread[1];
        WallSampler wall = new WallSampler();

        Thread main = new Thread(() -> {
            LoadTimeline.phase("Building mods");
            // Loading code: classes this JVM has not loaded yet.
            try {
                Class.forName("javax.swing.JTable");
                Class.forName("javax.swing.JTree");
                Class.forName("java.awt.datatransfer.DataFlavor");
            } catch (ClassNotFoundException | LinkageError e) {
                // Headless or trimmed runtime: class loading is just not part of this run.
            }
            spin(1_200_000_000L);
            LoadTimeline.phase("Loading resources");
            long reloadStart = System.nanoTime();
            spin(1_000_000_000L);
            try {
                Thread.sleep(1_200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            LoadTimeline.reloadDone("Resource packs", reloadStart, List.of(
                    new LoadTimeline.Listener("net.minecraft.client.resources.model.ModelManager", 1_800_000_000L, 400_000_000L),
                    new LoadTimeline.Listener("FontManager", 300_000_000L, 20_000_000L)));
            LoadTimeline.phase("Title screen");
            spin(300_000_000L);
        }, "Render thread");
        holder[0] = main;
        Capture.renderThread = main;
        wall.start();
        main.start();
        main.join();
        wall.stop();

        Capture.ACTIVE = null;
        capture.stopNanos = System.nanoTime();
        for (Track t : capture.tracks()) t.flushFinal();
        writer.finish();
        jfr.stop();

        LoadSnapshot load = new LoadSnapshot(LoadTimeline.milestones(), LoadTimeline.reloads(), wall,
                MixinScan.collect(name -> "test"), false, 0, capture.startNanos, 0);
        CaptureData data = new CaptureData(capture, dir.resolve("events.bin"), jfr.file, jfr.setupNanos, null,
                List.of(), null, new JsonObject(), writer.bytesWritten(), -1, null, null, load);
        Analyzer.Result result = Analyzer.analyze(data);
        JsonObject profile = JsonParser.parseString(new String(result.profileJson(), StandardCharsets.UTF_8)).getAsJsonObject();

        JsonObject loading = profile.getAsJsonObject("loading");
        assertEquals("join", loading.get("kind").getAsString());
        JsonArray phases = loading.getAsJsonArray("phases");
        List<String> names = phases.asList().stream().map(p -> p.getAsJsonObject().get("name").getAsString()).toList();
        assertTrue(names.containsAll(List.of("Building mods", "Loading resources", "Title screen")), "phases: " + names);
        double resources = phases.asList().stream().map(p -> p.getAsJsonObject()).filter(p -> p.get("name").getAsString().equals("Loading resources"))
                .findFirst().orElseThrow().get("ms").getAsDouble();
        assertTrue(resources > 2000 && resources < 3500, "Loading resources ms " + resources);

        JsonObject reload = loading.getAsJsonArray("reloads").get(0).getAsJsonObject();
        assertEquals("net.minecraft.client.resources.model.ModelManager", reload.getAsJsonArray("listeners").get(0).getAsJsonObject().get("name").getAsString());

        // The main thread's samples show it working and waiting, split by step.
        JsonObject mainThread = null;
        for (var t : loading.getAsJsonArray("threads")) if (t.getAsJsonObject().get("main").getAsBoolean()) mainThread = t.getAsJsonObject();
        assertTrue(mainThread != null, "main thread missing from wall samples");
        double waited = mainThread.getAsJsonObject("total").has("Waiting") ? mainThread.getAsJsonObject("total").get("Waiting").getAsDouble() : 0;
        assertTrue(waited > 900 && waited < 1600, "waited ms " + waited);
        assertTrue(mainThread.getAsJsonArray("timeline").size() > 10);

        if (loading.has("classLoading")) assertTrue(loading.getAsJsonObject("classLoading").get("classes").getAsInt() > 0);

        // The front page leads with the loading step that took longest.
        String first = profile.getAsJsonArray("contenders").get(0).getAsJsonObject().get("title").getAsString();
        assertTrue(first.startsWith("Loading resources took"), "first contender: " + first);
        assertTrue(result.summary().contains("## World join"), "brief lacks the loading section");
        JsonObject results = JsonParser.parseString(new String(result.results(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(results.has("loading"));
        assertTrue(!results.getAsJsonObject("loading").getAsJsonArray("threads").get(0).getAsJsonObject().has("timeline"));
        Capture.renderThread = null;
        // Keep the report next to the build for a look at the Loading tab.
        var exported = dev.lightning.export.Exporter.export(result, dir.resolve("captures"));
        Path build = Path.of(System.getProperty("lightning.testOutput", dir.toString()));
        java.nio.file.Files.copy(exported.html(), build.resolve("test-loading-report.html"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void earlyLogLinesAreRead() {
        long offset = System.currentTimeMillis() * 1_000_000L - System.nanoTime();
        java.time.LocalDate day = java.time.LocalDate.now();
        EarlyLog.Line full = EarlyLog.parse("[17Oct2026 08:40:00.067] [Render thread/DEBUG] [net.neoforged.fml.javafmlmod.FMLModContainer/LOADING]: Creating FMLModContainer instance for [x]", day, offset);
        assertTrue(full != null && full.thread().equals("Render thread") && full.logger().equals("net.neoforged.fml.javafmlmod.FMLModContainer"));
        EarlyLog.Line shortForm = EarlyLog.parse("[08:39:33] [main/INFO]: ModLauncher running: args [--username, x]", day, offset);
        assertTrue(shortForm != null && shortForm.level().equals("INFO") && shortForm.message().startsWith("ModLauncher running"));
        List<LoadTimeline.Milestone> steps = EarlyLog.steps(List.of(shortForm, full));
        assertEquals(List.of("Starting the mod loader", "Building mods"), steps.stream().map(LoadTimeline.Milestone::name).toList());
    }
}
