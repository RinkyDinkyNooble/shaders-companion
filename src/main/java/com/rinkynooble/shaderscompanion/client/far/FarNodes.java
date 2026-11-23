package com.rinkynooble.shaderscompanion.client.far;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
 * (per level, least recently used first; the finest level leaves first, as DESIGN's disk eviction has it), the state and biome ids, and the coarser levels' rebuilds. A captured section is converted to
 * ids and hashed; when the hash equals the one stored for that section nothing changes (what lets a rejoin skip
 * sections once M1b keeps the hashes on disk), else its 16 x 16 x 16 voxels go into their level-0 node and every node
 * above it is marked. A marked node is rebuilt from its changed children ({@link CoarseLevels}) no sooner than 5
 * seconds after its last mark, which marks its own parent in turn, up to level 6. A node pushed out of the cache folds
 * its pending changes into its parent first; then it is dropped (M1a has no disk: dropped nodes are counted, and M1b
 * writes them instead).
 */
final class FarNodes {
    static final long REBUILD_DELAY = 5_000_000_000L;

    final int generation;
    final String world;
    final String dimension;
    /** The dimension's build height, in blocks (the map scans nodes in it). */
    final int minY;
    final int maxY;
    final StateIds states = new StateIds();
    final BiomeIds biomes = new BiomeIds();
    private final CoarseLevels coarse = new CoarseLevels(this);
    /** Per level, in order of use (least recent first). */
    @SuppressWarnings("unchecked")
    private final Long2ObjectLinkedOpenHashMap<Node>[] cache = new Long2ObjectLinkedOpenHashMap[Node.LEVELS];
    private int cached;
    private long maxBytes;
    /** Nodes waiting for a rebuild, in the order they were marked, with the due time each was queued with. */
    private final LongArrayFIFOQueue rebuildKeys = new LongArrayFIFOQueue();
    private final LongArrayFIFOQueue rebuildDues = new LongArrayFIFOQueue();
    /** Chunk columns with at least one section stored. */
    final LongOpenHashSet explored = new LongOpenHashSet();

    // Per state id: CoarseLevels' flags, worked out the first time an id is used
    /** Air, or water a face can be seen through (water itself, kelp, seagrass). */
    static final byte OPEN = 1;
    /** The water block (any level). */
    static final byte WATER = 2;
    /** A decoration with no collision (grass and flowers, torches, vines; not fluids, not snow layers, which cover the ground). */
    static final byte THIN = 4;
    /** Solid but see-through with a collision shape (leaves, glass, fences, walls, panes): thin parts of a canopy or a build. */
    static final byte SHEER = 8;
    private byte[] flags = new byte[256];
    private int flagsKnown = 2;

    // Statistics (the worker's; FarWorker publishes them)
    final int[] nodes = new int[Node.LEVELS];
    final long[] dropped = new long[Node.LEVELS];
    long sectionsStored;
    long sectionsIngested;
    long sectionsUnchanged;
    long rebuilds;
    long folds;
    long ingestNanos;
    long rebuildNanos;
    long foldNanos;
    double ingestMicros;
    double rebuildMicros;

    // Scratch for one section
    private final int[] sectionIds = new int[4096];
    private final byte[] sectionLight = new byte[4096];
    private final short[] sectionBiomes = new short[64];

    FarNodes(int generation, String world, String dimension, int minY, int maxY, int cacheMb) {
        this.generation = generation;
        this.world = world;
        this.dimension = dimension;
        this.minY = minY;
        this.maxY = maxY;
        setCacheMb(cacheMb);
        flags[StateIds.AIR] = OPEN;
        for (int level = 0; level < Node.LEVELS; level++) {
            cache[level] = new Long2ObjectLinkedOpenHashMap<>();
        }
    }

    void setCacheMb(int cacheMb) {
        maxBytes = cacheMb * 1024L * 1024L;
    }

    int cacheMb() {
        return (int) (maxBytes >> 20);
    }

    void forEachNode(java.util.function.Consumer<Node> action) {
        for (Long2ObjectLinkedOpenHashMap<Node> level : cache) {
            for (Node node : level.values()) {
                action.accept(node);
            }
        }
    }

    int cachedNodes() {
        return cached;
    }

    int rebuildsWaiting() {
        return rebuildKeys.size();
    }

    /** A node if it is in memory, without counting as a use (the cache's order stays). */
    Node peek(long key) {
        return cache[(int) (key >>> 60)].get(key);
    }

    Node peek(int level, int x, int y, int z) {
        return cache[level].get(Node.key(level, x, y, z));
    }

    private Node getOrCreate(int level, int x, int y, int z) {
        long key = Node.key(level, x, y, z);
        Node node = cache[level].getAndMoveToLast(key);
        if (node == null) {
            node = new Node(level, x, y, z);
            cache[level].putAndMoveToLast(key, node);
            cached++;
            nodes[level]++;
        }
        return node;
    }

    /** CoarseLevels' flags of a state id ({@link #OPEN}, {@link #WATER}, {@link #THIN}, {@link #SHEER}). */
    byte flags(int id) {
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
                    if (state.getFluidState().is(FluidTags.WATER) && !state.canOcclude()) {
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
                }
                flags[i] = f;
            }
            flagsKnown = size;
        }
        return id < flagsKnown ? flags[id] : 0;
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
        Node node = getOrCreate(0, section.x >> 1, section.y >> 1, section.z >> 1);
        int slot = Node.sectionSlot(section.x, section.y, section.z);
        if (node.sectionHash[slot] == hash) {
            sectionsUnchanged++;
        } else {
            if (node.sectionHash[slot] == 0) {
                node.known += 4096;
                sectionsStored++;
                explored.add(ChunkPos.asLong(section.x, section.z));
            }
            node.sectionHash[slot] = hash;
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
            node.dirty = true;
            markParent(node, now);
        }
        long nanos = System.nanoTime() - start;
        ingestNanos += nanos;
        ingestMicros = ingestMicros == 0 ? nanos / 1.0e3 : ingestMicros * 0.99 + nanos / 1.0e3 * 0.01;
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

    /** A 64-bit hash of the converted section (ids, light, biomes); never 0, which means "none stored". */
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
        return h == 0 ? 1 : h;
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

    /** Nanoseconds until the first queued rebuild may run (0 if now, Long.MAX_VALUE if none is queued). */
    long nanosUntilRebuild(long now) {
        return rebuildKeys.isEmpty() ? Long.MAX_VALUE : Math.max(0, rebuildDues.firstLong() - now);
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
        node.dirty = true;
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
    }

    /**
     * Pushes nodes out until the cache fits its size: the least recently used of the finest level that has any, so the
     * coarser levels, which hold everything folded into them, stay while level 0 streams through.
     */
    void trim(long now) {
        while (cached > Node.LEVELS && cached * Node.BYTES > maxBytes) {
            int finest = 0;
            while (cache[finest].isEmpty()) {
                finest++;
            }
            Node node = cache[finest].removeFirst();
            cached--;
            nodes[node.level]--;
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
            if (node.level == 0) {
                for (long hash : node.sectionHash) {
                    if (hash != 0) {
                        sectionsStored--;
                    }
                }
            }
            // M1b writes a dirty node here; until then it is gone
            dropped[node.level]++;
        }
    }

    /** The node next to the given one at its level, if it is in memory (for CoarseLevels' exposure test at node borders). */
    Node neighbour(Node node, int dx, int dy, int dz) {
        return peek(node.level, node.x + dx, node.y + dy, node.z + dz);
    }

    long bytesInMemory() {
        return cached * Node.BYTES;
    }
}
