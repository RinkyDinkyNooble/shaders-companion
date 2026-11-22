package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.DoorLight;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Embeddium works out each cell's light once per chunk mesh build (smooth and flat lighting, fluids). Cells near closed
 * doors take the light {@link DoorLight} worked out with the doors solid.
 */
@Mixin(value = LightDataAccess.class, remap = false)
public abstract class LightDataAccessMixin {
    /** The last section looked up, and whether it holds corrected cells (a build reads one section's cells in a row). */
    @Unique
    private long shaderscompanion$section = Long.MIN_VALUE;
    @Unique
    private Object shaderscompanion$snapshot;
    @Unique
    private boolean shaderscompanion$corrected;

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true)
    private void shaderscompanion$doorLight(int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
        if (!DoorLight.any()) {
            return;
        }
        long section = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        Object snapshot = DoorLight.snapshot();
        if (section != shaderscompanion$section || snapshot != shaderscompanion$snapshot) {
            shaderscompanion$section = section;
            shaderscompanion$snapshot = snapshot;
            shaderscompanion$corrected = DoorLight.hasSection(section);
        }
        if (!shaderscompanion$corrected) {
            return;
        }
        int word = cir.getReturnValueI();
        long pos = BlockPos.asLong(x, y, z);
        int block = DoorLight.light(LightLayer.BLOCK, pos, LightDataAccess.unpackBL(word));
        int sky = DoorLight.light(LightLayer.SKY, pos, LightDataAccess.unpackSL(word));
        cir.setReturnValue(word & ~0xFF | LightDataAccess.packSL(sky) | LightDataAccess.packBL(block));
    }
}
