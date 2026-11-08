package com.rinkynooble.shaderscompanion.client;

import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ClientInit {
    private ClientInit() {
    }

    public static void init(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modBus.addListener(ClientInit::onClientSetup);
        modBus.addListener(ClientInit::onRegisterOverlays);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onRegisterCommands);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(CubeTextures::register);
    }

    private static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("cube_debug", DebugOverlay::render);
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        DebugOverlay.registerCommands(event.getDispatcher());
    }
}
