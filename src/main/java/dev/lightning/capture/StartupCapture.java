package dev.lightning.capture;

import dev.lightning.export.Exporter;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures of loading: game start-up from the JVM's first moment, and world joins from the click.
 *
 * <p>Start-up: {@code -Dlightning.startup=join} (default), {@code title} or {@code server}, plus
 * optionally a number of seconds to keep recording after the end point (default 10) and
 * {@code quit} to close the game once saved, e.g. {@code -Dlightning.startup=join,20,quit}. A
 * JFR recording started with the JVM ({@code -XX:StartFlightRecording=name=lightning,...}) is
 * used for the time before Lightning loads, and also turns the start-up capture on by itself.
 *
 * <p>World joins: config "captureWorldJoins" or {@code -Dlightning.join=true} records every join
 * from the click until the terrain is shown.
 */
public final class StartupCapture {
    private static final Logger LOG = LoggerFactory.getLogger("Lightning");

    private enum Until { TITLE, JOIN, SERVER }

    private static volatile Recorder recorder;
    private static volatile boolean running, startup, joinCapture, quit;
    private static volatile Until until = Until.JOIN;
    private static volatile int tailSeconds = 10;
    private static volatile boolean joining;
    private static volatile Consumer<Exporter.Result> saved = r -> {};
    private static volatile Consumer<String> say = LOG::info;
    private static volatile Runnable quitGame = () -> {};
    private static volatile java.util.function.BooleanSupplier joinsEnabled = () -> false;
    private static volatile Thread stopTimer;

    private StartupCapture() {}

    public static boolean running() {
        return running;
    }

    /** Whether a world join is in progress (between the click and the terrain showing). */
    public static boolean joining() {
        return joining;
    }

    /**
     * Called from the mod constructor. {@code client}: false on a dedicated server, where start-up
     * ends when the server is running. {@code savedTo}: shows the result to the player.
     */
    public static void install(Recorder r, boolean client, Consumer<Exporter.Result> savedTo, Consumer<String> sayTo, Runnable quitTheGame,
                               java.util.function.BooleanSupplier captureJoins) {
        recorder = r;
        saved = savedTo;
        say = sayTo;
        quitGame = quitTheGame;
        joinsEnabled = captureJoins;
        LoadTimeline.observer = StartupCapture::observe;
        String prop = System.getProperty("lightning.startup", "").trim().toLowerCase(Locale.ROOT);
        boolean external = JfrSession.startupRecording() != null;
        if (prop.equals("false") || prop.equals("off") || (prop.isEmpty() && !external)) return;
        until = client ? Until.JOIN : Until.SERVER;
        for (String part : prop.split("[,\\s]+")) {
            switch (part) {
                case "title" -> until = client ? Until.TITLE : Until.SERVER;
                case "join" -> until = client ? Until.JOIN : Until.SERVER;
                case "server" -> until = Until.SERVER;
                case "quit" -> quit = true;
                default -> {
                    if (part.matches("\\d{1,4}")) tailSeconds = Integer.parseInt(part);
                }
            }
        }
        startup = true;
        running = r.startLoading(Automation.options("--gpu frame").withLoading(), listener(), LoadTimeline.JVM_START_NANOS, Automation.label("Game start-up"));
        if (running) LOG.info("Lightning is recording game start-up (until {}{})", until.name().toLowerCase(Locale.ROOT), quit ? ", then quitting" : "");
    }

    private static Recorder.Listener listener() {
        return new Recorder.Listener() {
            @Override
            public void started(String message) {}

            @Override
            public void saved(Exporter.Result result) {
                boolean wasStartup = startup;
                running = startup = joinCapture = false;
                LOG.info("Lightning {} capture saved: {}", wasStartup ? "start-up" : "world join", result.archive());
                Automation.copyResults(result);
                saved.accept(result);
                if (wasStartup && quit) quitGame.run();
            }

            @Override
            public void failed(String message) {
                boolean wasStartup = startup;
                running = startup = joinCapture = false;
                LOG.warn("Lightning loading capture failed: {}", message);
                if (wasStartup && quit) quitGame.run();
            }
        };
    }

    private static void observe(LoadTimeline.Milestone m) {
        if (!running) return;
        String name = m.name();
        if (startup) {
            switch (until) {
                case TITLE -> {
                    if (name.equals("Title screen")) stopAfter(2);
                }
                case JOIN -> {
                    if (name.equals("In world")) stopAfter(tailSeconds);
                    // Nobody joins a world: save what start-up looked like after a minute on the menu.
                    else if (name.equals("Title screen")) stopUnlessJoining(60);
                }
                case SERVER -> {
                    if (name.equals("Server running")) stopAfter(Math.min(tailSeconds, 5));
                }
            }
        } else if (joinCapture && name.equals("In world")) {
            stopAfter(tailSeconds);
        }
    }

    private static synchronized void stopAfter(int seconds) {
        Thread previous = stopTimer;
        if (previous != null) previous.interrupt();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            Recorder r = recorder;
            if (running && r != null) r.stop(false);
        }, "Lightning loading capture stop");
        t.setDaemon(true);
        stopTimer = t;
        t.start();
    }

    private static void stopUnlessJoining(int seconds) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            Recorder r = recorder;
            if (running && startup && !joining && r != null) r.stop(false);
        }, "Lightning start-up capture watchdog");
        t.setDaemon(true);
        t.start();
    }

    // Loading steps, called from the game's hooks.

    /** The player clicked a world or server. */
    public static void joinStarted(boolean multiplayer) {
        joining = true;
        Recorder r = recorder;
        if (!running && r != null && r.state() == Recorder.State.IDLE && (Boolean.getBoolean("lightning.join") || safe(joinsEnabled))) {
            joinCapture = true;
            running = r.startLoading(Automation.options("").withLoading(), listener(), System.nanoTime(), Automation.label("World join"));
            if (!running) joinCapture = false;
        }
        LoadTimeline.phase(multiplayer ? "Joining: connecting to the server" : "Joining: starting the world");
    }

    /** The integrated server has finished starting (spawn area prepared). */
    public static void integratedServerStarted() {
        if (joining) LoadTimeline.phase("Joining: connecting");
    }

    public static void loggedIn() {
        if (joining) LoadTimeline.phase("Joining: loading terrain");
    }

    /** Once per client tick while joining: the loading screens are gone and the world shows. */
    public static void worldShown() {
        if (!joining) return;
        joining = false;
        LoadTimeline.phase("In world");
    }

    private static boolean safe(java.util.function.BooleanSupplier s) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void say(String message) {
        say.accept(message);
    }
}
