package dev.lightning.capture;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

/** Drains full event buffers to disk so recording threads never touch the file system. */
final class WriterThread extends Thread {
    private final Capture capture;
    private final EventFile.Writer writer;
    private volatile boolean finishing;
    private volatile IOException failure;
    long blocksWritten;

    WriterThread(Capture capture, Path file) throws IOException {
        super("Lightning event writer");
        setDaemon(true);
        setPriority(Thread.NORM_PRIORITY - 1);
        this.capture = capture;
        this.writer = new EventFile.Writer(file);
    }

    @Override
    public void run() {
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Capture.Block block = capture.blocks.poll();
                if (block == null) {
                    if (finishing && capture.blocks.isEmpty()) break;
                    long now = System.nanoTime();
                    if (now - lastFlush > 1_000_000_000L) {
                        writer.names(capture.names.snapshot());
                        writer.flush();
                        lastFlush = now;
                    }
                    LockSupport.parkNanos(10_000_000L);
                    continue;
                }
                writer.names(capture.names.snapshot());
                writer.block(block.track(), block.data(), block.length(), block.baseSeq());
                capture.pool.give(block.data());
                blocksWritten++;
            }
            writer.names(capture.names.snapshot());
        } catch (IOException e) {
            failure = e;
            // Keep draining so recording threads get their buffers back.
            while (true) {
                Capture.Block block = capture.blocks.poll();
                if (block != null) capture.pool.give(block.data());
                else if (finishing) break;
                else LockSupport.parkNanos(10_000_000L);
            }
        } finally {
            try {
                writer.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
    }

    /** Writes everything still queued, then closes the file. */
    void finish() throws IOException, InterruptedException {
        finishing = true;
        LockSupport.unpark(this);
        join();
        if (failure != null) throw failure;
    }

    long bytesWritten() {
        return writer.bytes;
    }
}
