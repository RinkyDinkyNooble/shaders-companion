package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.CaptureBufferSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While the mod draws an entity into its views, the main buffer source is its capture (see CaptureBufferSource). */
@Mixin(RenderBuffers.class)
public abstract class RenderBuffersMixin {
    @Inject(method = "bufferSource", at = @At("HEAD"), cancellable = true)
    private void shaderscompanion$captureBuffers(CallbackInfoReturnable<MultiBufferSource.BufferSource> cir) {
        MultiBufferSource.BufferSource capture = CaptureBufferSource.current();
        if (capture != null) {
            cir.setReturnValue(capture);
        }
    }
}
