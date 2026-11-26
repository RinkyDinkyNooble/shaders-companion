package com.rinkynooble.shaderscompanion.far;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server's anti-x-ray step for shared far terrain ({@code farShareHideEnclosed}): in a column about to be sent, every
 * opaque full block whose six neighbours are opaque full blocks takes the section's most common such block (stone,
 * deepslate) and no light, so the column carries no ores, no hidden rooms and no cave variety that can't be seen from
 * open air. The same rule the client's far store applies to what it stores (HiddenVoxels), with one difference: a
 * neighbour across the column's sides, which the server doesn't read, counts as opaque here (a wrong "open" would leak
 * a block through every chunk border; a wrong "opaque" hides a cave wall at a chunk border, which far terrain fills in
 * any case). Above the column's top is open air, below its bottom solid. Opacity is {@code isSolidRender} without a
 * world, worked out once per block state. Common code; thread-safe (the opacity table is only ever completed).
 */
final class EnclosedBlocks {
    private static final byte UNKNOWN = 0;
    private static final byte OPEN = 1;
    private static final byte OPAQUE = 2;
    private static volatile byte[] opacity = new byte[0];

    private EnclosedBlocks() {
    }

    /**
     * Replaces the enclosed blocks of a column's sections (global block state ids, index x + 16 z + 256 y; a null section
     * is all air) in place; returns per section the voxels replaced (null where none were).
     */
    static boolean[][] hide(int[][] sections) {
        int count = sections.length;
        boolean[][] opaque = new boolean[count][];
        for (int s = 0; s < count; s++) {
            if (sections[s] != null) {
                boolean[] o = new boolean[4096];
                int[] ids = sections[s];
                for (int i = 0; i < 4096; i++) {
                    o[i] = opaque(ids[i]);
                }
                opaque[s] = o;
            }
        }
        boolean[][] replaced = new boolean[count][];
        int[] votes = new int[0];
        int[] voteIds = new int[0];
        for (int s = 0; s < count; s++) {
            boolean[] o = opaque[s];
            if (o == null) {
                continue;
            }
            boolean[] hidden = null;
            for (int i = 0; i < 4096; i++) {
                if (!o[i]) {
                    continue;
                }
                int x = i & 15;
                int z = i >> 4 & 15;
                int y = i >> 8;
                // Across the column's sides: opaque (see the class comment)
                if (x > 0 && !o[i - 1] || x < 15 && !o[i + 1] || z > 0 && !o[i - 16] || z < 15 && !o[i + 16]) {
                    continue;
                }
                boolean below = y > 0 ? o[i - 256] : s == 0 || opaque[s - 1] != null && opaque[s - 1][i + 15 * 256];
                if (!below) {
                    continue;
                }
                boolean above = y < 15 ? o[i + 256] : s + 1 < count && opaque[s + 1] != null && opaque[s + 1][i - 15 * 256];
                if (!above) {
                    continue;
                }
                if (hidden == null) {
                    hidden = new boolean[4096];
                }
                hidden[i] = true;
            }
            if (hidden == null) {
                continue;
            }
            // The most common of the section's enclosed blocks
            int[] ids = sections[s];
            int distinct = 0;
            for (int i = 0; i < 4096; i++) {
                if (!hidden[i]) {
                    continue;
                }
                int k = 0;
                while (k < distinct && voteIds[k] != ids[i]) {
                    k++;
                }
                if (k == distinct) {
                    if (distinct == voteIds.length) {
                        voteIds = java.util.Arrays.copyOf(voteIds, Math.max(16, distinct * 2));
                        votes = java.util.Arrays.copyOf(votes, voteIds.length);
                    }
                    voteIds[distinct] = ids[i];
                    votes[distinct++] = 0;
                }
                votes[k]++;
            }
            int fill = voteIds[0];
            int best = votes[0];
            for (int k = 1; k < distinct; k++) {
                if (votes[k] > best) {
                    best = votes[k];
                    fill = voteIds[k];
                }
            }
            for (int i = 0; i < 4096; i++) {
                if (hidden[i]) {
                    ids[i] = fill;
                }
            }
            replaced[s] = hidden;
        }
        return replaced;
    }

    /** Whether a block state id is an opaque full block (worked out once per id). */
    private static boolean opaque(int id) {
        byte[] table = opacity;
        if (id >= 0 && id < table.length && table[id] != UNKNOWN) {
            return table[id] == OPAQUE;
        }
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
        boolean solid;
        try {
            solid = state != null && state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // A modded block that wants a real world: never counted as hiding anything
            solid = false;
        }
        if (id >= 0) {
            synchronized (EnclosedBlocks.class) {
                byte[] t = opacity;
                if (id >= t.length) {
                    t = java.util.Arrays.copyOf(t, Math.max(id + 1, Math.max(1024, Block.BLOCK_STATE_REGISTRY.size())));
                }
                t[id] = solid ? OPAQUE : OPEN;
                opacity = t;
            }
        }
        return solid;
    }
}
