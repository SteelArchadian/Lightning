package dev.lightning.capture;

import jdk.jfr.FlightRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initialises Java Flight Recorder in the background while the game is still loading.
 *
 * <p>The first initialisation of JFR in a JVM redefines core JDK classes ({@code Throwable},
 * {@code FileInputStream}, socket and channel classes) to add its event hooks. Redefining a class
 * discards compiled code that depends on it, so doing this in the middle of play slows the game
 * for seconds while the JIT recompiles. During loading little is compiled yet, so the cost is
 * small and invisible. Disable with {@code -Dlightning.jfrWarmup=false}.
 */
public final class JfrWarmup {
    private static final Logger LOG = LoggerFactory.getLogger("Lightning");
    private static volatile long warmupNanos = -1;
    private static volatile String status = "not started";

    private JfrWarmup() {}

    public static void startDuringLoading() {
        if (!Boolean.parseBoolean(System.getProperty("lightning.jfrWarmup", "true"))) {
            status = "disabled by -Dlightning.jfrWarmup=false";
            return;
        }
        Thread t = new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                if (!FlightRecorder.isAvailable()) {
                    status = "JFR not available in this Java runtime";
                    return;
                }
                FlightRecorder.getFlightRecorder();
                warmupNanos = System.nanoTime() - t0;
                status = "initialised during loading";
            } catch (RuntimeException | LinkageError e) {
                status = "failed: " + e;
                LOG.warn("JFR warm-up failed; JVM sampling will initialise when a capture starts", e);
            }
        }, "Lightning JFR warm-up");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static String status() {
        return status;
    }

    public static long warmupNanos() {
        return warmupNanos;
    }
}
