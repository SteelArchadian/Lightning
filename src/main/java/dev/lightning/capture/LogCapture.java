package dev.lightning.capture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Warnings and errors logged while a capture runs. Repeated messages are merged: numbers and
 * coordinates are masked to form a key, so one spamming mod cannot push out everything else.
 * Each occurrence's time is kept (bounded) so log bursts can be lined up with spikes.
 */
public final class LogCapture extends AbstractAppender {
    private static final Pattern NUMBERS = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final int MAX_KINDS = 400;
    private static final int MAX_OCCURRENCES = 20_000;

    public static final class Entry {
        public final String level, logger, thread, message, exception;
        public final long firstNanos;
        public long lastNanos;
        public int count;
        int index;

        Entry(String level, String logger, String thread, String message, String exception, long nanos) {
            this.level = level;
            this.logger = logger;
            this.thread = thread;
            this.message = message;
            this.exception = exception;
            this.firstNanos = nanos;
            this.lastNanos = nanos;
        }
    }

    private static LogCapture installed;

    private Capture capture;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Entry> kinds = new ArrayList<>();
    private long[] occurrenceTimes = new long[1024];
    private int[] occurrenceKinds = new int[1024];
    private int occurrences;
    private long dropped;

    private LogCapture() {
        super("Lightning", null, null, true, Property.EMPTY_ARRAY);
    }

    /** Adds the appender to the root logger once per game session. */
    public static synchronized LogCapture install() {
        if (installed != null) return installed;
        LogCapture appender = new LogCapture();
        if (LogManager.getRootLogger() instanceof Logger root) {
            appender.start();
            root.addAppender(appender);
            installed = appender;
        }
        return installed;
    }

    synchronized void begin(Capture capture) {
        this.capture = capture;
        entries.clear();
        kinds.clear();
        occurrences = 0;
        dropped = 0;
    }

    synchronized void end() {
        capture = null;
    }

    @Override
    public void append(LogEvent event) {
        if (capture == null || !event.getLevel().isMoreSpecificThan(Level.WARN)) return;
        long now = System.nanoTime();
        String message;
        try {
            message = event.getMessage().getFormattedMessage();
        } catch (RuntimeException e) {
            message = "(unformattable message)";
        }
        if (message == null) message = "";
        if (message.length() > 2000) message = message.substring(0, 2000) + "…";
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        String key = event.getLevel().name() + '|' + logger + '|' + NUMBERS.matcher(message).replaceAll("#");
        String exception = describe(event.getThrown());
        record(key, event.getLevel().name(), logger, event.getThreadName(), message, exception, now);
    }

    /** A warning or error read back from the log file, from before Lightning was listening. */
    synchronized void addEarly(String level, String logger, String thread, String message, long nanos) {
        String key = level + '|' + logger + '|' + NUMBERS.matcher(message).replaceAll("#");
        store(key, level, logger, thread, message.length() > 2000 ? message.substring(0, 2000) + "…" : message, null, nanos);
    }

    private synchronized void record(String key, String level, String logger, String thread, String message, String exception, long now) {
        if (capture == null) return;
        store(key, level, logger, thread, message, exception, now);
    }

    private void store(String key, String level, String logger, String thread, String message, String exception, long now) {
        Entry entry = entries.get(key);
        if (entry == null) {
            if (kinds.size() >= MAX_KINDS) {
                dropped++;
                return;
            }
            entry = new Entry(level, logger, thread, message, exception, now);
            entry.index = kinds.size();
            entries.put(key, entry);
            kinds.add(entry);
        }
        entry.count++;
        entry.lastNanos = now;
        if (occurrences < MAX_OCCURRENCES) {
            if (occurrences == occurrenceTimes.length) {
                occurrenceTimes = java.util.Arrays.copyOf(occurrenceTimes, occurrences * 2);
                occurrenceKinds = java.util.Arrays.copyOf(occurrenceKinds, occurrences * 2);
            }
            occurrenceTimes[occurrences] = now;
            occurrenceKinds[occurrences] = entry.index;
            occurrences++;
        }
    }

    private static String describe(Throwable thrown) {
        if (thrown == null) return null;
        StringBuilder out = new StringBuilder();
        Throwable t = thrown;
        for (int depth = 0; t != null && depth < 4; depth++, t = t.getCause()) {
            if (depth > 0) out.append("Caused by: ");
            out.append(t).append('\n');
            StackTraceElement[] frames = t.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 12); i++) out.append("  at ").append(frames[i]).append('\n');
            if (out.length() > 6000) break;
        }
        return out.toString();
    }

    public record Snapshot(List<Entry> kinds, long[] times, int[] kindIndex, long dropped) {}

    synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(kinds), java.util.Arrays.copyOf(occurrenceTimes, occurrences),
                java.util.Arrays.copyOf(occurrenceKinds, occurrences), dropped);
    }
}
