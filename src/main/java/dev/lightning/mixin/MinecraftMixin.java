package dev.lightning.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.lightning.capture.SectionProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The client builds a fresh profiler for every frame. Normally it is the no-op profiler, so
 * Minecraft's own {@code push}/{@code pop} markers go nowhere; while recording, Lightning tees
 * its recorder in. One field read per frame when idle.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @ModifyReturnValue(method = "constructProfiler", at = @At("RETURN"))
    private ProfilerFiller lightning$record(ProfilerFiller filler) {
        return SectionProfiler.wrapClient(filler);
    }
}
