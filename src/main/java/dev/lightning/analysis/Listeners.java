package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Event-listener cost by mod, from the CPU samples: every stack that runs through NeoForge's event
 * bus is charged to the first real method the bus called (the listener) and to the method that
 * posted the event. Sampled, so shares are estimates; nested posts are counted under each
 * listener on the way (inclusive time), like a flame graph.
 */
final class Listeners {
    record Row(String thread, String event, String listener, String mod, long samples, double threadPct, double ms) {}

    private Listeners() {}

    static boolean bus(String label) {
        return label.startsWith("net.neoforged.bus.");
    }

    /** Frames the bus goes through on its way to a listener: reflection, method handles, generated lambdas. */
    static boolean glue(String label) {
        return bus(label) || label.startsWith("java.lang.invoke.") || label.startsWith("jdk.internal.reflect.")
                || label.contains("$$Lambda") || label.startsWith("java.util.function.");
    }

    /**
     * Listeners using at least 0.1% of their thread's samples, most expensive first.
     * ms is per frame on the render thread, per tick on the server thread, per second elsewhere.
     */
    static List<Row> of(Report r) {
        if (r.jfr == null) return new ArrayList<>();
        return of(r.jfr.cpu, r.jfr.frames, r.frames.mean(), r.serverWork.mean());
    }

    /** @param frameNanos mean render frame; @param tickNanos mean server tick work */
    static List<Row> of(FlameTree t, FrameTable f, double frameNanos, double tickNanos) {
        List<Row> out = new ArrayList<>();
        int n = t.size();
        // Children lists, built once from the parent links.
        int[] firstChild = new int[n], nextSibling = new int[n];
        java.util.Arrays.fill(firstChild, -1);
        for (int i = n - 1; i > 0; i--) {
            int p = t.parent(i);
            nextSibling[i] = firstChild[p];
            firstChild[p] = i;
        }
        Map<String, long[]> charged = new HashMap<>();
        Map<String, String[]> keys = new HashMap<>();
        for (int i = 1; i < n; i++) {
            int p = t.parent(i);
            if (p <= 0 || !bus(f.label(t.frame(i))) || bus(f.label(t.frame(p)))) continue;
            // i enters the bus; p posted the event. Walk the bus's glue down to each listener.
            String thread = threadOf(t, f, i);
            // Mixin bridges ("mixinextras$bridge$post$135") and lambdas hide which event it was; name their caller.
            int poster = p;
            while (t.parent(poster) > 0 && (f.label(t.frame(poster)).contains("mixinextras$") || glue(f.label(t.frame(poster))))) poster = t.parent(poster);
            String event = f.label(t.frame(poster));
            List<Integer> stack = new ArrayList<>();
            stack.add(i);
            while (!stack.isEmpty()) {
                int x = stack.remove(stack.size() - 1);
                for (int c = firstChild[x]; c >= 0; c = nextSibling[c]) {
                    String label = f.label(t.frame(c));
                    if (glue(label)) {
                        stack.add(c);
                        continue;
                    }
                    String key = thread + '\u0000' + event + '\u0000' + label;
                    charged.computeIfAbsent(key, k -> new long[1])[0] += t.total(c);
                    keys.putIfAbsent(key, new String[] {thread, event, label, f.owner(t.frame(c))});
                }
            }
        }
        Map<String, Long> threadTotals = new HashMap<>();
        for (int c = firstChild[0]; c >= 0; c = nextSibling[c]) {
            threadTotals.merge(threadName(f.label(t.frame(c))), t.total(c), Long::sum);
        }
        for (var e : charged.entrySet()) {
            String[] k = keys.get(e.getKey());
            long samples = e.getValue()[0];
            long total = threadTotals.getOrDefault(k[0], 0L);
            if (total == 0) continue;
            double pct = 100.0 * samples / total;
            if (pct < 0.1) continue;
            out.add(new Row(k[0], k[1], k[2], k[3].isEmpty() ? "?" : k[3], samples, pct, ms(k[0], pct, frameNanos, tickNanos)));
        }
        out.sort((a, b) -> Long.compare(b.samples(), a.samples()));
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    /** Time per frame (render), per tick (server) or per second (other threads) for a share of a thread. */
    private static double ms(String thread, double pct, double frameNanos, double tickNanos) {
        if (thread.equals("Render thread")) return pct / 100 * frameNanos / 1e6;
        if (thread.equals("Server thread")) return pct / 100 * tickNanos / 1e6;
        return pct / 100 * 1000;
    }

    private static String threadOf(FlameTree t, FrameTable f, int node) {
        int x = node;
        while (t.parent(x) > 0) x = t.parent(x);
        return threadName(f.label(t.frame(x)));
    }

    private static String threadName(String rootLabel) {
        return rootLabel.startsWith("thread: ") ? rootLabel.substring(8) : rootLabel;
    }
}
