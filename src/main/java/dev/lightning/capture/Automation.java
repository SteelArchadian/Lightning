package dev.lightning.capture;

import dev.lightning.export.Exporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JVM flags for scripted runs (benchmarks, A/B tests driven by other tools):
 * <ul>
 *   <li>{@code -Dlightning.capture=gpu,render,cpu}: record only these parts (see {@link CaptureOptions#MODULES})</li>
 *   <li>{@code -Dlightning.options="--gpu-every 1 --interval 10"}: any other start options</li>
 *   <li>{@code -Dlightning.output=<folder>}: where the .lightning and .results.json files go</li>
 *   <li>{@code -Dlightning.label=<name>}: run name stored in the report and results</li>
 *   <li>{@code -Dlightning.html=false}: skip the browser report (saves a few seconds per run)</li>
 *   <li>{@code -Dlightning.resultsFile=<path>}: also copy results.json to exactly this path</li>
 *   <li>{@code -Dlightning.windows=30}: split every capture into 30-second phases</li>
 *   <li>{@code -Dlightning.gpuEvery=1}, {@code -Dlightning.interval=10}: the same as those start options, without spaces</li>
 * </ul>
 */
public final class Automation {
    private Automation() {}

    /** {@code base} options plus whatever the flags ask for. */
    public static CaptureOptions options(String base) {
        StringBuilder args = new StringBuilder(base == null ? "" : base);
        String modules = System.getProperty("lightning.capture", "").trim();
        if (modules.isEmpty()) modules = config(() -> dev.lightning.LightningConfig.CAPTURE_PARTS.get());
        if (!modules.isEmpty()) args.append(" --only ").append(modules.replace(" ", ""));
        // Single-value flags without spaces, for launchers that split arguments on whitespace.
        String every = System.getProperty("lightning.gpuEvery", "").trim();
        if (!every.isEmpty()) args.append(" --gpu-every ").append(every);
        String interval = System.getProperty("lightning.interval", "").trim();
        if (!interval.isEmpty()) args.append(" --interval ").append(interval);
        String extra = System.getProperty("lightning.options", "").trim().replace(',', ' ');
        if (!extra.isEmpty()) args.append(' ').append(extra);
        return CaptureOptions.parse(args.toString().trim());
    }

    /** Seconds per automatic window phase (flag, else config), or 0 for none. */
    public static int windowSeconds() {
        Integer flag = Integer.getInteger("lightning.windows");
        if (flag != null) return Math.max(0, flag);
        String v = config(() -> String.valueOf(dev.lightning.LightningConfig.WINDOW_SECONDS.get()));
        return v.isEmpty() ? 0 : Integer.parseInt(v);
    }

    private static String config(java.util.function.Supplier<String> value) {
        try {
            return dev.lightning.LightningConfig.loaded() ? value.get().trim() : "";
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    public static Path outputDir() {
        String dir = System.getProperty("lightning.output", "").trim();
        return dir.isEmpty() ? null : Path.of(dir);
    }

    public static String label(String fallback) {
        String label = System.getProperty("lightning.label", "").trim();
        return label.isEmpty() ? fallback : label;
    }

    public static boolean html() {
        return !"false".equalsIgnoreCase(System.getProperty("lightning.html", "true").trim());
    }

    /** Copies the run's results.json to {@code -Dlightning.resultsFile}, if set. */
    public static void copyResults(Exporter.Result result) {
        String target = System.getProperty("lightning.resultsFile", "").trim();
        if (target.isEmpty() || result == null) return;
        try {
            Path results = result.archive().resolveSibling(result.archive().getFileName().toString().replace(".lightning", ".results.json"));
            Path to = Path.of(target);
            if (to.getParent() != null) Files.createDirectories(to.getParent());
            Files.copy(results, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("Lightning").warn("Could not copy results to " + target, e);
        }
    }
}
