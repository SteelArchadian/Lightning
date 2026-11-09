package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VmPauseTest {
    @Test
    void zgcHandshakeIsNotAStopTheWorldPause() {
        JfrDigest.Pause handshake = new JfrDigest.Pause(0, 60_000_000L, "ZWorkerYoung#0", "vm operation", "HandshakeAllThreads", "");
        assertFalse(handshake.stopsAll());
        assertTrue(handshake.fromGc());
        JfrDigest.Pause dump = new JfrDigest.Pause(0, 20_000_000L, "JFR Periodic Tasks", "safepoint", "ThreadDump", "");
        assertTrue(dump.stopsAll());
        assertFalse(dump.fromGc());
    }
}
