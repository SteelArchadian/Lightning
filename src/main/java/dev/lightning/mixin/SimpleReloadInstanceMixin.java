package dev.lightning.mixin;

import dev.lightning.capture.ReloadTiming;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every resource and data pack reload is built here. During captures and game start-up Lightning
 * times each listener (model baking, fonts, a mod's data). Only the listener handed to its own
 * reload step is wrapped; the listener list, which other mods inspect by type, is left alone.
 */
@Mixin(SimpleReloadInstance.class)
abstract class SimpleReloadInstanceMixin {
    @Shadow
    protected CompletableFuture<?> allDone;
    @Unique
    private ReloadTiming lightning$timing;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            index = 2, require = 1)
    private PreparableReloadListener lightning$time(PreparableReloadListener listener) {
        try {
            if (lightning$timing == null) lightning$timing = ReloadTiming.begin();
            return lightning$timing.track(listener);
        } catch (RuntimeException e) {
            return listener;
        }
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void lightning$whenDone(CallbackInfo ci) {
        ReloadTiming timing = lightning$timing;
        if (timing != null && timing.active() && allDone != null) allDone.whenComplete((result, error) -> timing.done());
    }
}
