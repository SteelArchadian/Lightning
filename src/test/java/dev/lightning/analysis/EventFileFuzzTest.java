package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.fail;

import dev.lightning.capture.EventFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Damaged event files (crash, power loss, disk errors) may fail to read, but never with anything but an IOException. */
class EventFileFuzzTest {
    @TempDir
    Path dir;

    @Test
    void randomTailsNeverThrowUnexpectedly() throws IOException {
        Random random = new Random(42);
        Path file = dir.resolve("events.bin");
        for (int round = 0; round < 2000; round++) {
            int length = 5 + random.nextInt(400);
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            bytes[0] = 'L';
            bytes[1] = 'T';
            bytes[2] = 'E';
            bytes[3] = 'V';
            bytes[4] = 1;
            // Mostly well-formed record starts, so the fuzzing gets past the first byte.
            if (length > 5 && random.nextBoolean()) bytes[5] = (byte) (1 + random.nextInt(3));
            // A zeroed tail, as a power loss can leave behind.
            if (random.nextInt(4) == 0) java.util.Arrays.fill(bytes, 5 + random.nextInt(length - 4), length, (byte) 0);
            Files.write(file, bytes);
            SpanDigest spans = new SpanDigest(new ArrayList<>(), Map.of());
            try {
                EventFile.read(file, spans);
                spans.finish();
            } catch (IOException expected) {
                // fine: unusable file, reported as such
            } catch (RuntimeException | OutOfMemoryError e) {
                fail("round " + round + ": " + e, e);
            }
        }
    }
}
