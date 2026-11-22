package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.gl.program.ComputeProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): a mark at each compute program's use. */
@Mixin(value = ComputeProgram.class, remap = false)
public abstract class ProfileComputeUseMixin {
    @Inject(method = "use", at = @At("HEAD"))
    private void shaderscompanion$use(CallbackInfo ci) {
        PassProfiler.program(((ComputeProgram) (Object) this).getProgramId(), "compute");
    }
}
