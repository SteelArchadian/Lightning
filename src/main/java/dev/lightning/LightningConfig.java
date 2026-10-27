package dev.lightning;

import dev.lightning.capture.GpuTimer;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Settings editable in-game (e.g. through Configured). They are the defaults for
 * {@code /lightning profiler start}; options typed in the command still override them.
 * Values are read when a capture starts, so edits apply to the next capture.
 */
public final class LightningConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.EnumValue<GpuTimer.Mode> GPU;
    public static final ModConfigSpec.BooleanValue JAVA_SAMPLING;
    public static final ModConfigSpec.BooleanValue MEMORY_TRACKING;
    public static final ModConfigSpec.BooleanValue WORLD_TIMING;
    public static final ModConfigSpec.IntValue SAMPLE_INTERVAL;
    public static final ModConfigSpec.IntValue GPU_EVERY;
    public static final ModConfigSpec.IntValue TIMEOUT;
    public static final ModConfigSpec.BooleanValue PREPARE_AT_LOAD;
    public static final ModConfigSpec.BooleanValue HEAP_AT_END;
    public static final ModConfigSpec.BooleanValue AUTO_START;
    public static final ModConfigSpec.IntValue AUTO_DELAY;
    public static final ModConfigSpec.IntValue AUTO_DURATION;
    public static final ModConfigSpec.BooleanValue AUTO_QUIT;
    public static final ModConfigSpec.BooleanValue CAPTURE_JOINS;
    public static final ModConfigSpec.ConfigValue<String> CAPTURE_PARTS;
    public static final ModConfigSpec.IntValue WINDOW_SECONDS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("recording");
        GPU = b.comment("Graphics card timing: SECTIONS (per render step), FRAME (whole frame only) or OFF.")
                .defineEnum("gpu", GpuTimer.Mode.SECTIONS);
        GPU_EVERY = b.comment("Time graphics-card steps on every Nth frame. 1 = every frame (most detail; the timer calls cost some render-thread time).")
                .defineInRange("gpuEveryNthFrame", 4, 1, 120);
        JAVA_SAMPLING = b.comment("Sample which Java code and mods are using the CPU.")
                .define("javaSampling", true);
        MEMORY_TRACKING = b.comment("Track what is allocating memory (needs Java sampling).")
                .define("memoryTracking", true);
        WORLD_TIMING = b.comment("Time every mob, machine and block entity, by chunk.")
                .define("worldTiming", true);
        SAMPLE_INTERVAL = b.comment("How often Java code is sampled, in milliseconds. Lower = more detail, more cost.")
                .defineInRange("sampleIntervalMs", 20, 1, 1000);
        TIMEOUT = b.comment("Stop and save automatically after this many seconds. 0 = run until /lightning profiler stop.")
                .defineInRange("timeoutSeconds", 0, 0, 1800);
        HEAP_AT_END = b.comment("At the end of each capture, snapshot what is filling memory, by mod. Pauses the game briefly (about a second on big packs).")
                .define("heapSnapshotAtEnd", false);
        b.pop();
        b.push("automation");
        AUTO_START = b.comment("Start a capture automatically after joining a world (or after a dedicated server starts). For benchmarks.")
                .define("autoStart", false);
        AUTO_DELAY = b.comment("Seconds to wait after joining before the automatic capture starts.")
                .defineInRange("autoStartDelaySeconds", 30, 0, 3600);
        AUTO_DURATION = b.comment("Length of the automatic capture, in seconds.")
                .defineInRange("autoDurationSeconds", 120, 5, 1800);
        AUTO_QUIT = b.comment("Quit the game after the automatic capture has been saved.")
                .define("quitWhenDone", false);
        CAPTURE_JOINS = b.comment("Record every world join, from the click until the world shows, to see what makes joining slow.")
                .define("captureWorldJoins", false);
        CAPTURE_PARTS = b.comment("For automatic captures: record only these parts, comma-separated (gpu, render, ticks, world, cpu, alloc, gc, threads, io, heap, deep, load). Empty = everything. The JVM flag -Dlightning.capture wins.")
                .define("captureParts", "");
        WINDOW_SECONDS = b.comment("Split every capture into phases of this many seconds (0 = off). The JVM flag -Dlightning.windows wins.")
                .defineInRange("windowSeconds", 0, 0, 3600);
        b.pop();
        b.push("startup");
        PREPARE_AT_LOAD = b.comment("Prepare Java sampling while the game loads, so starting a capture later doesn't cause a few seconds of lag.")
                .define("prepareAtLoad", true);
        b.pop();
        SPEC = b.build();
    }

    private LightningConfig() {}

    public static boolean loaded() {
        return SPEC.isLoaded();
    }
}
