package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.Lattice;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the lattice where light changed (the client's light engine reports every changed section here). */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void shaderscompanion$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        Lattice.markSection(pos.x(), pos.y(), pos.z());
    }
}
