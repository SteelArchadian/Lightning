package dev.lightning.mixin.client;

import dev.lightning.capture.StartupCapture;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The moment a single-player world is opened or created: the start of a world join. */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
    @Inject(method = "openWorld", at = @At("HEAD"))
    private void lightning$open(CallbackInfo ci) {
        StartupCapture.joinStarted(false);
    }

    @Inject(method = "createFreshLevel", at = @At("HEAD"))
    private void lightning$create(CallbackInfo ci) {
        StartupCapture.joinStarted(false);
    }
}
