package dev.lightning.capture;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
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
    private int pendHead, pendCount;

    // Open markers by depth.
    private final int[] openQuery = new int[Track.MAX_DEPTH];
    private final long[] openSeq = new long[Track.MAX_DEPTH];
    private final int[] openStack = new int[Track.MAX_DEPTH];
    private int openCount;

    private int frameBeginQuery = -1;
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
            }
            frameBeginQuery = -1;
        }
    }

    /** Called on every section push; returns true if a GPU marker was opened. */
    boolean begin(Track track, int depth, int nameId, long seq) {
        // Vanilla's frame has several top-level sections ("render", "gameRenderer", "blit",
        // "updateDisplay"...); everything except client ticks and sound can put work on the GPU.
        if (depth == 0) {
            inRender = !cpuOnly.contains(nameId);
            if (nameId == displayNameId) closeFrame();
        }
        // Sections are timed on every Nth frame only; the frame span itself is timed every frame.
        if (mode != Mode.SECTIONS || !inRender || depth > maxDepth || frameNumber % every != 0) return false;
        if (pairsThisFrame >= maxPairsPerFrame || pendCount + openCount >= QUERIES / 2 - 2) {
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
        pendCount++;
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
            release(pendBegin[i]);
            release(pendEnd[i]);
            pendHead = (pendHead + 1) % QUERIES;
            pendCount--;
        }
        glNanos += System.nanoTime() - t0;
    }
}
