package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): marks at the phases and the boundaries of the pipeline's pass groups. */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class ProfilePipelineMixin {
    @Inject(method = "setPhase", at = @At("HEAD"))
    private void shaderscompanion$phase(WorldRenderingPhase phase, CallbackInfo ci) {
        PassProfiler.phase(phase.name());
    }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void shaderscompanion$begin(CallbackInfo ci) {
        PassProfiler.mark("oculus begin (clear, setup, begin passes)");
    }

    @Inject(method = {"beginLevelRendering", "renderShadows", "beginTranslucents", "beginHand"}, at = @At("RETURN"))
    private void shaderscompanion$back(CallbackInfo ci) {
        PassProfiler.markWorld();
    }

    @Inject(method = "beginTranslucents", at = @At("HEAD"))
    private void shaderscompanion$deferred(CallbackInfo ci) {
        PassProfiler.mark("oculus deferred setup (pre-translucent depth copy)");
    }

    @Inject(method = "beginHand", at = @At("HEAD"))
    private void shaderscompanion$hand(CallbackInfo ci) {
        PassProfiler.mark("oculus hand setup (center depth, depth copy)");
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"))
    private void shaderscompanion$composite(CallbackInfo ci) {
        PassProfiler.mark("oculus composite setup");
    }

    @Inject(method = "finalizeLevelRendering", at = @At("RETURN"))
    private void shaderscompanion$finalized(CallbackInfo ci) {
        PassProfiler.mark("after final pass (rest of level)");
    }
}
