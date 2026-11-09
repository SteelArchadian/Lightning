package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OtherProcessesTest {
    @Test
    void measuresOwnAndOtherCpu() throws Exception {
        OtherProcesses start = OtherProcesses.snapshot();
        // A child process that spins for half a second counts as another program.
        Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"),
                Spin.class.getName()).start();
        long until = System.nanoTime() + 600_000_000L;
        long x = 0;
        while (System.nanoTime() < until) x += x * 31 + 7; // own CPU
        child.waitFor();
        OtherProcesses.Usage u = OtherProcesses.between(start, OtherProcesses.snapshot());
        assertTrue(u.ownCores() > 0.3, "own " + u.ownCores() + " " + x);
        assertTrue(u.processors() > 0);
        assertTrue(u.others().stream().anyMatch(r -> r.name().startsWith("java")), u.others().toString());
        assertEquals("javaw", OtherProcesses.exeName("C:\\Program Files\\Java\\bin\\javaw.exe"));
        assertEquals("java", OtherProcesses.exeName("/usr/bin/java"));
    }

    public static final class Spin {
        public static void main(String[] args) {
            long until = System.nanoTime() + 400_000_000L;
            long x = 0;
            while (System.nanoTime() < until) x += x * 31 + 7;
            if (x == 42) System.out.println();
        }
    }
}
