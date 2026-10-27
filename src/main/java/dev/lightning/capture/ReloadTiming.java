package dev.lightning.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Times each listener of a resource or data pack reload: background (prepare) and main-thread
 * (apply) work, by timing the tasks it hands to each executor. Minecraft's own profiled reload
 * does the same but floods the log with profiler errors, so Lightning wraps the listeners itself.
 */
public final class ReloadTiming {
    private final String kind;
    private final long startNanos = System.nanoTime();
    private final List<Timed> timed = new ArrayList<>();

    private ReloadTiming(String kind) {
        this.kind = kind;
    }

    /** Not timing this reload: listeners pass through unchanged. */
    private static final ReloadTiming OFF = new ReloadTiming("");

    /**
     * Called once per reload, on the thread that creates it. Returns a timing that wraps listeners
     * only where they are handed their work, never in the list other mods inspect (Fabric API,
     * for example, looks for the RecipeManager by type in that list).
     */
    public static ReloadTiming begin() {
        String kind = LoadTimeline.takeReloadKind();
        return LoadTimeline.profileReloads() ? new ReloadTiming(kind) : OFF;
    }

    public boolean active() {
        return this != OFF;
    }

    /** The listener to hand to the reload step: a timing wrapper, or the listener itself when off. */
    public PreparableReloadListener track(PreparableReloadListener listener) {
        if (this == OFF) return listener;
        Timed t = new Timed(listener);
        synchronized (timed) {
            timed.add(t);
        }
        return t;
    }

    /** Called when the reload has finished. */
    public void done() {
        if (this == OFF) return;
        List<LoadTimeline.Listener> out = new ArrayList<>(timed.size());
        // A listener made from a lambda is named after its class plus "$$Lambda/0x…"; keep the class.
        synchronized (timed) {
            for (Timed t : timed) out.add(new LoadTimeline.Listener(t.getName().replaceAll("\\$\\$Lambda.*$", ""), t.prepare.get(), t.apply.get()));
        }
        LoadTimeline.reloadDone(kind, startNanos, out);
    }

    private static final class Timed implements PreparableReloadListener {
        final PreparableReloadListener inner;
        final AtomicLong prepare = new AtomicLong(), apply = new AtomicLong();

        Timed(PreparableReloadListener inner) {
            this.inner = inner;
        }

        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager, ProfilerFiller prepareProfiler,
                                              ProfilerFiller applyProfiler, Executor background, Executor main) {
            return inner.reload(barrier, manager, prepareProfiler, applyProfiler, timed(background, prepare), timed(main, apply));
        }

        private static Executor timed(Executor executor, AtomicLong total) {
            return task -> executor.execute(() -> {
                long t0 = System.nanoTime();
                try {
                    task.run();
                } finally {
                    total.addAndGet(System.nanoTime() - t0);
                }
            });
        }

        @Override
        public String getName() {
            return inner.getName();
        }
    }
}
