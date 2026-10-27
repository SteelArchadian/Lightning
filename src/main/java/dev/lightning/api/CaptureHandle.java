package dev.lightning.api;

import dev.lightning.Lightning;
import dev.lightning.capture.Recorder;
import java.util.concurrent.CompletableFuture;

/** A running capture. All methods are safe to call from any thread, including the game threads. */
public final class CaptureHandle {
    static final String BETWEEN_PHASES = "(between phases)";

    final CompletableFuture<CaptureResult> result = new CompletableFuture<>();

    CaptureHandle() {}

    /** Starts a named phase; the previous phase (if any) ends here. */
    public void beginPhase(String name) {
        Lightning.recorder().mark(name);
    }

    /** Ends the current phase; time until the next {@link #beginPhase} is "(between phases)". */
    public void endPhase() {
        Lightning.recorder().mark(BETWEEN_PHASES);
    }

    /** Same as {@link #beginPhase}: everything after this point belongs to {@code name}. */
    public void mark(String name) {
        Lightning.recorder().mark(name);
    }

    /** Stops and saves; completes when the files are written. */
    public CompletableFuture<CaptureResult> stop() {
        whenRecording(() -> Lightning.recorder().stop(false));
        return result;
    }

    /** Stops without saving; the future from {@link #stop()} / {@link #result()} fails with a cancellation. */
    public void cancel() {
        whenRecording(() -> Lightning.recorder().stop(true));
    }

    /** Completes when the capture is saved (also after a timeout set in the request). */
    public CompletableFuture<CaptureResult> result() {
        return result;
    }

    /** Starting takes a moment (on Lightning's own thread); act once it is actually recording. */
    private void whenRecording(Runnable action) {
        Thread t = new Thread(() -> {
            Recorder r = Lightning.recorder();
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (r.state() == Recorder.State.STARTING && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
            action.run();
        }, "Lightning API stop");
        t.setDaemon(true);
        t.start();
    }
}
