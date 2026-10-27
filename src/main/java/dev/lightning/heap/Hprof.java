package dev.lightning.heap;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Sequential big-endian reader for HotSpot .hprof heap dumps (files can be many GB). */
final class Hprof implements AutoCloseable {
    // Top-level record tags.
    static final int UTF8 = 0x01, LOAD_CLASS = 0x02, HEAP_DUMP = 0x0C, HEAP_DUMP_SEGMENT = 0x1C, HEAP_DUMP_END = 0x2C;
    // Heap dump sub-record tags.
    static final int ROOT_UNKNOWN = 0xFF, ROOT_JNI_GLOBAL = 0x01, ROOT_JNI_LOCAL = 0x02, ROOT_JAVA_FRAME = 0x03,
            ROOT_NATIVE_STACK = 0x04, ROOT_STICKY_CLASS = 0x05, ROOT_THREAD_BLOCK = 0x06, ROOT_MONITOR_USED = 0x07,
            ROOT_THREAD_OBJECT = 0x08, CLASS_DUMP = 0x20, INSTANCE_DUMP = 0x21, OBJ_ARRAY_DUMP = 0x22, PRIM_ARRAY_DUMP = 0x23;
    // Basic types.
    static final int T_OBJECT = 2, T_BOOLEAN = 4, T_CHAR = 5, T_FLOAT = 6, T_DOUBLE = 7, T_BYTE = 8, T_SHORT = 9, T_INT = 10, T_LONG = 11;

    private final FileChannel channel;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(1 << 24);
    private long position;
    final long size;
    int idSize;

    Hprof(Path path) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        size = channel.size();
        buf.limit(0);
    }

    /** Reads the file header; leaves the reader at the first record. */
    void header() throws IOException {
        StringBuilder magic = new StringBuilder();
        int c;
        while ((c = u1()) != 0) magic.append((char) c);
        if (!magic.toString().startsWith("JAVA PROFILE")) throw new IOException("Not an hprof heap dump: " + magic);
        idSize = u4();
        if (idSize != 4 && idSize != 8) throw new IOException("Unsupported identifier size " + idSize);
        u8(); // timestamp
    }

    long position() {
        return position;
    }

    boolean atEnd() {
        return position >= size;
    }

    private void need(int n) throws IOException {
        if (buf.remaining() >= n) return;
        buf.compact();
        while (buf.position() < n) {
            if (channel.read(buf) < 0) {
                buf.flip();
                throw new EOFException();
            }
        }
        buf.flip();
    }

    int u1() throws IOException {
        need(1);
        position++;
        return buf.get() & 0xFF;
    }

    int u2() throws IOException {
        need(2);
        position += 2;
        return buf.getShort() & 0xFFFF;
    }

    int u4() throws IOException {
        need(4);
        position += 4;
        return buf.getInt();
    }

    long u8() throws IOException {
        need(8);
        position += 8;
        return buf.getLong();
    }

    long id() throws IOException {
        return idSize == 8 ? u8() : u4() & 0xFFFFFFFFL;
    }

    void skip(long n) throws IOException {
        while (n > 0) {
            if (!buf.hasRemaining()) need(1);
            int step = (int) Math.min(n, buf.remaining());
            buf.position(buf.position() + step);
            position += step;
            n -= step;
        }
    }

    void readFully(byte[] out, int n) throws IOException {
        int off = 0;
        while (off < n) {
            if (!buf.hasRemaining()) need(1);
            int step = Math.min(n - off, buf.remaining());
            buf.get(out, off, step);
            off += step;
        }
        position += n;
    }

    byte[] bytes(int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            if (!buf.hasRemaining()) need(1);
            int step = Math.min(n - off, buf.remaining());
            buf.get(out, off, step);
            off += step;
        }
        position += n;
        return out;
    }

    /** Size in bytes of a value of a basic type in the dump. */
    int typeSize(int type) {
        return switch (type) {
            case T_OBJECT -> idSize;
            case T_BOOLEAN, T_BYTE -> 1;
            case T_CHAR, T_SHORT -> 2;
            case T_FLOAT, T_INT -> 4;
            case T_DOUBLE, T_LONG -> 8;
            default -> throw new IllegalStateException("Unknown basic type " + type);
        };
    }

    /** Size of the value in the live JVM (references assumed compressed to 4 bytes). */
    static int heapTypeSize(int type) {
        return switch (type) {
            case T_OBJECT -> 4;
            case T_BOOLEAN, T_BYTE -> 1;
            case T_CHAR, T_SHORT -> 2;
            case T_FLOAT, T_INT -> 4;
            case T_DOUBLE, T_LONG -> 8;
            default -> 4;
        };
    }

    static String primitiveArrayName(int type) {
        return switch (type) {
            case T_BOOLEAN -> "boolean[]";
            case T_CHAR -> "char[]";
            case T_FLOAT -> "float[]";
            case T_DOUBLE -> "double[]";
            case T_BYTE -> "byte[]";
            case T_SHORT -> "short[]";
            case T_INT -> "int[]";
            case T_LONG -> "long[]";
            default -> "?[]";
        };
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
