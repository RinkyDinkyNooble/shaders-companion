package com.rinkynooble.shaderscompanion.client.far;

import java.util.Arrays;

/**
 * The rules that build a coarser level from the one below (DESIGN "Building the coarser levels", M1-PLAN step 3). A
 * level L+1 voxel stands for a 2 x 2 x 2 group of level-L voxels, its children, all in one child node, so a child node
 * fills one 16 x 16 x 16 octant of its parent and an octant can be rebuilt on its own. Per group:
 * <ul>
 * <li>Unknown children (id 0) don't vote; the voxel is unknown only when all 8 are, and then a fold leaves the
 * parent's voxel as it was instead of writing unknown over it (known terrain never becomes unknown again).</li>
 * <li>Water stays water when any child in the top layer of the group is water, so coastlines and the sea surface
 * don't dissolve.</li>
 * <li>Decorations without collision (grass and flowers, torches, vines: {@link FarNodes#THIN}) count with the air:
 * they vanish at the coarser levels instead of turning into bushes, and the ground under them still counts as
 * exposed. Snow layers are not decorations: they colour the mountains.</li>
 * <li>Air only when more than half the known children are air (or decorations) and the exposed solid children (those
 * with an open or decorated face neighbour, inside the group or across its faces) weigh less than 3 in 8 of the known
 * ones, see-through solids (leaves, glass, fences: {@link FarNodes#SHEER}) counting twice: a one-voxel roof, wall or
 * floor (4 of 8) and a diagonal wall (3 of 8) stay, a lone post or beam (2 of 8) goes unless it is leaves or glass,
 * so canopies and lone trees last a level or two longer.</li>
 * <li>Otherwise the most common solid state among the children whose face above is open (what is seen from above:
 * grass over the dirt of a slope's side, snow over stone), then among the exposed ones, then among all; ties go to
 * the upper layer.</li>
 * <li>Light: the highest sky light and the highest block light among the open and decoration children, 0 when there
 * are none.</li>
 * <li>Biome: the most common known one of the 2 x 2 x 2 child biome cells.</li>
 * </ul>
 * The plan's rules (M1-PLAN step 3) were the first version; the map dumps of M1a showed slopes turning to dirt and
 * stone, and lone trees and canopies thinning by half a level, which the decoration, see-through and face-above parts
 * answer (research/lod/M1.md). A compact child (one state and light) is read as if it had its arrays; the parent gets
 * its arrays before it is written. Worker thread only.
 */
final class CoarseLevels {
    private final FarNodes nodes;
    /** Per voxel of the child node being folded: whether a face next to it is seen (open or a decoration). */
    private final boolean[] open = new boolean[Node.VOXELS];
    private final int[] ids = new int[8];
    /** Per child of the group: 0 not exposed, 1 exposed, 2 exposed with its face above open. */
    private final int[] exposed = new int[8];
    private final byte[] lights = new byte[8];
    private final int[] biomeVotes = new int[8];
    /** A compact child's one state and light, spread over arrays for the loops below (made on first use). */
    private int[] uniformState;
    private byte[] uniformLight;

    CoarseLevels(FarNodes nodes) {
        this.nodes = nodes;
    }

    /** Folds a child node into its octant of the parent, replacing what the parent held there. */
    void fold(Node child, Node parent, int octant) {
        nodes.expand(parent);
        int[] state = child.state;
        byte[] childLight = child.light;
        if (state == null) {
            if (uniformState == null) {
                uniformState = new int[Node.VOXELS];
                uniformLight = new byte[Node.VOXELS];
            }
            Arrays.fill(uniformState, child.uniformState);
            Arrays.fill(uniformLight, child.uniformLight);
            state = uniformState;
            childLight = uniformLight;
        }
        for (int i = 0; i < Node.VOXELS; i++) {
            open[i] = (nodes.flags(state[i]) & (FarNodes.OPEN | FarNodes.THIN)) != 0;
        }
        // The six nodes around the child, for the exposure of its border voxels (null when not in memory: not open)
        Node west = nodes.neighbour(child, -1, 0, 0);
        Node east = nodes.neighbour(child, 1, 0, 0);
        Node down = nodes.neighbour(child, 0, -1, 0);
        Node up = nodes.neighbour(child, 0, 1, 0);
        Node north = nodes.neighbour(child, 0, 0, -1);
        Node south = nodes.neighbour(child, 0, 0, 1);
        int ox = (octant & 1) * 16;
        int oy = (octant >> 1 & 1) * 16;
        int oz = (octant >> 2 & 1) * 16;
        int knownChange = 0;
        for (int py = 0; py < 16; py++) {
            for (int pz = 0; pz < 16; pz++) {
                for (int px = 0; px < 16; px++) {
                    // The 8 children, the top layer first
                    int k = 0;
                    int known = 0;
                    int air = 0;
                    int exposedSolid = 0;
                    int faceUp = 0;
                    int water = 0;
                    int sky = 0;
                    int block = 0;
                    for (int dy = 1; dy >= 0; dy--) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++, k++) {
                                int x = 2 * px + dx;
                                int y = 2 * py + dy;
                                int z = 2 * pz + dz;
                                int i = Node.index(x, y, z);
                                int id = state[i];
                                ids[k] = id;
                                exposed[k] = 0;
                                if (id == StateIds.UNKNOWN) {
                                    continue;
                                }
                                known++;
                                byte flags = nodes.flags(id);
                                if (open[i]) {
                                    int light = childLight[i];
                                    sky = Math.max(sky, light >> 4 & 15);
                                    block = Math.max(block, light & 15);
                                }
                                if (id == StateIds.AIR || (flags & FarNodes.THIN) != 0) {
                                    air++;
                                    continue;
                                }
                                if (dy == 1 && water == 0 && (flags & FarNodes.WATER) != 0) {
                                    water = id;
                                }
                                if (y < 31 ? open[Node.index(x, y + 1, z)] : openIn(up, x, 0, z)) {
                                    exposed[k] = 2;
                                    faceUp++;
                                } else if (exposed(child, x, y, z, west, east, down, north, south)) {
                                    exposed[k] = 1;
                                }
                                if (exposed[k] > 0) {
                                    exposedSolid += (flags & FarNodes.SHEER) != 0 ? 2 : 1;
                                }
                            }
                        }
                    }
                    int result;
                    if (known == 0) {
                        // Nothing known here: the parent keeps what it had (the store never forgets terrain, and a
                        // child made again after it left memory knows only part of what it held)
                        continue;
                    } else if (water != 0) {
                        result = water;
                    } else if (air * 2 > known && exposedSolid * 8 < known * 3) {
                        result = StateIds.AIR;
                    } else {
                        result = vote(faceUp > 0 ? 2 : exposedSolid > 0 ? 1 : 0);
                    }
                    int p = Node.index(ox + px, oy + py, oz + pz);
                    knownChange += (result != StateIds.UNKNOWN ? 1 : 0) - (parent.state[p] != StateIds.UNKNOWN ? 1 : 0);
                    parent.state[p] = result;
                    parent.light[p] = (byte) (sky << 4 | block);
                }
            }
        }
        parent.known += knownChange;
        // Biomes: each parent cell (4 x 4 x 4 parent voxels) from 2 x 2 x 2 child cells
        for (int by = 0; by < 4; by++) {
            for (int bz = 0; bz < 4; bz++) {
                for (int bx = 0; bx < 4; bx++) {
                    int k = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                biomeVotes[k++] = child.biome(Node.biomeIndex(2 * bx + dx, 2 * by + dy, 2 * bz + dz));
                            }
                        }
                    }
                    int biome = mostCommon(biomeVotes);
                    if (biome != BiomeIds.UNKNOWN) {
                        parent.biome[Node.biomeIndex(ox / 4 + bx, oy / 4 + by, oz / 4 + bz)] = (short) biome;
                    }
                }
            }
        }
    }

    /**
     * The most common solid state of the group's children whose exposure is at least the given one (2: face above
     * open, 1: exposed, 0: all). Decorations never get here: their exposure stays 0 and they are skipped.
     */
    private int vote(int minExposure) {
        int best = StateIds.UNKNOWN;
        int bestCount = 0;
        for (int a = 0; a < 8; a++) {
            int id = ids[a];
            if (id == StateIds.UNKNOWN || id == StateIds.AIR || exposed[a] < minExposure || (nodes.flags(id) & FarNodes.THIN) != 0) {
                continue;
            }
            int count = 0;
            for (int b = a; b < 8; b++) {
                if (ids[b] == id && exposed[b] >= minExposure) {
                    count++;
                }
            }
            // Strictly more: a tie keeps the earlier child, the upper layer
            if (count > bestCount) {
                best = id;
                bestCount = count;
            }
        }
        return best;
    }

    /** The most common value other than 0 (unknown), ties to the first; 0 when all are 0. */
    private static int mostCommon(int[] values) {
        int best = 0;
        int bestCount = 0;
        for (int a = 0; a < values.length; a++) {
            int v = values[a];
            if (v == 0) {
                continue;
            }
            int count = 0;
            for (int b = a; b < values.length; b++) {
                if (values[b] == v) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = v;
                bestCount = count;
            }
        }
        return best;
    }

    /**
     * Whether a child voxel has a seen face neighbour other than the one above (checked first by the caller), in its
     * node or in the next node (when that is in memory).
     */
    private boolean exposed(Node child, int x, int y, int z, Node west, Node east, Node down, Node north, Node south) {
        return (x > 0 ? open[Node.index(x - 1, y, z)] : openIn(west, 31, y, z))
                || (x < 31 ? open[Node.index(x + 1, y, z)] : openIn(east, 0, y, z))
                || (y > 0 ? open[Node.index(x, y - 1, z)] : openIn(down, x, 31, z))
                || (z > 0 ? open[Node.index(x, y, z - 1)] : openIn(north, x, y, 31))
                || (z < 31 ? open[Node.index(x, y, z + 1)] : openIn(south, x, y, 0));
    }

    private boolean openIn(Node node, int x, int y, int z) {
        return node != null && (nodes.flags(node.state(Node.index(x, y, z))) & (FarNodes.OPEN | FarNodes.THIN)) != 0;
    }
}
