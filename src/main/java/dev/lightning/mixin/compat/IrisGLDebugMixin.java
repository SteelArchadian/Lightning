package dev.lightning.mixin.compat;

import dev.lightning.capture.CompatHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris wraps every shader pass (shadow, begin/prepare/deferred/composite programs, final) in a
 * named GL debug group. Recording those groups as sections gives each pass, by the shader pack's
 * own program name, its CPU and GPU time.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.GLDebug", remap = false)
abstract class IrisGLDebugMixin {
    @Inject(method = "pushGroup", at = @At("HEAD"), remap = false, require = 0)
    private static void lightning$push(int id, String name, CallbackInfo ci) {
        CompatHooks.irisObserved = true;
        CompatHooks.push(name);
    }

    @Inject(method = "popGroup", at = @At("HEAD"), remap = false, require = 0)
    private static void lightning$pop(CallbackInfo ci) {
        CompatHooks.pop();
    }
}
