package dev.lightning.capture;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording state shared by all threads while a capture runs. Hooks reach it through
 * {@link #ACTIVE}; when that is null every hook returns immediately.
 */
public final class Capture {
    /** The capture currently recording, or null. Read on every hook call. */
    public static volatile Capture ACTIVE;

    /** The client render thread, set by the client entry point; null on dedicated servers. */
    public static volatile Thread renderThread;

    public final String id;
    public final CaptureOptions options;
    public final NameTable names = new NameTable();
    final BufferPool pool = new BufferPool(256); // at most 16 MiB of event buffers in flight
    final ConcurrentLinkedQueue<Block> blocks = new ConcurrentLinkedQueue<>();
    final CopyOnWriteArrayList<Track> tracks = new CopyOnWriteArrayList<>();
    private final ThreadLocal<Track> local = ThreadLocal.withInitial(this::newTrack);

    public final long startNanos;
    public final long startEpochMillis;
    /** Offset to convert this JVM's nanoTime to epoch nanoseconds. */
    public final long epochOffsetNanos;
    volatile long stopNanos;

    /** Phase marks: label and nanoTime, in order. */
    public record Mark(long nanos, String label) {}

    private final java.util.List<Mark> marks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void mark(String label) {
        markAt(System.nanoTime(), label);
    }

    /** Used by recovery to restore marks read from disk. */
    public void restoreMark(long nanos, String label) {
        marks.add(new Mark(nanos, label));
    }

    public java.util.List<Mark> marks() {
        return java.util.List.copyOf(marks);
    }

    record Block(Track track, long[] data, int length, long baseSeq) {}

    /** A name given by whoever started the capture (benchmark run label), or "". */
    public volatile String label = "";
    /** Measured cost of one recorded event on this machine (nanoTime + stores), for overhead estimates. */
    public volatile double eventCostNanos = 25;
    /** Where marks are appended as they happen, so a crashed capture can be recovered with its phases. */
    volatile java.nio.file.Path marksFile;

    Capture(String id, CaptureOptions options) {
        this(id, options, 0);
    }

    /** {@code startAtNanos}: an earlier start (the JVM's start, for a start-up capture); 0 = now. */
    Capture(String id, CaptureOptions options, long startAtNanos) {
        this.id = id;
        this.options = options;
        this.epochOffsetNanos = measureEpochOffset();
        this.startNanos = startAtNanos != 0 ? startAtNanos : System.nanoTime();
        this.startEpochMillis = (startNanos + epochOffsetNanos) / 1_000_000L;
    }

    /** Rebuilds a capture's header from disk (crash recovery); times are in the crashed JVM's clock. */
    public Capture(String id, CaptureOptions options, long startNanos, long startEpochMillis, long epochOffsetNanos) {
        this.id = id;
        this.options = options;
        this.startNanos = startNanos;
        this.startEpochMillis = startEpochMillis;
        this.epochOffsetNanos = epochOffsetNanos;
    }

    public void setStopNanos(long nanos) {
        stopNanos = nanos;
    }

    void markAt(long nanos, String label) {
        marks.add(new Mark(nanos, label));
        java.nio.file.Path f = marksFile;
        if (f != null) {
            try {
                java.nio.file.Files.writeString(f, nanos + "\t" + label.replace('\n', ' ') + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException ignored) {
                // Recovery just loses this mark.
            }
        }
    }

    /** The calling thread's track, or null when nothing is recording. */
    public static Track track() {
        Capture c = ACTIVE;
        return c == null ? null : c.local.get();
    }

    private Track newTrack() {
        Thread thread = Thread.currentThread();
        Track track;
        synchronized (tracks) {
            track = new Track(this, tracks.size(), thread);
            tracks.add(track);
        }
        if (thread == renderThread && options.gpu() != GpuTimer.Mode.OFF) {
            try {
                track.gpu = GpuTimer.forRenderThread(this);
            } catch (RuntimeException | LinkageError e) {
                gpuError = e.toString();
            }
        }
        return track;
    }

    volatile String gpuError;

    void publish(Track track, long[] data, int length, long baseSeq) {
        blocks.offer(new Block(track, data, length, baseSeq));
    }

    public List<Track> tracks() {
        return List.copyOf(tracks);
    }

    public long stopNanos() {
        return stopNanos;
    }

    public String gpuError() {
        return gpuError;
    }

    public GpuTimer gpuTimer() {
        for (Track t : tracks) if (t.gpu != null) return t.gpu;
        return null;
    }

    /** Pairs nanoTime with the wall clock, keeping the pair read with the least delay. */
    private static long measureEpochOffset() {
        long best = Long.MAX_VALUE, offset = 0;
        for (int i = 0; i < 9; i++) {
            long a = System.nanoTime();
            java.time.Instant now = java.time.Instant.now();
            long b = System.nanoTime();
            if (b - a < best) {
                best = b - a;
                long epochNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
                offset = epochNanos - (a + (b - a) / 2);
            }
        }
        return offset;
    }
}
