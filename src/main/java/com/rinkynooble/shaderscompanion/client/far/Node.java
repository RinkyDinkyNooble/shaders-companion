package com.rinkynooble.shaderscompanion.client.far;

import java.util.Arrays;

/**
 * One node of the far store, decoded: 32 x 32 x 32 voxels at a level L, each voxel 2^L blocks across (a level-0 node is
 * 2 x 2 x 2 chunk sections). Per voxel a state id ({@link StateIds}: 0 unknown, 1 air) and the light (sky light in the
 * high nibble, block light in the low one); per 4 x 4 x 4 voxels a biome id ({@link BiomeIds}, 0 unknown). Voxels are
 * stored x fastest, then z, then y, as vanilla's sections are, so a section's rows copy straight in.
 * <p>
 * A node whose voxels are all one state with one light (nearly half of level 0 is open sky) is kept compact: no
 * arrays, only {@link #uniformState} and {@link #uniformLight}, and its biomes as one {@link #uniformBiome} when they
 * are one too. Read through {@link #state(int)}, {@link #light(int)} and {@link #biome(int)}; whoever writes the arrays
 * expands the node first ({@link FarNodes#expand}). Only the worker thread touches nodes.
 */
final class Node {
    static final int SIZE = 32;
    static final int VOXELS = SIZE * SIZE * SIZE;
    static final int BIOME_CELLS = 8 * 8 * 8;
    static final int LEVELS = 7;
    /** Memory of a compact node: the object, its section hashes, its cache entry. */
    static final long COMPACT_BYTES = 128 + 8 * 8 + 16 + 64;
    /** Memory of the state and light arrays. */
    static final long ARRAY_BYTES = VOXELS * 4L + 16 + VOXELS + 16;
    /** Memory of the biome array. */
    static final long BIOME_BYTES = BIOME_CELLS * 2L + 16;
    /** Memory of a node with all its arrays (the most a node takes). */
    static final long FULL_BYTES = COMPACT_BYTES + ARRAY_BYTES + BIOME_BYTES;

    final long key;
    final int level;
    final int x;
    final int y;
    final int z;
    /** The voxels' state ids and light, or null while the node is compact. */
    int[] state;
    byte[] light;
    /** The one state and light of a compact node. */
    int uniformState;
    byte uniformLight;
    /** The biome cells, or null when they are all {@link #uniformBiome}. */
    short[] biome;
    short uniformBiome;
    /** Level 0: the hash of each of the 8 sections as last stored (0 = none yet), by {@link #sectionSlot}. */
    final long[] sectionHash;
    /** Voxels whose state is known (not 0). */
    int known;
    /** Changed since it was last written to disk. */
    boolean dirty;
    /** A copy is on disk, or waiting to be written. */
    boolean stored;
    /** Coarser levels: the octants whose child changed since this node was last rebuilt from them (bit = octant). */
    int dirtyOctants;
    /** When the next rebuild from the dirty octants may run (System.nanoTime), and whether it is in the rebuild queue. */
    long rebuildDue;
    boolean queued;

    /** A new node: compact, every voxel unknown. */
    Node(int level, int x, int y, int z) {
        this.key = key(level, x, y, z);
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sectionHash = level == 0 ? new long[8] : null;
    }

    int state(int i) {
        int[] s = state;
        return s != null ? s[i] : uniformState;
    }

    /** Sky light << 4 | block light, 0 to 255. */
    int light(int i) {
        byte[] l = light;
        return (l != null ? l[i] : uniformLight) & 0xFF;
    }

    int biome(int i) {
        short[] b = biome;
        return b != null ? b[i] : uniformBiome;
    }

    boolean compact() {
        return state == null;
    }

    /** Memory this node takes now. */
    long bytes() {
        return COMPACT_BYTES + (state != null ? ARRAY_BYTES : 0) + (biome != null ? BIOME_BYTES : 0);
    }

    /** Gives a compact node its arrays, filled with its one state, light and biome (use {@link FarNodes#expand}). */
    void expand() {
        if (state == null) {
            state = new int[VOXELS];
            light = new byte[VOXELS];
            if (uniformState != 0) {
                Arrays.fill(state, uniformState);
            }
            if (uniformLight != 0) {
                Arrays.fill(light, uniformLight);
            }
        }
        if (biome == null) {
            biome = new short[BIOME_CELLS];
            if (uniformBiome != 0) {
                Arrays.fill(biome, uniformBiome);
            }
        }
    }

    /**
     * Drops the arrays that hold one value (use {@link FarNodes#tryCompact}): the state and light arrays when every
     * voxel has the same state and light, the biome array when every cell has the same biome.
     */
    void tryCompact() {
        int[] s = state;
        if (s != null) {
            byte[] l = light;
            int first = s[0];
            byte firstLight = l[0];
            boolean same = true;
            for (int i = 1; i < VOXELS; i++) {
                if (s[i] != first || l[i] != firstLight) {
                    same = false;
                    break;
                }
            }
            if (same) {
                uniformState = first;
                uniformLight = firstLight;
                state = null;
                light = null;
            }
        }
        short[] b = biome;
        if (b != null) {
            short first = b[0];
            for (int i = 1; i < BIOME_CELLS; i++) {
                if (b[i] != first) {
                    return;
                }
            }
            uniformBiome = first;
            biome = null;
        }
    }

    /** Level in 3 bits, y in 10, x and z in 25 each (node coordinates reach 30 million / 32 blocks at level 0). */
    static long key(int level, int x, int y, int z) {
        return (long) level << 60 | (long) (y & 0x3FF) << 50 | (long) (x & 0x1FFFFFF) << 25 | (z & 0x1FFFFFF);
    }

    static int keyLevel(long key) {
        return (int) (key >>> 60);
    }

    static int keyX(long key) {
        return (int) (key << 14 >> 39);
    }

    static int keyY(long key) {
        return (int) (key << 4 >> 54);
    }

    static int keyZ(long key) {
        return (int) (key << 39 >> 39);
    }

    static int index(int x, int y, int z) {
        return x + SIZE * (z + SIZE * y);
    }

    static int biomeIndex(int x, int y, int z) {
        return x + 8 * (z + 8 * y);
    }

    /** Which of a level-0 node's 8 sections a section is (also the octant numbering: x, then y, then z bit). */
    static int sectionSlot(int sectionX, int sectionY, int sectionZ) {
        return (sectionX & 1) | (sectionY & 1) << 1 | (sectionZ & 1) << 2;
    }

    /** The key of the node one level up that this node is an octant of. */
    long parentKey() {
        return key(level + 1, x >> 1, y >> 1, z >> 1);
    }

    int octantInParent() {
        return (x & 1) | (y & 1) << 1 | (z & 1) << 2;
    }
}
