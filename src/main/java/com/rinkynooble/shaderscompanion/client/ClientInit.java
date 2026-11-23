package com.rinkynooble.shaderscompanion.client;

import com.rinkynooble.shaderscompanion.client.far.FarStore;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
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
        modBus.addListener(ClientInit::onModelsBaked);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onLevelUnload);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onChunkLoad);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onChunkUnload);
        MinecraftForge.EVENT_BUS.addListener(ClientInit::onClientTick);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LightLeaks.tick(Minecraft.getInstance());
            DoorLight.tick(Minecraft.getInstance());
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CubeView.forgetWorld();
            RainCover.forgetWorld();
            Lattice.forgetWorld();
            FarStore.forgetWorld();
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            Lattice.markChunk(event.getChunk().getPos().x, event.getChunk().getPos().z);
            FarStore.chunkLoaded((Level) event.getLevel(), event.getChunk().getPos().x, event.getChunk().getPos().z);
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            Lattice.markChunk(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (event.getChunk() instanceof LevelChunk chunk) {
                FarStore.chunkUnloaded((Level) event.getLevel(), chunk);
            }
        }
    }

    private static void onModelsBaked(ModelEvent.BakingCompleted event) {
        Lattice.modelsChanged();
        MirrorPlaneSearch.modelsChanged();
        com.rinkynooble.shaderscompanion.client.far.StateColors.modelsChanged();
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
