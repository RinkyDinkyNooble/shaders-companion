package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): marks around the level in GameRenderer.renderLevel. */
@Mixin(value = GameRenderer.class, remap = false)
public abstract class ProfileGameRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void shaderscompanion$begin(CallbackInfo ci) {
        PassProfiler.mark("game renderLevel setup (camera, mod hook before level)");
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void shaderscompanion$after(CallbackInfo ci) {
        PassProfiler.mark("after level (mod entity collection, hand)");
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void shaderscompanion$end(CallbackInfo ci) {
        PassProfiler.mark("after renderLevel (GUI, rest of frame)");
    }
}
