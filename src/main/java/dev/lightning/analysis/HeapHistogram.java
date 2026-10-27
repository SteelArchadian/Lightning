package dev.lightning.analysis;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;

/**
 * What is currently held in memory, by class and by owning mod. Uses the JVM's class histogram
 * (the same data as {@code jcmd GC.class_histogram}), which runs a full collection first so only
 * live objects are counted. That briefly pauses the game, so it is taken only on request.
 *
 * <p>Plain arrays ({@code long[]}, {@code byte[]}, ...) cannot be attributed from a histogram:
 * they are reported as their own group. Arrays of a mod's classes are charged to that mod.
 */
public final class HeapHistogram {
    public record Row(String className, long instances, long bytes, String owner) {}

    public final List<Row> rows;
    public final Map<String, long[]> byOwner; // owner → {bytes, instances, classes}
    public final long totalBytes;
    public final long pauseNanos;
    public final long takenAtNanos;

    private HeapHistogram(List<Row> rows, Map<String, long[]> byOwner, long totalBytes, long pauseNanos, long takenAtNanos) {
        this.rows = rows;
        this.byOwner = byOwner;
        this.totalBytes = totalBytes;
        this.pauseNanos = pauseNanos;
        this.takenAtNanos = takenAtNanos;
    }

    public static HeapHistogram take(ModIndex mods) throws Exception {
        long t0 = System.nanoTime();
        Object text = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()});
        long pause = System.nanoTime() - t0;
        return parse(String.valueOf(text), mods, pause, t0);
    }

    static HeapHistogram parse(String text, ModIndex mods, long pauseNanos, long takenAt) {
        List<Row> rows = new ArrayList<>();
        Map<String, long[]> byOwner = new HashMap<>();
        long total = 0;
        for (String line : text.split("\\R")) {
            // " 1:   123456   7890123  java.lang.String (java.base@21)"
            String[] p = line.trim().split("\\s+");
            if (p.length < 4 || !p[0].endsWith(":")) continue;
            long instances, bytes;
            try {
                instances = Long.parseLong(p[1]);
                bytes = Long.parseLong(p[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            String cls = p[3];
            String owner = owner(cls, mods);
            rows.add(new Row(cls, instances, bytes, owner));
            long[] o = byOwner.computeIfAbsent(owner, k -> new long[3]);
            o[0] += bytes;
            o[1] += instances;
            o[2]++;
            total += bytes;
        }
        return new HeapHistogram(rows, byOwner, total, pauseNanos, takenAt);
    }

    /** Readable name for JVM type descriptors such as {@code [J} or {@code [Lnet.foo.Bar;}. */
    public static String readable(String cls) {
        int dims = 0;
        while (dims < cls.length() && cls.charAt(dims) == '[') dims++;
        if (dims == 0) return cls;
        String base = cls.substring(dims);
        String name = switch (base) {
            case "J" -> "long";
            case "I" -> "int";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "Z" -> "boolean";
            case "F" -> "float";
            case "D" -> "double";
            default -> base.startsWith("L") && base.endsWith(";") ? base.substring(1, base.length() - 1) : base;
        };
        return name + "[]".repeat(dims);
    }

    private static String owner(String cls, ModIndex mods) {
        int dims = 0;
        while (dims < cls.length() && cls.charAt(dims) == '[') dims++;
        if (dims > 0) {
            String base = cls.substring(dims);
            if (!(base.startsWith("L") && base.endsWith(";"))) return "plain arrays (owner unknown)";
            String element = base.substring(1, base.length() - 1);
            String owner = mods.ownerOfClass(element);
            return owner.equals("java") ? "plain arrays (owner unknown)" : owner;
        }
        if (cls.contains("$$Lambda") || cls.contains("/0x")) cls = cls.replaceAll("\\$\\$Lambda.*|/0x.*", "");
        return mods.ownerOfClass(cls);
    }

    /** Owners by retained bytes, largest first. */
    public List<Map.Entry<String, long[]>> owners() {
        List<Map.Entry<String, long[]>> list = new ArrayList<>(byOwner.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        return list;
    }

    /** Plain-text report. */
    public String text() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(java.util.Locale.ROOT, "Live heap after a full collection: %.0f MB in %d classes (taking it paused the game for %.0f ms).%n%n",
                totalBytes / 1048576.0, rows.size(), pauseNanos / 1e6));
        b.append("By owner (where the class's code lives):\n");
        for (var e : owners()) {
            if (e.getValue()[0] < totalBytes / 1000) break;
            b.append(String.format(java.util.Locale.ROOT, "  %-40s %8.1f MB  %5.1f%%  (%d classes)%n", e.getKey(),
                    e.getValue()[0] / 1048576.0, 100.0 * e.getValue()[0] / Math.max(1, totalBytes), e.getValue()[2]));
        }
        b.append("\nLargest classes:\n");
        for (int i = 0; i < Math.min(60, rows.size()); i++) {
            Row r = rows.get(i);
            b.append(String.format(java.util.Locale.ROOT, "  %8.1f MB  %,12d  %-70s [%s]%n", r.bytes / 1048576.0, r.instances, readable(r.className), r.owner));
        }
        b.append("\nPlain arrays (long[], byte[], Object[] ...) can belong to any mod; a histogram cannot tell which.\n");
        return b.toString();
    }
}
