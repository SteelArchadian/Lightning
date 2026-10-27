package dev.lightning.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.Lightning;
import dev.lightning.capture.CaptureOptions;
import dev.lightning.capture.Recorder;
import dev.lightning.export.Exporter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runs a real capture on a real server world: entities and ticking block entities in a test
 * arena, the vanilla profiler bridge on the server thread, JFR, analysis and export. Verifies
 * that every hook delivered data. Run with {@code gradlew runGameTestServer}.
 */
@GameTestHolder(Lightning.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LightningGameTests {
    private LightningGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200_000)
    public static void captureRecordsServerWork(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) helper.setBlock(x, 0, z, Blocks.STONE);
        for (int x = 1; x < 8; x++) {
            helper.setBlock(x, 1, 1, Blocks.HOPPER);
            helper.setBlock(x, 1, 7, Blocks.FURNACE);
        }
        for (int i = 0; i < 12; i++) helper.spawn(EntityType.PIG, 2 + i % 5, 1, 3 + i % 3);

        long requested = System.nanoTime();
        AtomicReference<Exporter.Result> saved = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        boolean started = Lightning.recorder().start(CaptureOptions.parse("--all --gpu off"), new Recorder.Listener() {
            @Override
            public void started(String message) {
                org.slf4j.LoggerFactory.getLogger("LightningTest").info("Recording started {} ms after the request", (System.nanoTime() - requested) / 1_000_000);
            }

            @Override
            public void saved(Exporter.Result result) {
                saved.set(result);
            }

            @Override
            public void failed(String message) {
                failed.set(message);
            }
        });
        if (!started) helper.fail("recorder did not start: " + Lightning.recorder().status());
        // The test server ticks as fast as it can, so stop after a number of ticks rather than seconds.
        // Starting happens on the recorder thread; wait until it is recording before counting ticks.
        java.util.concurrent.atomic.AtomicInteger recordedTicks = new java.util.concurrent.atomic.AtomicInteger();
        helper.onEachTick(() -> {
            if (Lightning.recorder().state() == Recorder.State.RECORDING && recordedTicks.incrementAndGet() == 150) Lightning.mark("second half");
            if (Lightning.recorder().state() == Recorder.State.RECORDING && recordedTicks.get() == 300) {
                Lightning.recorder().stop(false);
            }
        });

        helper.succeedWhen(() -> {
            if (failed.get() != null) helper.fail("capture failed: " + failed.get());
            Exporter.Result result = saved.get();
            if (result == null) throw new GameTestAssertException("waiting for the capture to be saved");
            verify(helper, result);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400_000, batch = "heap")
    public static void deepMemoryAnalysisRuns(GameTestHelper helper) {
        AtomicReference<String> report = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        Lightning.recorder().heapDeep(false, msg -> {}, (line, file) -> {
            try {
                report.set(line + System.lineSeparator() + java.nio.file.Files.readString(file));
            } catch (java.io.IOException e) {
                failed.set(e.toString());
            }
        }, failed::set);
        helper.succeedWhen(() -> {
            if (failed.get() != null) helper.fail("deep analysis failed: " + failed.get());
            if (report.get() == null) throw new GameTestAssertException("waiting for the analysis");
            check(report.get().contains("## Memory by owner"), "report has no owner table");
            check(report.get().contains("minecraft") || report.get().contains("Minecraft"), "nothing charged to Minecraft");
        });
    }

    /** The public API: quiet capture, two phases, no HTML, results.json keyed by phase. */
    @GameTest(template = "empty", timeoutTicks = 200_000, batch = "api")
    public static void apiCaptureWithPhases(GameTestHelper helper) {
        java.nio.file.Path out = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("lightning/api-test");
        dev.lightning.api.CaptureHandle handle = dev.lightning.api.LightningApi.get().start(
                new dev.lightning.api.CaptureRequest("api test", out, true, true, false, false, false, 0));
        handle.beginPhase("warmup");
        java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<dev.lightning.api.CaptureResult> done = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        helper.onEachTick(() -> {
            if (Lightning.recorder().state() != Recorder.State.RECORDING) return;
            int n = ticks.incrementAndGet();
            if (n == 100) handle.beginPhase("main");
            if (n == 200) handle.stop().whenComplete((r, e) -> {
                if (e != null) error.set(e);
                else done.set(r);
            });
        });
        helper.succeedWhen(() -> {
            if (error.get() != null) helper.fail("API capture failed: " + error.get());
            dev.lightning.api.CaptureResult r = done.get();
            if (r == null) throw new GameTestAssertException("waiting for the API capture");
            check(r.html() == null, "HTML written although exportViewer=false");
            check(r.archive().startsWith(out), "archive not in the requested folder: " + r.archive());
            JsonObject results;
            try {
                results = JsonParser.parseString(java.nio.file.Files.readString(r.resultsJson())).getAsJsonObject();
            } catch (java.io.IOException e) {
                throw new GameTestAssertException("results.json unreadable: " + e);
            }
            check(results.get("schema_version").getAsInt() == 2, "schema_version");
            check(results.get("label").getAsString().equals("api test"), "label");
            JsonObject phases = results.getAsJsonObject("phases");
            check(phases.has("warmup") && phases.has("main"), "phases: " + phases.keySet());
            check(phases.getAsJsonObject("main").get("tickP50") != null, "no tick stats in phase");
            check(r.gameThreadOverheadPct() >= 0 && r.gameThreadOverheadPct() < 50, "overhead " + r.gameThreadOverheadPct());
        });
    }

    private static void verify(GameTestHelper helper, Exporter.Result result) {
        JsonObject profile;
        try (ZipFile zip = new ZipFile(result.archive().toFile())) {
            profile = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("profile.json")).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            check(zip.getEntry("summary.md") != null, "summary.md missing");
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("cannot read archive: " + e);
        }
        JsonObject coverage = profile.getAsJsonObject("coverage");
        check(coverage.get("profilerBridge").getAsBoolean(), "vanilla profiler bridge never observed");
        check(coverage.get("entityHook").getAsBoolean(), "entity hook never observed");
        check(coverage.get("blockEntityHook").getAsBoolean(), "block entity hook never observed");
        check(coverage.get("jfr").getAsString().equals("recorded"), "JFR: " + coverage.get("jfr").getAsString());

        JsonObject server = null;
        for (JsonElement e : profile.getAsJsonArray("threads")) {
            if (e.getAsJsonObject().get("role").getAsString().equals("server")) server = e.getAsJsonObject();
        }
        check(server != null, "no server thread in profile");
        int ticks = server.getAsJsonObject("ticks").getAsJsonArray("duration").size();
        check(ticks >= 40, "only " + ticks + " server ticks recorded");
        // The server tree must contain vanilla's per-level sections.
        JsonArray names = profile.getAsJsonArray("names");
        boolean blockEntities = false, entities = false;
        for (JsonElement n : server.getAsJsonObject("tree").getAsJsonArray("name")) {
            int id = n.getAsInt();
            if (id < 0) continue;
            String name = names.get(id).getAsString();
            blockEntities |= name.equals("blockEntities");
            entities |= name.equals("entities");
        }
        check(blockEntities && entities, "vanilla level sections missing from server tree");

        boolean pig = false, hopper = false;
        for (JsonElement l : profile.getAsJsonArray("world")) {
            for (JsonElement t : l.getAsJsonObject().getAsJsonArray("types")) {
                String type = t.getAsJsonObject().get("type").getAsString();
                pig |= type.equals("minecraft:pig");
                hopper |= type.equals("minecraft:hopper");
            }
        }
        check(pig, "pigs were not attributed");
        check(hopper, "hoppers were not attributed");
        check(profile.getAsJsonObject("jvm").get("executionSamples").getAsLong() > 0, "no JVM samples");
        check(profile.has("deepMemory") && profile.get("deepMemory").getAsString().contains("## Memory by owner"), "no memory breakdown in the report");
        check(profile.getAsJsonArray("phases").size() == 2, "phases: " + profile.getAsJsonArray("phases").size());
        check(java.nio.file.Files.exists(result.archive().resolveSibling(result.archive().getFileName().toString().replace(".lightning", ".results.json"))), "results.json missing");

    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Lightning check failed: " + message);
    }
}
