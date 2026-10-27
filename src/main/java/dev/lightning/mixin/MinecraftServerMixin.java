package dev.lightning.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.lightning.capture.SectionProfiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The server builds its profiler at the start of every tick; tee the recorder in while recording. */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
    @ModifyExpressionValue(method = "startMetricsRecordingTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/SingleTickProfiler;decorateFiller(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/util/profiling/SingleTickProfiler;)Lnet/minecraft/util/profiling/ProfilerFiller;"))
    private ProfilerFiller lightning$record(ProfilerFiller filler) {
        return SectionProfiler.wrapServer(filler);
    }
}
