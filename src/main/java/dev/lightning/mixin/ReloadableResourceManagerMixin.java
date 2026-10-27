package dev.lightning.mixin;

import dev.lightning.capture.LoadTimeline;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Resource-pack (asset) reloads go through here; data-pack reloads do not. */
@Mixin(ReloadableResourceManager.class)
abstract class ReloadableResourceManagerMixin {
    @Inject(method = "createReload", at = @At("HEAD"))
    private void lightning$starting(CallbackInfoReturnable<?> cir) {
        LoadTimeline.resourceReloadStarting();
    }
}
