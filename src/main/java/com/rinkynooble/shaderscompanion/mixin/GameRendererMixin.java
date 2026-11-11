package com.rinkynooble.shaderscompanion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rinkynooble.shaderscompanion.client.CubeView;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the cube and mirror views each frame just before the world is rendered, outside Oculus's world pass, and
 * collects the entities for the next frame's views just after it.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"
            )
    )
    private void shaderscompanion$beforeLevel(float partialTick, long finishNanos, PoseStack poseStack, CallbackInfo ci) {
        CubeView.renderFrame(((GameRenderer) (Object) this).getMainCamera(), partialTick, new Matrix4f(poseStack.last().pose()));
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void shaderscompanion$afterLevel(float partialTick, long finishNanos, PoseStack poseStack, CallbackInfo ci) {
        CubeView.collectEntitiesAfterFrame(((GameRenderer) (Object) this).getMainCamera(), partialTick);
    }
}
