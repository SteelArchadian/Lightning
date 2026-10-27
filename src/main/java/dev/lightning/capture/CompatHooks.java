package dev.lightning.capture;

/**
 * Named sections from other mods' render code (Iris shader passes, Voxy LODs), recorded like
 * Minecraft's own profiler sections so they get CPU and GPU times. No-ops when not recording.
 */
public final class CompatHooks {
    public static volatile boolean irisObserved, voxyObserved;

    private CompatHooks() {}

    public static void push(String name) {
        Track t = Capture.track();
        if (t != null) t.pushCompat(name);
    }

    public static void pop() {
        Track t = Capture.track();
        if (t != null) t.popCompat();
    }
}
