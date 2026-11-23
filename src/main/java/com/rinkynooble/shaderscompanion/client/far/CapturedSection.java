package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
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
 * client's world. All-air sections carry no states, only their light. The import makes the same from the chunks the
 * integrated server saved ({@link #fromSaved}), on the worker.
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
    /** Copied from the client's world (not imported). */
    final boolean live;

    private CapturedSection(int generation, int x, int y, int z, PalettedContainer<BlockState> states, Object[] biomes, DataLayer blockLight,
                            DataLayer skyLight, byte[] skyColumn, int skyUniform, boolean live) {
        this.generation = generation;
        this.live = live;
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
        return new CapturedSection(generation, x, y, z, states, biomes, block == null ? null : block.copy(), sky, column, uniform, true);
    }

    /**
     * The sections of a chunk as the integrated server saved it (the import; worker thread), decoded as vanilla's
     * {@code ChunkSerializer.read} does: the block states and biomes with the same codecs (a section without states is
     * air, without biomes the fallback, plains), the {@code BlockLight} and {@code SkyLight} arrays, and where a section
     * has no sky array the sky light from above, as the light engine reads it and as {@link #capture} copies it: the
     * bottom row of the first section above with an array (the chunk keeps one above the top), or 15. Sections that
     * fail to decode are left out and counted in {@code errors[0]}.
     */
    static List<CapturedSection> fromSaved(CompoundTag chunk, int chunkX, int chunkZ, int minSection, int sections, boolean hasSky,
                                           Codec<PalettedContainer<BlockState>> blockCodec, Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                           Holder<Biome> fallbackBiome, int generation, int[] errors) {
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        // By section y, from one below the bottom to one above the top (the light engine's range)
        CompoundTag[] byY = new CompoundTag[sections + 2];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            int at = tag.getByte("Y") - minSection + 1;
            if (at >= 0 && at < byY.length) {
                byY[at] = tag;
            }
        }
        List<CapturedSection> out = new ArrayList<>(sections);
        for (int index = 0; index < sections; index++) {
            CompoundTag tag = byY[index + 1];
            PalettedContainer<BlockState> states = null;
            if (tag != null && tag.contains("block_states", Tag.TAG_COMPOUND)) {
                Optional<PalettedContainer<BlockState>> parsed = blockCodec.parse(NbtOps.INSTANCE, tag.getCompound("block_states"))
                        .resultOrPartial(message -> errors[0]++);
                if (parsed.isEmpty()) {
                    continue;
                }
                states = parsed.get();
                if (!states.maybeHas(state -> !state.isAir())) {
                    states = null;
                }
            }
            PalettedContainerRO<Holder<Biome>> biomeContainer = null;
            if (tag != null && tag.contains("biomes", Tag.TAG_COMPOUND)) {
                biomeContainer = biomeCodec.parse(NbtOps.INSTANCE, tag.getCompound("biomes")).resultOrPartial(message -> errors[0]++).orElse(null);
            }
            Object[] biomes = new Object[64];
            int i = 0;
            for (int by = 0; by < 4; by++) {
                for (int bz = 0; bz < 4; bz++) {
                    for (int bx = 0; bx < 4; bx++) {
                        biomes[i++] = biomeContainer == null ? fallbackBiome : biomeContainer.get(bx, by, bz);
                    }
                }
            }
            DataLayer block = savedLayer(tag, "BlockLight");
            DataLayer sky = null;
            byte[] column = null;
            int uniform = 0;
            if (hasSky) {
                sky = savedLayer(tag, "SkyLight");
                if (sky == null) {
                    uniform = 15;
                    for (int above = index + 2; above < byY.length; above++) {
                        DataLayer layer = savedLayer(byY[above], "SkyLight");
                        if (layer != null) {
                            column = new byte[256];
                            for (int cz = 0; cz < 16; cz++) {
                                for (int cx = 0; cx < 16; cx++) {
                                    column[cx + 16 * cz] = (byte) layer.get(cx, 0, cz);
                                }
                            }
                            break;
                        }
                    }
                }
            }
            out.add(new CapturedSection(generation, chunkX, minSection + index, chunkZ, states, biomes, block, sky, column, uniform, false));
        }
        return out;
    }

    /** A saved light array (read only: it is the server's own tag's array), or null. */
    private static DataLayer savedLayer(CompoundTag section, String name) {
        if (section == null || !section.contains(name, Tag.TAG_BYTE_ARRAY)) {
            return null;
        }
        byte[] data = section.getByteArray(name);
        return data.length == DataLayer.SIZE ? new DataLayer(data) : null;
    }

    int sky(int x, int y, int z) {
        if (skyLight != null) {
            return skyLight.get(x, y, z);
        }
        return skyColumn != null ? skyColumn[x + 16 * z] : skyUniform;
    }
}
