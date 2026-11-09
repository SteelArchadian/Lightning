package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VmHitchTest {
    @Test
    void namesDeoptsAndTheClassDefinedBeforeIt() {
        JfrDigest.Pause op = new JfrDigest.Pause(1_000_000_000L, 115_000_000L, "Render thread", "vm operation", "HandshakeAllThreads", "");
        List<JfrDigest.Deopt> deopts = List.of(
                new JfrDigest.Deopt(1_010_000_000L, "Render thread", "net.minecraft.Foo.bar", "minecraft", "class_check"),
                new JfrDigest.Deopt(5_000_000_000L, "Render thread", "late.Out.ofWindow", "x", "null_check"));
        List<JfrDigest.ClassDef> defs = List.of(new JfrDigest.ClassDef(800_000_000L, "com.example.NewThing", "examplemod"));
        String d = VmHitch.explain(op, deopts, defs);
        assertNotNull(d);
        assertTrue(d.contains("1 compiled method was thrown away"), d);
        assertTrue(d.contains("class_check ×1") && d.contains("com.example.NewThing [examplemod]") && !d.contains("null_check"), d);
        assertNull(VmHitch.explain(op, List.of(), List.of()));
    }
}
