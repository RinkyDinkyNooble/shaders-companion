package com.rinkynooble.shaderscompanion.verify.mixin;

import com.rinkynooble.shaderscompanion.verify.PassProfiler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dev-only (-PdevProfile): marks at the level's profiler sections. */
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class ProfileLevelMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void shaderscompanion$begin(CallbackInfo ci) {
        PassProfiler.levelStart();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V"))
    private String shaderscompanion$popPush(String name) {
        PassProfiler.section(name);
        return name;
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V"))
    private String shaderscompanion$push(String name) {
        PassProfiler.section(name);
        return name;
    }
}
