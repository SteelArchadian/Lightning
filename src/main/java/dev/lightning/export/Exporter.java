package dev.lightning.export;

import dev.lightning.analysis.Analyzer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a capture as a portable {@code .lightning} archive and a standalone HTML report.
 *
 * <pre>
 * capture.lightning (ZIP)
 *   profile.json    full structured data (format "lightning-profile", version 2)
 *   summary.md      plain-language brief with the key tables, for people and language models
 *   results.json    headline numbers (whole capture and per phase) for comparing runs
 *   viewer.html     the interactive report with the data embedded
 *   recording.jfr   raw JVM recording, when one was made (open with JDK Mission Control)
 *   README.txt
 * </pre>
 */
public final class Exporter {
    public record Result(String id, Path archive, Path html, String headline) {}

    private static final String PLACEHOLDER = "__LIGHTNING_DATA__";
    private static final long MAX_EMBEDDED_JFR = 256L << 20;

    private Exporter() {}

    public static Result export(Analyzer.Result analysis, Path directory) throws IOException {
        return export(analysis, directory, true);
    }

    public static Result export(Analyzer.Result analysis, Path directory, boolean withViewer) throws IOException {
        Files.createDirectories(directory);
        String base = "lightning-" + analysis.id();
        Path archive = unique(directory, base, ".lightning");
        Path html = directory.resolve(archive.getFileName().toString().replace(".lightning", ".html"));
        if (withViewer) {
            Path tmpHtml = directory.resolve(html.getFileName() + ".partial");
            try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(tmpHtml), 1 << 16)) {
                viewer(analysis.profileFile(), out);
            }
            Files.move(tmpHtml, html, StandardCopyOption.REPLACE_EXISTING);
        }

        Path tmpArchive = directory.resolve(archive.getFileName() + ".partial");
        try (OutputStream file = Files.newOutputStream(tmpArchive); ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("profile.json"));
            Files.copy(analysis.profileFile(), zip);
            zip.closeEntry();
            put(zip, "summary.md", analysis.summary().getBytes(StandardCharsets.UTF_8));
            put(zip, "results.json", analysis.results());
            if (withViewer) {
                zip.putNextEntry(new ZipEntry("viewer.html"));
                Files.copy(html, zip);
                zip.closeEntry();
            }
            Path jfr = analysis.jfrFile();
            if (jfr != null && Files.isRegularFile(jfr) && Files.size(jfr) <= MAX_EMBEDDED_JFR) {
                zip.putNextEntry(new ZipEntry("recording.jfr"));
                Files.copy(jfr, zip);
                zip.closeEntry();
            }
            put(zip, "README.txt", readme().getBytes(StandardCharsets.UTF_8));
        }
        Files.move(tmpArchive, archive, StandardCopyOption.REPLACE_EXISTING);
        // Headline numbers beside the archive, for scripts comparing runs.
        Files.write(directory.resolve(archive.getFileName().toString().replace(".lightning", ".results.json")), analysis.results());
        String headline = analysis.findings().isEmpty() ? "" : analysis.findings().get(0).title();
        return new Result(analysis.id(), archive, withViewer ? html : null, headline);
    }

    private static Path unique(Path dir, String base, String ext) {
        Path p = dir.resolve(base + ext);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "-" + i + ext);
        return p;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** The viewer template with the profile embedded as gzip + base64 (decompressed by the browser), streamed. */
    static void viewer(Path json, OutputStream out) throws IOException {
        String template;
        try (InputStream in = Exporter.class.getResourceAsStream("/assets/lightning/viewer.html")) {
            if (in == null) throw new IOException("Viewer template missing from the mod jar");
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int at = template.indexOf(PLACEHOLDER);
        if (at < 0) throw new IOException("Viewer template has no data placeholder");
        out.write(template.substring(0, at).getBytes(StandardCharsets.UTF_8));
        // Base64 stream left open (closing it would close the file); gzip's close flushes its last bytes into it.
        OutputStream base64 = Base64.getEncoder().wrap(new java.io.FilterOutputStream(out) {
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                flush();
            }
        });
        try (GZIPOutputStream gz = new GZIPOutputStream(base64, 1 << 16)) {
            Files.copy(json, gz);
        }
        out.write(template.substring(at + PLACEHOLDER.length()).getBytes(StandardCharsets.UTF_8));
    }

    private static String readme() {
        return """
                Lightning capture
                =================
                viewer.html   Open in a web browser for the interactive report (works offline).
                summary.md    Plain-text brief with the key measurements; suitable to paste into a chat with an analyst or AI.
                profile.json  All structured data (format "lightning-profile", version 2). Times are microseconds.
                recording.jfr Raw Java Flight Recorder data, if JVM sampling was on. Opens in JDK Mission Control.
                """;
    }
}
