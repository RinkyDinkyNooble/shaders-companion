package com.rinkynooble.shaderscompanion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rinkynooble.shaderscompanion.client.EntityMaterials;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An entity type's class is inferred from the first entity of it drawn, before Oculus looks up its id. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private <E extends Entity> void shaderscompanion$entityClass(E entity, double x, double y, double z, float yaw, float partialTick, PoseStack poseStack,
                                                                 MultiBufferSource buffers, int light, CallbackInfo ci) {
        EntityMaterials.drawing(entity);
    }
}
