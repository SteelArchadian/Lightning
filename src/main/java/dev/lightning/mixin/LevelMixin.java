package dev.lightning.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import dev.lightning.capture.WorldHooks;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times every entity and block-entity tick, on both logical sides. */
@Mixin(Level.class)
abstract class LevelMixin {
    @Inject(method = "guardEntityTick", at = @At("HEAD"))
    private void lightning$entityStart(Consumer<?> action, Entity entity, CallbackInfo ci) {
        WorldHooks.entityStart();
    }

    @Inject(method = "guardEntityTick", at = @At("RETURN"))
    private void lightning$entityEnd(Consumer<?> action, Entity entity, CallbackInfo ci) {
        WorldHooks.entityEnd((Level) (Object) this, entity);
    }

    @Inject(method = "tickBlockEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
    private void lightning$blockEntityStart(CallbackInfo ci, @Share("lightningStart") LocalLongRef start) {
        start.set(WorldHooks.blockEntityStart());
    }

    @Inject(method = "tickBlockEntities",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V", shift = At.Shift.AFTER))
    private void lightning$blockEntityEnd(CallbackInfo ci, @Local TickingBlockEntity ticker, @Share("lightningStart") LocalLongRef start) {
        WorldHooks.blockEntityEnd((Level) (Object) this, ticker, start.get());
    }
}
