package com.rinkynooble.shaderscompanion.client.far;

import com.rinkynooble.shaderscompanion.ShadersCompanion;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;

/**
 * The far store of one world and dimension, owned by the worker thread: the decoded nodes in a cache sized in MB
 * (least recently used first, all levels in one order), the state and biome ids, the coarser levels' rebuilds and the
 * disk ({@link FarDisk}). A captured section is converted to ids
 * and hashed; when the hash equals the one stored for that section (in its node when the node is in memory, else in
 * its region file's table, without reading the node) nothing changes: this is how a rejoin, or an import of what is
 * already stored, writes nothing. Otherwise its 16 x 16 x 16 voxels go into their level-0 node and every node above it
 * is marked. A marked node is rebuilt from its changed children ({@link CoarseLevels}) no sooner than 5 seconds after
 * its last mark, which marks its own parent in turn, up to level 6. A node pushed out of the cache folds its pending
 * changes into its parent first and is written if it changed; a node not in memory is read back when it is needed.
 * Changed nodes are also written every 10 seconds, and all of them (after every rebuild still waiting) when the store
 * closes.
 */
final class FarNodes {
    static final long REBUILD_DELAY = 5_000_000_000L;
    /**
     * A stored section hash that no copy can match ({@link #hash} never returns it): the section is stored again in full
     * the next time it is copied or imported ({@link HiddenVoxels}: a voxel next to it opened).
     */
    static final long STALE = 1;
    /** Dev checks: false stores sections as captured, without replacing hidden voxels (FarStore.hideVoxelsForChecks). */
    static volatile boolean hideVoxels = true;
    /** Dev checks: false never marks sections stale (the tunnel check's negative control; FarStore.markStaleForChecks). */
    static volatile boolean markStale = true;
    /** Changed nodes not waiting for a rebuild are written this often. */
    static final long WRITE_INTERVAL = 10_000_000_000L;

    final int generation;
    final String world;
    final String dimension;
    /** The dimension's build height, in blocks (the map scans nodes in it). */
    final int minY;
    final int maxY;
    /**
     * Whether the mesher fills dark air here ({@link FarMesher#FILL}): only in a dimension with sky light and no ceiling,
     * where air without sky light is a cave or a closed room. Without sky light (the Nether, the End) every air voxel is
     * dark, and under a ceiling the open space is too.
     */
    final boolean caveFill;
    final StateIds states = new StateIds();
    final BiomeIds biomes = new BiomeIds();
    /** The store's folder, or null when it couldn't be opened (then what leaves the cache is lost, as in M1a). */
    final FarDisk disk;
    final String diskError;
    private final CoarseLevels coarse = new CoarseLevels(this);
    private final HiddenVoxels hidden = new HiddenVoxels(this);
    /** Sections marked stale for the main thread to copy again (SectionPos.asLong); the main thread takes them. */
    final java.util.concurrent.ConcurrentLinkedQueue<Long> recaptures = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /**
     * Every level's nodes in order of use (least recent first). A parent is used after each change of a child (its
     * mark), so a changed child leaves before its parent; a parent that leaves first rebuilds itself from the children
     * still here.
     */
    private final Long2ObjectLinkedOpenHashMap<Node> cache = new Long2ObjectLinkedOpenHashMap<>();
    private int cached;
    private int compact;
    private long bytes;
    private long maxBytes;
    /** Nodes waiting for a rebuild, in the order they were marked, with the due time each was queued with. */
    private final LongArrayFIFOQueue rebuildKeys = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue rebuildDues = new LongArrayFIFOQueue();
    /** Chunk columns with at least one section stored. */
    final LongOpenHashSet explored = new LongOpenHashSet();
    /**
     * Chunk columns the client showed this session (copied live): the import leaves them alone, since the server's
     * saved copy can be older than what the client saw (changes not saved yet).
     */
    final LongOpenHashSet liveColumns = new LongOpenHashSet();
    private long lastWrite;
    private boolean changedSinceWrite;

    // Per state id: CoarseLevels' flags, worked out the first time an id is used
    /** Air, or water a face can be seen through (water itself, kelp, seagrass). */
    static final byte OPEN = 1;
    /** The water block (any level). */
    static final byte WATER = 2;
    /** A decoration with no collision (grass and flowers, torches, vines; not fluids, not snow layers, which cover the ground). */
    static final byte THIN = 4;
    /** Solid but see-through with a collision shape (leaves, glass, fences, walls, panes): thin parts of a canopy or a build. */
    static final byte SHEER = 8;
    /**
     * A full cube that hides what is behind it ({@code isSolidRender}: occluding, a full occlusion shape): a voxel with
     * six of these around it can't be seen ({@link HiddenVoxels}).
     */
    static final byte OPAQUE = 16;
    private byte[] flags = new byte[256];
    /** Per state id: the far mesher's class and its tint and translucent bits ({@link #meshClasses}, worked out only while something meshes). */
    private byte[] meshClasses = new byte[256];
    private int flagsKnown = 2;
    /** Ids whose mesh class is worked out (only while something meshes: {@link #meshClasses}). */
    private int meshKnown = 2;
    private int waterId = -1;
    private int stoneId = -1;
    /**
     * Told the key of every node whose voxels change (an ingest, a fold into a coarser node, hidden voxels next to a
     * new section): the far residency's remeshing ({@link FarResidency}). Worker thread; null when nothing listens.
     */
    java.util.function.LongConsumer changeListener;

    // Statistics (the worker's; FarWorker publishes them)
    final int[] nodes = new int[Node.LEVELS];
    final long[] dropped = new long[Node.LEVELS];
    /** Sections with a stored hash, on disk or in memory. */
    long sectionsStored;
    /** This session: sections stored for the first time, sections whose content changed, sections found unchanged. */
    long sessionNew;
    long sessionChanged;
    long sessionUnchanged;
    /** Of the unchanged ones: found by the hash in the region file, the node not read. */
    long unchangedOnDisk;
    /** Of the changed ones: those whose block states differ, those where only the light does, those where only biomes do. */
    long changedStates;
    long changedLightOnly;
    long changedBiomesOnly;
    /** Dev checks: log what differs in the first changed sections (FarStore.logChangesForChecks). */
    static volatile int logChanges;
    long sectionsIngested;
    /** Hidden voxels ({@link HiddenVoxels}): sections whose voxels were looked at, voxels replaced, the time it took. */
    long hiddenSections;
    long hiddenReplaced;
    long hiddenNanos;
    /** Voxels of stored neighbours replaced when a section next to them arrived; sections marked stale; stale ones stored again. */
    long hiddenLate;
    long staleMarked;
    long staleRestored;
    long rebuilds;
    long folds;
    long ingestNanos;
    long rebuildNanos;
    long foldNanos;
    double ingestMicros;
    double rebuildMicros;
    /** Cache lookups that found the node, and those that didn't (then read from disk, or made new). */
    long hits;
    long misses;
    long loads;
    long creates;
    long written;

    // Scratch for one section
    private final int[] sectionIds = new int[4096];
    private final byte[] sectionLight = new byte[4096];
    private final short[] sectionBiomes = new short[64];

    /** Opens the store; {@code folder} null keeps it in memory only. */
    FarNodes(int generation, String world, String dimension, int minY, int maxY, boolean caveFill, int cacheMb, Path folder) {
        this.generation = generation;
        this.world = world;
        this.dimension = dimension;
        this.minY = minY;
        this.maxY = maxY;
        this.caveFill = caveFill;
        setCacheMb(cacheMb);
        flags[StateIds.AIR] = OPEN;
        meshClasses[StateIds.UNKNOWN] = FarMesher.UNKNOWN;
        meshClasses[StateIds.AIR] = FarMesher.AIR;
        FarDisk opened = null;
        String error = null;
        if (folder != null) {
            long start = System.nanoTime();
            try {
                opened = new FarDisk(folder, states, biomes, minY, maxY);
                opened.open();
                sectionsStored = opened.sectionsOnDisk;
                explored.addAll(opened.exploredOnDisk);
                opened.exploredOnDisk.clear();
                ShadersCompanion.LOGGER.info("Shaders Companion far store: opened {} in {} ms: {} sections stored in {} chunk columns, {} region files ({} MB), {} block states, {} biomes",
                        folder, (System.nanoTime() - start) / 1_000_000, sectionsStored, explored.size(), opened.regionFiles, opened.fileBytes >> 20,
                        states.size() - 2, biomes.size() - 1);
                if (states.unparsed > 0) {
                    ShadersCompanion.LOGGER.warn("Shaders Companion far store: {} block states of states.txt no longer exist (kept as unknown blocks)", states.unparsed);
                }
            } catch (IOException | RuntimeException e) {
                ShadersCompanion.LOGGER.error("Shaders Companion far store: opening {} failed; keeping the store in memory only", folder, e);
                opened = null;
                error = e.toString();
            }
        }
        disk = opened;
        diskError = error;
        lastWrite = System.nanoTime();
    }

    void setCacheMb(int cacheMb) {
        maxBytes = cacheMb * 1024L * 1024L;
    }

    int cacheMb() {
        return (int) (maxBytes >> 20);
    }

    void forEachNode(java.util.function.Consumer<Node> action) {
        for (Node node : cache.values()) {
            action.accept(node);
        }
    }

    int cachedNodes() {
        return cached;
    }

    int compactNodes() {
        return compact;
    }

    int rebuildsWaiting() {
        return rebuildKeys.size();
    }

    /** A node if it is in memory, without counting as a use (the cache's order stays). */
    Node peek(long key) {
        return cache.get(key);
    }

    Node peek(int level, int x, int y, int z) {
        return cache.get(Node.key(level, x, y, z));
    }

    /**
     * A node to read, from memory or else from disk without keeping it (the map), or null when none is stored. Don't
     * change what it returns.
     */
    Node read(int level, int x, int y, int z) {
        Node node = peek(level, x, y, z);
        if (node != null || disk == null) {
            return node;
        }
        try {
            return disk.load(Node.key(level, x, y, z));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A node in memory, read from disk or made new when it isn't (and kept, as the most recently used). */
    private Node getOrCreate(int level, int x, int y, int z) {
        long key = Node.key(level, x, y, z);
        Node node = cache.getAndMoveToLast(key);
        if (node != null) {
            hits++;
            return node;
        }
        misses++;
        if (disk != null) {
            try {
                node = disk.load(key);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (node != null) {
            loads++;
        } else {
            node = new Node(level, x, y, z);
            creates++;
        }
        keep(node);
        return node;
    }

    /** A stored level-0 node, in memory or read from disk into it; null when none is stored. */
    Node levelZeroIfStored(int x, int y, int z) {
        long key = Node.key(0, x, y, z);
        Node node = cache.getAndMoveToLast(key);
        if (node != null || disk == null) {
            return node;
        }
        try {
            node = disk.load(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (node != null) {
            loads++;
            keep(node);
        }
        return node;
    }

    private void keep(Node node) {
        int level = node.level;
        long key = node.key;
        // It may change from now on: masks kept from disk would go stale
        faceMasks.remove(key);
        cache.putAndMoveToLast(key, node);
        cached++;
        nodes[level]++;
        bytes += node.bytes();
        if (node.compact()) {
            compact++;
        }
    }

    /** A section next to a voxel that opened: stored again in full next time, and the main thread asked to copy it ({@link HiddenVoxels}). */
    void markStale(Node node, int slot, int sectionX, int sectionY, int sectionZ) {
        node.sectionHash[slot] = STALE;
        node.dirty = true;
        changedSinceWrite = true;
        staleMarked++;
        recaptures.add(net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ));
    }

    /** A node's voxels changed outside an ingest (hidden voxels next to a new section): written with the next batch. */
    void changedWithoutIngest(Node node) {
        node.dirty = true;
        changedSinceWrite = true;
        changed(node.key);
    }

    private void changed(long key) {
        java.util.function.LongConsumer listener = changeListener;
        if (listener != null) {
            listener.accept(key);
        }
    }

    /** Gives a node in the cache its arrays before they are written to. */
    void expand(Node node) {
        if (node.state == null || node.biome == null) {
            boolean wasCompact = node.compact();
            long before = node.bytes();
            node.expand();
            bytes += node.bytes() - before;
            if (wasCompact) {
                compact--;
            }
        }
    }

    /** Lets a node in the cache drop the arrays that hold one value. */
    private void tryCompact(Node node) {
        boolean wasCompact = node.compact();
        long before = node.bytes();
        node.tryCompact();
        bytes += node.bytes() - before;
        if (!wasCompact && node.compact()) {
            compact++;
        }
    }

    /**
     * The flags of a state id ({@link #OPEN}, {@link #WATER}, {@link #THIN}, {@link #SHEER}, {@link #OPAQUE}). Small so
     * that it is inlined: it runs for every voxel of a fold (M1c's JFR: 16% of the worker while flying, as one method).
     */
    byte flags(int id) {
        byte[] f = flags;
        return id < flagsKnown && id >= 0 ? f[id] : flagsOfNew(id);
    }

    /** {@link #flags} for ids assigned since it was last asked: worked out once per state. */
    private byte flagsOfNew(int id) {
        if (id >= flagsKnown) {
            int size = states.size();
            if (flags.length < size) {
                flags = Arrays.copyOf(flags, Math.max(size, flags.length * 2));
            }
            for (int i = flagsKnown; i < size; i++) {
                BlockState state = states.state(i);
                byte f = 0;
                if (state != null) {
                    if (state.is(Blocks.WATER)) {
                        f |= WATER;
                    }
                    if (state.isAir()) {
                        f |= OPEN;
                    } else if (state.getFluidState().is(FluidTags.WATER) && !state.canOcclude()) {
                        // Water, and plants standing in it (kelp, seagrass): a face next to them is seen
                        f |= OPEN;
                    } else if (state.getFluidState().isEmpty() && !state.canOcclude()) {
                        boolean collides;
                        try {
                            collides = !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
                        } catch (RuntimeException e) {
                            // A modded block that wants a real world: counted as solid
                            collides = true;
                        }
                        if (!collides && !(state.getBlock() instanceof SnowLayerBlock)) {
                            f |= THIN;
                        } else if (collides) {
                            f |= SHEER;
                        }
                    }
                    boolean opaque;
                    try {
                        opaque = state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                    } catch (RuntimeException e) {
                        // A modded block that wants a real world: never counted as hiding anything
                        opaque = false;
                    }
                    if (opaque) {
                        f |= OPAQUE;
                    }
                }
                flags[i] = f;
            }
            flagsKnown = size;
        }
        return id < flagsKnown ? flags[id] : 0;
    }

    /**
     * The far mesher's class of a state with these flags (FarMesher's class comment), with {@link FarMesher#TINTED}
     * when the state has a tint provider (its faces keep their biome in a mesh's palette) or is water-like, and
     * {@link FarMesher#TRANSLUCENT} when it is drawn in the translucent pass (water-like ones too).
     */
    private static byte meshClassOf(BlockState state, byte f) {
        if (state == null) {
            // A line of states.txt that no longer parses: an unknown block, still a block
            return FarMesher.SOLID;
        }
        int c;
        boolean invisible;
        try {
            invisible = state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE && state.getFluidState().isEmpty();
        } catch (RuntimeException e) {
            invisible = false;
        }
        if ((f & OPEN) != 0) {
            c = state.isAir() ? FarMesher.AIR : FarMesher.WATER;
        } else if (invisible || (f & THIN) != 0) {
            // Decorations, and blocks nothing draws (barriers, light blocks, structure voids)
            c = FarMesher.AIR;
        } else if ((f & SHEER) != 0) {
            c = FarMesher.SHEER;
        } else {
            c = FarMesher.SOLID;
        }
        boolean tinted = c == FarMesher.WATER;
        boolean translucent = c == FarMesher.WATER;
        if (!tinted && c != FarMesher.AIR) {
            try {
                // A tint provider answers without a world (vanilla's give their default colours); none answers -1
                tinted = net.minecraft.client.Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0) != -1;
            } catch (RuntimeException e) {
                tinted = true;
            }
            try {
                // Drawn in the translucent pass (stained glass, ice, slime): the far terrain's second pass too
                translucent = net.minecraft.client.Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                        .getRenderTypes(state, net.minecraft.util.RandomSource.create(42L), net.minecraftforge.client.model.data.ModelData.EMPTY)
                        .contains(net.minecraft.client.renderer.RenderType.translucent());
            } catch (RuntimeException e) {
                translucent = false;
            }
        }
        return (byte) (c | (tinted ? FarMesher.TINTED : 0) | (translucent ? FarMesher.TRANSLUCENT : 0));
    }

    /** Every state id's mesh class so far (worker thread; the array is shared with mesh jobs, entries never change). */
    byte[] meshClasses() {
        int size = states.size();
        if (meshKnown < size) {
            if (flagsKnown < size) {
                flagsOfNew(size - 1);
            }
            byte[] classes = meshClasses;
            if (classes.length < size) {
                // A new array: jobs made before keep the old one, whose entries stay as they were
                classes = Arrays.copyOf(classes, Math.max(size, classes.length * 2));
            }
            for (int i = meshKnown; i < size; i++) {
                classes[i] = meshClassOf(states.state(i), flags[i]);
            }
            meshClasses = classes;
            meshKnown = size;
        }
        return meshClasses;
    }

    /** The id of still water (the palette's state for every water-like voxel), assigned when new. */
    int waterId() {
        if (waterId < 0) {
            waterId = states.id(Blocks.WATER.defaultBlockState());
        }
        return waterId;
    }

    /** The id of stone (the palette's state for cave-filled air), assigned when new. */
    int stoneId() {
        if (stoneId < 0) {
            stoneId = states.id(Blocks.STONE.defaultBlockState());
        }
        return stoneId;
    }

    /** Converts a captured section to ids, and stores it in its level-0 node unless it is unchanged. */
    void ingest(CapturedSection section, long now) {
        long start = System.nanoTime();
        int[] ids = sectionIds;
        if (section.states == null) {
            Arrays.fill(ids, StateIds.AIR);
        } else {
            // Vanilla's index order (x fastest, then z, then y) is the node's
            BlockState last = null;
            int lastId = 0;
            int i = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, i++) {
                        BlockState state = section.states.get(x, y, z);
                        if (state != last) {
                            last = state;
                            lastId = states.id(state);
                        }
                        ids[i] = lastId;
                    }
                }
            }
        }
        readLight(section);
        for (int i = 0; i < 64; i++) {
            @SuppressWarnings("unchecked")
            Holder<Biome> biome = (Holder<Biome>) section.biomes[i];
            sectionBiomes[i] = (short) biomes.id(biome);
        }
        long hash = hash();
        sectionsIngested++;
        if (section.live) {
            liveColumns.add(ChunkPos.asLong(section.x, section.z));
        }
        int nx = section.x >> 1;
        int ny = section.y >> 1;
        int nz = section.z >> 1;
        long key = Node.key(0, nx, ny, nz);
        int slot = Node.sectionSlot(section.x, section.y, section.z);
        Node node = cache.getAndMoveToLast(key);
        long stored;
        if (node != null) {
            hits++;
            stored = node.sectionHash[slot];
        } else {
            try {
                stored = disk == null ? 0 : disk.storedHash(key, slot);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (stored == hash) {
            sessionUnchanged++;
            if (node == null) {
                unchangedOnDisk++;
            }
        } else {
            if (node == null) {
                node = getOrCreate(0, nx, ny, nz);
            }
            long old = node.sectionHash[slot];
            boolean hide = hideVoxels;
            if (hide) {
                // Hidden voxels (the hash above is of the copy as captured): what opened next to the stored sections
                // around, then this section's own
                long hiddenStart = System.nanoTime();
                hidden.prepare(section.x, section.y, section.z, ids);
                if (old != 0 && markStale) {
                    hidden.invalidateOpened(section.x, section.y, section.z, node, ids);
                }
                hiddenReplaced += hidden.normalize(ids, sectionLight);
                hiddenSections++;
                hiddenNanos += System.nanoTime() - hiddenStart;
            }
            if (old == 0) {
                node.known += 4096;
                sectionsStored++;
                sessionNew++;
                explored.add(ChunkPos.asLong(section.x, section.z));
            } else {
                if (old == STALE) {
                    staleRestored++;
                }
                sessionChanged++;
                countChange(node, section);
            }
            node.sectionHash[slot] = hash;
            expand(node);
            int ox = (section.x & 1) * 16;
            int oy = (section.y & 1) * 16;
            int oz = (section.z & 1) * 16;
            int i = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++, i += 16) {
                    int at = Node.index(ox, oy + y, oz + z);
                    System.arraycopy(ids, i, node.state, at, 16);
                    System.arraycopy(sectionLight, i, node.light, at, 16);
                }
            }
            i = 0;
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        node.biome[Node.biomeIndex(ox / 4 + x, oy / 4 + y, oz / 4 + z)] = sectionBiomes[i++];
                    }
                }
            }
            if (hide) {
                // The faces of the stored sections around that face this one
                long hiddenStart = System.nanoTime();
                hiddenLate += hidden.late(section.x, section.y, section.z);
                hiddenNanos += System.nanoTime() - hiddenStart;
            }
            if (node.known == Node.VOXELS && sectionUniform()) {
                // Complete, and the section was one state: perhaps the whole node is (open sky, solid rock)
                tryCompact(node);
            }
            node.dirty = true;
            changedSinceWrite = true;
            markParent(node, now);
            changed(node.key);
        }
        long nanos = System.nanoTime() - start;
        ingestNanos += nanos;
        ingestMicros = ingestMicros == 0 ? nanos / 1.0e3 : ingestMicros * 0.99 + nanos / 1.0e3 * 0.01;
    }

    /** What differs between a section as stored and its new copy (before it is written over): the statistics, and the log in dev checks. */
    private void countChange(Node node, CapturedSection section) {
        int ox = (section.x & 1) * 16;
        int oy = (section.y & 1) * 16;
        int oz = (section.z & 1) * 16;
        int states = 0;
        int sky = 0;
        int block = 0;
        int biomeCells = 0;
        int example = -1;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++, i++) {
                    int at = Node.index(ox + x, oy + y, oz + z);
                    if (node.state(at) != sectionIds[i]) {
                        states++;
                        if (example < 0) {
                            example = i;
                        }
                    }
                    int light = node.light(at) ^ (sectionLight[i] & 0xFF);
                    if ((light & 0xF0) != 0) {
                        sky++;
                    }
                    if ((light & 0x0F) != 0) {
                        block++;
                    }
                }
            }
        }
        i = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    if (node.biome(Node.biomeIndex(ox / 4 + x, oy / 4 + y, oz / 4 + z)) != sectionBiomes[i++]) {
                        biomeCells++;
                    }
                }
            }
        }
        if (states > 0) {
            changedStates++;
        } else if (sky + block > 0) {
            changedLightOnly++;
        } else {
            changedBiomesOnly++;
        }
        if (logChanges > 0) {
            logChanges--;
            String what = "";
            if (example >= 0) {
                int at = Node.index(ox + (example & 15), oy + (example >> 8), oz + (example >> 4 & 15));
                what = String.format(" (first at %d %d %d: %s -> %s)", section.x * 16 + (example & 15), section.y * 16 + (example >> 8),
                        section.z * 16 + (example >> 4 & 15), states(node.state(at)), states(sectionIds[example]));
            }
            ShadersCompanion.LOGGER.info("Shaders Companion far store: section {} {} {} changed ({}): {} states{}, {} sky light, {} block light, {} biome cells",
                    section.x, section.y, section.z, section.live ? "live" : "imported", states, what, sky, block, biomeCells);
        }
    }

    private String states(int id) {
        BlockState state = states.state(id);
        return state == null ? (id == StateIds.UNKNOWN ? "unknown" : "#" + id) : state.toString();
    }

    private boolean sectionUniform() {
        int id = sectionIds[0];
        byte light = sectionLight[0];
        for (int i = 1; i < 4096; i++) {
            if (sectionIds[i] != id || sectionLight[i] != light) {
                return false;
            }
        }
        return true;
    }

    /** The section's light into {@link #sectionLight}: sky light in the high nibble, block light in the low one. */
    private void readLight(CapturedSection section) {
        DataLayer block = section.blockLight;
        byte[] blockData = block == null || block.isDefinitelyHomogenous() ? null : block.getData();
        int blockUniform = block == null ? 0 : block.get(0, 0, 0);
        DataLayer sky = section.skyLight;
        byte[] skyData = sky == null || sky.isDefinitelyHomogenous() ? null : sky.getData();
        for (int i = 0; i < 4096; i++) {
            // DataLayer's nibble order: index y << 8 | z << 4 | x, the low nibble first
            int shift = (i & 1) << 2;
            int b = blockData == null ? blockUniform : blockData[i >> 1] >> shift & 15;
            int s;
            if (skyData != null) {
                s = skyData[i >> 1] >> shift & 15;
            } else if (sky != null) {
                s = sky.get(0, 0, 0);
            } else {
                s = section.skyColumn != null ? section.skyColumn[i & 255] : section.skyUniform;
            }
            sectionLight[i] = (byte) (s << 4 | b);
        }
    }

    /** A 64-bit hash of the converted section (ids, light, biomes); never 0, which means "none stored", nor {@link #STALE}. */
    private long hash() {
        long h = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 4096; i++) {
            h = (h ^ sectionIds[i]) * 0xFF51AFD7ED558CCDL;
            h ^= h >>> 32;
        }
        for (int i = 0; i < 4096; i += 8) {
            long v = 0;
            for (int j = 0; j < 8; j++) {
                v = v << 8 | (sectionLight[i + j] & 0xFF);
            }
            h = (h ^ v) * 0xC4CEB9FE1A85EC53L;
            h ^= h >>> 29;
        }
        for (int i = 0; i < 64; i++) {
            h = (h ^ sectionBiomes[i]) * 0xFF51AFD7ED558CCDL;
            h ^= h >>> 32;
        }
        return h == 0 || h == STALE ? 2 : h;
    }

    /** A node changed: its octant of the parent is marked, the parent's rebuild due 5 seconds from now. */
    private void markParent(Node node, long now) {
        if (node.level >= Node.LEVELS - 1) {
            return;
        }
        Node parent = getOrCreate(node.level + 1, node.x >> 1, node.y >> 1, node.z >> 1);
        parent.dirtyOctants |= 1 << node.octantInParent();
        parent.rebuildDue = now + REBUILD_DELAY;
        if (!parent.queued) {
            parent.queued = true;
            rebuildKeys.enqueue(parent.key);
            rebuildDues.enqueue(parent.rebuildDue);
        }
    }

    /**
     * Nanoseconds until the worker has something to do here: the first queued rebuild, or writing the changed nodes
     * (0 if now, Long.MAX_VALUE if nothing is waiting).
     */
    long nanosUntilWork(long now) {
        long wait = rebuildKeys.isEmpty() ? Long.MAX_VALUE : Math.max(0, rebuildDues.firstLong() - now);
        if (disk != null && (changedSinceWrite || !disk.batchEmpty())) {
            wait = Math.min(wait, Math.max(0, lastWrite + WRITE_INTERVAL - now));
        }
        return wait;
    }

    /** Runs the rebuilds that are due, for at most the given time. */
    void runDueRebuilds(long now, long budgetNanos) {
        long start = System.nanoTime();
        while (!rebuildKeys.isEmpty() && rebuildDues.firstLong() <= now && System.nanoTime() - start < budgetNanos) {
            long key = rebuildKeys.dequeueLong();
            rebuildDues.dequeueLong();
            Node node = peek(key);
            if (node == null || !node.queued) {
                continue;
            }
            if (node.rebuildDue > now) {
                // Marked again since it was queued: back in line with its new due time
                rebuildKeys.enqueue(key);
                rebuildDues.enqueue(node.rebuildDue);
                continue;
            }
            node.queued = false;
            rebuild(node, now);
        }
    }

    /** Rebuilds a coarser node's changed octants from its children in memory, and marks its own parent. */
    private void rebuild(Node node, long now) {
        long start = System.nanoTime();
        int octants = node.dirtyOctants;
        node.dirtyOctants = 0;
        for (int octant = 0; octant < 8; octant++) {
            if ((octants & 1 << octant) != 0) {
                Node child = peek(node.level - 1, 2 * node.x + (octant & 1), 2 * node.y + (octant >> 1 & 1), 2 * node.z + (octant >> 2 & 1));
                if (child != null) {
                    // A child no longer in memory folded itself in when it left
                    fold(child, node, octant);
                }
            }
        }
        if (node.known == Node.VOXELS) {
            tryCompact(node);
        }
        node.dirty = true;
        changedSinceWrite = true;
        markParent(node, now);
        long nanos = System.nanoTime() - start;
        rebuilds++;
        rebuildNanos += nanos;
        rebuildMicros = rebuildMicros == 0 ? nanos / 1.0e3 : rebuildMicros * 0.95 + nanos / 1.0e3 * 0.05;
    }

    private void fold(Node child, Node parent, int octant) {
        long start = System.nanoTime();
        coarse.fold(child, parent, octant);
        folds++;
        foldNanos += System.nanoTime() - start;
        changed(parent.key);
    }

    /**
     * Pushes the least recently used nodes out until the cache fits its size. A node leaving runs its own pending
     * rebuild from its children still in memory, folds its changes into its parent and is written if it changed (into
     * the disk's batch, written when full). M1a evicted the finest level first, which kept the coarser levels whole
     * while there was no disk; with the disk that starved level 0 during an import (research/lod/M1.md, "M1b").
     */
    void trim(long now) {
        while (cached > Node.LEVELS && bytes > maxBytes) {
            Node node = cache.get(cache.firstLongKey());
            // Its own pending rebuild first, from its children still in memory
            if (node.dirtyOctants != 0) {
                node.queued = false;
                rebuild(node, now);
            }
            // Then its changes into its parent, which can't read it from the cache after this
            if (node.level < Node.LEVELS - 1) {
                Node parent = peek(node.parentKey());
                int bit = 1 << node.octantInParent();
                if (parent != null && (parent.dirtyOctants & bit) != 0) {
                    fold(node, parent, node.octantInParent());
                    parent.dirtyOctants &= ~bit;
                    // The parent changed: its own parent is marked now, since the parent too may leave memory before
                    // its queued rebuild would have marked it
                    parent.dirty = true;
                    markParent(parent, now);
                }
            }
            cache.remove(node.key);
            if (diskBorders && node.level < Node.LEVELS - 1 && node.known > 0) {
                // Its faces for the exposure test of the nodes next to it, so that test needn't read it back from disk
                keepMasks(node.key, masksOf(node));
                faceMasksKept++;
            }
            cached--;
            nodes[node.level]--;
            bytes -= node.bytes();
            if (node.compact()) {
                compact--;
            }
            if (disk != null) {
                if (node.dirty) {
                    disk.put(node);
                    written++;
                }
            } else {
                // No disk: gone
                if (node.level == 0) {
                    for (long hash : node.sectionHash) {
                        if (hash != 0) {
                            sectionsStored--;
                        }
                    }
                }
                dropped[node.level]++;
            }
        }
        if (disk != null && disk.batchFull()) {
            writeBatch(now);
        }
    }

    /** Writes the changed nodes when it is time (every {@link #WRITE_INTERVAL}). */
    void writeWhenDue(long now) {
        if (disk != null && now - lastWrite >= WRITE_INTERVAL && (changedSinceWrite || !disk.batchEmpty())) {
            writeChanged(now, false);
        }
    }

    /** Writes every changed node in memory (but those waiting for a rebuild, unless {@code all}), then the batch. */
    private void writeChanged(long now, boolean all) {
        for (Node node : cache.values()) {
            if (node.dirty && (all || !node.queued)) {
                if (disk.put(node)) {
                    tryCompact(node);
                }
                written++;
                if (disk.batchFull()) {
                    writeBatch(now);
                }
            }
        }
        changedSinceWrite = false;
        writeBatch(now);
    }

    private void writeBatch(long now) {
        try {
            disk.writeBatch();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        lastWrite = now;
    }

    /**
     * Closes the store: every queued rebuild runs now (cascading up to level 6, so the coarser levels on disk hold every
     * change), every changed node is written, and the region files are closed.
     */
    void close() {
        if (disk == null) {
            return;
        }
        long start = System.nanoTime();
        int waiting = rebuildKeys.size();
        long t = start;
        while (!rebuildKeys.isEmpty()) {
            t += REBUILD_DELAY + 1;
            runDueRebuilds(t, Long.MAX_VALUE);
        }
        long rebuilt = System.nanoTime();
        writeChanged(t, true);
        try {
            disk.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ShadersCompanion.LOGGER.info("Shaders Companion far store: closed {} {} ({} rebuilds waiting, run in {} ms; written in {} ms): {} sections stored, {} MB in {} region files",
                world, dimension, waiting, (rebuilt - start) / 1_000_000, (System.nanoTime() - rebuilt) / 1_000_000, sectionsStored, disk.fileBytes >> 20, disk.regionFiles);
    }

    /** The node next to the given one at its level, if it is in memory (for CoarseLevels' exposure test at node borders). */
    Node neighbour(Node node, int dx, int dy, int dz) {
        return peek(node.level, node.x + dx, node.y + dy, node.z + dz);
    }

    /** Face masks kept for nodes on disk ({@link #faceMasks}); M1c measured the hit rate during an import. */
    static final int FACE_MASKS = 8192;
    /** Dev checks: false keeps M1a's exposure test at node borders, which sees only neighbours in memory (FarStore.diskBordersForChecks). */
    static volatile boolean diskBorders = true;
    private static final long[] NO_NODE = new long[0];
    /**
     * For nodes not in memory: per face (west, east, down, up, north, south: the layer x 0, x 31, y 0, y 31, z 0, z 31),
     * which voxels are open or decorations (CoarseLevels' "exposed"), 1,024 bits each, so the exposure test at node
     * borders sees the node next door when it has left memory (768 bytes a node, at most {@link #FACE_MASKS} of them,
     * least recently used first out). Kept when a node leaves memory, read from disk only for nodes not seen this
     * session (the first version read them all from disk: the import took 29% longer). A node entering memory drops
     * its masks.
     */
    private final Long2ObjectLinkedOpenHashMap<long[]> faceMasks = new Long2ObjectLinkedOpenHashMap<>();
    long faceMaskHits;
    long faceMaskReads;
    long faceMasksKept;

    /** The face masks of a node not in memory (null when none is stored), read from disk the first time. */
    long[] faceMasks(int level, int x, int y, int z) {
        if (!diskBorders) {
            return null;
        }
        long key = Node.key(level, x, y, z);
        long[] masks = faceMasks.getAndMoveToLast(key);
        if (masks != null) {
            faceMaskHits++;
            return masks == NO_NODE ? null : masks;
        }
        Node node = null;
        if (disk != null) {
            try {
                node = disk.load(key);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        faceMaskReads++;
        masks = node == null ? NO_NODE : masksOf(node);
        keepMasks(key, masks);
        return masks == NO_NODE ? null : masks;
    }

    private void keepMasks(long key, long[] masks) {
        faceMasks.putAndMoveToLast(key, masks);
        if (faceMasks.size() > FACE_MASKS) {
            faceMasks.removeFirst();
        }
    }

    private long[] masksOf(Node node) {
        long[] masks = new long[6 * 16];
        for (int a = 0; a < Node.SIZE; a++) {
            for (int b = 0; b < Node.SIZE; b++) {
                int bit = a * 32 + b;
                setOpen(masks, 0, bit, node, Node.index(0, a, b));
                setOpen(masks, 1, bit, node, Node.index(31, a, b));
                setOpen(masks, 2, bit, node, Node.index(a, 0, b));
                setOpen(masks, 3, bit, node, Node.index(a, 31, b));
                setOpen(masks, 4, bit, node, Node.index(a, b, 0));
                setOpen(masks, 5, bit, node, Node.index(a, b, 31));
            }
        }
        return masks;
    }

    private void setOpen(long[] masks, int face, int bit, Node node, int i) {
        if ((flags(node.state(i)) & (OPEN | THIN)) != 0) {
            int at = face * 1024 + bit;
            masks[at >> 6] |= 1L << (at & 63);
        }
    }

    long bytesInMemory() {
        return bytes;
    }
}
