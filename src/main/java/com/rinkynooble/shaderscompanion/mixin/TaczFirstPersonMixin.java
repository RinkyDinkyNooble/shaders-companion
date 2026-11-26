package com.rinkynooble.shaderscompanion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rinkynooble.shaderscompanion.client.Materials;
import com.tacz.guns.client.renderer.item.GunItemRendererWrapper;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TaCZ draws the gun in first person from its own hand event, outside the item renderer, so Oculus never gives its vertices
 * the item's id (the third-person gun goes through the item renderer and has it). The gun is drawn here with its item's
 * id, as Oculus does for every other item, so the shader pack sees the gun's material class like any held item's. Applied
 * only when TaCZ is installed ({@code MixinPlugin}).
 */
@Mixin(value = GunItemRendererWrapper.class, remap = false)
public abstract class TaczFirstPersonMixin {
    @Inject(method = "renderFirstPerson", at = @At("HEAD"))
    private void shaderscompanion$gunId(LocalPlayer player, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                                        MultiBufferSource buffers, int light, float partialTick, CallbackInfo ci) {
        Object2IntFunction<NamespacedId> itemIds = WorldRenderingSettings.INSTANCE.getItemIds();
        if (itemIds != null && !stack.isEmpty()) {
            CapturedRenderingState.INSTANCE.setCurrentRenderedItem(itemIds.applyAsInt(Materials.namespacedId(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        }
    }

    @Inject(method = "renderFirstPerson", at = @At("RETURN"))
    private void shaderscompanion$gunIdDone(LocalPlayer player, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                                            MultiBufferSource buffers, int light, float partialTick, CallbackInfo ci) {
        CapturedRenderingState.INSTANCE.setCurrentRenderedItem(0);
    }
}
