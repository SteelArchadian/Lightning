package dev.lightning.capture;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightning's hooks run inside other code's frames and ticks. A bug in Lightning, or a value
 * from another mod it doesn't expect, must never crash the game: hook bodies catch here, which
 * logs the first failure per hook (with its stack) and counts the rest for the report.
 */
public final class Guard {
    private static final Logger LOG = LoggerFactory.getLogger("Lightning");
    private static final ConcurrentHashMap<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();

    /** World timing is switched off for the rest of the session after its hooks fail this often. */
    static final int WORLD_LIMIT = 16;
    public static volatile boolean worldDisabled;

    private Guard() {}

    public static void failed(String hook, Throwable t) {
        int n = FAILURES.computeIfAbsent(hook, k -> new AtomicInteger()).incrementAndGet();
        if (n == 1) LOG.warn("Lightning hook '{}' failed; recording continues without that event. Please report this.", hook, t);
    }

    static void worldFailed(String hook, Throwable t) {
        failed(hook, t);
        if (FAILURES.get(hook).get() >= WORLD_LIMIT && !worldDisabled) {
            worldDisabled = true;
            LOG.warn("Lightning: world timing turned off after repeated failures in '{}'.", hook);
        }
    }

    /** Wraps an event listener so a failure in it is logged and counted instead of crashing the game. */
    public static <E> java.util.function.Consumer<E> safe(String hook, java.util.function.Consumer<E> listener) {
        return e -> {
            try {
                listener.accept(e);
            } catch (RuntimeException t) {
                failed(hook, t);
            }
        };
    }

    /** Hook failures so far, by hook name; empty when everything worked. */
    public static java.util.Map<String, Integer> failures() {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        FAILURES.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
