package dev.lightning.capture;

import java.util.List;
import java.util.Locale;

/** Settings for one capture, parsed from the command line of {@code /lightning profiler start}. */
public record CaptureOptions(
        int timeoutSeconds,
        GpuTimer.Mode gpu,
        int gpuDepth,
        int gpuMaxSections,
        int gpuEvery,
        boolean jfr,
        int sampleIntervalMillis,
        boolean nativeSamples,
        boolean allocations,
        boolean world,
        boolean heap,
        boolean deepMemory,
        boolean loading,
        boolean render,
        boolean ticks,
        boolean cpu,
        boolean gc,
        boolean io,
        boolean threads) {

    public static final int MAX_SECONDS = 30 * 60;

    /** Parts of a capture that can be chosen with {@code --only} or {@code -Dlightning.capture=}. */
    public static final List<String> MODULES = List.of("gpu", "render", "ticks", "world", "cpu", "alloc", "gc", "threads", "io", "heap", "deep", "load");

    /** These options plus loading detail (the start-up and world-join captures always need it, and JFR with it). */
    public CaptureOptions withLoading() {
        return new CaptureOptions(timeoutSeconds, gpu, gpuDepth, gpuMaxSections, gpuEvery, true, sampleIntervalMillis, nativeSamples, allocations, world,
                heap, deepMemory, true, render, ticks, cpu, gc, io, threads);
    }

    /** The parts this capture records, in {@link #MODULES} order. */
    public List<String> modules() {
        List<String> out = new java.util.ArrayList<>();
        if (gpu != GpuTimer.Mode.OFF) out.add("gpu");
        if (render) out.add("render");
        if (ticks) out.add("ticks");
        if (world) out.add("world");
        if (cpu) out.add("cpu");
        if (allocations) out.add("alloc");
        if (gc) out.add("gc");
        if (threads) out.add("threads");
        if (io) out.add("io");
        if (heap) out.add("heap");
        if (deepMemory) out.add("deep");
        if (loading) out.add("load");
        return out;
    }

    public static CaptureOptions defaults() {
        try {
            if (dev.lightning.LightningConfig.loaded()) {
                return new CaptureOptions(dev.lightning.LightningConfig.TIMEOUT.get(), dev.lightning.LightningConfig.GPU.get(), 6, 160, dev.lightning.LightningConfig.GPU_EVERY.get(),
                        dev.lightning.LightningConfig.JAVA_SAMPLING.get(), dev.lightning.LightningConfig.SAMPLE_INTERVAL.get(), true,
                        dev.lightning.LightningConfig.MEMORY_TRACKING.get(), dev.lightning.LightningConfig.WORLD_TIMING.get(),
                        dev.lightning.LightningConfig.HEAP_AT_END.get(), false, false, true, true, true, true, false, true);
            }
        } catch (RuntimeException | LinkageError notAvailable) {
            // Outside the game (tests): built-in defaults.
        }
        return new CaptureOptions(0, GpuTimer.Mode.SECTIONS, 6, 160, 4, true, 20, true, true, true, false, false, false, true, true, true, true, false, true);
    }

    /** An option shown as a suggestion while typing the command. */
    public record Option(String name, String description, List<String> values) {}

    /** Everything is recorded by default; these options change that. */
    public static final List<Option> OPTIONS = List.of(
            new Option("--timeout", "Stop and save automatically after this many seconds (default: run until /lightning profiler stop)",
                    List.of("30", "60", "120", "300")),
            new Option("--minimal", "Lightest recording: frame and tick timings only (no GPU, Java code or mob/machine timing)", List.of()),
            new Option("--no-gpu", "Don't time the graphics card", List.of()),
            new Option("--gpu", "How much graphics card detail to record (default: sections)", List.of("sections", "frame", "off")),
            new Option("--no-java", "Don't sample which Java code / mods are running (CPU and memory breakdown)", List.of()),
            new Option("--no-alloc", "Don't track what is using memory", List.of()),
            new Option("--no-world", "Don't time individual mobs, machines and chunks", List.of()),
            new Option("--all", "Everything on, including the full memory breakdown at the end (same as /lightning report)", List.of()),
            new Option("--deep-memory", "At the end, work out which mod holds which memory (game freezes a few seconds; needs free disk space)", List.of()),
            new Option("--heap", "At the end, take a snapshot of what is filling memory, by mod (pauses the game briefly)", List.of()),
            new Option("--gpu-every", "Time graphics-card steps on every Nth frame (default 4; 1 = every frame, more detail, more cost)", List.of("1", "4", "8")),
            new Option("--only", "Record only these parts, e.g. gpu,render,cpu for a shader test (parts: gpu, render, ticks, world, cpu, alloc, gc, threads, io, heap, deep, load)",
                    List.of("gpu,render,cpu", "render,cpu,alloc,gc", "ticks,world,cpu", "gpu,render,ticks,cpu,alloc,gc")),
            new Option("--io", "Also record slow file and network reads/writes (over 1 ms)", List.of()),
            new Option("--interval", "How often to sample Java code, in milliseconds (default 20; lower = more detail, more cost)",
                    List.of("10", "20", "50")));

    public static final String HELP = "Options: " + String.join(", ", OPTIONS.stream().map(Option::name).toList())
            + ". Type a space after \"start\" to see them with descriptions.";

    /** Command-line form that {@link #parse} turns back into these options (for crash recovery). */
    public static String toArgs(CaptureOptions o) {
        StringBuilder b = new StringBuilder();
        if (o.timeoutSeconds() > 0) b.append("--timeout ").append(o.timeoutSeconds()).append(' ');
        b.append("--gpu ").append(o.gpu().name().toLowerCase(Locale.ROOT)).append(' ');
        b.append("--gpu-depth ").append(o.gpuDepth()).append(' ');
        b.append("--gpu-every ").append(o.gpuEvery()).append(' ');
        b.append("--interval ").append(o.sampleIntervalMillis()).append(' ');
        if (!o.jfr()) b.append("--no-jfr ");
        if (!o.nativeSamples()) b.append("--no-native ");
        if (!o.allocations()) b.append("--no-alloc ");
        if (!o.world()) b.append("--no-world ");
        if (o.heap()) b.append("--heap ");
        if (o.deepMemory()) b.append("--deep-memory ");
        if (o.loading()) b.append("--loading ");
        b.append("--only ").append(String.join(",", o.modules())).append(' ');
        return b.toString().trim();
    }

    /** Plain list of what this capture records, for the start message. */
    public String describe() {
        List<String> parts = new java.util.ArrayList<>();
        if (render && ticks) parts.add("frame & tick timings");
        else if (render) parts.add("frame timings");
        else if (ticks) parts.add("tick timings");
        if (gpu != GpuTimer.Mode.OFF) parts.add("GPU");
        if (cpu) parts.add(allocations ? "Java code & memory" : "Java code");
        else if (allocations) parts.add("memory use by mod");
        if (gc) parts.add("garbage collection");
        if (io) parts.add("file & network waits");
        if (world) parts.add("mobs, machines & chunks");
        if (heap) parts.add("memory snapshot at the end");
        if (deepMemory) parts.add("full memory breakdown at the end");
        if (loading) parts.add("loading steps, class loading & main-thread waits");
        parts.add("logs");
        return String.join(", ", parts);
    }

    /** Suggestions for the next word, given what has been typed after "start". */
    public static List<Option> suggest(String typed) {
        String[] words = typed.isEmpty() ? new String[0] : typed.split("\\s+", -1);
        String previous = words.length >= 2 ? words[words.length - 2].toLowerCase(Locale.ROOT) : "";
        for (Option o : OPTIONS) {
            if (o.name.equals(previous) && !o.values.isEmpty()) {
                return o.values.stream().map(v -> new Option(v, o.name + " " + v, List.of())).toList();
            }
        }
        java.util.Set<String> used = new java.util.HashSet<>(java.util.Arrays.asList(words));
        return OPTIONS.stream().filter(o -> !used.contains(o.name)).toList();
    }

    public static CaptureOptions parse(String text) {
        CaptureOptions o = defaults();
        if (text == null || text.isBlank()) return o;
        // "--gpu-every=1" is the same as "--gpu-every 1" (launch scripts that split arguments on spaces need it).
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String t : text.trim().split("\\s+")) {
            int eq = t.indexOf('=');
            if (t.startsWith("--") && eq > 2) {
                tokens.add(t.substring(0, eq));
                tokens.add(t.substring(eq + 1));
            } else {
                tokens.add(t);
            }
        }
        String[] args = tokens.toArray(new String[0]);
        int timeout = o.timeoutSeconds, gpuDepth = o.gpuDepth, interval = o.sampleIntervalMillis, gpuEvery = o.gpuEvery;
        GpuTimer.Mode gpu = o.gpu;
        boolean jfr = o.jfr, nativeSamples = o.nativeSamples, alloc = o.allocations, world = o.world, heap = o.heap, deep = o.deepMemory, loading = o.loading;
        boolean render = o.render, ticks = o.ticks, cpu = o.cpu, gc = o.gc, io = o.io, threads = o.threads;
        for (int i = 0; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            switch (a) {
                case "--timeout", "-t" -> timeout = intArg(args, ++i, a, 1, MAX_SECONDS);
                case "--interval" -> interval = intArg(args, ++i, a, 1, 1000);
                case "--gpu-depth" -> gpuDepth = intArg(args, ++i, a, 0, 32);
                case "--gpu-every" -> gpuEvery = intArg(args, ++i, a, 1, 120);
                case "--gpu" -> {
                    String v = i + 1 < args.length ? args[++i].toLowerCase(Locale.ROOT) : "";
                    gpu = switch (v) {
                        case "off" -> GpuTimer.Mode.OFF;
                        case "frame" -> GpuTimer.Mode.FRAME;
                        case "sections", "on" -> GpuTimer.Mode.SECTIONS;
                        default -> throw new IllegalArgumentException("--gpu takes off, frame or sections");
                    };
                }
                case "--no-gpu" -> gpu = GpuTimer.Mode.OFF;
                case "--no-jfr", "--no-java" -> {
                    threads = false;
                    cpu = false;
                    alloc = false;
                    gc = false;
                    io = false;
                }
                case "--no-native" -> nativeSamples = false;
                case "--no-alloc" -> alloc = false;
                case "--no-world" -> world = false;
                case "--heap" -> heap = true;
                case "--deep-memory" -> deep = true;
                case "--all" -> {
                    gpu = GpuTimer.Mode.SECTIONS;
                    render = true;
                    ticks = true;
                    cpu = true;
                    gc = true;
                    threads = true;
                    nativeSamples = true;
                    alloc = true;
                    world = true;
                    deep = true;
                }
                case "--no-heap" -> heap = false;
                // Game start-up and world joins (set by the start-up capture, not offered in chat).
                case "--loading" -> loading = true;
                case "--only", "--modules" -> {
                    // Exactly these parts and nothing else, e.g. "--only gpu,render,cpu" for a shader test.
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--only needs a list, e.g. gpu,render,cpu. Parts: " + String.join(", ", MODULES));
                    java.util.Set<String> on = new java.util.HashSet<>();
                    for (String m : args[++i].toLowerCase(Locale.ROOT).split("[,+]")) {
                        if (m.isBlank()) continue;
                        if (!MODULES.contains(m)) throw new IllegalArgumentException("Unknown part " + m + ". Parts: " + String.join(", ", MODULES));
                        on.add(m);
                    }
                    gpu = on.contains("gpu") ? (gpu == GpuTimer.Mode.OFF ? GpuTimer.Mode.SECTIONS : gpu) : GpuTimer.Mode.OFF;
                    render = on.contains("render") || on.contains("gpu");
                    ticks = on.contains("ticks");
                    world = on.contains("world");
                    cpu = on.contains("cpu");
                    alloc = on.contains("alloc");
                    gc = on.contains("gc");
                    io = on.contains("io");
                    threads = on.contains("threads");
                    heap = on.contains("heap");
                    deep = on.contains("deep");
                    loading = loading || on.contains("load");
                }
                case "--io" -> io = true;
                case "--minimal" -> {
                    threads = false;
                    cpu = false;
                    alloc = false;
                    gc = false;
                    io = false;
                    gpu = GpuTimer.Mode.OFF;
                    world = false;
                }
                default -> {
                    if (a.matches("\\d+")) timeout = intArg(args, i, "timeout", 1, MAX_SECONDS);
                    else throw new IllegalArgumentException("Unknown option " + args[i] + ". " + HELP);
                }
            }
        }
        if (gpu != GpuTimer.Mode.OFF) render = true; // GPU timers ride on the render thread's sections
        jfr = cpu || alloc || gc || io || threads || loading;
        return new CaptureOptions(timeout, gpu, gpuDepth, o.gpuMaxSections, gpuEvery, jfr, interval, nativeSamples && cpu, alloc, world, heap && !deep, deep, loading,
                render, ticks, cpu, gc, io, threads);
    }

    private static int intArg(String[] args, int i, String name, int min, int max) {
        if (i >= args.length) throw new IllegalArgumentException(name + " needs a number");
        try {
            int v = Integer.parseInt(args[i]);
            if (v < min || v > max) throw new IllegalArgumentException(name + " must be " + min + "–" + max);
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " needs a number, got " + args[i]);
        }
    }
}
