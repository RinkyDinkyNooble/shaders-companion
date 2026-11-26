package com.rinkynooble.shaderscompanion.far;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * A copy of one chunk section for the far store's worker: everything in it is the worker's own (copies, never the
 * world's objects), so the worker never reads a world. All-air sections carry no states, only their light. Made three
 * ways: copied from a loaded chunk on the main thread ({@link #capture}, the client's only main-thread work for the far
 * store), decoded from a saved chunk ({@link SavedChunks#decode}: the singleplayer import, and the server's far terrain
 * sharing), and read from the server's far terrain ({@code ColumnWire}). Common code: nothing here touches the client.
 * <p>
 * The block states come as a container ({@link #states}) or, from the network, as a palette and one index per voxel
 * ({@link #palette}, {@link #indices}), in vanilla's index order (x fastest, then z, then y); {@link #state} reads either.
 */
public final class CapturedSection {
    /** The far store's generation it was made for (the client's; 0 elsewhere): sections of an earlier store are let go. */
    public final int generation;
    public final int x;
    public final int y;
    public final int z;
    /** The block states, or null when the section holds only air or they come as {@link #palette} and {@link #indices}. */
    public final PalettedContainer<BlockState> states;
    /** The block states from the network: the palette (every entry a real state) and 4096 indices into it; else null. */
    public final BlockState[] palette;
    public final short[] indices;
    /** The 4 x 4 x 4 biome holders, x fastest, then z, then y (copied out: a biome container's recreate() is an empty one). */
    public final Object[] biomes;
    /** Block light, or null for none (all 0). */
    public final DataLayer blockLight;
    /** Sky light, or null when the light engine keeps no data here (then the column array or the uniform value). */
    public final DataLayer skyLight;
    /** With no sky data: per column (x + 16 z) the sky light coming from above, or null when that is {@link #skyUniform}. */
    public final byte[] skyColumn;
    public final int skyUniform;
    /** Copied from the client's world (not imported, not from the network). */
    public final boolean live;

    public CapturedSection(int generation, int x, int y, int z, PalettedContainer<BlockState> states, BlockState[] palette, short[] indices, Object[] biomes,
                           DataLayer blockLight, DataLayer skyLight, byte[] skyColumn, int skyUniform, boolean live) {
        this.generation = generation;
        this.live = live;
        this.x = x;
        this.y = y;
        this.z = z;
        this.states = states;
        this.palette = palette;
        this.indices = indices;
        this.biomes = biomes;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
        this.skyColumn = skyColumn;
        this.skyUniform = skyUniform;
    }

    /** Whether the section holds blocks other than air (else {@link #state} is air everywhere). */
    public boolean hasBlocks() {
        return states != null || palette != null;
    }

    /** The block state at index {@code i} (x + 16 z + 256 y); only while {@link #hasBlocks}. */
    public BlockState state(int i) {
        return palette != null ? palette[indices[i]] : states.get(i & 15, i >> 8, i >> 4 & 15);
    }

    /** Copies a loaded chunk's section (the main thread of the chunk's level). */
    public static CapturedSection capture(Level level, LevelChunk chunk, int sectionIndex, int generation) {
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        int y = level.getSectionYFromSectionIndex(sectionIndex);
        LevelChunkSection section = chunk.getSection(sectionIndex);
        PalettedContainer<BlockState> states = section.hasOnlyAir() ? null : section.getStates().copy();
        Object[] biomes = biomesOf(section.getBiomes(), null);
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
                            column = bottomRow(layer);
                        }
                        break;
                    }
                }
            } else {
                sky = sky.copy();
            }
        }
        return new CapturedSection(generation, x, y, z, states, null, null, biomes, block == null ? null : block.copy(), sky, column, uniform, true);
    }

    /** A biome container's 64 holders in the section's order (x fastest, then z, then y); {@code fallback} for a missing container. */
    static Object[] biomesOf(PalettedContainerRO<?> container, Object fallback) {
        Object[] biomes = new Object[64];
        int i = 0;
        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    biomes[i++] = container == null ? fallback : container.get(bx, by, bz);
                }
            }
        }
        return biomes;
    }

    /** A light layer's bottom row (y 0), per column x + 16 z: the light a section below without data of its own gets from it. */
    static byte[] bottomRow(DataLayer layer) {
        byte[] column = new byte[256];
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                column[cx + 16 * cz] = (byte) layer.get(cx, 0, cz);
            }
        }
        return column;
    }

    public int sky(int x, int y, int z) {
        if (skyLight != null) {
            return skyLight.get(x, y, z);
        }
        return skyColumn != null ? skyColumn[x + 16 * z] : skyUniform;
    }
}
