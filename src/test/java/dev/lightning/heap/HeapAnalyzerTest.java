package dev.lightning.heap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.lightning.analysis.ModIndex;
import dev.lightning.heap.fixture.BigHolder;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapAnalyzerTest {
    @TempDir
    Path dir;

    @Test
    void chargesArraysToTheModThatHoldsThem() throws Exception {
        BigHolder.INSTANCE = new BigHolder();
        Path dump = dir.resolve("test.hprof");
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
        ModIndex mods = new ModIndex();
        mods.putPackage("dev.lightning.heap.fixture", "testmod");
        String report = new HeapAnalyzer(mods, System.out::println).analyze(dump);
        System.out.println(report.lines().limit(40).reduce("", (a, b) -> a + "\n" + b));
        // 16 MB long[] + 8 MB of byte[] chunks belong to testmod.
        var m = java.util.regex.Pattern.compile("(?m)^testmod\s+([0-9.]+)").matcher(report);
        assertTrue(m.find(), "testmod missing from report");
        double mb = Double.parseDouble(m.group(1));
        assertTrue(mb > 23 && mb < 26, "testmod charged " + mb + " MB");
        assertTrue(report.contains("BigHolder"), "holder class not named");
        Files.writeString(Path.of(System.getProperty("lightning.testOutput", dir.toString()), "test-heap-report.txt"), report);
    }
}
