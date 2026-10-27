package dev.lightning.capture;

import java.util.function.Supplier;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

/**
 * Receives Minecraft's own profiler calls ({@code push("terrain")}, {@code popPush("entities")},
 * ...) on every thread that uses them, and records them as timed sections. Vanilla and many
 * mods already mark hundreds of phases of each frame and tick this way, so this one bridge
 * gives a full, exact breakdown without per-method instrumentation.
 *
 * <p>Installed only while a capture runs (see {@code SingleTickProfilerMixin}); each call
 * resolves the calling thread's track, so dimension threads and other callers are separated.
 */
public final class SectionProfiler implements ProfilerFiller {
    public static final SectionProfiler INSTANCE = new SectionProfiler();

    /** Set the first time vanilla's profiler is observed while recording; reported as coverage. */
    public static volatile boolean observed;

    private SectionProfiler() {}

    /** Client frames: tees the vanilla filler with this one when recording frames; otherwise returns it unchanged. */
    public static ProfilerFiller wrapClient(ProfilerFiller vanilla) {
        Capture c = Capture.ACTIVE;
        if (c == null || !c.options.render()) return vanilla;
        observed = true;
        return ProfilerFiller.tee(vanilla, INSTANCE);
    }

    /** Server ticks: the same, when recording ticks. */
    public static ProfilerFiller wrapServer(ProfilerFiller vanilla) {
        Capture c = Capture.ACTIVE;
        if (c == null || !c.options.ticks()) return vanilla;
        observed = true;
        return ProfilerFiller.tee(vanilla, INSTANCE);
    }

    @Override
    public void startTick() {
        Track t = Capture.track();
        if (t != null) t.tickStart();
    }

    @Override
    public void endTick() {
        Track t = Capture.track();
        if (t != null) t.tickEnd();
    }

    @Override
    public void push(String name) {
        Track t = Capture.track();
        if (t != null) t.push(name);
    }

    @Override
    public void push(Supplier<String> name) {
        Track t = Capture.track();
        if (t != null) t.push(name);
    }

    @Override
    public void pop() {
        Track t = Capture.track();
        if (t != null) t.pop();
    }

    @Override
    public void popPush(String name) {
        Track t = Capture.track();
        if (t != null) {
            t.pop();
            t.push(name);
        }
    }

    @Override
    public void popPush(Supplier<String> name) {
        Track t = Capture.track();
        if (t != null) {
            t.pop();
            t.push(name);
        }
    }

    @Override
    public void markForCharting(MetricCategory category) {}

    @Override
    public void incrementCounter(String name, int amount) {}

    @Override
    public void incrementCounter(Supplier<String> name, int amount) {}
}
