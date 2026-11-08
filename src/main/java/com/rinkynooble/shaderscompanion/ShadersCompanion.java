package com.rinkynooble.shaderscompanion;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ShadersCompanion.MOD_ID)
public final class ShadersCompanion {
    public static final String MOD_ID = "shaderscompanion";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ShadersCompanion() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.rinkynooble.shaderscompanion.client.ClientInit.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
