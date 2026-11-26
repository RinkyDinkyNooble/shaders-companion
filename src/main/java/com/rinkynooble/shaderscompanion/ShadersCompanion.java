package com.rinkynooble.shaderscompanion;

import com.mojang.logging.LogUtils;
import com.rinkynooble.shaderscompanion.far.net.FarNet;
import com.rinkynooble.shaderscompanion.server.FarShare;
import com.rinkynooble.shaderscompanion.server.ShareConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(ShadersCompanion.MOD_ID)
public final class ShadersCompanion {
    public static final String MOD_ID = "shaderscompanion";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Both sides: the far terrain channel and the server's sharing (a dedicated server, or a singleplayer world opened
     * to LAN; its settings in config/shaderscompanion-common.toml). The client: everything else, behind the dist check,
     * so a dedicated server never loads a client class.
     */
    public ShadersCompanion() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ShareConfig.SPEC);
        FarNet.register();
        FarShare.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.rinkynooble.shaderscompanion.client.ClientInit.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
