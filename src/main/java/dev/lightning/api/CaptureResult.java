package dev.lightning.api;

import java.nio.file.Path;

/**
 * A saved capture.
 *
 * @param archive               the .lightning file (profile.json, summary.md, results.json inside)
 * @param resultsJson           results.json beside the archive (schema_version 2)
 * @param html                  interactive report, or null when not requested
 * @param gameThreadOverheadPct estimated share of render/server thread time spent in Lightning's hooks
 * @param backgroundCores       CPU cores used on average by Lightning's and JFR's own threads
 */
public record CaptureResult(Path archive, Path resultsJson, Path html, double gameThreadOverheadPct, double backgroundCores) {}
