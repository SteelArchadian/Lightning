package dev.lightning.capture;

/**
 * Event kinds stored in a {@link Track}'s buffer. Each event occupies two longs:
 * {@code [time or value, (kind << 32) | payload]}.
 */
public final class EventKind {
    /** Section entered. Payload: name id. Time: nanoTime. */
    public static final int PUSH = 1;
    /** Section left. Time: nanoTime. */
    public static final int POP = 2;
    /** Frame (client) or tick (server) started. Time: nanoTime. */
    public static final int TICK_START = 3;
    /** Frame or tick finished. Time: nanoTime. */
    public static final int TICK_END = 4;
    /** GPU duration for an earlier PUSH or TICK_START. Payload: that event's sequence number (low 32 bits). Value: GPU nanoseconds. */
    public static final int GPU = 5;
    /** Events were discarded before this point because the writer fell behind. Time: nanoTime. */
    public static final int LOSS = 6;
    /**
     * The client's open screen changed (render thread, just before a frame). Payload: name id of the
     * screen's class + 1, or 0 when no screen is open (playing). Time: nanoTime.
     */
    public static final int SCREEN = 7;

    private EventKind() {}

    public static boolean hasTime(int kind) {
        return kind != GPU;
    }

    /** Timed events that also store their payload in the event file. */
    public static boolean hasPayload(int kind) {
        return kind == PUSH || kind == SCREEN;
    }
}
