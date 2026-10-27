package dev.lightning.capture;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the game's own log files for what happened before Lightning was loaded: the mod loader's
 * steps (for the loading timeline) and early warnings and errors (for the log view). The log
 * switches format once logging is reconfigured, from {@code [HH:mm:ss]} to
 * {@code [ddMMMyyyy HH:mm:ss.SSS]}; both are read.
 */
public final class EarlyLog {
    public record Line(long nanos, String thread, String level, String logger, String message) {}

    /** Log lines that start a loading step, and the step's name. */
    private static final String[][] STEPS = {
            {"ModLauncher running", "Starting the mod loader", "phase"},
            {"Found mod file", "Finding mods", "phase"},
            {"Launching target", "Loading Minecraft's code", "phase"},
            {"Setting user:", "Minecraft starting up", "milestone"},
            {"Backend library: LWJGL", "Game window opened", "milestone"},
            {"Creating FMLModContainer instance", "Building mods", "phase"},
    };

    private static final Pattern FULL = Pattern.compile("^\\[(\\d{2}\\p{L}{3,}\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})] \\[([^\\]]*)/(\\w+)] \\[([^\\]]*)]: (.*)$");
    private static final Pattern SHORT = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})] \\[([^\\]]*)/(\\w+)](?: \\[([^\\]]*)])?: (.*)$");

    private EarlyLog() {}

    /**
     * Lines logged between the JVM start and {@code untilNanos}, from latest.log (and debug.log for
     * lines it alone has, such as DEBUG-level mod loading). Sorted by time.
     */
    public static List<Line> read(Path logsDir, long untilNanos) {
        List<Line> out = new ArrayList<>();
        long epochOffset = System.currentTimeMillis() * 1_000_000L - System.nanoTime();
        LocalDate jvmDay = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli((LoadTimeline.JVM_START_NANOS + epochOffset) / 1_000_000L),
                ZoneId.systemDefault()).toLocalDate();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : new String[] {"latest.log", "debug.log"}) {
            Path file = logsDir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String text;
                while ((text = in.readLine()) != null) {
                    Line line = parse(text, jvmDay, epochOffset);
                    if (line == null) continue;
                    if (line.nanos > untilNanos) break;
                    if (line.nanos < LoadTimeline.JVM_START_NANOS - 5_000_000_000L) continue; // an older session's file
                    // debug.log repeats latest.log's lines; keep one copy (by second + message).
                    if (seen.add(line.message.hashCode() + "|" + line.nanos / 1_000_000_000L)) out.add(line);
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable or locked: the timeline just starts later.
            }
        }
        out.sort(java.util.Comparator.comparingLong(Line::nanos));
        return out;
    }

    private static final DateTimeFormatter[] FULL_FORMATS = {
            DateTimeFormatter.ofPattern("ddMMMyyyy HH:mm:ss.SSS", Locale.getDefault()),
            DateTimeFormatter.ofPattern("ddMMMyyyy HH:mm:ss.SSS", Locale.ENGLISH),
    };

    static Line parse(String text, LocalDate jvmDay, long epochOffset) {
        Matcher m = FULL.matcher(text);
        LocalDateTime when = null;
        if (m.matches()) {
            for (DateTimeFormatter f : FULL_FORMATS) {
                try {
                    when = LocalDateTime.parse(m.group(1), f);
                    break;
                } catch (RuntimeException ignored) {
                    // Try the next locale.
                }
            }
        } else {
            m = SHORT.matcher(text);
            if (!m.matches()) return null;
            // Seconds only: the middle of the second is the best estimate.
            when = LocalDateTime.of(jvmDay, LocalTime.parse(m.group(1))).plusNanos(500_000_000L);
        }
        if (when == null) return null;
        long epochNanos = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() * 1_000_000L;
        long nanos = epochNanos - epochOffset;
        String logger = m.group(4) == null ? "" : m.group(4);
        int slash = logger.indexOf('/');
        if (slash >= 0) logger = logger.substring(0, slash);
        return new Line(nanos, m.group(2), m.group(3), logger, m.group(5));
    }

    /** Loading steps found in the lines: the first line matching each step. */
    public static List<LoadTimeline.Milestone> steps(List<Line> lines) {
        List<LoadTimeline.Milestone> out = new ArrayList<>();
        boolean[] found = new boolean[STEPS.length];
        for (Line line : lines) {
            for (int i = 0; i < STEPS.length; i++) {
                if (!found[i] && line.message.contains(STEPS[i][0])) {
                    found[i] = true;
                    out.add(new LoadTimeline.Milestone(line.nanos, STEPS[i][1], STEPS[i][2].equals("phase")));
                }
            }
        }
        return out;
    }
}
