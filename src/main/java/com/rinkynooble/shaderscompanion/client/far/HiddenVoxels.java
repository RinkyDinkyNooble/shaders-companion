package com.rinkynooble.shaderscompanion.client.far;

import java.util.Arrays;

/**
 * Hidden voxels (M1c, research/lod/M1.md): an opaque voxel whose six neighbours are all opaque full blocks
 * ({@link FarNodes#OPAQUE}) can never be seen and touches no air, so neither drawing nor lighting needs its exact state
 * or light. When a section is stored, its hidden voxels all take one state, the most common of them in the section,
 * and light 0. Only opaque voxels are replaced (a shut-in pocket of air or lava stays as it is), so the store is opaque
 * exactly where the capture was, and a neighbour's test never rests on a replaced pocket. The variety underground (ores, granite, diorite, tuff, deepslate's) is most of a level-0 blob
 * on disk, and it goes. The section's stored hash stays the hash of what was captured, so a rejoin or an import still
 * skips what is unchanged.
 * <p>
 * A voxel counts as hidden only when all six neighbours are known to be opaque: inside the section from the capture
 * itself, across its faces from what the store holds in memory for the sections next to it (in its own node, or in the
 * nodes around when they are in memory). A neighbour not stored, or not in memory, counts as open: a wrong "open"
 * costs bytes, a wrong "hidden" would show a wrong block. When a section arrives, the faces of the stored sections next
 * to it that face it are looked at again ({@link #late}), so the order sections arrive in matters less (a neighbour
 * that has left memory keeps its face as it was).
 * <p>
 * Changes: when a section is stored again and a voxel on one of its faces was opaque and now isn't, the section next
 * to that face may hold a replaced voxel that can now be seen, whose true state the store no longer has. That section's
 * hash is marked {@link FarNodes#STALE} (it is stored again in full the next time it is copied or imported, as if it
 * had changed) and the main thread is asked to copy it again ({@link FarNodes#recaptures}). A stale section still
 * counts for its neighbours as it is stored (its opacity is the capture's). Worker thread only.
 */
final class HiddenVoxels {
    private static final int P = 18;
    private static final int PP = P * P;

    private final FarNodes nodes;
    /** Opacity of the section being stored (as captured) and of the face layers around it (as stored), 18^3. */
    private final boolean[] grid = new boolean[P * P * P];
    private final boolean[] hidden = new boolean[4096];
    private int[] votes = new int[256];
    private final int[] touched = new int[4096];
    /** The node of the last lookup outside the node at hand. */
    private long lastKey;
    private Node lastNode;
    private boolean lastValid;

    HiddenVoxels(FarNodes nodes) {
        this.nodes = nodes;
    }

    private static int g(int x, int y, int z) {
        return (x + 1) + P * ((z + 1) + P * (y + 1));
    }

    private boolean opaque(int id) {
        return (nodes.flags(id) & FarNodes.OPAQUE) != 0;
    }

    /** The level-0 node holding a block position, if it is in memory. */
    private Node nodeAt(int wx, int wy, int wz) {
        long key = Node.key(0, wx >> 5, wy >> 5, wz >> 5);
        if (!lastValid || key != lastKey) {
            lastKey = key;
            lastNode = nodes.peek(key);
            lastValid = true;
        }
        return lastNode;
    }

    /** Whether the store holds an opaque state at a block position, looking in {@code near} first (null: none). */
    private boolean storedOpaque(Node near, int wx, int wy, int wz) {
        Node n = near != null && wx >> 5 == near.x && wy >> 5 == near.y && wz >> 5 == near.z ? near : nodeAt(wx, wy, wz);
        return n != null && opaque(n.state(Node.index(wx & 31, wy & 31, wz & 31)));
    }

    /**
     * Fills the opacity grid for a section about to be stored: its own voxels from the capture ({@code ids}, x fastest,
     * then z, then y) and the face layers around it from the store.
     */
    void prepare(int sx, int sy, int sz, int[] ids) {
        lastValid = false;
        boolean any = false;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int row = g(0, y, z);
                for (int x = 0; x < 16; x++, i++) {
                    boolean o = opaque(ids[i]);
                    grid[row + x] = o;
                    any |= o;
                }
            }
        }
        int bx = sx << 4;
        int by = sy << 4;
        int bz = sz << 4;
        Node west = nodeAt(bx - 1, by, bz);
        Node east = nodeAt(bx + 16, by, bz);
        Node down = nodeAt(bx, by - 1, bz);
        Node up = nodeAt(bx, by + 16, bz);
        Node north = nodeAt(bx, by, bz - 1);
        Node south = nodeAt(bx, by, bz + 16);
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                if (!any) {
                    // Nothing opaque here: no voxel of it can be hidden, and its neighbours' faces don't matter
                    grid[g(-1, a, b)] = grid[g(16, a, b)] = grid[g(a, -1, b)] = grid[g(a, 16, b)] = grid[g(a, b, -1)] = grid[g(a, b, 16)] = false;
                    continue;
                }
                grid[g(-1, a, b)] = storedOpaque(west, bx - 1, by + a, bz + b);
                grid[g(16, a, b)] = storedOpaque(east, bx + 16, by + a, bz + b);
                grid[g(a, -1, b)] = storedOpaque(down, bx + a, by - 1, bz + b);
                grid[g(a, 16, b)] = storedOpaque(up, bx + a, by + 16, bz + b);
                grid[g(a, b, -1)] = storedOpaque(north, bx + a, by + b, bz - 1);
                grid[g(a, b, 16)] = storedOpaque(south, bx + a, by + b, bz + 16);
            }
        }
    }

    /**
     * Replaces the hidden voxels of the section {@link #prepare} was given ({@code ids} and {@code light}, in place) by
     * its most common hidden opaque state, light 0; returns how many voxels changed.
     */
    int normalize(int[] ids, byte[] light) {
        int best = 0;
        int fill = StateIds.UNKNOWN;
        int distinct = 0;
        int count = 0;
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                int row = g(0, y, z);
                for (int x = 0; x < 16; x++, i++) {
                    int c = row + x;
                    boolean h = grid[c - 1] && grid[c + 1] && grid[c - P] && grid[c + P] && grid[c - PP] && grid[c + PP];
                    hidden[i] = h;
                    if (h) {
                        count++;
                        int id = ids[i];
                        if (opaque(id)) {
                            if (id >= votes.length) {
                                votes = Arrays.copyOf(votes, Math.max(id + 1, votes.length * 2));
                            }
                            if (votes[id]++ == 0) {
                                touched[distinct++] = id;
                            }
                            if (votes[id] > best) {
                                best = votes[id];
                                fill = id;
                            }
                        }
                    }
                }
            }
        }
        for (int k = 0; k < distinct; k++) {
            votes[touched[k]] = 0;
        }
        if (count == 0 || fill == StateIds.UNKNOWN) {
            return 0;
        }
        int replaced = 0;
        for (i = 0; i < 4096; i++) {
            // Opaque voxels only: a pocket of air, water or lava shut in on every side keeps its state, so what the
            // store holds is opaque exactly where the world was (the neighbours' tests can rely on it)
            if (hidden[i] && opaque(ids[i]) && (ids[i] != fill || light[i] != 0)) {
                ids[i] = fill;
                light[i] = 0;
                replaced++;
            }
        }
        return replaced;
    }

    /**
     * Before a stored section is written over by a new copy ({@code ids}, as captured; {@code own} its node, still
     * holding the old one): where a voxel on one of its faces was opaque and now isn't, the stored section next to
     * that face is marked stale if its voxel there is opaque (it may be a replaced one that can now be seen), and the
     * main thread asked to copy it again. Returns the sections marked.
     */
    int invalidateOpened(int sx, int sy, int sz, Node own, int[] ids) {
        int marked = 0;
        int ox = (sx & 1) * 16;
        int oy = (sy & 1) * 16;
        int oz = (sz & 1) * 16;
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            boolean high = (face & 1) != 0;
            int layer = high ? 15 : 0;
            int dx = axis == 0 ? (high ? 1 : -1) : 0;
            int dy = axis == 1 ? (high ? 1 : -1) : 0;
            int dz = axis == 2 ? (high ? 1 : -1) : 0;
            Node neighbour = null;
            int slot = Node.sectionSlot(sx + dx, sy + dy, sz + dz);
            boolean looked = false;
            boolean stale = false;
            scan:
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int x = axis == 0 ? layer : a;
                    int y = axis == 1 ? layer : axis == 0 ? a : b;
                    int z = axis == 2 ? layer : b;
                    int i = x + 16 * (z + 16 * y);
                    if (!opaque(own.state(Node.index(ox + x, oy + y, oz + z))) || opaque(ids[i])) {
                        continue;
                    }
                    // Opened: the voxel across, in the stored neighbour (read from disk if it isn't in memory)
                    if (!looked) {
                        looked = true;
                        neighbour = nodes.levelZeroIfStored(sx + dx >> 1, sy + dy >> 1, sz + dz >> 1);
                        if (neighbour == null || neighbour.sectionHash[slot] == 0 || neighbour.sectionHash[slot] == FarNodes.STALE) {
                            break scan;
                        }
                    }
                    int wx = (sx << 4) + x + dx;
                    int wy = (sy << 4) + y + dy;
                    int wz = (sz << 4) + z + dz;
                    if (opaque(neighbour.state(Node.index(wx & 31, wy & 31, wz & 31)))) {
                        stale = true;
                        break scan;
                    }
                }
            }
            if (stale) {
                nodes.markStale(neighbour, slot, sx + dx, sy + dy, sz + dz);
                marked++;
            }
        }
        lastValid = false;
        return marked;
    }

    /**
     * After a section is stored: the faces of the stored sections next to it that are in memory, looked at again
     * against its new copy ({@link #prepare}'s grid). A voxel there whose six neighbours are now all opaque takes the
     * state of its neighbour one further from this section when that is opaque (the neighbour section's own common
     * state, as a rule), light 0. Returns the voxels changed.
     */
    int late(int sx, int sy, int sz) {
        lastValid = false;
        int changed = 0;
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            boolean high = (face & 1) != 0;
            int layer = high ? 15 : 0;
            int d = high ? 1 : -1;
            int dx = axis == 0 ? d : 0;
            int dy = axis == 1 ? d : 0;
            int dz = axis == 2 ? d : 0;
            int nsx = sx + dx;
            int nsy = sy + dy;
            int nsz = sz + dz;
            Node n = nodes.peek(Node.key(0, nsx >> 1, nsy >> 1, nsz >> 1));
            if (n == null) {
                continue;
            }
            long hash = n.sectionHash[Node.sectionSlot(nsx, nsy, nsz)];
            if (hash == 0) {
                continue;
            }
            boolean nodeChanged = false;
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int x = axis == 0 ? layer : a;
                    int y = axis == 1 ? layer : axis == 0 ? a : b;
                    int z = axis == 2 ? layer : b;
                    // w: this section's voxel on the face (as captured); u: the neighbour's voxel across it
                    if (!grid[g(x, y, z)]) {
                        continue;
                    }
                    int ux = (sx << 4) + x + dx;
                    int uy = (sy << 4) + y + dy;
                    int uz = (sz << 4) + z + dz;
                    // u's other five neighbours, as stored: one further from this section, then the four beside it
                    int fx = ux + dx;
                    int fy = uy + dy;
                    int fz = uz + dz;
                    if (!storedOpaque(n, fx, fy, fz)) {
                        continue;
                    }
                    boolean all = axis == 0
                            ? storedOpaque(n, ux, uy - 1, uz) && storedOpaque(n, ux, uy + 1, uz) && storedOpaque(n, ux, uy, uz - 1) && storedOpaque(n, ux, uy, uz + 1)
                            : axis == 1
                            ? storedOpaque(n, ux - 1, uy, uz) && storedOpaque(n, ux + 1, uy, uz) && storedOpaque(n, ux, uy, uz - 1) && storedOpaque(n, ux, uy, uz + 1)
                            : storedOpaque(n, ux - 1, uy, uz) && storedOpaque(n, ux + 1, uy, uz) && storedOpaque(n, ux, uy - 1, uz) && storedOpaque(n, ux, uy + 1, uz);
                    if (!all) {
                        continue;
                    }
                    int fill = n.state(Node.index(fx & 31, fy & 31, fz & 31));
                    int at = Node.index(ux & 31, uy & 31, uz & 31);
                    int was = n.state(at);
                    if (!opaque(was) || was == fill && n.light(at) == 0) {
                        // Opaque voxels only, as in normalize
                        continue;
                    }
                    nodes.expand(n);
                    n.state[at] = fill;
                    n.light[at] = 0;
                    nodeChanged = true;
                    changed++;
                }
            }
            if (nodeChanged) {
                nodes.changedWithoutIngest(n);
            }
        }
        return changed;
    }
}
