package dev.lightning.capture;

import java.io.BufferedInputStream;
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
        /** Own buffer: encoding byte by byte into a BufferedOutputStream takes its lock per byte. */
        private final byte[] buf = new byte[1 << 20];
        private int pos;
        private long[] lastTime = new long[64];
        private boolean[] described = new boolean[64];
        private int namesWritten;
        long bytes;

        public Writer(Path path) throws IOException {
            out = Files.newOutputStream(path);
            for (byte b : MAGIC) put(b);
            put(VERSION);
        }

        /** Writes the names added to the table since the last call. */
        void names(NameTable table) throws IOException {
            names(table.since(namesWritten), namesWritten);
        }

        void names(java.util.List<String> names) throws IOException {
            if (names.size() > namesWritten) names(names.subList(namesWritten, names.size()), namesWritten);
        }

        private void names(java.util.List<String> fresh, int first) throws IOException {
            for (int i = 0; i < fresh.size(); i++) {
                put(REC_NAME);
                varint(first + i);
                string(fresh.get(i));
            }
            namesWritten = first + fresh.size();
        }

        void block(Track track, long[] data, int length, long baseSeq) throws IOException {
            int t = track.index;
            if (t >= lastTime.length) {
                lastTime = java.util.Arrays.copyOf(lastTime, t * 2);
                described = java.util.Arrays.copyOf(described, t * 2);
            }
            if (!described[t]) {
                described[t] = true;
                put(REC_TRACK);
                varint(t);
                varint(track.threadId);
                string(track.threadName);
            }
            put(REC_BLOCK);
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
                    if (EventKind.hasPayload(kind)) varint(payload & 0xFFFFFFFFL);
                } else {
                    varint(payload & 0xFFFFFFFFL);
                    varint(value);
                }
            }
            lastTime[t] = last;
        }

        void flush() throws IOException {
            drain();
            out.flush();
        }

        private void drain() throws IOException {
            if (pos > 0) {
                out.write(buf, 0, pos);
                pos = 0;
            }
        }

        private void put(int b) throws IOException {
            if (pos == buf.length) drain();
            buf[pos++] = (byte) b;
            bytes++;
        }

        private void varint(long v) throws IOException {
            if (pos > buf.length - 10) drain();
            while ((v & ~0x7FL) != 0) {
                buf[pos++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
                bytes++;
            }
            buf[pos++] = (byte) v;
            bytes++;
        }

        private void string(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varint(b.length);
            drain();
            out.write(b);
            bytes += b.length;
        }

        @Override
        public void close() throws IOException {
            try {
                drain();
            } finally {
                out.close();
            }
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
            long records = 0;
            try {
                int rec;
                while ((rec = in.read()) >= 0) {
                    switch (rec) {
                        case REC_NAME -> {
                            int id = index(varint(in));
                            if (id >= MAX_NAMES) throw new Corrupt("name " + id);
                            visitor.name(id, string(in));
                        }
                        case REC_TRACK -> visitor.track(index(varint(in)), varint(in), string(in));
                        case REC_BLOCK -> {
                            int t = index(varint(in));
                            if (t >= MAX_TRACKS) throw new Corrupt("track " + t);
                            if (t >= lastTime.length) lastTime = java.util.Arrays.copyOf(lastTime, Math.min(MAX_TRACKS, t * 2 + 1));
                            long seq = varint(in);
                            long count = varint(in);
                            if (count < 0 || count > MAX_BLOCK_EVENTS) throw new Corrupt("block of " + count + " events");
                            long last = lastTime[t];
                            for (long i = 0; i < count; i++, seq++) {
                                int kind = (int) varint(in);
                                if (EventKind.hasTime(kind)) {
                                    long z = varint(in);
                                    last += (z >>> 1) ^ -(z & 1);
                                    int payload = EventKind.hasPayload(kind) ? (int) varint(in) : 0;
                                    visitor.event(t, seq, kind, payload, last);
                                } else {
                                    int payload = (int) varint(in);
                                    visitor.event(t, seq, kind, payload, varint(in));
                                }
                            }
                            lastTime[t] = last;
                        }
                        default -> throw new Corrupt("record " + rec);
                    }
                    records++;
                }
            } catch (EOFException truncated) {
                // Partial final record: keep what was read.
            } catch (Corrupt bad) {
                // A crash or power loss can leave garbage (often zeros) after the last whole record;
                // keep what was read. Garbage before anything was read means it isn't usable.
                if (records == 0) throw new IOException("Corrupt event file (" + bad.getMessage() + ")");
                org.slf4j.LoggerFactory.getLogger("Lightning").warn("Lightning: event file {} is damaged after record {} ({}); using what came before.",
                        path.getFileName(), records, bad.getMessage());
            }
        }
    }

    /** Limits no real capture reaches; beyond them the bytes are damaged, not data. */
    private static final int MAX_TRACKS = 1 << 16, MAX_STRING = 1 << 20, MAX_NAMES = 1 << 22;
    private static final long MAX_BLOCK_EVENTS = 1L << 26;

    private static final class Corrupt extends IOException {
        Corrupt(String what) {
            super(what);
        }
    }

    private static int index(long v) throws Corrupt {
        if (v < 0 || v > Integer.MAX_VALUE) throw new Corrupt("index " + v);
        return (int) v;
    }

    private static long varint(InputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new Corrupt("varint");
    }

    private static String string(InputStream in) throws IOException {
        long len = varint(in);
        if (len < 0 || len > MAX_STRING) throw new Corrupt("string of " + len + " bytes");
        int n = (int) len;
        byte[] b = in.readNBytes(n);
        if (b.length < n) throw new EOFException();
        return new String(b, StandardCharsets.UTF_8);
    }
}
