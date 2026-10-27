package dev.lightning;

import com.google.gson.JsonObject;
import dev.lightning.capture.Capture;
import dev.lightning.capture.LoadTimeline;
import dev.lightning.capture.StartupCapture;
import dev.lightning.export.Downloads;
import dev.lightning.export.Exporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/** Client side: commands, the chat completion message, file actions and video/GPU details. */
final class LightningClient {
    private static final Map<String, Exporter.Result> results = new ConcurrentHashMap<>();
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lightning file actions");
        t.setDaemon(true);
        return t;
    });
    private static volatile String glRenderer = "", glVendor = "", glVersion = "";

    private LightningClient() {}

    static void init(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new);
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> LightningCommands.registerClient(e.getDispatcher(), (c, client) -> new ChatFeedback()));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> onRenderThread());
        if (Boolean.getBoolean("lightning.smoke")) {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> onRenderThread());
        }
        Lightning.clientEnvironment = LightningClient::environment;
        dev.lightning.api.LightningApi.savedNotifier = result -> new ChatFeedback().saved(result);
        if (Boolean.getBoolean("lightning.smokeWorld")) {
            // Development check (run config "clientSmokeWorld"): join the test world, record, quit.
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn e) -> worldSmoke());
        }
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn e) ->
                Lightning.autoStart(msg -> chat(Component.literal(msg).withStyle(ChatFormatting.GOLD)),
                        () -> Minecraft.getInstance().execute(Minecraft.getInstance()::stop)));
        loadingSteps(modBus);
    }

    /** Start-up capture (from the JVM's start) and world-join captures; see {@link StartupCapture}. */
    static void installStartupCapture(dev.lightning.capture.Recorder recorder) {
        StartupCapture.install(recorder, true, LightningClient::savedWhenInWorld,
                msg -> org.slf4j.LoggerFactory.getLogger("Lightning").info(msg),
                () -> Minecraft.getInstance().execute(Minecraft.getInstance()::stop),
                () -> LightningConfig.loaded() && LightningConfig.CAPTURE_JOINS.get());
    }

    private static volatile Exporter.Result pendingSaved;
    private static boolean firstWorldFrame;

    /** The completion message, now if a world is open, otherwise once the player joins one. */
    private static void savedWhenInWorld(Exporter.Result result) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) new ChatFeedback().saved(result);
            else pendingSaved = result;
        });
    }

    /** Loading steps on the client: setup, title screen, and each world join's stages. */
    private static void loadingSteps(IEventBus modBus) {
        modBus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, (net.neoforged.fml.event.lifecycle.FMLClientSetupEvent e) ->
                LoadTimeline.milestone("Client setup"));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post e) -> {
            if (e.getScreen() instanceof net.minecraft.client.gui.screens.TitleScreen && Minecraft.getInstance().getOverlay() == null
                    && LoadTimeline.firstTime("title")) {
                LoadTimeline.phase("Title screen");
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn e) -> {
            StartupCapture.loggedIn();
            firstWorldFrame = true;
            Exporter.Result waiting = pendingSaved;
            if (waiting != null) {
                pendingSaved = null;
                new ChatFeedback().saved(waiting);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post e) -> {
            if (firstWorldFrame && Minecraft.getInstance().level != null) {
                firstWorldFrame = false;
                if (StartupCapture.joining()) LoadTimeline.milestone("First world frame");
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> {
            if (!StartupCapture.joining()) return;
            Minecraft mc = Minecraft.getInstance();
            var screen = mc.screen;
            boolean loadingScreen = screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                    || screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                    || screen instanceof net.minecraft.client.gui.screens.ConnectScreen
                    || screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen
                    || screen instanceof net.minecraft.client.gui.screens.ProgressScreen;
            if (mc.player != null && mc.level != null && !loadingScreen && mc.getOverlay() == null) StartupCapture.worldShown();
        });
    }

    /** Runs on the render thread with the GL context current. */
    private static void onRenderThread() {
        if (Capture.renderThread == null) firstRenderThreadCall();
        boolean worldSmoke = Boolean.getBoolean("lightning.smokeWorld") || Boolean.getBoolean("lightning.smokeStartup");
        if (Boolean.getBoolean("lightning.smoke") || worldSmoke) {
            if (!smokeMinimized) {
                smokeMinimized = true;
                // Test clients must stay silent and out of the way. A world test needs a real-size
                // framebuffer to render, so its window is hidden rather than minimised.
                Minecraft.getInstance().options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
                long window = Minecraft.getInstance().getWindow().getWindow();
                if (worldSmoke) org.lwjgl.glfw.GLFW.glfwHideWindow(window);
                else org.lwjgl.glfw.GLFW.glfwIconifyWindow(window);
            }
            if (!worldSmoke) smokeTest();
        }
    }

    private static boolean smokeStarted, smokeMinimized;

    /**
     * Development check (run config "clientSmoke"): records five seconds at the first menu with
     * GPU timing, saves, writes the outcome to {@code lightning-smoke.txt} and quits.
     */
    private static void smokeTest() {
        Minecraft mc = Minecraft.getInstance();
        if (smokeStarted || mc.getOverlay() != null || mc.screen == null) return;
        smokeStarted = true;
        Path out = mc.gameDirectory.toPath().resolve("lightning-smoke.txt");
        java.util.function.Consumer<String> finish = text -> {
            try {
                Files.writeString(out, text);
            } catch (java.io.IOException ignored) {
            }
            mc.execute(mc::stop);
        };
        Lightning.recorder().start(dev.lightning.capture.CaptureOptions.parse("--timeout 5"), new dev.lightning.capture.Recorder.Listener() {
            @Override
            public void started(String message) {}

            @Override
            public void saved(Exporter.Result result) {
                finish.accept("OK " + result.archive() + System.lineSeparator() + result.html());
            }

            @Override
            public void failed(String message) {
                finish.accept("FAILED " + message);
            }
        });
    }

    /** Runs a step on the render thread and waits for it, plus a pause for the screen to update. */
    private static void onRender(Runnable step, long pauseMillis) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        mc.execute(() -> {
            try {
                step.run();
            } finally {
                done.complete(null);
            }
        });
        done.join();
        Thread.sleep(pauseMillis);
    }

    private static void screenshot(String name) throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();
        onRender(() -> net.minecraft.client.Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {}), 500);
    }

    /** Development only (-Dlightning.smokeShots=true): screenshots of the commands and the completion message. */
    private static void screenshotFlow() throws InterruptedException {
        Minecraft mc = Minecraft.getInstance();
        Path out = mc.gameDirectory.toPath().resolve("lightning-smoke.txt");
        // Daytime, clear sky: the test world is otherwise dark.
        var server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                server.overworld().setDayTime(6000);
                server.overworld().setWeatherParameters(6000, 0, false, false);
            });
        }
        Thread.sleep(3000);
        onRender(() -> {
            var screen = new net.minecraft.client.gui.screens.ChatScreen("/lightning profiler start ");
            mc.setScreen(screen);
            // Typing a character (rather than pasting the text) makes Minecraft show its suggestion list.
            screen.charTyped('-', 0);
        }, 1500);
        screenshot("lightning-commands.png");
        onRender(() -> mc.setScreen(null), 300);
        java.util.concurrent.CompletableFuture<Exporter.Result> saved = new java.util.concurrent.CompletableFuture<>();
        ChatFeedback chat = new ChatFeedback();
        Lightning.recorder().start(dev.lightning.capture.CaptureOptions.parse("--timeout 6"), new dev.lightning.capture.Recorder.Listener() {
            @Override
            public void started(String message) {
                chat.message(Component.literal(message).withStyle(ChatFormatting.GOLD));
            }

            @Override
            public void saved(Exporter.Result result) {
                chat.saved(result);
                saved.complete(result);
            }

            @Override
            public void failed(String message) {
                saved.completeExceptionally(new IllegalStateException(message));
            }
        });
        Exporter.Result result = saved.join();
        Thread.sleep(800);
        onRender(() -> mc.setScreen(new net.minecraft.client.gui.screens.ChatScreen("")), 1000);
        screenshot("lightning-saved.png");
        write(out, "OK " + result.archive() + System.lineSeparator() + result.html());
        mc.execute(mc::stop);
    }

    private static void worldSmoke() {
        Minecraft mc = Minecraft.getInstance();
        if (Boolean.getBoolean("lightning.smokeShots")) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(8000);
                    screenshotFlow();
                } catch (Exception e) {
                    write(mc.gameDirectory.toPath().resolve("lightning-smoke.txt"), "FAILED " + e);
                    mc.execute(mc::stop);
                }
            }, "Lightning screenshots");
            t.setDaemon(true);
            t.start();
            return;
        }
        Path out = mc.gameDirectory.toPath().resolve("lightning-smoke.txt");
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000); // let chunks and shaders load
            } catch (InterruptedException e) {
                return;
            }
            Lightning.recorder().start(dev.lightning.capture.CaptureOptions.parse("--timeout 10"), new dev.lightning.capture.Recorder.Listener() {
                @Override
                public void started(String message) {}

                @Override
                public void saved(Exporter.Result result) {
                    write(out, "OK " + result.archive() + System.lineSeparator() + result.html());
                    mc.execute(mc::stop);
                }

                @Override
                public void failed(String message) {
                    write(out, "FAILED " + message);
                    mc.execute(mc::stop);
                }
            });
        }, "Lightning world smoke");
        t.setDaemon(true);
        t.start();
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text);
        } catch (java.io.IOException ignored) {
        }
    }

    private static void firstRenderThreadCall() {
        Capture.renderThread = Thread.currentThread();
        try {
            glRenderer = String.valueOf(GL11.glGetString(GL11.GL_RENDERER));
            glVendor = String.valueOf(GL11.glGetString(GL11.GL_VENDOR));
            glVersion = String.valueOf(GL11.glGetString(GL11.GL_VERSION));
        } catch (RuntimeException ignored) {
            // No context: leave the GPU description empty.
        }
    }

    private static void environment(JsonObject env) {
        Minecraft mc = Minecraft.getInstance();
        env.addProperty("gpuRenderer", glRenderer);
        env.addProperty("gpuVendor", glVendor);
        env.addProperty("openGl", glVersion);
        env.addProperty("context", mc.level == null ? "Menu (no world)"
                : mc.hasSingleplayerServer() ? "Single-player (integrated server in the same process)"
                : "Multiplayer client (server not profiled)");
        var o = mc.options;
        env.addProperty("fpsLimit", o.framerateLimit().get());
        env.addProperty("vsync", o.enableVsync().get());
        env.addProperty("renderDistance", o.renderDistance().get());
        env.addProperty("simulationDistance", o.simulationDistance().get());
        env.addProperty("graphics", o.graphicsMode().get().toString());
        env.addProperty("guiScale", o.guiScale().get());
        env.addProperty("window", mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
        env.addProperty("fullscreen", mc.getWindow().isFullscreen());
        var target = mc.getMainRenderTarget();
        if (target != null) {
            env.addProperty("framebufferWidth", target.width);
            env.addProperty("framebufferHeight", target.height);
        }
        shaderInfo(env, mc.gameDirectory.toPath());
    }

    /** Active shader pack and its changed options, read from Iris's own files (no Iris API needed). */
    private static void shaderInfo(JsonObject env, Path game) {
        for (String name : new String[] {"iris.properties", "oculus.properties"}) {
            Path config = game.resolve("config").resolve(name);
            if (!Files.isRegularFile(config)) continue;
            try (var in = Files.newBufferedReader(config)) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                boolean enabled = Boolean.parseBoolean(p.getProperty("enableShaders", "false"));
                String pack = p.getProperty("shaderPack", "");
                env.addProperty("shadersEnabled", enabled);
                if (!pack.isEmpty()) env.addProperty("shaderPack", pack);
                if (p.getProperty("maxShadowRenderDistance") != null) env.addProperty("shaderMaxShadowDistance", p.getProperty("maxShadowRenderDistance"));
                Path options = game.resolve("shaderpacks").resolve(pack + ".txt");
                if (!pack.isEmpty() && Files.isRegularFile(options) && Files.size(options) < 64 * 1024) {
                    String text = Files.readString(options).strip();
                    if (!text.isEmpty()) env.addProperty("shaderOptions", text);
                }
            } catch (java.io.IOException | RuntimeException ignored) {
                // Optional detail.
            }
            return;
        }
    }

    private static void chat(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.getChat().addMessage(text));
    }

    private static final class ChatFeedback implements LightningCommands.Feedback {
        @Override
        public void message(Component text) {
            chat(text);
        }

        @Override
        public void saved(Exporter.Result result) {
            results.put(result.id(), result);
            MutableComponent line = Component.literal("⚡ Capture saved. ").withStyle(ChatFormatting.GOLD);
            line.append(button("Open in browser", "/lightning open " + result.id(), "Open the interactive report"));
            line.append(Component.literal(" "));
            line.append(button("Download file", "/lightning download " + result.id(), "Save the .lightning file to your Downloads folder"));
            chat(line);
        }
    }

    private static Component button(String label, String command, String hint) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(ChatFormatting.YELLOW)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hint))));
    }

    static int fileAction(String action, String id) {
        FILES.execute(() -> {
            try {
                Exporter.Result result = find(id);
                if (action.equals("open")) {
                    Util.getPlatform().openFile(result.html().toFile());
                } else {
                    Path copy = Downloads.copy(result.archive(), Downloads.directory());
                    Util.getPlatform().openFile(copy.getParent().toFile());
                }
            } catch (Exception e) {
                chat(Component.literal("⚡ Could not " + action + " the capture: " + e.getMessage()).withStyle(ChatFormatting.RED));
            }
        });
        return 1;
    }

    private static Exporter.Result find(String id) throws java.io.IOException {
        Exporter.Result known = results.get(id);
        if (known != null && Files.exists(known.archive())) return known;
        if (!id.matches("[0-9A-Za-z_.-]+")) throw new java.io.IOException("invalid capture id");
        Path dir = Lightning.recorder().capturesDirectory();
        Path archive = dir.resolve("lightning-" + id + ".lightning");
        if (!Files.exists(archive)) throw new java.io.IOException("no capture named " + id + " in " + dir);
        return new Exporter.Result(id, archive, dir.resolve("lightning-" + id + ".html"), "");
    }
}
