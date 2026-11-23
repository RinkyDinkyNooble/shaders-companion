package com.rinkynooble.shaderscompanion.client.far;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The singleplayer import ({@code /shaderscompanion lod import [radius]}, M1-PLAN "Import"): the chunks the integrated
 * server has saved for the player's dimension, in a square of the given radius in chunks around the player, read
 * through the server's own chunk storage ({@link ChunkMap#read}: its IO worker, which also answers from writes still
 * waiting; the region files are never opened here), complete chunks only ({@code minecraft:full}, lit), decoded with
 * vanilla's codecs on the far store's worker ({@link CapturedSection#fromSaved}) and stored like captured sections, so
 * the hash skips what is already stored; chunk columns the client has shown this session are left out (what the client
 * saw is newer than the save, or the same). Nearest first, in groups of 4 x 4 chunks (a level-1 node column; each group
 * 2 x 2 at a time, a level-0 node column), so a node gets all its sections while it is in memory; at most
 * {@link #IN_FLIGHT} chunks are read ahead, so memory stays flat. The region folder is only listed, to skip chunks of
 * region files that don't exist. Owned by the worker; the main thread makes it and reads its published progress.
 */
final class FarImport {
    /** Chunks read ahead of the worker. */
    static final int IN_FLIGHT = 16;
    /** The largest radius, in chunks (32,768 blocks). */
    static final int MAX_RADIUS = 2048;

    /** A chunk the server's IO worker answered (null tag: none saved). */
    record Loaded(FarImport owner, long pos, CompoundTag tag, Throwable error) {
    }

    /** What the main thread shows: the status line's part and, once the import has ended, the line for the chat. */
    record Progress(int done, int total, double perSecond, String end) {
    }

    final int generation;
    final int centerX;
    final int centerZ;
    final int radius;
    private final ServerLevel level;
    private final ChunkMap chunkMap;
    private final Path regionFolder;
    private final int minSection;
    private final int sectionCount;
    private final boolean hasSky;
    private final int dataVersion;
    private final Codec<PalettedContainer<BlockState>> blockCodec;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Holder<Biome> plains;
    private long[] order = new long[0];
    private int next;
    private int inFlight;
    boolean stopped;
    long startNanos;

    // Counts
    int done;
    int complete;
    int missing;
    int live;
    int notFull;
    int notLit;
    int failed;
    int upgraded;
    long sections;
    /** The import's own sections (not the live capture's meanwhile): stored new, changed, unchanged, of those by hash alone. */
    long sectionsNew;
    long sectionsChanged;
    long sectionsUnchanged;
    long sectionsOnDisk;
    final int[] decodeErrors = new int[1];
    long decodeNanos;
    long storeNanos;
    // The store's cache counters when the import started
    private long hitsAtStart;
    private long missesAtStart;
    private long loadsAtStart;

    /** Made on the main thread from the integrated server's level of the player's dimension. */
    FarImport(ServerLevel level, int centerX, int centerZ, int radius, int generation) {
        this.level = level;
        this.chunkMap = level.getChunkSource().chunkMap;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = Math.max(0, Math.min(MAX_RADIUS, radius));
        this.generation = generation;
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

    /**
     * Lists the region files and orders the chunks of the square that may be saved (worker thread): groups of 4 x 4
     * chunks nearest the centre first, within a group 2 x 2 at a time.
     */
    void plan(FarNodes store) throws IOException {
        LongOpenHashSet regions = new LongOpenHashSet();
        if (Files.isDirectory(regionFolder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(regionFolder, "r.*.mca")) {
                for (Path file : files) {
                    String[] parts = file.getFileName().toString().split("\\.");
                    if (parts.length == 4 && Files.size(file) > 0) {
                        try {
                            regions.add(ChunkPos.asLong(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                        } catch (NumberFormatException e) {
                            // not a region file
                        }
                    }
                }
            }
        }
        int side = 2 * radius + 1;
        long[] keys = new long[side * side];
        int n = 0;
        int gx0 = (centerX - radius) >> 2;
        int gz0 = (centerZ - radius) >> 2;
        for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
            for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
                if (!regions.contains(ChunkPos.asLong(cx >> 5, cz >> 5))) {
                    continue;
                }
                int gx = cx >> 2;
                int gz = cz >> 2;
                // The group's centre against the centre chunk's, in half chunks
                long dx = gx * 8L + 4 - (centerX * 2L + 1);
                long dz = gz * 8L + 4 - (centerZ * 2L + 1);
                int within = (cz >> 1 & 1) << 3 | (cx >> 1 & 1) << 2 | (cz & 1) << 1 | (cx & 1);
                keys[n++] = (dx * dx + dz * dz) << 28 | (long) (gx - gx0) << 16 | (long) (gz - gz0) << 4 | within;
            }
        }
        keys = Arrays.copyOf(keys, n);
        Arrays.sort(keys);
        order = new long[n];
        for (int i = 0; i < n; i++) {
            long key = keys[i];
            int gx = (int) (key >> 16 & 0xFFF) + gx0;
            int gz = (int) (key >> 4 & 0xFFF) + gz0;
            int within = (int) (key & 15);
            int cx = gx * 4 + (within >> 2 & 1) * 2 + (within & 1);
            int cz = gz * 4 + (within >> 3 & 1) * 2 + (within >> 1 & 1);
            order[i] = ChunkPos.asLong(cx, cz);
        }
        startNanos = System.nanoTime();
        hitsAtStart = store.hits;
        missesAtStart = store.misses;
        loadsAtStart = store.loads;
    }

    int total() {
        return order.length;
    }

    /** Asks the server's IO worker for chunks until {@link #IN_FLIGHT} are on their way (worker thread). */
    void pump(FarWorker worker) {
        while (!stopped && inFlight < IN_FLIGHT && next < order.length) {
            long pos = order[next++];
            CompletableFuture<Optional<CompoundTag>> read;
            try {
                read = chunkMap.read(new ChunkPos(pos));
            } catch (RuntimeException e) {
                failed++;
                done++;
                continue;
            }
            inFlight++;
            read.whenComplete((tag, error) -> worker.post(new Loaded(this, pos, tag == null ? null : tag.orElse(null), error)));
        }
    }

    /** A chunk arrived: decoded and stored when it is complete (worker thread). */
    void accept(Loaded loaded, FarNodes store, long now) {
        inFlight--;
        done++;
        if (loaded.error() != null) {
            failed++;
            return;
        }
        CompoundTag tag = loaded.tag();
        if (tag == null) {
            missing++;
            return;
        }
        if (store.liveColumns.contains(loaded.pos())) {
            live++;
            return;
        }
        if (ChunkStorage.getVersion(tag) < dataVersion) {
            // Saved by an older game: brought up to date as the server does when it loads it (on a copy: the tag may be
            // the server's own, waiting to be written)
            tag = chunkMap.upgradeChunkTag(level.dimension(), () -> level.getServer().overworld().getDataStorage(), tag.copy(),
                    level.getChunkSource().getGenerator().getTypeNameForDataFixer());
            upgraded++;
        }
        if (ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL) {
            notFull++;
            return;
        }
        if (!tag.getBoolean("isLightOn")) {
            notLit++;
            return;
        }
        long start = System.nanoTime();
        List<CapturedSection> decoded = CapturedSection.fromSaved(tag, ChunkPos.getX(loaded.pos()), ChunkPos.getZ(loaded.pos()), minSection, sectionCount, hasSky,
                blockCodec, biomeCodec, plains, generation, decodeErrors);
        long decodedAt = System.nanoTime();
        decodeNanos += decodedAt - start;
        long newBefore = store.sessionNew;
        long changedBefore = store.sessionChanged;
        long unchangedBefore = store.sessionUnchanged;
        long onDiskBefore = store.unchangedOnDisk;
        for (CapturedSection section : decoded) {
            store.ingest(section, now);
        }
        sectionsNew += store.sessionNew - newBefore;
        sectionsChanged += store.sessionChanged - changedBefore;
        sectionsUnchanged += store.sessionUnchanged - unchangedBefore;
        sectionsOnDisk += store.unchangedOnDisk - onDiskBefore;
        storeNanos += System.nanoTime() - decodedAt;
        sections += decoded.size();
        complete++;
    }

    boolean finished() {
        return stopped || next >= order.length && inFlight == 0;
    }

    double perSecond() {
        double seconds = (System.nanoTime() - startNanos) / 1.0e9;
        return seconds <= 0 ? 0 : done / seconds;
    }

    Progress progress(String end) {
        return new Progress(done, order.length, perSecond(), end);
    }

    /** The line for the chat and the log when it ends. */
    String summary(FarNodes store) {
        double seconds = (System.nanoTime() - startNanos) / 1.0e9;
        long lookups = store.hits - hitsAtStart + store.misses - missesAtStart;
        return String.format("Far store import %s: %d of %d chunks in %.1f s (%.0f a second): %d complete, %d sections (%d new, %d changed, %d unchanged, %d of them "
                        + "by their stored hash alone); %d not saved, %d shown live this session, %d not complete, %d not lit, %d failed, %d upgraded, "
                        + "%d section decode errors; "
                        + "worker %.2f ms a chunk decoding, %.2f storing; cache hit rate %.1f%% (%d lookups, %d read from disk)",
                stopped ? "stopped" : "done", done, order.length, seconds, done / Math.max(1.0e-9, seconds), complete, sections, sectionsNew,
                sectionsChanged, sectionsUnchanged, sectionsOnDisk, missing, live, notFull, notLit,
                failed, upgraded, decodeErrors[0], complete == 0 ? 0.0 : decodeNanos / 1.0e6 / complete, complete == 0 ? 0.0 : storeNanos / 1.0e6 / complete,
                lookups == 0 ? 0.0 : 100.0 * (store.hits - hitsAtStart) / lookups, lookups, store.loads - loadsAtStart);
    }
}
