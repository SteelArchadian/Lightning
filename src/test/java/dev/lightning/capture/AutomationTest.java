package dev.lightning.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutomationTest {
    @Test
    void aTypoInThePartsFlagKeepsTheKnownParts() {
        System.setProperty("lightning.capture", "gpu,rendr,cpu");
        try {
            CaptureOptions o = Automation.options("");
            assertTrue(o.gpu() != GpuTimer.Mode.OFF);
            assertFalse(o.ticks());
        } finally {
            System.clearProperty("lightning.capture");
        }
    }

    @Test
    void aBadExtraOptionFallsBackToTheBase() {
        System.setProperty("lightning.options", "--no-such-option");
        try {
            Automation.options("--gpu frame");
        } finally {
            System.clearProperty("lightning.options");
        }
    }

    @Test
    void knownPartsDropsUnknownNames() {
        assertEquals("gpu,cpu", Automation.knownParts("GPU, bogus ,cpu"));
        assertTrue(dev.lightning.LightningConfig.validParts("gpu, render"));
        assertFalse(dev.lightning.LightningConfig.validParts("gpu,rendr"));
    }
}
