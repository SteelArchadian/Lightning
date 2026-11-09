package dev.lightning.capture;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

/**
 * GPU time per profiler section on the render thread, using OpenGL timestamp queries.
 *
 * <p>A timestamp is written into the command stream when a section opens and closes. Results
 * are read several frames later and only when the driver reports them available, so the CPU
 * never waits for the GPU. A GPU duration is the time between the GPU reaching the two
 * markers: it includes GPU idle gaps if the CPU submitted slowly, which is itself a signal.
 *
 * <p>Only one instance exists: it belongs to the render thread and its GL context.
 */
public final class GpuTimer {
    public enum Mode { OFF, FRAME, SECTIONS }

    private static final int QUERIES = 4096;
    private static final int RESULT_DELAY_FRAMES = 3;

    private static GpuTimer instance;

    private final int[] queries = new int[QUERIES];
    private final int[] free = new int[QUERIES];
    private int freeCount;

    // Pending pairs in submission order (ring).
    private final int[] pendBegin = new int[QUERIES];
    private final int[] pendEnd = new int[QUERIES];
    private final long[] pendSeq = new long[QUERIES];
    private final long[] pendFrame = new long[QUERIES];
    /** Frame-span pairs only: GPU clock when the frame's first marker was submitted, else -1. */
    private final long[] pendSubmitGpu = new long[QUERIES];
    private final long[] pendSubmitCpu = new long[QUERIES];
    private final boolean[] pendIsFrame = new boolean[QUERIES];
    private int pendHead, pendCount;

    // Open markers by depth.
    private final int[] openQuery = new int[Track.MAX_DEPTH];
    private final long[] openSeq = new long[Track.MAX_DEPTH];
    private final int[] openStack = new int[Track.MAX_DEPTH];
    private int openCount;

    private int frameBeginQuery = -1;
    private long frameSubmitGpu = -1, frameSubmitCpu;
    /** GPU end timestamp of the last frame span read back, to measure the GPU gap to the next one. */
    private long lastFrameEndGpu = -1;
    /**
     * Per timed frame (every Nth): CPU time of the frame start, how far the GPU ran behind the CPU
     * when it reached the frame's first command (queue lag), and the GPU gap since the previous
     * frame span ended (swap/present work or GPU idle; -1 when the previous frame was not read).
     */
    private long[] lagCpu = new long[4096], lagNanos = new long[4096], gapNanos = new long[4096];
    private int lagCount;
    private long frameSeq;
    private long frameNumber;
    private int pairsThisFrame;
    private boolean inRender;
    /** Top-level frame sections that are CPU-only bookkeeping (client ticks, sound); not GPU-timed. */
    private final java.util.Set<Integer> cpuOnly = new java.util.HashSet<>();
    private int displayNameId = -1;
    private int every = 1;
    private Capture boundCapture;

    private Mode mode = Mode.OFF;
    private int maxDepth;
    private int maxPairsPerFrame;

    // Self-cost accounting.
    long glNanos;
    long glCalls;
    long skippedMarks;
    long resultsRead;

    private GpuTimer() {}

    public long glNanos() {
        return glNanos;
    }

    public long glCalls() {
        return glCalls;
    }

    public long skippedMarks() {
        return skippedMarks;
    }

    public long resultsRead() {
        return resultsRead;
    }

    public int lagCount() {
        return lagCount;
    }

    /** CPU nanoTime of the frame start of lag sample i. */
    public long lagCpuTime(int i) {
        return lagCpu[i];
    }

    /** How long after its submission the GPU reached the frame's first command, in ns. */
    public long queueLagNanos(int i) {
        return lagNanos[i];
    }

    /** GPU time between the previous frame span's end and this frame's start, in ns, or -1. */
    public long frameGapNanos(int i) {
        return gapNanos[i];
    }

    public Mode mode() {
        return mode;
    }

    /** Returns the timer for the render thread, or null when timestamp queries are unsupported. */
    static GpuTimer forRenderThread(Capture capture) {
        if (instance == null) {
            GLCapabilities caps = GL.getCapabilities();
            if (!(caps.OpenGL33 || caps.GL_ARB_timer_query)) return null;
            GpuTimer timer = new GpuTimer();
            GL15.glGenQueries(timer.queries);
            instance = timer;
        }
        instance.bind(capture);
        return instance;
    }

    private void bind(Capture capture) {
        if (boundCapture == capture) return;
        boundCapture = capture;
        mode = capture.options.gpu();
        maxDepth = capture.options.gpuDepth();
        maxPairsPerFrame = capture.options.gpuMaxSections();
        every = Math.max(1, capture.options.gpuEvery());
        // Results still pending from an earlier capture are abandoned; reusing a query id simply
        // overwrites it.
        freeCount = 0;
        for (int i = QUERIES - 1; i >= 0; i--) free[freeCount++] = i;
        pendHead = pendCount = 0;
        openCount = 0;
        frameBeginQuery = -1;
        frameNumber = 0;
        lastFrameEndGpu = -1;
        lagCount = 0;
        cpuOnly.clear();
        displayNameId = capture.names.id("updateDisplay");
        // updateDisplay is the buffer swap: its GPU span is mostly waiting for the display, not work.
        for (String s : new String[] {"tick", "scheduledExecutables", "sound", "yield", "updateDisplay"}) cpuOnly.add(capture.names.id(s));
        glNanos = glCalls = skippedMarks = resultsRead = 0;
    }

    private int alloc() {
        return freeCount == 0 ? -1 : free[--freeCount];
    }

    private void release(int slot) {
        free[freeCount++] = slot;
    }

    private void stamp(int slot) {
        long t0 = System.nanoTime();
        GL33.glQueryCounter(queries[slot], GL33.GL_TIMESTAMP);
        glNanos += System.nanoTime() - t0;
        glCalls++;
    }

    void frameStart(Track track, long seq) {
        frameNumber++;
        pairsThisFrame = 0;
        openCount = 0;
        inRender = false;
        int slot = alloc();
        if (slot < 0) {
            skippedMarks++;
            frameBeginQuery = -1;
            return;
        }
        stamp(slot);
        frameBeginQuery = slot;
        frameSeq = seq;
        frameSubmitGpu = -1;
        if (frameNumber % every == 0) {
            // The GPU clock now, without waiting for queued work: comparing it with the time the GPU
            // actually reaches the marker shows whether the GPU runs behind the CPU (GPU-bound).
            long t0 = System.nanoTime();
            frameSubmitGpu = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
            frameSubmitCpu = t0;
            glNanos += System.nanoTime() - t0;
            glCalls++;
        }
    }

    void frameEnd(Track track) {
        // Close markers left open by unbalanced sections.
        while (openCount > 0) end(track);
        closeFrame();
        collect(track);
    }

    /**
     * Ends the frame's GPU span. Called when the buffer swap starts (so waiting for the display
     * is not counted as GPU work), or at the end of the frame if there was no swap.
     */
    private void closeFrame() {
        if (frameBeginQuery >= 0) {
            int slot = alloc();
            if (slot < 0) {
                release(frameBeginQuery);
                skippedMarks++;
            } else {
                stamp(slot);
                enqueue(frameBeginQuery, slot, frameSeq);
                int i = (pendHead + pendCount - 1) % QUERIES;
                if (pendCount > 0 && pendBegin[i] == frameBeginQuery) {
                    pendIsFrame[i] = true;
                    pendSubmitGpu[i] = frameSubmitGpu;
                    pendSubmitCpu[i] = frameSubmitCpu;
                }
            }
            frameBeginQuery = -1;
        }
    }

    /**
     * Called on every section push; returns true if a GPU marker was opened. Priority sections (the
     * frame's top levels and named passes from other mods' hooks, such as Iris shader passes) are
     * timed past the depth limit and get extra room past the per-frame cap: the passes at the end of
     * a frame (Iris composite and final) must not be starved by the many small sections before them.
     */
    boolean begin(Track track, int depth, int nameId, long seq, boolean priority) {
        // Vanilla's frame has several top-level sections ("render", "gameRenderer", "blit",
        // "updateDisplay"...); everything except client ticks and sound can put work on the GPU.
        if (depth == 0) {
            inRender = !cpuOnly.contains(nameId);
            if (nameId == displayNameId) closeFrame();
        }
        // Sections are timed on every Nth frame only; the frame span itself is timed every frame.
        priority |= depth <= 1;
        if (mode != Mode.SECTIONS || !inRender || (depth > maxDepth && !priority) || frameNumber % every != 0) return false;
        int cap = priority ? maxPairsPerFrame + Math.max(64, maxPairsPerFrame / 2) : maxPairsPerFrame;
        if (pairsThisFrame >= cap || pendCount + openCount >= QUERIES / 2 - 2) {
            skippedMarks++;
            return false;
        }
        int slot = alloc();
        if (slot < 0) {
            skippedMarks++;
            return false;
        }
        stamp(slot);
        pairsThisFrame++;
        openQuery[openCount] = slot;
        openSeq[openCount] = seq;
        openCount++;
        return true;
    }

    void end(Track track) {
        if (openCount == 0) return;
        openCount--;
        int begin = openQuery[openCount];
        int slot = alloc();
        if (slot < 0) {
            release(begin);
            skippedMarks++;
            return;
        }
        stamp(slot);
        enqueue(begin, slot, openSeq[openCount]);
    }

    private void enqueue(int begin, int end, long seq) {
        if (pendCount == QUERIES) {
            release(begin);
            release(end);
            skippedMarks++;
            return;
        }
        int i = (pendHead + pendCount) % QUERIES;
        pendBegin[i] = begin;
        pendEnd[i] = end;
        pendSeq[i] = seq;
        pendFrame[i] = frameNumber;
        pendIsFrame[i] = false;
        pendCount++;
    }

    private void recordLag(long cpuTime, long lag, long gap) {
        if (lagCount == lagCpu.length) {
            if (lagCount >= 1 << 20) return;
            lagCpu = java.util.Arrays.copyOf(lagCpu, lagCount * 2);
            lagNanos = java.util.Arrays.copyOf(lagNanos, lagCount * 2);
            gapNanos = java.util.Arrays.copyOf(gapNanos, lagCount * 2);
        }
        lagCpu[lagCount] = cpuTime;
        lagNanos[lagCount] = Math.max(0, lag);
        gapNanos[lagCount] = gap < 0 || gap >= 10_000_000_000L ? -1 : gap;
        lagCount++;
    }

    private void collect(Track track) {
        long t0 = System.nanoTime();
        while (pendCount > 0) {
            int i = pendHead;
            if (frameNumber - pendFrame[i] < RESULT_DELAY_FRAMES) break;
            int endQuery = queries[pendEnd[i]];
            if (GL15.glGetQueryObjecti(endQuery, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break;
            long begin = GL33.glGetQueryObjecti64(queries[pendBegin[i]], GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjecti64(endQuery, GL15.GL_QUERY_RESULT);
            glCalls += 3;
            long duration = end - begin;
            if (duration >= 0 && duration < 10_000_000_000L) {
                track.emit(EventKind.GPU, (int) pendSeq[i], duration);
                resultsRead++;
            }
            if (pendIsFrame[i]) {
                if (pendSubmitGpu[i] >= 0) recordLag(pendSubmitCpu[i], begin - pendSubmitGpu[i], lastFrameEndGpu < 0 ? -1 : begin - lastFrameEndGpu);
                lastFrameEndGpu = end;
            }
            release(pendBegin[i]);
            release(pendEnd[i]);
            pendHead = (pendHead + 1) % QUERIES;
            pendCount--;
        }
        glNanos += System.nanoTime() - t0;
    }
}
