package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.client.RainCover;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): a mark at the rain cover's update. */
@Mixin(value = RainCover.class, remap = false)
public abstract class ProfileRainMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private static void shaderscompanion$rain(CallbackInfo ci) {
        PassProfiler.mark("mod rain cover update");
    }
}
