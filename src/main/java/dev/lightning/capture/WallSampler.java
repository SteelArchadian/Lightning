package dev.lightning.capture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wall-clock sampler for the few threads that loading waits on (the main/render thread, the
 * server thread, mod-loading workers). Every 10 ms it records each thread's state and stack,
 * whether the thread is running Java, inside the JVM (defining or verifying classes), in native
 * code or waiting. JFR's sampler only sees threads running Java code, and only a few threads per
 * interval, so it misses exactly the time loading spends in the JVM and in waits.
 *
 * <p>Stacks are stored as nodes of a prefix tree, so a sample costs a few ints.
 */
public final class WallSampler {
    public static final int PERIOD_MILLIS = 10;
    private static final int MAX_DEPTH = 160;
    private static final int MAX_SAMPLES = 600_000;

    public static final byte RUNNING = 0, NATIVE = 1, WAITING = 2, BLOCKED = 3;

    /** Frames as "class.method" strings; flag per frame: native method. */
    public final List<String> frames = new ArrayList<>();
    public final List<Boolean> frameNative = new ArrayList<>();
    private final Map<StackTraceElement, Integer> frameIds = new HashMap<>();
    /** Prefix tree: parent node and frame per node; node 0 is the root. */
    public int[] nodeParent = new int[4096], nodeFrame = new int[4096];
    public int nodes = 1;
    private final Map<Long, Integer> children = new HashMap<>();

    public final List<String> threadNames = new ArrayList<>();
    private final Map<Thread, Integer> threadIds = new HashMap<>();

    public long[] time = new long[8192];
    public int[] thread = new int[8192], leaf = new int[8192];
    public byte[] state = new byte[8192];
    public int size;
    public long cpuNanos;
    private volatile boolean running;
    private Thread worker;

    public void start() {
        running = true;
        worker = new Thread(this::loop, "Lightning wall sampler");
        worker.setDaemon(true);
        worker.setPriority(Thread.MAX_PRIORITY);
        worker.start();
    }

    /** Stops sampling and waits for the sampler thread; the data is then safe to read. */
    public void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Thread thread() {
        return worker;
    }

    private void loop() {
        List<Thread> targets = new ArrayList<>();
        long nextScan = 0;
        long next = System.nanoTime();
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        long cpu0 = bean.isCurrentThreadCpuTimeSupported() ? bean.getCurrentThreadCpuTime() : 0;
        while (running && size < MAX_SAMPLES) {
            long now = System.nanoTime();
            if (now >= nextScan) {
                targets = findTargets();
                nextScan = now + 500_000_000L;
            }
            for (Thread t : targets) {
                if (!t.isAlive()) continue;
                Thread.State s = t.getState();
                StackTraceElement[] stack = t.getStackTrace();
                if (stack.length == 0) continue;
                byte st = switch (s) {
                    case BLOCKED -> BLOCKED;
                    case WAITING, TIMED_WAITING -> WAITING;
                    default -> stack[0].isNativeMethod() ? NATIVE : RUNNING;
                };
                add(System.nanoTime(), threadId(t), st, node(stack));
            }
            next += PERIOD_MILLIS * 1_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep < -50_000_000L) next = System.nanoTime(); // fell far behind (machine busy): don't burst
            else if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        if (bean.isCurrentThreadCpuTimeSupported()) cpuNanos = bean.getCurrentThreadCpuTime() - cpu0;
    }

    /** Threads loading waits on. Found by name; enumerating threads does not pause the JVM. */
    private static List<Thread> findTargets() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] all = new Thread[root.activeCount() + 32];
        int n = root.enumerate(all, true);
        List<Thread> out = new ArrayList<>();
        Thread render = Capture.renderThread;
        if (render != null) out.add(render);
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            String name = t.getName();
            if (t == render) continue;
            if ((render == null && (name.equals("Render thread") || name.equals("main"))) || name.equals("Server thread")
                    || name.startsWith("modloading-worker")) {
                out.add(t);
            }
        }
        return out;
    }

    private int threadId(Thread t) {
        Integer id = threadIds.get(t);
        if (id == null) {
            id = threadNames.size();
            threadNames.add(t.getName().startsWith("modloading-worker") ? "modloading-worker-#" : t.getName());
            threadIds.put(t, id);
        }
        return id;
    }

    private int node(StackTraceElement[] stack) {
        int node = 0;
        int depth = Math.min(stack.length, MAX_DEPTH);
        for (int i = stack.length - 1; i >= stack.length - depth; i--) {
            StackTraceElement e = stack[i];
            Integer f = frameIds.get(e);
            if (f == null) {
                // Line numbers are dropped: one frame per method keeps the tree small.
                StackTraceElement key = new StackTraceElement(e.getClassName(), e.getMethodName(), null, e.isNativeMethod() ? -2 : -1);
                f = frameIds.get(key);
                if (f == null) {
                    f = frames.size();
                    frames.add(e.getClassName() + "." + e.getMethodName());
                    frameNative.add(e.isNativeMethod());
                    frameIds.put(key, f);
                }
                frameIds.put(e, f);
            }
            long key = ((long) node << 32) | f;
            Integer child = children.get(key);
            if (child == null) {
                if (nodes == nodeParent.length) {
                    nodeParent = java.util.Arrays.copyOf(nodeParent, nodes * 2);
                    nodeFrame = java.util.Arrays.copyOf(nodeFrame, nodes * 2);
                }
                child = nodes++;
                nodeParent[child] = node;
                nodeFrame[child] = f;
                children.put(key, child);
            }
            node = child;
        }
        return node;
    }

    private void add(long t, int threadId, byte st, int node) {
        if (size == time.length) {
            time = java.util.Arrays.copyOf(time, size * 2);
            thread = java.util.Arrays.copyOf(thread, size * 2);
            leaf = java.util.Arrays.copyOf(leaf, size * 2);
            state = java.util.Arrays.copyOf(state, size * 2);
        }
        time[size] = t;
        thread[size] = threadId;
        leaf[size] = node;
        state[size] = st;
        size++;
    }
}
