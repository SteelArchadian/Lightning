package dev.lightning.capture;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Loading steps of this game session: milestones (a moment, e.g. "client setup") and phases (a
 * step that lasts until the next phase, e.g. "Loading resources"), plus per-listener times of
 * every resource and data pack reload. Recorded whether or not a capture runs; it is a few dozen
 * entries per session. A running capture also gets each phase as a mark.
 */
public final class LoadTimeline {
    public record Milestone(long nanos, String name, boolean phase) {}

    public record Listener(String name, long prepareNanos, long applyNanos) {}

    /** One reload: resource packs (client assets) or data packs (server data), with each listener's time. */
    public record Reload(String kind, long startNanos, long endNanos, List<Listener> listeners) {}

    /** The JVM's start, in this JVM's nanoTime. */
    public static final long JVM_START_NANOS = jvmStartNanos();
    /** When Lightning was loaded (this class is first used by its constructor). */
    public static final long LIGHTNING_LOADED_NANOS = System.nanoTime();

    private static final List<Milestone> milestones = new CopyOnWriteArrayList<>();
    private static final List<Reload> reloads = new CopyOnWriteArrayList<>();
    private static final java.util.Set<String> once = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Notified of every milestone and phase (the start-up capture decides when to stop). */
    static volatile Consumer<Milestone> observer;

    private LoadTimeline() {}

    private static long jvmStartNanos() {
        try {
            long startEpochMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
            long epochNanosNow = System.currentTimeMillis() * 1_000_000L;
            return System.nanoTime() - (epochNanosNow - startEpochMillis * 1_000_000L);
        } catch (RuntimeException | LinkageError e) {
            return System.nanoTime();
        }
    }

    /** Milliseconds since the JVM started, for a nanoTime. */
    public static double sinceJvmStartMillis(long nanos) {
        return (nanos - JVM_START_NANOS) / 1e6;
    }

    /** A moment worth seeing on the loading timeline. Safe from any thread. */
    public static void milestone(String name) {
        add(new Milestone(System.nanoTime(), name, false));
    }

    /** Starts a loading phase: everything from now until the next phase belongs to it. */
    public static void phase(String name) {
        add(new Milestone(System.nanoTime(), name, true));
    }

    /** {@link #milestone} or {@link #phase} only the first time {@code key} is seen this session. */
    public static boolean firstTime(String key) {
        return once.add(key);
    }

    /** Adds a point given in milliseconds since JVM start (other mods' own timelines). */
    public static void addAt(String name, double msSinceJvmStart, boolean phase) {
        add(new Milestone(JVM_START_NANOS + Math.round(msSinceJvmStart * 1e6), name, phase));
    }

    static void addAtNanos(String name, long nanos, boolean phase) {
        add(new Milestone(nanos, name, phase));
    }

    private static void add(Milestone m) {
        milestones.add(m);
        if (m.phase()) {
            Capture c = Capture.ACTIVE;
            if (c != null && m.nanos() >= c.startNanos) c.markAt(m.nanos(), m.name());
        }
        Consumer<Milestone> o = observer;
        if (o != null) {
            try {
                o.accept(m);
            } catch (RuntimeException ignored) {
                // Never break loading for the timeline.
            }
        }
    }

    public static List<Milestone> milestones() {
        List<Milestone> out = new ArrayList<>(milestones);
        out.sort(java.util.Comparator.comparingLong(Milestone::nanos));
        return out;
    }

    public static List<Reload> reloads() {
        return List.copyOf(reloads);
    }

    static void reloadFinished(Reload reload) {
        reloads.add(reload);
    }

    /**
     * Whether reloads should be timed per listener (Minecraft's own profiled reload): during any
     * capture, and until the game has finished starting so the first reloads are always known.
     */
    public static boolean profileReloads() {
        return Capture.ACTIVE != null || StartupCapture.running() || !once.contains("title");
    }

    // Reload hooks (called from mixins).

    private static final ThreadLocal<String> nextReloadKind = new ThreadLocal<>();

    /** Called when the resource manager starts a resource-pack reload on this thread. */
    public static void resourceReloadStarting() {
        nextReloadKind.set("Resource packs");
        if (firstTime("resourceReload")) phase("Loading resources");
    }

    /** Kind of the reload being created on this thread ("Resource packs" or "Data packs"). */
    public static String takeReloadKind() {
        String kind = nextReloadKind.get();
        nextReloadKind.remove();
        return kind != null ? kind : "Data packs";
    }

    /** Called with Minecraft's profiled reload results. */
    public static void reloadDone(String kind, long startNanos, List<Listener> listeners) {
        reloadFinished(new Reload(kind, startNanos, System.nanoTime(), List.copyOf(listeners)));
        if (kind.equals("Resource packs") && firstTime("resourceReloadDone")) milestone("Resources loaded");
    }
}
