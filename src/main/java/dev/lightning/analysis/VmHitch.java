package dev.lightning.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explains one long VM operation by the deoptimisations and class definitions just before it. */
final class VmHitch {
    private VmHitch() {}

    /** Plain-words cause for the finding, or null when nothing happened around it. */
    static String explain(JfrDigest.Pause p, List<JfrDigest.Deopt> deopts, List<JfrDigest.ClassDef> defines) {
        long end = p.time() + p.duration();
        int count = 0;
        Map<String, Integer> reasons = new HashMap<>(), owners = new HashMap<>();
        for (JfrDigest.Deopt d : deopts) {
            if (d.time() < p.time() - 100_000_000L || d.time() > end) continue;
            count++;
            reasons.merge(d.reason(), 1, Integer::sum);
            owners.merge(d.owner().isEmpty() ? "?" : d.owner(), 1, Integer::sum);
        }
        StringBuilder classes = new StringBuilder();
        int defined = 0;
        for (JfrDigest.ClassDef c : defines) {
            if (c.time() < p.time() - 500_000_000L || c.time() > end) continue;
            if (defined++ < 3) classes.append(defined > 1 ? ", " : "").append(c.name()).append(" [").append(c.owner().isEmpty() ? "?" : c.owner()).append(']');
        }
        if (count == 0 && defined == 0) return null;
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%s, requested by %s, held up the threads it waits on for %s. ",
                p.detail(), p.thread(), Stats.ms(p.duration())));
        if (count > 0) {
            b.append(String.format(Locale.ROOT, "%d compiled method%s %s thrown away (deoptimised) around it", count, count == 1 ? "" : "s", count == 1 ? "was" : "were"));
            b.append(" (reasons: ").append(top(reasons)).append("; code of: ").append(top(owners)).append("). ");
        }
        if (defined > 0) {
            b.append(String.format(Locale.ROOT, "%d class%s %s defined just before: %s%s. ", defined, defined == 1 ? "" : "es", defined == 1 ? "was" : "were",
                    classes, defined > 3 ? ", …" : ""));
        }
        if (count > 0 && defined > 0) {
            b.append("A class loading mid-game can invalidate compiled code that assumed it did not exist, so the JVM stops threads to throw that code away; loading those classes earlier (or not at all) avoids the hitch.");
        }
        return b.toString().trim();
    }

    private static String top(Map<String, Integer> m) {
        StringBuilder b = new StringBuilder();
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3)
                .forEach(e -> b.append(b.length() > 0 ? ", " : "").append(e.getKey()).append(" ×").append(e.getValue()));
        return b.toString();
    }
}
