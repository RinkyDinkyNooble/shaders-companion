package com.rinkynooble.shaderscompanion.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * A copy of one chunk section, taken on the main thread for the worker: the only main-thread work of the far store.
 * Everything in it is the worker's own afterwards (copies, never the world's objects), so the worker never reads the
 * client's world. All-air sections carry no states, only their light.
 */
final class CapturedSection {
    final int generation;
    final int x;
    final int y;
    final int z;
    /** The block states, or null when the section holds only air. */
    final PalettedContainer<BlockState> states;
    /** The 4 x 4 x 4 biome holders, x fastest, then z, then y (copied out: a biome container's recreate() is an empty one). */
    final Object[] biomes;
    /** Block light, or null for none (all 0). */
    final DataLayer blockLight;
    /** Sky light, or null when the light engine keeps no data here (then the column array or the uniform value). */
    final DataLayer skyLight;
    /** With no sky data: per column (x + 16 z) the sky light coming from above, or null when that is {@link #skyUniform}. */
    final byte[] skyColumn;
    final int skyUniform;

    private CapturedSection(int generation, int x, int y, int z, PalettedContainer<BlockState> states, Object[] biomes, DataLayer blockLight,
                            DataLayer skyLight, byte[] skyColumn, int skyUniform) {
        this.generation = generation;
        this.x = x;
        this.y = y;
        this.z = z;
        this.states = states;
        this.biomes = biomes;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.skyColumn = skyColumn;
        this.skyUniform = skyUniform;
    }

    /** Copies a loaded chunk's section (main thread). */
    static CapturedSection capture(ClientLevel level, LevelChunk chunk, int sectionIndex, int generation) {
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        int y = level.getSectionYFromSectionIndex(sectionIndex);
        LevelChunkSection section = chunk.getSection(sectionIndex);
        PalettedContainer<BlockState> states = section.hasOnlyAir() ? null : section.getStates().copy();
        PalettedContainerRO<?> biomeContainer = section.getBiomes();
        Object[] biomes = new Object[64];
        int i = 0;
        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    biomes[i++] = biomeContainer.get(bx, by, bz);
                }
            }
        }
        SectionPos pos = SectionPos.of(x, y, z);
        DataLayer block = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
        DataLayer sky = null;
        byte[] column = null;
        int uniform = 0;
        if (level.dimensionType().hasSkyLight()) {
            LayerLightEventListener skyListener = level.getLightEngine().getLayerListener(LightLayer.SKY);
            sky = skyListener.getDataLayerData(pos);
            if (sky == null) {
                // No data stored here: each column holds what comes from above, the bottom row of the first section above
                // with data, or full sky when there is none up to the top of the light engine's range (as the sky light
                // storage itself reads it, and Lattice.fill through it, but without 256 lookups)
                uniform = 15;
                for (int above = y + 1; above <= level.getMaxSection(); above++) {
                    DataLayer layer = skyListener.getDataLayerData(SectionPos.of(x, above, z));
                    if (layer != null) {
                        if (layer.isDefinitelyHomogenous()) {
                            uniform = layer.get(0, 0, 0);
                        } else {
                            column = new byte[256];
                            for (int cz = 0; cz < 16; cz++) {
                                for (int cx = 0; cx < 16; cx++) {
                                    column[cx + 16 * cz] = (byte) layer.get(cx, 0, cz);
                                }
                            }
                        }
                        break;
                    }
                }
            } else {
                sky = sky.copy();
            }
        }
        return new CapturedSection(generation, x, y, z, states, biomes, block == null ? null : block.copy(), sky, column, uniform);
    }

    int sky(int x, int y, int z) {
        if (skyLight != null) {
            return skyLight.get(x, y, z);
        }
        return skyColumn != null ? skyColumn[x + 16 * z] : skyUniform;
    }
}
