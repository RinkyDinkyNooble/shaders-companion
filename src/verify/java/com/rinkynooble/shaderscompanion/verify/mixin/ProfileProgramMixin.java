package com.rinkynooble.shaderscompanion.verify.mixin;

import com.google.common.collect.ImmutableSet;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): remembers each Oculus program's name by its GL id. */
@Mixin(value = ProgramBuilder.class, remap = false)
public abstract class ProfileProgramMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void shaderscompanion$name(String name, int program, ImmutableSet<Integer> reservedTextureUnits, CallbackInfo ci) {
        PassProfiler.PROGRAM_NAMES.put(program, name);
    }
}
