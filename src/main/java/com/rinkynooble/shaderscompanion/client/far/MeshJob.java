package com.rinkynooble.shaderscompanion.client.far;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.LongUnaryOperator;

/**
 * A mesh job (research/lod/M2-PLAN.md, "Threads and data flow"): what {@link FarMesher} needs to mesh one node, made on
 * the store's worker ({@link #make}) so the mesher never touches nodes. The node itself is either a copy of its arrays
 * (a node in the store's memory, which may be newer than its disk copy), its stored blob for the mesher to decode, or
 * its uniform values; each of the six neighbours at the same level is either its face layer (read here from a node in
 * memory, or a uniform one: {@link FarMesher#layerValue} values, one short a voxel or one for the whole layer), its blob
 * (the mesher decodes it, or finds its layers in its cache by {@link #stamp}), or unknown. Reading a blob from the
 * region file is cheap; decoding it (155 us, M1c) is left to the mesher, since the worker already takes 14 to 35% of a
 * core (M1c) and most resident nodes are on disk, not in its 64 MB cache.
 */
final class MeshJob {
    /** {@link #make}'s answer for a node that isn't stored (nothing to mesh yet). */
    static final MeshJob ABSENT = new MeshJob(0, 0);
    /** {@link #make}'s answer for a node that can't have faces (uniform air, decorations or unknown). */
    static final MeshJob EMPTY = new MeshJob(0, 0);

    /** A neighbour or the node itself as stored on disk: the blob to decode, and its change stamp for the mesher's cache. */
    record Blob(long key, long stamp, byte[] blob) {
    }

    final long key;
    final int level;
    final long version;
    /** The node from memory: its arrays (from the mesher's {@link FarMesher.Pool}, given back once meshed), or null. */
    int[] state;
    byte[] light;
    short[] biome;
    /** The node as stored on disk, or null. */
    Blob blob;
    /** The node's uniform values (when it has no arrays and no blob, or its biomes are uniform). */
    int uniformState;
    byte uniformLight;
    short uniformBiome;
    /** By Direction ordinal: short[] (a face layer, 1,024 values or 1 for a uniform layer) or {@link Blob}. */
    final Object[] neighbours = new Object[6];
    /** Per state id, {@link FarNodes#meshClass}; ids from {@link #classCount} on count as solid. */
    byte[] classes;
    int classCount;
    int waterId;
    int stoneId;
    /** Where the result goes (a residency; null for the mesh map, which meshes inline). */
    FarResidency sink;

    private MeshJob(long key, long version) {
        this.key = key;
        this.level = Node.keyLevel(key);
        this.version = version;
    }

    boolean caveFill() {
        return level >= FarMesher.fillFromLevel;
    }

    /**
     * Makes the job for a node (worker thread): {@link #ABSENT} when it isn't stored, {@link #EMPTY} when it is uniform
     * and can't have faces. {@code stamps} gives a node's change stamp (0 when it hasn't changed this session).
     */
    static MeshJob make(FarNodes store, long key, long version, LongUnaryOperator stamps, FarMesher.Pool pool) {
        int level = Node.keyLevel(key);
        int nx = Node.keyX(key);
        int ny = Node.keyY(key);
        int nz = Node.keyZ(key);
        // Water's and stone's ids first: they may be new, and the classes must cover them
        int waterId = store.waterId();
        int stoneId = store.stoneId();
        byte[] classes = store.meshClasses();
        int classCount = store.states.size();
        MeshJob job = new MeshJob(key, version);
        boolean fill = job.caveFill();
        Node node = store.peek(key);
        if (node != null) {
            if (node.known == 0) {
                return EMPTY;
            }
            if (node.compact()) {
                job.uniformState = node.uniformState;
                job.uniformLight = node.uniformLight;
            } else {
                job.state = pool.takeState();
                System.arraycopy(node.state, 0, job.state, 0, Node.VOXELS);
                job.light = pool.takeLight();
                System.arraycopy(node.light, 0, job.light, 0, Node.VOXELS);
            }
            if (node.biome != null) {
                job.biome = node.biome.clone();
            } else {
                job.uniformBiome = node.uniformBiome;
            }
        } else {
            FarDisk.Raw raw = raw(store, key);
            if (raw == null) {
                return ABSENT;
            }
            if (raw.blob() != null) {
                job.blob = new Blob(key, stamps.applyAsLong(key), raw.blob());
            } else {
                job.uniformState = raw.uniformState();
                job.uniformLight = raw.uniformLight();
                job.uniformBiome = raw.uniformBiome();
            }
        }
        if (job.state == null && job.blob == null) {
            int c = FarMesher.voxelClass(job.uniformState, job.uniformLight, classes, classCount, fill);
            if (!FarMesher.draws(c)) {
                return EMPTY;
            }
        }
        int y0 = Math.floorDiv(store.minY, Node.SIZE << level);
        int y1 = Math.floorDiv(store.maxY - 1, Node.SIZE << level);
        for (int d = 0; d < 6; d++) {
            int x = nx + FarMesher.STEP_X[d];
            int y = ny + FarMesher.STEP_Y[d];
            int z = nz + FarMesher.STEP_Z[d];
            int side = FarMesher.OPPOSITE[d];
            if (y > y1) {
                // Above the build height: open sky
                job.neighbours[d] = FarMesher.SKY_LAYER;
                continue;
            }
            if (y < y0) {
                job.neighbours[d] = FarMesher.UNKNOWN_LAYER;
                continue;
            }
            long nk = Node.key(level, x, y, z);
            Node n = store.peek(nk);
            if (n != null) {
                job.neighbours[d] = n.known == 0 ? FarMesher.UNKNOWN_LAYER
                        : n.compact() ? new short[]{FarMesher.layerValue(n.uniformState, n.uniformLight, classes, classCount, fill)}
                        : FarMesher.layer(n.state, n.light, side, classes, classCount, fill);
                continue;
            }
            FarDisk.Raw raw = raw(store, nk);
            if (raw == null) {
                job.neighbours[d] = FarMesher.UNKNOWN_LAYER;
            } else if (raw.blob() == null) {
                job.neighbours[d] = new short[]{FarMesher.layerValue(raw.uniformState(), raw.uniformLight(), classes, classCount, fill)};
            } else {
                job.neighbours[d] = new Blob(nk, stamps.applyAsLong(nk), raw.blob());
            }
        }
        job.classes = classes;
        job.classCount = classCount;
        job.waterId = waterId;
        job.stoneId = stoneId;
        return job;
    }

    private static FarDisk.Raw raw(FarNodes store, long key) {
        if (store.disk == null) {
            return null;
        }
        try {
            return store.disk.raw(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
