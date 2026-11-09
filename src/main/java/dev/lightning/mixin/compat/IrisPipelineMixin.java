package dev.lightning.mixin.compat;

import dev.lightning.capture.Guard;
import dev.lightning.capture.Track;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris builds a dimension's pipeline (compiles every program of the pack) on the first frame
 * after a pack load or dimension change. Only that frame is tagged: the factory call runs once
 * per build, while preparePipeline itself runs every frame.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.PipelineManager", remap = false)
abstract class IrisPipelineMixin {
    @Inject(method = "preparePipeline", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private void lightning$build(CallbackInfoReturnable<?> cir) {
        try {
            Track.notGameplay(Track.SHADER_LOAD);
        } catch (RuntimeException e) {
            Guard.failed("shader pipeline tag", e);
        }
    }
}
