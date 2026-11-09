package dev.lightning.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ListenersTest {
    @Test
    void chargesTheFirstMethodTheBusCalls() {
        FrameTable f = new FrameTable(new ModIndex());
        FlameTree t = new FlameTree();
        int render = f.special("thread: Render thread");
        int poster = f.method("net.neoforged.neoforge.client.ClientHooks", "fireClientTickPost");
        int bridge = f.method("net.neoforged.neoforge.client.ClientHooks", "mixinextras$bridge$post$135");
        int post = f.method("net.neoforged.bus.EventBus", "post");
        int invoke = f.method("net.neoforged.bus.ConsumerEventHandler", "invoke");
        int lambda = f.method("com.example.Mod$$Lambda/0x123", "accept");
        int listener = f.method("com.example.Mod", "onTick");
        int inner = f.method("com.example.Mod", "work");
        int other = f.method("net.minecraft.client.Minecraft", "runTick");
        // 30 samples inside the listener (20 deeper in its own code), 70 elsewhere on the render thread.
        t.add(render, new int[] {other, poster, bridge, post, invoke, lambda, listener}, 7, 10);
        t.add(render, new int[] {other, poster, bridge, post, invoke, lambda, listener, inner}, 8, 20);
        t.add(render, new int[] {other}, 1, 70);
        List<Listeners.Row> rows = Listeners.of(t, f, 10_000_000, 0);
        assertEquals(1, rows.size(), rows.toString());
        Listeners.Row r = rows.get(0);
        assertEquals("com.example.Mod.onTick", r.listener());
        assertEquals("net.neoforged.neoforge.client.ClientHooks.fireClientTickPost", r.event());
        assertEquals("Render thread", r.thread());
        assertEquals(30, r.samples());
        assertEquals(30.0, r.threadPct(), 1e-9);
        assertEquals(3.0, r.ms(), 1e-9); // 30% of a 10 ms frame
        assertTrue(Listeners.glue("java.lang.invoke.LambdaForm$DMH.invokeVirtual"));
    }
}
