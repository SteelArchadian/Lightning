package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class GuardTest {
    @Test
    void aFailingListenerIsCountedNotThrown() {
        var listener = Guard.safe("test listener", (String s) -> { throw new IllegalStateException(s); });
        assertDoesNotThrow(() -> listener.accept("boom"));
        assertDoesNotThrow(() -> listener.accept("again"));
        assertEquals(2, Guard.failures().get("test listener"));
    }

    @Test
    void worldTimingTurnsOffAfterRepeatedFailures() {
        for (int i = 0; i < Guard.WORLD_LIMIT; i++) Guard.worldFailed("test world hook", new RuntimeException());
        assertEquals(true, Guard.worldDisabled);
        Guard.worldDisabled = false;
    }
}
