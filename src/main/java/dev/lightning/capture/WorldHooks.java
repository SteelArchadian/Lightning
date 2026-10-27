package dev.lightning.capture;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/** Entry points for the entity and block-entity tick mixins. Each is a no-op when idle. */
public final class WorldHooks {
    public static volatile boolean entityHookObserved;
    public static volatile boolean blockEntityHookObserved;

    private WorldHooks() {}

    public static void entityStart() {
        Capture c = Capture.ACTIVE;
        if (c == null || !c.options.world()) return;
        Track t = Capture.track();
        if (t == null) return;
        int d = t.entityDepth;
        if (d < t.entityStart.length) t.entityStart[d] = System.nanoTime();
        t.entityDepth = d + 1;
    }

    public static void entityEnd(Level level, Entity entity) {
        Capture c = Capture.ACTIVE;
        if (c == null || !c.options.world()) return;
        long now = System.nanoTime();
        Track t = Capture.track();
        if (t == null || t.entityDepth == 0) return;
        int d = --t.entityDepth;
        if (d >= t.entityStart.length) return;
        if (!entityHookObserved) entityHookObserved = true;
        t.world.entity(level, entity, now - t.entityStart[d]);
    }

    /** Called just before a block entity ticks. */
    public static long blockEntityStart() {
        Capture c = Capture.ACTIVE;
        return c == null || !c.options.world() ? 0L : System.nanoTime();
    }

    public static void blockEntityEnd(Level level, TickingBlockEntity ticker, long start) {
        if (start == 0L) return;
        long now = System.nanoTime();
        Track t = Capture.track();
        if (t == null) return;
        if (!blockEntityHookObserved) blockEntityHookObserved = true;
        t.world.blockEntity(level, ticker, now - start);
    }
}
