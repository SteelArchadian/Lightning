package dev.lightning.mixin.compat;

import dev.lightning.capture.CompatHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Voxy's LOD pipeline (normal or the Iris variant): culling, opaque and translucent LOD draws. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.AbstractRenderPipeline", remap = false)
abstract class VoxyPipelineMixin {
    @Inject(method = "runPipeline", at = @At("HEAD"), remap = false, require = 0)
    private void lightning$start(CallbackInfo ci) {
        CompatHooks.push("voxy pipeline");
    }

    @Inject(method = "runPipeline", at = @At("RETURN"), remap = false, require = 0)
    private void lightning$end(CallbackInfo ci) {
        CompatHooks.pop();
    }
}
