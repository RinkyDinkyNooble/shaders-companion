package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.MirrorShadows;
import net.irisshaders.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oculus's shadow pass draws only what can cast a shadow into the main view. While the mirror view is drawn, what can
 * cast a shadow into the mirror view is kept too, so reflections of what is behind the player keep their shadows (see
 * {@link MirrorShadows}). Both checks take a box relative to the camera, after Oculus's shadow distance check.
 */
@Mixin(value = AdvancedShadowCullingFrustum.class, remap = false)
public abstract class ShadowCullingMixin {
    @Shadow
    @Final
    private Vector3f shadowLightVectorFromOrigin;

    @Inject(method = "checkCornerVisibility", at = @At("RETURN"), cancellable = true)
    private void shaderscompanion$keepMirrorCasters(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                                    CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 0 && MirrorShadows.casts(this.shadowLightVectorFromOrigin, minX, minY, minZ, maxX, maxY, maxZ)) {
            cir.setReturnValue(2);
        }
    }

    @Inject(method = "checkCornerVisibilityBool", at = @At("RETURN"), cancellable = true)
    private void shaderscompanion$keepMirrorCastersBool(float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && MirrorShadows.casts(this.shadowLightVectorFromOrigin, minX, minY, minZ, maxX, maxY, maxZ)) {
            cir.setReturnValue(true);
        }
    }
}
