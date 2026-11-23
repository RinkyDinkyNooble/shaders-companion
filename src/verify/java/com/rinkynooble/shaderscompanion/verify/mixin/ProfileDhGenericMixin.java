package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.compat.dh.IrisGenericRenderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dev-only (-PdevProfile with -PdevDh): Distant Horizons' generic objects (its clouds and beacon beams) drawn with
 * Oculus's generic program, marked as "pass dh generic" until it is unbound.
 */
@Mixin(value = IrisGenericRenderProgram.class, remap = false)
public abstract class ProfileDhGenericMixin {
    @Inject(method = "bind", at = @At("HEAD"))
    private void shaderscompanion$bind(CallbackInfo ci) {
        PassProfiler.mark("pass dh generic (clouds, beacons)");
    }

    @Inject(method = "unbind", at = @At("RETURN"))
    private void shaderscompanion$unbind(CallbackInfo ci) {
        PassProfiler.markWorld();
    }
}
