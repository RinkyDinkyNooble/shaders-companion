package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.client.CubeView;
import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dev-only (-PdevProfile): marks in the mod's own frame hook. */
@Mixin(value = CubeView.class, remap = false)
public abstract class ProfileModMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private static void shaderscompanion$views(CallbackInfo ci) {
        PassProfiler.mark("mod views setup (plane search, builds, entities)");
    }

    @Inject(method = "drawFace", at = @At("HEAD"))
    private static void shaderscompanion$face(CallbackInfo ci) {
        PassProfiler.mark("mod cube faces");
    }

    @Inject(method = "renderFrameUnguarded", at = @At(value = "INVOKE", target = "Lcom/rinkynooble/shaderscompanion/client/MirrorView;draw(Lnet/minecraft/world/phys/Vec3;FFLme/jellysquid/mods/sodium/client/render/chunk/region/RenderRegionManager;Z)V"))
    private static void shaderscompanion$mirror(CallbackInfoReturnable<Integer> cir) {
        PassProfiler.mark("mod mirror planes");
    }

    @Inject(method = "renderFrameUnguarded", at = @At(value = "INVOKE", target = "Lcom/rinkynooble/shaderscompanion/client/Lods;clear()V"))
    private static void shaderscompanion$info(CallbackInfoReturnable<Integer> cir) {
        PassProfiler.mark("mod info textures, state restore");
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private static void shaderscompanion$done(CallbackInfo ci) {
        PassProfiler.mark("game renderLevel setup (after mod hook)");
    }
}
