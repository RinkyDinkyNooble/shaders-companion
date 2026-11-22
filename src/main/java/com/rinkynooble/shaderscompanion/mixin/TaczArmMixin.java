package com.rinkynooble.shaderscompanion.mixin;

import com.tacz.guns.util.RenderHelper;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TaCZ draws the player's arms inside the first-person gun ({@link TaczFirstPersonMixin} gives that the gun's item id):
 * the arms are drawn without it, as Minecraft draws a hand, so skin and sleeves take no material.
 */
@Mixin(value = RenderHelper.class, remap = false)
public abstract class TaczArmMixin {
    @Unique
    private static int shaderscompanion$gunId;

    @Inject(method = "renderFirstPersonArm", at = @At("HEAD"))
    private static void shaderscompanion$armWithoutItem(CallbackInfo ci) {
        shaderscompanion$gunId = CapturedRenderingState.INSTANCE.getCurrentRenderedItem();
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
    }

    @Inject(method = "renderFirstPersonArm", at = @At("RETURN"))
    private static void shaderscompanion$armDone(CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(shaderscompanion$gunId);
    }
}
