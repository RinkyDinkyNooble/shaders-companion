package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.client.far.FarStore;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): a mark at the far store's frame (copies, the far terrain's colours and uploads). */
@Mixin(value = FarStore.class, remap = false)
public abstract class ProfileFarStoreMixin {
    @Inject(method = "frame", at = @At("HEAD"))
    private static void shaderscompanion$frame(CallbackInfo ci) {
        PassProfiler.mark("mod far store");
    }
}
