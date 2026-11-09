package dev.lightning.mixin.compat;

import dev.lightning.capture.Guard;
import dev.lightning.capture.Track;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loading a shader pack (start-up, F3+R, the shader screen's Apply) stalls one frame for a
 * fraction of a second. That is a reload, not lag: the frame is tagged and left out of fps, lows
 * and spikes.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
abstract class IrisReloadMixin {
    @Inject(method = "loadShaderpack", at = @At("HEAD"), remap = false, require = 0)
    private static void lightning$load(CallbackInfo ci) {
        try {
            Track.notGameplay(Track.SHADER_LOAD);
        } catch (RuntimeException e) {
            Guard.failed("shader pack load tag", e);
        }
    }
}
