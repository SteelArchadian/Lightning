package dev.lightning.analysis;

import java.util.Arrays;

/** Distribution summary of durations in nanoseconds (exact, from all values). */
public record Stats(int count, double mean, long p50, long p95, long p99, long max, long sum) {
    public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0);

    public static Stats of(long[] values, int size) {
        long[] v = new long[size];
        int n = 0;
        for (int i = 0; i < size; i++) if (values[i] >= 0) v[n++] = values[i];
        if (n == 0) return EMPTY;
        v = Arrays.copyOf(v, n);
        Arrays.sort(v);
        long sum = 0;
        for (long x : v) sum += x;
        return new Stats(n, (double) sum / n, at(v, 0.5), at(v, 0.95), at(v, 0.99), v[n - 1], sum);
    }

    private static long at(long[] sorted, double q) {
        int i = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    public static String ms(double nanos) {
        double ms = nanos / 1e6;
        if (ms >= 100) return String.format(java.util.Locale.ROOT, "%.0f ms", ms);
        if (ms >= 10) return String.format(java.util.Locale.ROOT, "%.1f ms", ms);
        return String.format(java.util.Locale.ROOT, "%.2f ms", ms);
    }
}
