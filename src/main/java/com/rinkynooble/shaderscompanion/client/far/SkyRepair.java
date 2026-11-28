package com.rinkynooble.shaderscompanion.client.far;

/**
 * Sky light repair (3.0.1): a world's saved light can be incomplete, leaving air with sky light 0 right next to sunlit
 * air, which the light engine never does (through air and decorations, sky light drops by at most 1 a block). Lost
 * Cities' highways show it: a deck along a chunk row whose light came in from one side only, 0 under its other, open
 * edge (the user's world, 3.0.0). The far mesher takes air with sky light 0 for a closed cave ({@link FarMesher#FILL})
 * and closed it with stone, a grey wall under the deck as far as the far terrain reached.
 * <p>
 * When a section is stored, its clear voxels (air and decorations, {@link FarNodes#CLEAR}) take at least the sky light
 * of a clear neighbour less one, spread through the section as the light engine spreads it, from its own lit voxels
 * and from the stored sections around it that are in memory ({@link #own}); then those stored sections take the same
 * from it ({@link #push}), so the order sections arrive in doesn't matter while both are in memory. Light only goes up,
 * and only where the rule was broken, so a correctly lit section is left exactly as it was. The repair stays inside one
 * section at a time (a voxel raised next to a further section doesn't carry on into it). The section's stored hash stays
 * the hash of what was captured, as with {@link HiddenVoxels}. Worker thread only.
 */
final class SkyRepair {
    /** Dev checks: the repair off, as before 3.0.1 (FarChecks.skyRepairOff). */
    static volatile boolean off;
    private final FarNodes nodes;
    /** The voxels whose light went up and whose neighbours haven't been looked at since (each in it at most once). */
    private final int[] queue = new int[4096];
    private final boolean[] queued = new boolean[4096];
    private int head;
    private int count;
    /** Per voxel of the section at hand: clear. */
    private final boolean[] clear = new boolean[4096];
    /** The light of the section at hand before the repair, to count what changed. */
    private final byte[] before = new byte[4096];
    /** A stored neighbour section copied out of its node for {@link #push}. */
    private final int[] otherIds = new int[4096];
    private final byte[] otherLight = new byte[4096];

    SkyRepair(FarNodes nodes) {
        this.nodes = nodes;
    }

    private boolean clearId(int id) {
        return (nodes.flags(id) & FarNodes.CLEAR) != 0;
    }

    /**
     * Repairs the sky light of a section about to be stored ({@code ids} and {@code light} in vanilla's order, x
     * fastest, then z, then y; light in place) from its own voxels and the stored sections around it in memory.
     * Returns the voxels raised.
     */
    int own(int sx, int sy, int sz, int[] ids, byte[] light) {
        if (off || !prepare(ids, light)) {
            return 0;
        }
        for (int i = 0; i < 4096; i++) {
            if (clear[i] && (light[i] & 0xFF) >> 4 >= 2) {
                enqueue(i);
            }
        }
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            int d = (face & 1) != 0 ? 1 : -1;
            int nsx = sx + (axis == 0 ? d : 0);
            int nsy = sy + (axis == 1 ? d : 0);
            int nsz = sz + (axis == 2 ? d : 0);
            Node n = storedInMemory(nsx, nsy, nsz);
            if (n == null) {
                continue;
            }
            int ox = (nsx & 1) * 16;
            int oy = (nsy & 1) * 16;
            int oz = (nsz & 1) * 16;
            int inside = d > 0 ? 15 : 0;
            int across = d > 0 ? 0 : 15;
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int i = index(axis, inside, a, b);
                    if (!clear[i]) {
                        continue;
                    }
                    int u = Node.index(ox + coordX(axis, across, a), oy + coordY(axis, across, a, b), oz + coordZ(axis, across, b));
                    if (clearId(n.state(u))) {
                        raise(i, spread(n.light(u) >> 4, axis == 1 && d > 0), light);
                    }
                }
            }
        }
        return relax(light);
    }

    /**
     * After a section is stored ({@code ids} and {@code light} as stored): the stored sections around it that are in
     * memory take its sky light less one where theirs is lower, spread through each of them; their nodes are marked
     * changed (written, folded into the coarser levels and meshed again). Returns the voxels raised.
     */
    int push(int sx, int sy, int sz, int[] ids, byte[] light, long now) {
        int raised = 0;
        if (off) {
            return 0;
        }
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            int d = (face & 1) != 0 ? 1 : -1;
            int nsx = sx + (axis == 0 ? d : 0);
            int nsy = sy + (axis == 1 ? d : 0);
            int nsz = sz + (axis == 2 ? d : 0);
            Node n = storedInMemory(nsx, nsy, nsz);
            if (n == null) {
                continue;
            }
            int ox = (nsx & 1) * 16;
            int oy = (nsy & 1) * 16;
            int oz = (nsz & 1) * 16;
            int inside = d > 0 ? 15 : 0;
            int across = d > 0 ? 0 : 15;
            // First only whether the face breaks the rule anywhere (it seldom does)
            boolean broken = false;
            scan:
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int i = index(axis, inside, a, b);
                    int s = spread((light[i] & 0xFF) >> 4, axis == 1 && d < 0);
                    if (s < 1 || !clearId(ids[i])) {
                        continue;
                    }
                    int u = Node.index(ox + coordX(axis, across, a), oy + coordY(axis, across, a, b), oz + coordZ(axis, across, b));
                    if (clearId(n.state(u)) && n.light(u) >> 4 < s) {
                        broken = true;
                        break scan;
                    }
                }
            }
            if (!broken) {
                continue;
            }
            int k = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, k++) {
                        int at = Node.index(ox + x, oy + y, oz + z);
                        otherIds[k] = n.state(at);
                        otherLight[k] = (byte) n.light(at);
                    }
                }
            }
            if (!prepare(otherIds, otherLight)) {
                continue;
            }
            for (int a = 0; a < 16; a++) {
                for (int b = 0; b < 16; b++) {
                    int i = index(axis, inside, a, b);
                    if (clearId(ids[i])) {
                        raise(index(axis, across, a, b), spread((light[i] & 0xFF) >> 4, axis == 1 && d < 0), otherLight);
                    }
                }
            }
            int changed = relax(otherLight);
            if (changed == 0) {
                continue;
            }
            nodes.expand(n);
            k = 0;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++, k++) {
                        if (otherLight[k] != before[k]) {
                            n.light[Node.index(ox + x, oy + y, oz + z)] = otherLight[k];
                        }
                    }
                }
            }
            nodes.lightChanged(n, now);
            raised += changed;
        }
        return raised;
    }

    /** A level-0 node in memory holding a stored copy of the section, or null. */
    private Node storedInMemory(int sx, int sy, int sz) {
        Node n = nodes.peek(0, sx >> 1, sy >> 1, sz >> 1);
        return n != null && n.sectionHash[Node.sectionSlot(sx, sy, sz)] != 0 ? n : null;
    }

    /**
     * Marks the clear voxels, keeps the light as it was and empties the queue; false when no clear voxel is dark
     * enough to be raised (13 or less), which is most sections: open sky, solid rock.
     */
    private boolean prepare(int[] ids, byte[] light) {
        boolean any = false;
        for (int i = 0; i < 4096; i++) {
            boolean c = clearId(ids[i]);
            clear[i] = c;
            any |= c && (light[i] & 0xFF) >> 4 <= 13;
        }
        if (!any) {
            return false;
        }
        System.arraycopy(light, 0, before, 0, 4096);
        head = 0;
        count = 0;
        return true;
    }

    private void enqueue(int i) {
        if (!queued[i]) {
            queued[i] = true;
            queue[(head + count++) & 4095] = i;
        }
    }

    /** Raises a clear voxel's sky light to {@code sky} if it is lower, and queues it. */
    private void raise(int i, int sky, byte[] light) {
        if (sky <= 0 || !clear[i]) {
            return;
        }
        int l = light[i] & 0xFF;
        if (l >> 4 >= sky) {
            return;
        }
        light[i] = (byte) (sky << 4 | l & 15);
        enqueue(i);
    }

    /** Spreads the queued voxels' light through the section; returns the voxels whose light changed. */
    private int relax(byte[] light) {
        while (count > 0) {
            int i = queue[head];
            head = head + 1 & 4095;
            count--;
            queued[i] = false;
            int own = (light[i] & 0xFF) >> 4;
            int s = own - 1;
            int x = i & 15;
            int z = i >> 4 & 15;
            int y = i >> 8;
            if (y > 0) {
                raise(i - 256, spread(own, true), light);
            }
            if (s <= 0) {
                continue;
            }
            if (x > 0) {
                raise(i - 1, s, light);
            }
            if (x < 15) {
                raise(i + 1, s, light);
            }
            if (z > 0) {
                raise(i - 16, s, light);
            }
            if (z < 15) {
                raise(i + 16, s, light);
            }
            if (y < 15) {
                raise(i + 256, s, light);
            }
        }
        int changed = 0;
        for (int i = 0; i < 4096; i++) {
            if (light[i] != before[i]) {
                changed++;
            }
        }
        return changed;
    }

    /** The sky light a clear voxel passes to a clear neighbour: one less, but full sky light straight down stays full. */
    private static int spread(int sky, boolean down) {
        return down && sky == 15 ? 15 : sky - 1;
    }

    /** A voxel of a section's face: {@code layer} along the axis, (a, b) across it, as {@link HiddenVoxels} walks faces. */
    private static int index(int axis, int layer, int a, int b) {
        return coordX(axis, layer, a) + 16 * (coordZ(axis, layer, b) + 16 * coordY(axis, layer, a, b));
    }

    private static int coordX(int axis, int layer, int a) {
        return axis == 0 ? layer : a;
    }

    private static int coordY(int axis, int layer, int a, int b) {
        return axis == 1 ? layer : axis == 0 ? a : b;
    }

    private static int coordZ(int axis, int layer, int b) {
        return axis == 2 ? layer : b;
    }
}
