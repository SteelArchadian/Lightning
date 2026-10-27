package dev.lightning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lightning.capture.LoadTimeline;
import dev.lightning.capture.LogCapture;
import dev.lightning.capture.StartupCapture;
import dev.lightning.capture.Recorder;
import java.lang.management.ManagementFactory;
import java.util.function.Consumer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@Mod(Lightning.MOD_ID)
public final class Lightning {
    public static final String MOD_ID = "lightning";
    public static final String VERSION = version();

    private static String version() {
        try {
            return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("dev");
        } catch (RuntimeException | LinkageError e) {
            return "dev";
        }
    }

    private static Recorder recorder;
    /** Client-only details (GPU, video settings), installed by the client entry point. */
    static volatile Consumer<JsonObject> clientEnvironment;

    public Lightning(IEventBus modBus, Dist dist, net.neoforged.fml.ModContainer container) {
        LoadTimeline.milestone("Lightning loaded");
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, LightningConfig.SPEC);
        recorder = new Recorder(FMLPaths.GAMEDIR.get().resolve("lightning"), Lightning::environment);
        LogCapture.install();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("Lightning");
        if (dist.isClient()) {
            LightningClient.installStartupCapture(recorder);
        } else {
            StartupCapture.install(recorder, false, result -> {}, log::info,
                    () -> { var server = ServerLifecycleHooks.getCurrentServer(); if (server != null) server.halt(false); }, () -> false);
        }
        recorder.recoverPending(result -> {});
        // Config is loaded by common setup; the warm-up still runs during loading. Not during a
        // start-up capture, which already has JFR running.
        modBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) -> {
            LoadTimeline.milestone("Common setup");
            if (LightningConfig.PREPARE_AT_LOAD.get() && !StartupCapture.running()) dev.lightning.capture.JfrWarmup.startDuringLoading();
        });
        modBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent e) ->
                LoadTimeline.milestone("Mod loading complete"));
        // Closing the game (or a dedicated server) mid-capture saves the capture rather than leaving it to crash recovery.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent e) -> recorder.saveBeforeExit(90_000));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent e) -> {
            if (e.getServer().isDedicatedServer()) recorder.saveBeforeExit(90_000);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent e) -> {
            if (e.getServer().isDedicatedServer()) LoadTimeline.phase("Starting the server");
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) -> {
            if (e.getServer().isDedicatedServer()) {
                LoadTimeline.phase("Server running");
            } else {
                LoadTimeline.milestone("World server started");
                StartupCapture.integratedServerStarted();
            }
        });
        if (dist.isClient()) {
            LightningClient.init(modBus, container);
        } else {
            NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> LightningCommands.registerServer(e.getDispatcher()));
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent e) ->
                    autoStart(msg -> org.slf4j.LoggerFactory.getLogger("Lightning").info(msg), () -> e.getServer().halt(false)));
        }
    }

    public static Recorder recorder() {
        return recorder;
    }

    /**
     * Marks the start of a named phase in the running capture (for example "baseline" or "boss
     * fight"). The report then breaks results down per phase. Safe to call from any mod or thread;
     * does nothing when not recording.
     */
    public static boolean mark(String label) {
        return recorder != null && recorder.mark(label);
    }

    /**
     * Automatic capture for benchmarks: from the config, or {@code -Dlightning.autoStart=delay,seconds[,quit]}.
     * {@code quit} closes the game (client) or stops the server once the capture is saved.
     */
    static void autoStart(java.util.function.Consumer<String> say, Runnable quit) {
        int delay, duration;
        boolean exit;
        String prop = System.getProperty("lightning.autoStart", "");
        if (!prop.isBlank()) {
            String[] p = prop.split(",");
            delay = Integer.parseInt(p[0].trim());
            duration = p.length > 1 ? Integer.parseInt(p[1].trim()) : 120;
            exit = p.length > 2 && p[2].trim().equalsIgnoreCase("quit");
        } else if (LightningConfig.loaded() && LightningConfig.AUTO_START.get()) {
            delay = LightningConfig.AUTO_DELAY.get();
            duration = LightningConfig.AUTO_DURATION.get();
            exit = LightningConfig.AUTO_QUIT.get();
        } else {
            return;
        }
        if (autoStarted) return;
        autoStarted = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            dev.lightning.capture.CaptureOptions o = dev.lightning.capture.Automation.options("--timeout " + duration);
            recorder.start(o, new Recorder.Listener() {
                @Override
                public void started(String message) {
                    say.accept(message + " (automatic)");
                }

                @Override
                public void saved(dev.lightning.export.Exporter.Result result) {
                    dev.lightning.capture.Automation.copyResults(result);
                    say.accept("⚡ Automatic capture saved: " + result.archive());
                    if (exit) quit.run();
                }

                @Override
                public void failed(String message) {
                    say.accept(message);
                    if (exit) quit.run();
                }
            }, dev.lightning.capture.Automation.outputDir(), dev.lightning.capture.Automation.html(), dev.lightning.capture.Automation.label("auto"));
        }, "Lightning auto-start");
        t.setDaemon(true);
        t.start();
    }

    private static volatile boolean autoStarted;

    static JsonObject environment() {
        JsonObject env = new JsonObject();
        env.addProperty("minecraftVersion", net.minecraft.SharedConstants.getCurrentVersion().getName());
        ModList.get().getModContainerById("neoforge").ifPresent(c -> env.addProperty("neoForgeVersion", c.getModInfo().getVersion().toString()));
        env.addProperty("javaVersion", System.getProperty("java.version"));
        env.addProperty("javaVendor", System.getProperty("java.vendor"));
        env.addProperty("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        env.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        env.addProperty("processors", Runtime.getRuntime().availableProcessors());
        env.addProperty("maxHeapMb", Runtime.getRuntime().maxMemory() >> 20);
        // JVM tuning flags only; other arguments can contain local paths.
        JsonArray flags = new JsonArray();
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-XX:") || arg.startsWith("-Xm") || arg.startsWith("-Xs")) flags.add(arg);
        }
        env.add("jvmFlags", flags);
        JsonArray mods = new JsonArray();
        ModList.get().getMods().forEach(m -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getModId());
            o.addProperty("name", m.getDisplayName());
            o.addProperty("version", m.getVersion().toString());
            mods.add(o);
        });
        env.add("mods", mods);
        env.addProperty("modCount", mods.size());
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            env.addProperty("serverType", server.isDedicatedServer() ? "dedicated" : "integrated");
            env.addProperty("playersOnline", server.getPlayerCount());
        }
        Consumer<JsonObject> client = clientEnvironment;
        if (client != null) {
            try {
                client.accept(env);
            } catch (RuntimeException ignored) {
                // Best effort: settings can change while the game runs.
            }
        } else {
            env.addProperty("context", "Dedicated server");
        }
        return env;
    }
}
