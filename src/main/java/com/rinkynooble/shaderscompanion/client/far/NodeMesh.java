package com.rinkynooble.shaderscompanion.client.far;

/**
 * One node's mesh (research/lod/M2-PLAN.md, "Meshing"), made by {@link FarMesher} from a {@link MeshJob} and passed to
 * the render thread through {@link FarResidency}'s outbox. Immutable once made; any thread may read it.
 * <p>
 * <b>Quads</b>: {@link #quads} holds 2 ints a quad (8 bytes, little-endian ints when uploaded as they are), sorted into
 * 12 ranges, range {@code 2 * direction + (border ? 1 : 0)} with the direction as Minecraft's {@code Direction}
 * ordinal (0 down -y, 1 up +y, 2 north -z, 3 south +z, 4 west -x, 5 east +x). {@link #rangeStart} and
 * {@link #rangeCount} are in quads. Within a range the opaque quads come first ({@link #rangeOpaque} of them), then
 * the translucent ones (water, stained glass, ice: states drawn in the translucent pass), so the opaque pass and the
 * water and glass pass each draw one part of the same 12 ranges.
 * <ul>
 * <li>word 0: bits 0-4 x, 5-9 y, 10-14 z (the voxel in the node whose face this is: the quad's corner with the lowest
 * u and v), 15-19 width - 1 (along u), 20-24 height - 1 (along v), 25-27 the direction (also implied by the range),
 * 28-31 zero.</li>
 * <li>word 1: bits 0-13 the index into {@link #palette}, 14-21 the light of the voxel in front of the face (sky light
 * in the high nibble, block light in the low one, as the store keeps it), 22-29 corner shading (2 bits a corner,
 * corners (u0, v0), (u1, v0), (u1, v1), (u0, v1) from the low bits; 3 is unshaded, 0 the darkest; all 3 while
 * {@link FarMesher#cornerShading} is off), 30-31 zero.</li>
 * </ul>
 * The in-plane axes: down and up faces u = x, v = z; north and south u = x, v = y; west and east u = z, v = y. A face
 * lies on the voxel's side in its direction: an up face of voxel y at height y + 1, a down face at y, and so on, in
 * voxels of {@code 2^level} blocks from the node's origin ({@link #x} * 32 * 2^level blocks, likewise y and z).
 * <p>
 * <b>Border ranges</b> hold the faces on the node's side in that direction that the neighbour node at the same level
 * hides (its voxel there is solid, or unknown, or for water also water): the seam faces, to be drawn only where that
 * neighbour is drawn at another level or not at all, and only where the neighbour's voxel is within
 * {@link FarMesher#SKIRT_REACH} voxels of an open one in its face layer (near the surface, where a coarser or finer
 * level can differ). Faces on that side that are seen at the same level are in the direction's main range.
 * <p>
 * <b>Palette</b>: per entry a store state id in bits 0-19 and a store biome id in bits 20-31 (biome 0 for states
 * without a tint provider, so their faces merge across biomes). Water-like voxels (water, kelp, seagrass) use the
 * water block's id; cave-filled air (levels 1 and up) uses stone's. Colours come from {@link StateColors}.
 */
public final class NodeMesh {
    /** Quad ranges: 2 per direction, main then border. */
    public static final int RANGES = 12;

    public final long key;
    public final int level;
    public final int x;
    public final int y;
    public final int z;
    /** The residency's version of the node this was meshed from; an older one than the residency's is stale. */
    public final long version;
    /** 2 ints a quad, exactly {@link #quadCount} quads long. */
    public final int[] quads;
    public final int quadCount;
    public final int[] rangeStart;
    public final int[] rangeCount;
    /** Per range: how many of its quads, from its start, are opaque (the rest are translucent). */
    public final int[] rangeOpaque;
    public final int[] palette;
    /**
     * The geometry's bounding box in voxels from the node's origin, min x, y, z then max x, y, z (exclusive, 0 to 32);
     * all 0 when there are no quads.
     */
    public final int[] box;
    /** Meshing time on the mesher thread, nanoseconds (decoding the node and its neighbours included). */
    public final long nanos;
    /** Quads of the cave-filled or dark parts: faces whose voxel in front has sky light 0 (for the statistics). */
    public final int darkQuads;

    NodeMesh(long key, long version, int[] quads, int quadCount, int[] rangeStart, int[] rangeCount, int[] rangeOpaque, int[] palette, int[] box, long nanos,
             int darkQuads) {
        this.key = key;
        this.level = Node.keyLevel(key);
        this.x = Node.keyX(key);
        this.y = Node.keyY(key);
        this.z = Node.keyZ(key);
        this.version = version;
        this.quads = quads;
        this.quadCount = quadCount;
        this.rangeStart = rangeStart;
        this.rangeCount = rangeCount;
        this.rangeOpaque = rangeOpaque;
        this.palette = palette;
        this.box = box;
        this.nanos = nanos;
        this.darkQuads = darkQuads;
    }

    public boolean empty() {
        return quadCount == 0;
    }

    /** Bytes of quads and palette (what the GPU buffers will hold for it, without its record). */
    public long bytes() {
        return quadCount * 8L + palette.length * 4L;
    }

    public int translucentQuads() {
        int n = 0;
        for (int r = 0; r < RANGES; r++) {
            n += rangeCount[r] - rangeOpaque[r];
        }
        return n;
    }

    public int borderQuads() {
        int n = 0;
        for (int r = 1; r < RANGES; r += 2) {
            n += rangeCount[r];
        }
        return n;
    }

    // The quad layout (see the class comment)

    static int word0(int x, int y, int z, int width, int height, int direction) {
        return x | y << 5 | z << 10 | (width - 1) << 15 | (height - 1) << 20 | direction << 25;
    }

    static int word1(int paletteIndex, int light, int shading) {
        return paletteIndex | light << 14 | shading << 22;
    }

    public static int quadX(int word0) {
        return word0 & 31;
    }

    public static int quadY(int word0) {
        return word0 >> 5 & 31;
    }

    public static int quadZ(int word0) {
        return word0 >> 10 & 31;
    }

    public static int quadWidth(int word0) {
        return (word0 >> 15 & 31) + 1;
    }

    public static int quadHeight(int word0) {
        return (word0 >> 20 & 31) + 1;
    }

    public static int quadDirection(int word0) {
        return word0 >> 25 & 7;
    }

    public static int quadPalette(int word1) {
        return word1 & 0x3FFF;
    }

    public static int quadLight(int word1) {
        return word1 >> 14 & 0xFF;
    }

    public static int quadShading(int word1) {
        return word1 >>> 22 & 0xFF;
    }

    public static int paletteState(int entry) {
        return entry & 0xFFFFF;
    }

    public static int paletteBiome(int entry) {
        return entry >>> 20;
    }

    static int paletteEntry(int state, int biome) {
        return state & 0xFFFFF | biome << 20;
    }
}
