package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.DoorLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every light lookup on the client level (entities, block entities, particles, the shader pack's eye brightness) asks its
 * light engine; near closed doors the client's engines answer with the light {@link DoorLight} worked out with the doors
 * solid. The integrated server's engines (the same class) answer as before.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {
    @Inject(method = "getLightValue", at = @At("RETURN"), cancellable = true)
    private void shaderscompanion$doorLight(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (DoorLight.any()) {
            int value = cir.getReturnValueI();
            int corrected = DoorLight.engineLight(this, pos, value);
            if (corrected != value) {
                cir.setReturnValue(corrected);
            }
        }
    }
}
