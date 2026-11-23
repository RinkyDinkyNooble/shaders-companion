package com.rinkynooble.shaderscompanion.client.far;

/**
 * One node of the far store, decoded: 32 x 32 x 32 voxels at a level L, each voxel 2^L blocks across (a level-0 node is
 * 2 x 2 x 2 chunk sections). Per voxel a state id ({@link StateIds}: 0 unknown, 1 air) and the light (sky light in the
 * high nibble, block light in the low one); per 4 x 4 x 4 voxels a biome id ({@link BiomeIds}, 0 unknown). Voxels are
 * stored x fastest, then z, then y, as vanilla's sections are, so a section's rows copy straight in. Only the worker
 * thread touches nodes.
 */
final class Node {
    static final int SIZE = 32;
    static final int VOXELS = SIZE * SIZE * SIZE;
    static final int BIOME_CELLS = 8 * 8 * 8;
    static final int LEVELS = 7;
    /** Memory of one decoded node, for the cache's size: the arrays and a little for the object and its map entry. */
    static final long BYTES = VOXELS * 4L + VOXELS + BIOME_CELLS * 2L + 8 * 8 + 160;

    final long key;
    final int level;
    final int x;
    final int y;
    final int z;
    final int[] state = new int[VOXELS];
    final byte[] light = new byte[VOXELS];
    final short[] biome = new short[BIOME_CELLS];
    /** Level 0: the hash of each of the 8 sections as last stored (0 = none yet), by {@link #sectionSlot}. */
    final long[] sectionHash;
    /** Voxels whose state is known (not 0). */
    int known;
    /** Changed since it was last written (M1b writes it to disk; in M1a nothing reads it yet). */
    boolean dirty;
    /** Coarser levels: the octants whose child changed since this node was last rebuilt from them (bit = octant). */
    int dirtyOctants;
    /** When the next rebuild from the dirty octants may run (System.nanoTime), and whether it is in the rebuild queue. */
    long rebuildDue;
    boolean queued;

    Node(int level, int x, int y, int z) {
        this.key = key(level, x, y, z);
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sectionHash = level == 0 ? new long[8] : null;
    }

    /** Level in 3 bits, y in 10, x and z in 25 each (node coordinates reach 30 million / 32 blocks at level 0). */
    static long key(int level, int x, int y, int z) {
        return (long) level << 60 | (long) (y & 0x3FF) << 50 | (long) (x & 0x1FFFFFF) << 25 | (z & 0x1FFFFFF);
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
