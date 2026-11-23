package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev-only (-PdevProfile with -PdevDh): Oculus's Distant Horizons terrain programs (the pack's dh_terrain, dh_water,
 * dh_shadow) by name, a mark where Distant Horizons binds one for its buffers and one back to the level's section
 * where it unbinds it, so the profile shows Distant Horizons' own drawing as "pass dh_terrain" and so on.
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public abstract class ProfileDhProgramMixin {
    @Shadow
    @Final
    private int id;

    @Inject(method = "createProgram", at = @At("RETURN"))
    private static void shaderscompanion$name(String name, boolean isShadowPass, boolean translucent, ProgramSource source, CustomUniforms uniforms,
                                              IrisRenderingPipeline pipeline, CallbackInfoReturnable<IrisLodRenderProgram> cir) {
        if (cir.getReturnValue() != null) {
            PassProfiler.PROGRAM_NAMES.put(((ProfileDhProgramMixin) (Object) cir.getReturnValue()).id, name);
        }
    }

    @Inject(method = "bind", at = @At("HEAD"))
    private void shaderscompanion$bind(CallbackInfo ci) {
        PassProfiler.program(this.id, "dh program");
    }

    @Inject(method = "unbind", at = @At("RETURN"))
    private void shaderscompanion$unbind(CallbackInfo ci) {
        PassProfiler.markWorld();
    }
}
