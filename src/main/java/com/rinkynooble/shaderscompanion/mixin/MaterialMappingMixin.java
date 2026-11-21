package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.client.Materials;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.List;
import net.irisshaders.iris.shaderpack.materialmap.BlockEntry;
import net.irisshaders.iris.shaderpack.materialmap.BlockMaterialMapping;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oculus turns a shader pack's block.properties into a block state to id map once per pipeline, after it has created
 * the pack's custom textures. When that pack reads the material table, the material classes go into the map here.
 */
@Mixin(value = BlockMaterialMapping.class, remap = false)
public abstract class MaterialMappingMixin {
    @Inject(method = "createBlockStateIdMap", at = @At("RETURN"))
    private static void shaderscompanion$materialClasses(Int2ObjectMap<List<BlockEntry>> blockPropertiesMap, CallbackInfoReturnable<Object2IntMap<BlockState>> cir) {
        Materials.mergeInto(cir.getReturnValue());
    }
}
