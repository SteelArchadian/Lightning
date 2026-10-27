package dev.lightning.mixin.client;

import dev.lightning.capture.StartupCapture;
import net.minecraft.client.gui.screens.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The moment a server join starts. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void lightning$connect(CallbackInfo ci) {
        StartupCapture.joinStarted(true);
    }
}
