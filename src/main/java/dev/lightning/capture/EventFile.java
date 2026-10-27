package dev.lightning.capture;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compact on-disk form of the event stream. Events are written while the capture runs so the
 * data survives a crash and does not accumulate in the game's heap.
 *
 * <pre>
 * file   := "LTEV" version:u8 record*
 * record := 1 track:var baseSeq:var count:var event*      (block of one thread's events)
 *         | 2 track:var threadId:var name:str             (thread description)
 *         | 3 id:var name:str                             (section name)
 * event  := kind:var [timeDelta:zigzag] [nameId:var | seq:var value:var]
 * </pre>
 * Times are delta-encoded per thread, so a typical event takes 3–4 bytes.
 */
public final class EventFile {
    static final byte[] MAGIC = {'L', 'T', 'E', 'V'};
    static final int VERSION = 1;
    static final int REC_BLOCK = 1, REC_TRACK = 2, REC_NAME = 3;

    private EventFile() {}

    /** Receives decoded events in file order; each thread's events arrive in time order. */
    public interface Visitor {
        default void track(int track, long threadId, String name) {}

        default void name(int id, String name) {}

        /** @param time nanoTime for timed kinds; for GPU, the GPU duration in nanoseconds */
        void event(int track, long seq, int kind, int payload, long time);
    }

    // --- writing --------------------------------------------------------------------------

    public static final class Writer implements AutoCloseable {
        private final OutputStream out;
        private long[] lastTime = new long[64];
        private boolean[] described = new boolean[64];
        private int namesWritten;
        long bytes;

        public Writer(Path path) throws IOException {
            out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
            out.write(MAGIC);
            out.write(VERSION);
            bytes = 5;
        }

        void names(java.util.List<String> names) throws IOException {
            for (int i = namesWritten; i < names.size(); i++) {
                out.write(REC_NAME);
                varint(i);
                string(names.get(i));
            }
            namesWritten = Math.max(namesWritten, names.size());
        }

        void block(Track track, long[] data, int length, long baseSeq) throws IOException {
            int t = track.index;
            if (t >= lastTime.length) {
                lastTime = java.util.Arrays.copyOf(lastTime, t * 2);
                described = java.util.Arrays.copyOf(described, t * 2);
            }
            if (!described[t]) {
                described[t] = true;
                out.write(REC_TRACK);
                varint(t);
                varint(track.threadId);
                string(track.threadName);
            }
            out.write(REC_BLOCK);
            varint(t);
            varint(baseSeq);
            varint(length >> 1);
            long last = lastTime[t];
            for (int i = 0; i < length; i += 2) {
                long value = data[i];
                long tag = data[i + 1];
                int kind = (int) (tag >>> 32);
                int payload = (int) tag;
                varint(kind);
                if (EventKind.hasTime(kind)) {
                    long delta = value - last;
                    last = value;
                    varint((delta << 1) ^ (delta >> 63));
                    if (kind == EventKind.PUSH) varint(payload & 0xFFFFFFFFL);
                } else {
                    varint(payload & 0xFFFFFFFFL);
                    varint(value);
                }
            }
            lastTime[t] = last;
        }

        void flush() throws IOException {
            out.flush();
        }

        private void varint(long v) throws IOException {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
                bytes++;
            }
            out.write((int) v);
            bytes++;
        }

        private void string(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varint(b.length);
            out.write(b);
            bytes += b.length;
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    // --- reading --------------------------------------------------------------------------

    /** Reads a whole file. A truncated tail (for example after a crash) ends reading quietly. */
    public static void read(Path path, Visitor visitor) throws IOException {
        try (InputStream raw = Files.newInputStream(path); InputStream in = new BufferedInputStream(raw, 1 << 20)) {
            byte[] magic = in.readNBytes(4);
            if (!java.util.Arrays.equals(magic, MAGIC)) throw new IOException("Not a Lightning event file");
            int version = in.read();
            if (version != VERSION) throw new IOException("Unsupported event file version " + version);
            long[] lastTime = new long[64];
            try {
                int rec;
                while ((rec = in.read()) >= 0) {
                    switch (rec) {
                        case REC_NAME -> visitor.name((int) varint(in), string(in));
                        case REC_TRACK -> visitor.track((int) varint(in), varint(in), string(in));
                        case REC_BLOCK -> {
                            int t = (int) varint(in);
                            if (t >= lastTime.length) lastTime = java.util.Arrays.copyOf(lastTime, t * 2);
                            long seq = varint(in);
                            long count = varint(in);
                            long last = lastTime[t];
                            for (long i = 0; i < count; i++, seq++) {
                                int kind = (int) varint(in);
                                if (EventKind.hasTime(kind)) {
                                    long z = varint(in);
                                    last += (z >>> 1) ^ -(z & 1);
                                    int payload = kind == EventKind.PUSH ? (int) varint(in) : 0;
                                    visitor.event(t, seq, kind, payload, last);
                                } else {
                                    int payload = (int) varint(in);
                                    visitor.event(t, seq, kind, payload, varint(in));
                                }
                            }
                            lastTime[t] = last;
                        }
                        default -> throw new IOException("Corrupt event file (record " + rec + ")");
                    }
                }
            } catch (EOFException truncated) {
                // Partial final record: keep what was read.
            }
        }
    }

    private static long varint(InputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("Malformed varint");
    }

    private static String string(InputStream in) throws IOException {
        int n = (int) varint(in);
        byte[] b = in.readNBytes(n);
        if (b.length < n) throw new EOFException();
        return new String(b, StandardCharsets.UTF_8);
    }
}
