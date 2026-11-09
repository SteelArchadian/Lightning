package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who allocated the plain arrays ({@code long[]}, {@code byte[]}, {@code Object[]} ...) in the
 * memory snapshot. The class histogram only knows each array type's total; JFR's old-object
 * samples (objects still alive at the end, with the stack that made them) split each total by
 * allocating code. Estimates: a few hundred samples spread over gigabytes.
 */
final class ArrayOwners {
    record Row(String type, String mod, String site, long bytes, int samples) {}

    private ArrayOwners() {}


    /** Histogram bytes of each unattributed array type, split by the sampled allocation sites; largest first. */
    static List<Row> of(HeapHistogram heap, List<JfrDigest.OldSample> samples) {
        Map<String, List<JfrDigest.OldSample>> byType = new HashMap<>();
        for (JfrDigest.OldSample s : samples) byType.computeIfAbsent(readable(s.className()), k -> new ArrayList<>()).add(s);
        List<Row> out = new ArrayList<>();
        for (HeapHistogram.Row h : heap.rows) {
            if (!HeapHistogram.ownerUnknownArray(h.className())) continue;
            List<JfrDigest.OldSample> list = byType.get(readable(h.className()));
            if (list == null || list.isEmpty()) continue;
            // JFR picks old-object samples per amount allocated, not per object, so each sample stands for
            // about the same bytes: share each type's total by sample count. Weighting by the sampled
            // object's size would hand one large buffer (Lightning's own 1 MB) a fifth of all byte[].
            // A site never gets less than the bytes its samples measured.
            Map<String, long[]> sites = new HashMap<>();
            for (JfrDigest.OldSample s : list) {
                long[] agg = sites.computeIfAbsent(s.mod() + '\u0000' + s.site(), k -> new long[2]);
                agg[0] += Math.max(0, s.size());
                agg[1]++;
            }
            for (var e : sites.entrySet()) {
                String[] k = e.getKey().split("\u0000", 2);
                long share = Math.round((double) h.bytes() * e.getValue()[1] / list.size());
                out.add(new Row(readable(h.className()), k[0], k[1], Math.max(share, e.getValue()[0]), (int) e.getValue()[1]));
            }
        }
        out.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
        return out;
    }

    private static String readable(String className) {
        return className.startsWith("[") ? HeapHistogram.readable(className) : className;
    }
}
