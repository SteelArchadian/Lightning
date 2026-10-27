package dev.lightning.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lightning.Lightning;
import dev.lightning.capture.CaptureOptions;
import dev.lightning.capture.Recorder;
import dev.lightning.capture.Recovery;
import dev.lightning.export.Exporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Lightning for other mods (benchmarks, tools). Check {@code ModList.get().isLoaded("lightning")}
 * before touching this class.
 *
 * <pre>
 * CaptureHandle h = LightningApi.get().start(CaptureRequest.defaults("my run"));
 * h.beginPhase("baseline"); ... h.beginPhase("boss fight"); ...
 * h.stop().thenAccept(result -> read(result.resultsJson()));
 * </pre>
 */
public final class LightningApi {
    /** 2: loading steps ({@link #loadingStep}). 3: {@link #start(CaptureRequest, String)} with chosen parts. */
    public static final int API_VERSION = 3;
    private static final LightningApi INSTANCE = new LightningApi();

    /** Optional chat line for non-quiet API captures, installed by the client. */
    public static volatile Consumer<Exporter.Result> savedNotifier;

    private LightningApi() {}

    public static LightningApi get() {
        return INSTANCE;
    }

    public int apiVersion() {
        return API_VERSION;
    }

    public boolean isCapturing() {
        return Lightning.recorder().state() != Recorder.State.IDLE;
    }

    /** Starts recording. Throws {@link IllegalStateException} if a capture is already running. */
    public CaptureHandle start(CaptureRequest request) {
        return start(request, "");
    }

    /**
     * Starts recording with extra start options on top of the request, most usefully the parts to
     * record: {@code "--only gpu,render,cpu"} for a shader test (no tick, world or memory hooks),
     * {@code "--only ticks,world,cpu,alloc,gc"} for a server-side test. Same options as
     * {@code /lightning profiler start}; parts are listed in {@link #modules()}.
     */
    public CaptureHandle start(CaptureRequest request, String options) {
        StringBuilder args = new StringBuilder();
        if (request.timeoutSeconds() > 0) args.append("--timeout ").append(request.timeoutSeconds()).append(' ');
        if (!request.jfrSampling()) args.append("--no-jfr ");
        args.append(request.gpuSections() ? "--gpu sections " : "--gpu frame ");
        if (request.heapAtEnd()) args.append("--deep-memory ");
        if (options != null && !options.isBlank()) args.append(options.trim());
        CaptureOptions parsed = CaptureOptions.parse(args.toString().trim());
        CaptureHandle handle = new CaptureHandle();
        boolean started = Lightning.recorder().start(parsed, new Recorder.Listener() {
            @Override
            public void started(String message) {}

            @Override
            public void saved(Exporter.Result result) {
                handle.result.complete(toResult(result));
                Consumer<Exporter.Result> notify = savedNotifier;
                if (!request.quiet() && notify != null) notify.accept(result);
            }

            @Override
            public void failed(String message) {
                handle.result.completeExceptionally(message.contains("cancelled")
                        ? new CancellationException(message) : new IllegalStateException(message));
            }
        }, request.outputDir(), request.exportViewer(), request.label());
        if (!started) throw new IllegalStateException("Lightning is busy: " + Lightning.recorder().status());
        return handle;
    }

    /**
     * Rebuilds a report from a capture that did not finish (a crash). {@code dir} is the capture's
     * temporary folder ({@code <game>/lightning/tmp/<id>}). Empty if there is nothing to recover there.
     * Lightning also recovers unfinished captures by itself when the game next starts.
     */
    public Optional<CompletableFuture<CaptureResult>> recover(Path dir) {
        if (!Recovery.isRecoverable(dir)) return Optional.empty();
        return Optional.of(CompletableFuture.supplyAsync(() -> {
            try {
                return toResult(Recovery.recover(dir, Lightning.recorder().capturesDirectory()));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }));
    }

    /**
     * Adds a step to the loading timeline shown in start-up and world-join reports, at a time you
     * measured yourself, in milliseconds since the JVM started
     * ({@code ManagementFactory.getRuntimeMXBean().getUptime()} at that moment). {@code phase}
     * true: a step that lasts until the next one ("Baking models"); false: a moment ("Atlas
     * stitched"). Safe from any thread, also before a capture starts.
     */
    public void loadingStep(String name, double msSinceJvmStart, boolean phase) {
        dev.lightning.capture.LoadTimeline.addAt(name, msSinceJvmStart, phase);
    }

    /** {@link #loadingStep(String, double, boolean)} for right now. */
    public void loadingStep(String name, boolean phase) {
        if (phase) dev.lightning.capture.LoadTimeline.phase(name);
        else dev.lightning.capture.LoadTimeline.milestone(name);
    }

    /** Milliseconds since the JVM started, on Lightning's clock (the same one the report uses). */
    public double msSinceJvmStart() {
        return dev.lightning.capture.LoadTimeline.sinceJvmStartMillis(System.nanoTime());
    }

    /** Parts that {@code --only} accepts: gpu, render, ticks, world, cpu, alloc, gc, io, heap, deep, load. */
    public java.util.List<String> modules() {
        return CaptureOptions.MODULES;
    }

    static CaptureResult toResult(Exporter.Result r) {
        Path results = r.archive().resolveSibling(r.archive().getFileName().toString().replace(".lightning", ".results.json"));
        double overhead = 0, background = 0;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(results)).getAsJsonObject().getAsJsonObject("overhead");
            overhead = o.get("gameThreadPct").getAsDouble();
            background = o.get("backgroundCores").getAsDouble();
        } catch (Exception ignored) {
            // Older or partial results: leave the estimates at 0.
        }
        return new CaptureResult(r.archive(), results, r.html(), overhead, background);
    }
}
