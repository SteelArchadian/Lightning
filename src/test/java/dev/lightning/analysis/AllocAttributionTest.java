package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class AllocAttributionTest {
    @Test
    void wrapperModIsOnStackButNotCharged() {
        // vanilla ResourceLocation.parse ← vanilla codec ← CodecUI wrapper ← vanilla ChunkSerializer
        String[] owners = {"java", "minecraft", "minecraft", "codecui", "minecraft"};
        assertArrayEquals(new String[] {"minecraft", "codecui"}, JfrDigest.chargeAllocation(owners));
    }

    @Test
    void modAllocatingItselfIsCharged() {
        String[] owners = {"java", "library", "sodium", "minecraft"};
        assertArrayEquals(new String[] {"sodium", "sodium"}, JfrDigest.chargeAllocation(owners));
    }

    @Test
    void pureJdkAndVanilla() {
        assertArrayEquals(new String[] {"java", null}, JfrDigest.chargeAllocation(new String[] {"java", "library"}));
        assertArrayEquals(new String[] {"minecraft", null}, JfrDigest.chargeAllocation(new String[] {"java", "minecraft", "neoforge"}));
    }
}
