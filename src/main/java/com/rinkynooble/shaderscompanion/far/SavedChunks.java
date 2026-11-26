package com.rinkynooble.shaderscompanion.far;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The saved chunks of one server level, read for far terrain: by the client's singleplayer import (its integrated
 * server's levels) and by the server's far terrain sharing. Chunks are read through the server's own chunk storage
 * ({@link ChunkMap#read}: its IO worker, which also answers from writes still waiting), never from the region files
 * directly; only the region folder is listed, and a region file's first 8 KiB (its tables) read for the time each chunk
 * was written. Thread-safe: reading and decoding run on whichever worker asks, the codecs and registries being frozen.
 * Common code: nothing here touches the client.
 */
public final class SavedChunks {
    /** What {@link #check} found. */
    public enum Status {
        /** Complete and lit: decode it. */
        COMPLETE,
        /** Not generated to the end yet (it is saved again when that changes). */
        NOT_FULL,
        /** Complete but not lit yet (the same). */
        NOT_LIT
    }

    /** A region file's timestamp of one chunk: the chunk and when it was last written (seconds since 1970). */
    @FunctionalInterface
    public interface StampSink {
        void accept(long chunk, int writtenSeconds);
    }

    public final ServerLevel level;
    private final ChunkMap chunkMap;
    private final Path regionFolder;
    public final int minSection;
    public final int sectionCount;
    public final boolean hasSky;
    private final int dataVersion;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Holder<Biome> plains;

    public SavedChunks(ServerLevel level) {
        this.level = level;
        this.chunkMap = level.getChunkSource().chunkMap;
        this.regionFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        this.minSection = level.getMinSection();
        this.sectionCount = level.getSectionsCount();
        this.hasSky = level.dimensionType().hasSkyLight();
        this.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        this.blockCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.plains = registry.getHolderOrThrow(Biomes.PLAINS);
        this.biomeCodec = PalettedContainer.codecRO(registry.asHolderIdMap(), registry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, plains);
    }

    /** The dimension's region files (r.X.Z.mca, not empty). */
    public List<Path> regionFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(regionFolder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(regionFolder, "r.*.mca")) {
                for (Path file : files) {
                    if (regionKey(file) != Long.MIN_VALUE && Files.isRegularFile(file) && Files.size(file) > 0) {
                        out.add(file);
                    }
                }
            }
        }
        return out;
    }

    /** A region file's coordinates as a ChunkPos long, or Long.MIN_VALUE when the name isn't r.X.Z.mca. */
    public static long regionKey(Path file) {
        String[] parts = file.getFileName().toString().split("\\.");
        if (parts.length != 4) {
            return Long.MIN_VALUE;
        }
        try {
            return ChunkPos.asLong(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Every saved chunk within {@code radius} chunks (a square) of a chunk, with the time its region file says it was
     * written. A region file's first 8 KiB are its tables: 1,024 sector offsets (0: no chunk), then 1,024 timestamps in
     * seconds. A file that can't be read whole is skipped (the server may be replacing it).
     */
    public void stamps(int centerX, int centerZ, int radius, StampSink sink) throws IOException {
        int rx0 = (centerX - radius) >> 5;
        int rx1 = (centerX + radius) >> 5;
        int rz0 = (centerZ - radius) >> 5;
        int rz1 = (centerZ + radius) >> 5;
        java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(8192);
        for (Path file : regionFiles()) {
            long key = regionKey(file);
            int rx = ChunkPos.getX(key);
            int rz = ChunkPos.getZ(key);
            if (rx < rx0 || rx > rx1 || rz < rz0 || rz > rz1) {
                continue;
            }
            header.clear();
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
                while (header.hasRemaining() && channel.read(header) >= 0) {
                    // read on
                }
            } catch (IOException e) {
                continue;
            }
            if (header.hasRemaining()) {
                continue;
            }
            for (int i = 0; i < 1024; i++) {
                int cx = rx * 32 + (i & 31);
                int cz = rz * 32 + (i >> 5);
                if (header.getInt(i * 4) == 0 || Math.abs(cx - centerX) > radius || Math.abs(cz - centerZ) > radius) {
                    continue;
                }
                sink.accept(ChunkPos.asLong(cx, cz), header.getInt(4096 + i * 4));
            }
        }
    }

    /** The region keys ({@link #regionKey}) of region files: a chunk of a region file that doesn't exist was never saved. */
    public static it.unimi.dsi.fastutil.longs.LongOpenHashSet regionsOf(List<Path> files) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet regions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (Path file : files) {
            regions.add(regionKey(file));
        }
        return regions;
    }

    /** Asks the server's IO worker for a chunk's saved copy (any thread). */
    public CompletableFuture<Optional<CompoundTag>> read(long chunk) {
        return chunkMap.read(new ChunkPos(chunk));
    }

    /**
     * Brings a saved chunk up to the game's version when an older game saved it (on a copy: the tag may be the server's
     * own, waiting to be written), as the server does when it loads it; the tag as it is otherwise.
     */
    public CompoundTag upgraded(CompoundTag tag) {
        if (ChunkStorage.getVersion(tag) >= dataVersion) {
            return tag;
        }
        return chunkMap.upgradeChunkTag(level.dimension(), () -> level.getServer().overworld().getDataStorage(), tag.copy(),
                level.getChunkSource().getGenerator().getTypeNameForDataFixer());
    }

    /** Whether a saved chunk (brought up to date) is complete and lit. */
    public static Status check(CompoundTag tag) {
        if (ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL) {
            return Status.NOT_FULL;
        }
        return tag.getBoolean("isLightOn") ? Status.COMPLETE : Status.NOT_LIT;
    }

    /**
     * The sections of a saved chunk, decoded as vanilla's {@code ChunkSerializer.read} does: the block states and biomes
     * with the same codecs (a section without states is air, without biomes the fallback, plains), the {@code BlockLight}
     * and {@code SkyLight} arrays, and where a section has no sky array the sky light from above, as the light engine
     * reads it and as {@link CapturedSection#capture} copies it: the bottom row of the first section above with an array
     * (the chunk keeps one above the top), or 15. Sections that fail to decode are left out and counted in
     * {@code errors[0]}.
     */
    public List<CapturedSection> decode(CompoundTag chunk, int chunkX, int chunkZ, int generation, int[] errors) {
        ListTag list = chunk.getList("sections", Tag.TAG_COMPOUND);
        // By section y, from one below the bottom to one above the top (the light engine's range)
        CompoundTag[] byY = new CompoundTag[sectionCount + 2];
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            int at = tag.getByte("Y") - minSection + 1;
            if (at >= 0 && at < byY.length) {
                byY[at] = tag;
            }
        }
        List<CapturedSection> out = new ArrayList<>(sectionCount);
        for (int index = 0; index < sectionCount; index++) {
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
            Object[] biomes = CapturedSection.biomesOf(biomeContainer, plains);
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
                            column = CapturedSection.bottomRow(layer);
                            break;
                        }
                    }
                }
            }
            out.add(new CapturedSection(generation, chunkX, minSection + index, chunkZ, states, null, null, biomes, block, sky, column, uniform, false));
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

    /**
     * The first n chunks ordered for reading around a centre: groups of 4 x 4 chunks nearest the centre first, within a
     * group 2 x 2 at a time (a level-1 and a level-0 node column of the far store, so a node gets all its sections while it
     * is in memory). The chunks must lie within {@code radius} (a square) of the centre.
     */
    public static long[] nearestFirst(long[] chunks, int n, int centerX, int centerZ, int radius) {
        int gx0 = (centerX - radius) >> 2;
        int gz0 = (centerZ - radius) >> 2;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            int cx = ChunkPos.getX(chunks[i]);
            int cz = ChunkPos.getZ(chunks[i]);
            int gx = cx >> 2;
            int gz = cz >> 2;
            // The group's centre against the centre chunk's, in half chunks
            long dx = gx * 8L + 4 - (centerX * 2L + 1);
            long dz = gz * 8L + 4 - (centerZ * 2L + 1);
            int within = (cz >> 1 & 1) << 3 | (cx >> 1 & 1) << 2 | (cz & 1) << 1 | (cx & 1);
            keys[i] = (dx * dx + dz * dz) << 28 | (long) (gx - gx0) << 16 | (long) (gz - gz0) << 4 | within;
        }
        Arrays.sort(keys);
        long[] order = new long[n];
        for (int i = 0; i < n; i++) {
            long key = keys[i];
            int gx = (int) (key >> 16 & 0xFFF) + gx0;
            int gz = (int) (key >> 4 & 0xFFF) + gz0;
            int within = (int) (key & 15);
            int cx = gx * 4 + (within >> 2 & 1) * 2 + (within & 1);
            int cz = gz * 4 + (within >> 3 & 1) * 2 + (within >> 1 & 1);
            order[i] = ChunkPos.asLong(cx, cz);
        }
        return order;
    }
}
