package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.gl.program.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): a mark at each deferred, composite and final program's use. */
@Mixin(value = Program.class, remap = false)
public abstract class ProfileProgramUseMixin {
    @SuppressWarnings("deprecation")
    @Inject(method = "use", at = @At("HEAD"))
    private void shaderscompanion$use(CallbackInfo ci) {
        PassProfiler.program(((Program) (Object) this).getProgramId(), "program");
    }
}
