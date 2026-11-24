package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.client.far.FarRenderer;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-only (-PdevProfile): the far terrain's own pass (gpu-lods M2b): its selection and draw as "pass far terrain", beside
 * Distant Horizons' "pass dh_terrain", and its debug view's overlay after the world.
 */
@Mixin(value = FarRenderer.class, remap = false)
public abstract class ProfileFarMixin {
    @Inject(method = "draw", at = @At("HEAD"))
    private static void shaderscompanion$draw(CallbackInfo ci) {
        PassProfiler.mark("pass far terrain");
    }

    @Inject(method = "draw", at = @At("RETURN"))
    private static void shaderscompanion$drawn(CallbackInfo ci) {
        PassProfiler.mark("mod far store");
    }

    @Inject(method = "overlay", at = @At("HEAD"))
    private static void shaderscompanion$overlay(CallbackInfo ci) {
        PassProfiler.mark("mod far overlay");
    }

    @Inject(method = "overlay", at = @At("RETURN"))
    private static void shaderscompanion$overlaid(CallbackInfo ci) {
        PassProfiler.mark("after level (mod entity collection, hand)");
    }
}
