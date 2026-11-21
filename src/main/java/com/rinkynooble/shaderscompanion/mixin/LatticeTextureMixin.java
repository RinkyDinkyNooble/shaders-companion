package com.rinkynooble.shaderscompanion.mixin;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import com.rinkynooble.shaderscompanion.client.Lattice;
import com.rinkynooble.shaderscompanion.client.Materials;
import java.util.function.IntSupplier;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.gl.texture.TextureWrapper;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Oculus binds a shader pack's custom texture named by a resource location ({@code shaderscompanion:lattice_cells})
 * as a 2D texture. The lattice's textures are 3D: they are handed over with their own type here.
 */
@Mixin(value = CustomTextureManager.class, remap = false)
public abstract class LatticeTextureMixin {
    @Inject(method = "createCustomTexture", at = @At("HEAD"), cancellable = true)
    private void shaderscompanion$latticeTexture(CustomTextureData data, CallbackInfoReturnable<TextureAccess> cir) {
        if (data instanceof CustomTextureData.ResourceData resource && ShadersCompanion.MOD_ID.equals(resource.getNamespace())) {
            Materials.noteCustomTexture(resource.getLocation());
            IntSupplier texture = Lattice.textureForPack(resource.getLocation());
            if (texture != null) {
                cir.setReturnValue(new TextureWrapper(texture, TextureType.TEXTURE_3D));
            }
        }
    }
}
