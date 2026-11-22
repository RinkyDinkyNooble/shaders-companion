package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.client.Lattice;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): a mark at the lattice's update. */
@Mixin(value = Lattice.class, remap = false)
public abstract class ProfileLatticeMixin {
    @Inject(method = "update", at = @At("HEAD"))
    private static void shaderscompanion$lattice(CallbackInfo ci) {
        PassProfiler.mark("mod lattice update");
    }
}
