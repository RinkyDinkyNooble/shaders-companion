package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.ItemMaterials;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Oculus hands each new pipeline's item id map (the shader pack's item.properties) to the item renderer through
 * WorldRenderingSettings, right after it has built the block state to id map. When that pack reads the material table,
 * the items' material classes go in here.
 */
@Mixin(value = WorldRenderingSettings.class, remap = false)
public abstract class ItemMaterialMixin {
    @ModifyVariable(method = "setItemIds", at = @At("HEAD"), argsOnly = true)
    private Object2IntFunction<NamespacedId> shaderscompanion$itemClasses(Object2IntFunction<NamespacedId> itemIds) {
        return ItemMaterials.wrap(itemIds);
    }
}
