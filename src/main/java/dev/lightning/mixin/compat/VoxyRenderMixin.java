package dev.lightning.mixin.compat;

import dev.lightning.capture.CompatHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Voxy draws its distant-terrain LODs here; it has no profiler section of its own. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
abstract class VoxyRenderMixin {
    @Inject(method = "renderOpaque", at = @At("HEAD"), remap = false, require = 0)
    private void lightning$start(CallbackInfo ci) {
        CompatHooks.voxyObserved = true;
        CompatHooks.push("voxy LODs");
    }

    @Inject(method = "renderOpaque", at = @At("RETURN"), remap = false, require = 0)
    private void lightning$end(CallbackInfo ci) {
        CompatHooks.pop();
    }
}
