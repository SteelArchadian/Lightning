package dev.lightning.analysis;

/**
 * One observation for the report. {@code basis} says how it was measured, so a reader can tell
 * an exact timing from a statistical sample or a correlation in time.
 *
 * @param severity "high", "medium", "low" or "info"
 * @param area     "frames", "server", "world", "memory", "jvm", "logs" or "lightning"
 * @param link     where the viewer should jump to show the evidence (e.g. "sections:render")
 */
public record Finding(String severity, String area, String title, String detail, String basis, String link) {}
