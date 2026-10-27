package dev.lightning.heap.fixture;

/** Holds ~24 MB of plain arrays, reachable only through this object. */
public final class BigHolder {
    public static BigHolder INSTANCE;
    final long[] sections = new long[2_000_000];
    final java.util.List<byte[]> chunks = new java.util.ArrayList<>();

    public BigHolder() {
        for (int i = 0; i < 64; i++) chunks.add(new byte[128 * 1024]);
    }
}
